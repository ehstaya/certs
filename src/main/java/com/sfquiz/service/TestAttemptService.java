package com.sfquiz.service;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ExamTopic;
import com.sfquiz.entity.Question;
import com.sfquiz.entity.TestAttempt;
import com.sfquiz.entity.TestAttemptAnswer;
import com.sfquiz.entity.User;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.ExamTopicRepository;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.repository.TestAttemptAnswerRepository;
import com.sfquiz.repository.TestAttemptRepository;
import com.sfquiz.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class TestAttemptService {

    private static final Logger log = LoggerFactory.getLogger(TestAttemptService.class);

    private final TestAttemptRepository attempts;
    private final TestAttemptAnswerRepository answerRepo;
    private final ExamRepository exams;
    private final UserRepository users;
    private final QuestionRepository questions;
    private final ExamTopicRepository examTopics;

    public TestAttemptService(TestAttemptRepository attempts,
                              TestAttemptAnswerRepository answerRepo,
                              ExamRepository exams,
                              UserRepository users,
                              QuestionRepository questions,
                              ExamTopicRepository examTopics) {
        this.attempts = attempts;
        this.answerRepo = answerRepo;
        this.exams = exams;
        this.users = users;
        this.questions = questions;
        this.examTopics = examTopics;
    }

    /** Per-question detail submitted alongside the summary on finalize.
     *  {@code selectedChoiceIds} is the list the user picked at submit time;
     *  {@code correct} is the client-computed score (re-validated server-side
     *  before persisting). */
    public record AnswerDetail(
            Long questionId,
            List<Long> selectedChoiceIds,
            boolean correct
    ) {}

    /** Server-validated input shape from the quiz UI's finalize-test POST.
     *  {@code questionIds} captures the EXACT set of questions served in
     *  the order they were rendered — drives the "Retake same test" flow
     *  by letting us re-serve the same set instead of a fresh random sample.
     *  {@code mode} ("PRACTICE"/"EXAM") records whether this attempt was
     *  a feedback-everywhere practice session or a strict real-exam run. */
    public record RecordRequest(
            String examSlug,
            Instant startedAt,
            Instant finishedAt,
            int totalQuestions,
            int correctCount,
            int incorrectCount,
            int unansweredCount,
            List<Long> questionIds,
            String mode,
            List<AnswerDetail> answers
    ) {}

    @Transactional
    public TestAttempt record(String userEmail, RecordRequest req) {
        if (userEmail == null) throw new IllegalArgumentException("Not signed in");
        if (req.examSlug() == null || req.examSlug().isBlank()) throw new IllegalArgumentException("Missing examSlug");
        if (req.startedAt() == null || req.finishedAt() == null) throw new IllegalArgumentException("Missing timestamps");
        if (req.totalQuestions() <= 0) throw new IllegalArgumentException("totalQuestions must be > 0");

        User u = users.findByEmailIgnoreCase(userEmail).orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        Exam e = exams.findBySlug(req.examSlug()).orElseThrow(() -> new IllegalArgumentException("Unknown exam"));

        int score = (int) Math.round(100.0 * req.correctCount() / req.totalQuestions());
        int duration = (int) Math.max(0, java.time.Duration.between(req.startedAt(), req.finishedAt()).getSeconds());

        TestAttempt a = new TestAttempt();
        a.setUser(u);
        a.setExam(e);
        a.setStartedAt(req.startedAt());
        a.setFinishedAt(req.finishedAt());
        a.setDurationSeconds(duration);
        a.setTotalQuestions(req.totalQuestions());
        a.setCorrectCount(req.correctCount());
        a.setIncorrectCount(req.incorrectCount());
        a.setUnansweredCount(req.unansweredCount());
        a.setScorePercent(score);
        a.setPassingScorePercent(e.getPassingScorePercent());
        a.setPassed(score >= e.getPassingScorePercent());

        // Parse + persist the delivery mode. Unknown / null values fall
        // back to PRACTICE so a malformed client request can't accidentally
        // mark an attempt as a real exam run.
        if (req.mode() != null) {
            try {
                a.setMode(TestAttempt.Mode.valueOf(req.mode().trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                a.setMode(TestAttempt.Mode.PRACTICE);
            }
        }

        // Snapshot the original question set so the user can re-attempt
        // this exact test later via /index.html?retake=<id>. Stored as
        // a comma-separated list of ids — compact, ordered, no JSON
        // parsing needed when we replay them.
        if (req.questionIds() != null && !req.questionIds().isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Long id : req.questionIds()) {
                if (id == null) continue;
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
            a.setQuestionIds(ids.length() == 0 ? null : ids.toString());
        }

        attempts.save(a);

        // Persist per-question detail so the My Reports drill-down can show
        // the user what they got right/wrong + the explanation. Re-validates
        // each answer against the current correct-choice set so a malicious
        // client can't flip an incorrect to correct just by sending
        // {correct:true}. Unknown question ids are skipped silently.
        int saved = 0;
        if (req.answers() != null) {
            for (AnswerDetail d : req.answers()) {
                if (d == null || d.questionId() == null) continue;
                Question q = questions.findById(d.questionId()).orElse(null);
                if (q == null) continue;
                List<Long> picked = d.selectedChoiceIds() == null ? List.of() : d.selectedChoiceIds();
                boolean serverCorrect = scoreAnswer(q, picked);

                TestAttemptAnswer ans = new TestAttemptAnswer();
                ans.setAttempt(a);
                ans.setQuestion(q);
                ans.setSelectedChoiceIds(picked.isEmpty()
                        ? ""
                        : picked.stream().map(String::valueOf).reduce((x, y) -> x + "," + y).orElse(""));
                ans.setCorrect(serverCorrect);
                answerRepo.save(ans);
                saved++;
            }
        }

        log.info("Recorded attempt user={} exam={} score={}% passed={} duration={}s answers={}",
                userEmail, e.getSlug(), score, a.isPassed(), duration, saved);
        return a;
    }

    /** Server-side scoring — true iff the user picked exactly the set of
     *  choices marked correct on the question. Matches the per-submit
     *  scoring done in QuizService so the drill-down agrees with the live
     *  feedback the user saw mid-test. */
    private boolean scoreAnswer(Question q, List<Long> pickedIds) {
        java.util.Set<Long> correct = new java.util.HashSet<>();
        for (com.sfquiz.entity.Choice c : q.getChoices()) {
            if (c.isCorrect()) correct.add(c.getId());
        }
        java.util.Set<Long> picked = new java.util.HashSet<>(pickedIds == null ? List.of() : pickedIds);
        return correct.equals(picked);
    }

    /** Recent FINISHED attempts for the My-reports dashboard. */
    public List<TestAttempt> listForUser(String userEmail) {
        return users.findByEmailIgnoreCase(userEmail)
                .map(u -> attempts.findByUserAndStatusOrderByFinishedAtDesc(
                        u, TestAttempt.Status.FINISHED))
                .orElse(List.of());
    }

    /** Trend over time — FINISHED-only so an in-progress save can't
     *  drag the chart down to a zero score. */
    public List<TestAttempt> trendForUserAndExam(String userEmail, String examSlug) {
        User u = users.findByEmailIgnoreCase(userEmail).orElse(null);
        Exam e = exams.findBySlug(examSlug).orElse(null);
        if (u == null || e == null) return List.of();
        return attempts.findByUserAndExamAndStatusOrderByFinishedAtAsc(
                u, e, TestAttempt.Status.FINISHED);
    }

    /** Per-test page Saved-tests section — most recently touched first. */
    public List<TestAttempt> savedForUserAndExam(String userEmail, String examSlug) {
        User u = users.findByEmailIgnoreCase(userEmail).orElse(null);
        Exam e = exams.findBySlug(examSlug).orElse(null);
        if (u == null || e == null) return List.of();
        return attempts.findByUserAndExamAndStatusOrderByFinishedAtDesc(
                u, e, TestAttempt.Status.SAVED);
    }

    /** Paged variant for the per-test page. Status drives both sections
     *  (SAVED → "Saved tests", FINISHED → "Attempt history"). Returns an
     *  empty page when the user / exam isn't known so the template can
     *  render its empty state without special-casing. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<TestAttempt> pageForUserAndExam(
            String userEmail, String examSlug,
            TestAttempt.Status status,
            int page, int pageSize) {
        User u = users.findByEmailIgnoreCase(userEmail).orElse(null);
        Exam e = exams.findBySlug(examSlug).orElse(null);
        if (u == null || e == null) {
            return org.springframework.data.domain.Page.empty();
        }
        org.springframework.data.domain.Pageable p = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page),
                Math.min(50, Math.max(1, pageSize)),
                org.springframework.data.domain.Sort.by("finishedAt").descending());
        return attempts.findByUserAndExamAndStatus(u, e, status, p);
    }

    /** Save a practice test in progress. Creates a new SAVED row on first
     *  call, updates the existing one on subsequent calls. The saved-state
     *  JSON is opaque to the server — it's a client-supplied snapshot the
     *  client knows how to rehydrate. */
    @Transactional
    public TestAttempt saveAttempt(String userEmail, Long existingId, SaveRequest req) {
        if (userEmail == null) throw new IllegalArgumentException("Not signed in");
        if (req.examSlug() == null || req.examSlug().isBlank()) throw new IllegalArgumentException("Missing examSlug");

        User u = users.findByEmailIgnoreCase(userEmail).orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        Exam e = exams.findBySlug(req.examSlug()).orElseThrow(() -> new IllegalArgumentException("Unknown exam"));

        TestAttempt existing = null;
        if (existingId != null) {
            existing = attempts.findById(existingId).orElse(null);
            if (existing != null && existing.getUser() != null
                    && !existing.getUser().getId().equals(u.getId())) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "This saved test belongs to another user.");
            }
            if (existing != null && existing.getStatus() != TestAttempt.Status.SAVED) {
                // Don't let a Finished attempt regress to Saved.
                existing = null;
            }
        }
        boolean isNew = (existing == null);
        TestAttempt a;
        if (isNew) {
            a = new TestAttempt();
            a.setUser(u);
            a.setExam(e);
            a.setStartedAt(req.startedAt() != null ? req.startedAt() : java.time.Instant.now());
            a.setStatus(TestAttempt.Status.SAVED);
            a.setMode(parseMode(req.mode()));
            a.setPassingScorePercent(e.getPassingScorePercent());
            // Sequence + name: per-user across all exams. Assigned at first
            // save so the user can identify the test in their reports.
            int seq = attempts.findMaxSequenceForUser(u) + 1;
            a.setSequenceNumber(seq);
            a.setDisplayName(buildDisplayName(u, seq));
        } else {
            a = existing;
        }
        // finishedAt is repurposed as "last saved at" for SAVED rows so
        // the existing-index-orderable queries still work.
        a.setFinishedAt(java.time.Instant.now());
        a.setDurationSeconds((int) Math.max(0,
                java.time.Duration.between(a.getStartedAt(), a.getFinishedAt()).getSeconds()));
        a.setTotalQuestions(req.totalQuestions());
        // Score / passed are zeroed for SAVED rows — they get real
        // numbers when the user finally clicks Finish.
        a.setCorrectCount(0);
        a.setIncorrectCount(0);
        a.setUnansweredCount(req.totalQuestions());
        a.setScorePercent(0);
        a.setPassed(false);
        if (req.questionIds() != null && !req.questionIds().isEmpty()) {
            a.setQuestionIds(csvOfIds(req.questionIds()));
        }
        a.setSavedStateJson(req.savedStateJson());
        attempts.save(a);
        log.info("{} attempt user={} exam={} id={} name={}",
                isNew ? "Saved new" : "Updated saved",
                userEmail, e.getSlug(), a.getId(), a.getDisplayName());
        return a;
    }

    /** Convert a SAVED attempt to FINISHED with the final scores. Mirrors
     *  the body of {@link #record} but updates an existing row instead of
     *  inserting. */
    @Transactional
    public TestAttempt finishSavedAttempt(String userEmail, Long savedId, RecordRequest req) {
        if (userEmail == null) throw new IllegalArgumentException("Not signed in");
        TestAttempt a = attempts.findById(savedId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown saved test"));
        User u = users.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        if (a.getUser() == null || !a.getUser().getId().equals(u.getId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This test belongs to another user.");
        }
        if (a.getStatus() == TestAttempt.Status.FINISHED) {
            // Idempotent — finishing a finished attempt is a no-op.
            return a;
        }
        int score = (int) Math.round(100.0 * req.correctCount() / Math.max(1, req.totalQuestions()));
        int duration = (int) Math.max(0,
                java.time.Duration.between(a.getStartedAt(), req.finishedAt() == null ? java.time.Instant.now() : req.finishedAt())
                                  .getSeconds());
        a.setStatus(TestAttempt.Status.FINISHED);
        a.setFinishedAt(req.finishedAt() != null ? req.finishedAt() : java.time.Instant.now());
        a.setDurationSeconds(duration);
        a.setTotalQuestions(req.totalQuestions());
        a.setCorrectCount(req.correctCount());
        a.setIncorrectCount(req.incorrectCount());
        a.setUnansweredCount(req.unansweredCount());
        a.setScorePercent(score);
        a.setPassed(score >= a.getPassingScorePercent());
        a.setSavedStateJson(null); // no longer needed
        if (req.mode() != null) a.setMode(parseMode(req.mode()));
        if (req.questionIds() != null && !req.questionIds().isEmpty()) {
            a.setQuestionIds(csvOfIds(req.questionIds()));
        }
        attempts.save(a);

        // Wipe + re-persist per-question answers (replay drill-down).
        // Bulk delete avoids the N+1 of loading 60 entities just to remove
        // them — one statement instead of 60 round trips.
        answerRepo.deleteAllByAttempt(a);
        if (req.answers() != null) {
            for (AnswerDetail d : req.answers()) {
                if (d == null || d.questionId() == null) continue;
                Question q = questions.findById(d.questionId()).orElse(null);
                if (q == null) continue;
                List<Long> picked = d.selectedChoiceIds() == null ? List.of() : d.selectedChoiceIds();
                TestAttemptAnswer ans = new TestAttemptAnswer();
                ans.setAttempt(a);
                ans.setQuestion(q);
                ans.setSelectedChoiceIds(picked.isEmpty()
                        ? ""
                        : picked.stream().map(String::valueOf).reduce((x, y) -> x + "," + y).orElse(""));
                ans.setCorrect(scoreAnswer(q, picked));
                answerRepo.save(ans);
            }
        }
        log.info("Finished saved attempt id={} user={} exam={} score={}%",
                a.getId(), userEmail, a.getExam().getSlug(), score);
        return a;
    }

    /** GET path companion — load a saved attempt for resume. The client
     *  uses {@link TestAttempt#getSavedStateJson()} to rehydrate answers
     *  + active index, and the existing /retake-questions endpoint to
     *  refetch the question set (saved question_ids are authoritative). */
    @Transactional(readOnly = true)
    public TestAttempt loadSavedForResume(String userEmail, Long savedId) {
        TestAttempt a = attempts.findById(savedId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown saved test"));
        if (a.getUser() == null || !a.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This test belongs to another user.");
        }
        if (a.getStatus() != TestAttempt.Status.SAVED) {
            throw new IllegalStateException("This attempt is already finished and can't be resumed.");
        }
        return a;
    }

    /** Hard delete of a saved attempt. Used by the Delete button on the
     *  Saved-tests section so users can prune drafts. Finished attempts
     *  are not deletable from this method. */
    @Transactional
    public void deleteSavedAttempt(String userEmail, Long savedId) {
        TestAttempt a = attempts.findById(savedId).orElse(null);
        if (a == null) return;
        if (a.getUser() == null || !a.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This test belongs to another user.");
        }
        if (a.getStatus() != TestAttempt.Status.SAVED) {
            throw new IllegalStateException("Only saved tests can be deleted from this page.");
        }
        // Bulk delete of answer rows (none expected for a typical saved
        // attempt since saves don't persist per-question answers, but
        // the explicit wipe keeps the schema invariant: no orphan rows).
        answerRepo.deleteAllByAttempt(a);
        attempts.delete(a);
    }

    private TestAttempt.Mode parseMode(String mode) {
        if (mode == null) return TestAttempt.Mode.PRACTICE;
        try { return TestAttempt.Mode.valueOf(mode.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return TestAttempt.Mode.PRACTICE; }
    }

    private String csvOfIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Display name = "<first name> #<sequence>". Falls back to email-local
     *  part if the user has no fullName. */
    private String buildDisplayName(User u, int sequence) {
        String first = "";
        if (u.getFullName() != null && !u.getFullName().isBlank()) {
            first = u.getFullName().trim().split("\\s+")[0];
        }
        if (first.isEmpty() && u.getEmail() != null) {
            int at = u.getEmail().indexOf('@');
            first = at > 0 ? u.getEmail().substring(0, at) : u.getEmail();
        }
        if (first.isEmpty()) first = "Test";
        // Title-case the first letter for a cleaner label.
        first = first.substring(0, 1).toUpperCase() + first.substring(1);
        return first + " #" + sequence;
    }

    /** Save-request shape from the client. Mirrors RecordRequest but with
     *  the optional in-progress savedStateJson and no score fields. */
    public record SaveRequest(
            String examSlug,
            java.time.Instant startedAt,
            int totalQuestions,
            List<Long> questionIds,
            String mode,
            String savedStateJson
    ) {}

    public record ExamSummary(
            String slug, String name, int passingScorePercent,
            long attempts, int averageScore, int bestScore,
            long passed, long avgDurationSeconds
    ) {}

    /** One row for the My Reports drill-down. {@code userPicks} is the
     *  list of choices the user picked, in label order; {@code correctPicks}
     *  is the set of choices the question marks correct. */
    public record AttemptAnswerDetail(
            Long questionId,
            int questionNumber,
            String questionText,
            String questionType,
            String explanation,
            String helpUrl,
            List<ChoiceView> userPicks,
            List<ChoiceView> correctPicks,
            boolean correct
    ) {}

    public record ChoiceView(String label, String text) {}

    public record AttemptDetailView(
            Long attemptId,
            String examSlug,
            String examName,
            int totalQuestions,
            int correctCount,
            int incorrectCount,
            int unansweredCount,
            int scorePercent,
            boolean passed,
            java.time.Instant finishedAt,
            /** True when this attempt captured its question set and can be
             *  retaken via /?retake=<attemptId>. Pre-feature legacy rows
             *  return false and the UI hides the Retake button. */
            boolean retakeable,
            /** "PRACTICE" or "EXAM" — the delivery mode of this attempt.
             *  Used to label rows on the per-test report. */
            String mode,
            List<AttemptAnswerDetail> items,
            // Pagination metadata for the detail page.
            int page,           // current page (1-based for the URL)
            int pageSize,       // page size
            int totalItems,     // total matching the filter (across all pages)
            int totalPages      // total pages, min 1
    ) {}

    /** Fetch the per-question detail for an attempt owned by {@code userEmail}.
     *  {@code filter} selects "correct", "incorrect", or "all". {@code page}
     *  is 1-based; {@code pageSize} is clamped to a sane range so a hostile
     *  client can't request the whole bank in one go. Throws if the attempt
     *  doesn't belong to the caller — keeps users from peeking at each
     *  other's results by guessing ids.
     *
     *  Read-only @Transactional so the lazy {@code TestAttempt.user} proxy
     *  stays initializable for the duration of the ownership check + the
     *  per-answer Question/Choice walks the template later does. */
    /** Ownership-checked attempt lookup for the retake flow. Returns the
     *  attempt if it exists AND belongs to {@code userEmail}; returns null
     *  otherwise (so a 404 is indistinguishable between "doesn't exist"
     *  and "belongs to someone else" — no info leak by id-guessing).
     *  Read-only @Transactional so the lazy {@code TestAttempt.exam} +
     *  {@code TestAttempt.user} associations stay loadable in the caller. */
    @Transactional(readOnly = true)
    public TestAttempt findOwnedById(Long attemptId, String userEmail) {
        if (attemptId == null || userEmail == null) return null;
        TestAttempt a = attempts.findById(attemptId).orElse(null);
        if (a == null || a.getUser() == null) return null;
        if (!a.getUser().getEmail().equalsIgnoreCase(userEmail)) return null;
        // Touch the lazy exam association so it's initialized before the
        // session closes and the caller serializes the DTO.
        if (a.getExam() != null) a.getExam().getName();
        return a;
    }

    /** Reconstruct the question-id list for a retake. Tries the explicit
     *  snapshot first (post-feature attempts), then falls back to the
     *  per-answer rows for legacy attempts whose set wasn't captured at
     *  finalize-time. Order matches the original render order in both
     *  paths. Returns an empty list when neither signal exists — the
     *  controller turns that into a 410 and the client redirects to a
     *  fresh sample on the same exam. */
    @Transactional(readOnly = true)
    public List<Long> resolveRetakeQuestionIds(TestAttempt attempt) {
        if (attempt == null) return List.of();
        String csv = attempt.getQuestionIds();
        if (csv != null && !csv.isBlank()) {
            List<Long> out = new ArrayList<>();
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                try { out.add(Long.parseLong(t)); } catch (NumberFormatException ignored) { /* skip */ }
            }
            if (!out.isEmpty()) return out;
        }
        // Legacy fallback — pull whatever the user actually submitted. We
        // lose the unanswered/skipped ones (no row was ever saved for
        // those), so the replay may be shorter than the original total.
        // Still useful: the user gets to redo every question they tried.
        List<Long> reconstructed = new ArrayList<>();
        for (TestAttemptAnswer r : answerRepo.findByAttemptOrderByIdAsc(attempt)) {
            if (r.getQuestion() == null) continue;
            reconstructed.add(r.getQuestion().getId());
        }
        return reconstructed;
    }

    @Transactional(readOnly = true)
    public AttemptDetailView attemptDetail(String userEmail, Long attemptId,
                                           String filter, int page, int pageSize) {
        TestAttempt a = attempts.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown attempt id"));
        if (a.getUser() == null || !a.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This attempt belongs to another user.");
        }
        boolean wantCorrect   = filter == null || filter.isBlank() || "correct".equalsIgnoreCase(filter) || "all".equalsIgnoreCase(filter);
        boolean wantIncorrect = filter == null || filter.isBlank() || "incorrect".equalsIgnoreCase(filter) || "all".equalsIgnoreCase(filter);
        // Strict mode: a non-empty filter only includes the matching side.
        if ("correct".equalsIgnoreCase(filter))   { wantCorrect = true;  wantIncorrect = false; }
        if ("incorrect".equalsIgnoreCase(filter)) { wantCorrect = false; wantIncorrect = true;  }

        // Clamp pagination — defends against ?pageSize=99999 and ?page=-1.
        if (pageSize <= 0)  pageSize = 10;
        if (pageSize > 100) pageSize = 100;
        if (page < 1)       page = 1;

        List<TestAttemptAnswer> rows = answerRepo.findByAttemptOrderByIdAsc(a);
        List<TestAttemptAnswer> matching = new ArrayList<>();
        for (TestAttemptAnswer r : rows) {
            if (r.isCorrect() && !wantCorrect) continue;
            if (!r.isCorrect() && !wantIncorrect) continue;
            matching.add(r);
        }

        int totalItems = matching.size();
        int totalPages = Math.max(1, (totalItems + pageSize - 1) / pageSize);
        if (page > totalPages) page = totalPages;
        int fromIdx = (page - 1) * pageSize;
        int toIdx = Math.min(fromIdx + pageSize, totalItems);

        List<AttemptAnswerDetail> items = new ArrayList<>();
        for (int i = fromIdx; i < toIdx; i++) {
            TestAttemptAnswer r = matching.get(i);
            Question q = r.getQuestion();
            java.util.Set<Long> pickedIds = parseIds(r.getSelectedChoiceIds());
            List<ChoiceView> userPicks = new ArrayList<>();
            List<ChoiceView> correctPicks = new ArrayList<>();
            for (com.sfquiz.entity.Choice c : q.getChoices()) {
                if (pickedIds.contains(c.getId())) userPicks.add(new ChoiceView(c.getLabel(), c.getText()));
                if (c.isCorrect()) correctPicks.add(new ChoiceView(c.getLabel(), c.getText()));
            }
            // questionNumber is the 1-based index *across the filtered set*,
            // not the page slice — so "Question 27 of 50" makes sense even
            // when you're paging through results.
            items.add(new AttemptAnswerDetail(
                    q.getId(),
                    i + 1,
                    q.getText(),
                    q.getType() == null ? "SINGLE" : q.getType().name(),
                    q.getExplanation(),
                    q.getHelpUrl(),
                    userPicks,
                    correctPicks,
                    r.isCorrect()));
        }
        boolean retakeable = a.getQuestionIds() != null && !a.getQuestionIds().isBlank();
        String modeLabel = a.getMode() == null ? "PRACTICE" : a.getMode().name();
        return new AttemptDetailView(
                a.getId(),
                a.getExam() == null ? "" : a.getExam().getSlug(),
                a.getExam() == null ? "" : a.getExam().getName(),
                a.getTotalQuestions(), a.getCorrectCount(),
                a.getIncorrectCount(), a.getUnansweredCount(),
                a.getScorePercent(), a.isPassed(),
                a.getFinishedAt(),
                retakeable,
                modeLabel,
                items,
                page, pageSize, totalItems, totalPages);
    }

    private static java.util.Set<Long> parseIds(String csv) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            try { out.add(Long.parseLong(part.trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** One row in the per-attempt topic breakdown: an exam area + how many
     *  of that area's questions the user answered correctly. {@code total}
     *  counts every answered question in that area (correct + incorrect);
     *  unanswered rows weren't persisted to TestAttemptAnswer so they
     *  don't dilute the accuracy denominator. */
    public record TopicStat(String topicKey, String name, int correct, int total, int accuracyPercent) {}

    /** Best area + worst N areas for a single attempt, plus the full per-area
     *  table for an optional detail view. Returns an empty breakdown when
     *  the questions in the attempt have no topic populated (cert hasn't
     *  been classified yet — TopicAutoClassifier kicks in past 100 Qs). */
    public record AttemptTopicBreakdown(
            List<TopicStat> rows,
            TopicStat best,
            List<TopicStat> worst,
            int totalAnswered,
            int totalTagged) {}

    @Transactional(readOnly = true)
    public AttemptTopicBreakdown topicBreakdownForAttempt(TestAttempt attempt) {
        if (attempt == null || attempt.getExam() == null) {
            return new AttemptTopicBreakdown(List.of(), null, List.of(), 0, 0);
        }
        // Pre-load the exam's topic list so we can resolve topicKey -> display
        // name without a per-row lookup, and so areas the user didn't see in
        // this attempt simply don't appear (vs. showing 0/0).
        List<ExamTopic> topics = examTopics.findByExamOrderBySortOrderAscIdAsc(attempt.getExam());
        java.util.Map<String, String> keyToName = new java.util.HashMap<>();
        for (ExamTopic t : topics) keyToName.put(t.getTopicKey(), t.getName());

        // correct[topicKey] / total[topicKey] accumulators.
        java.util.Map<String, int[]> tally = new java.util.LinkedHashMap<>();
        // Seed in canonical exam order so the rows render in the same order
        // as the topic info shown at start-of-test.
        for (ExamTopic t : topics) tally.put(t.getTopicKey(), new int[]{0, 0});

        int totalAnswered = 0;
        int totalTagged = 0;
        // One JPQL projection query — (topic, isCorrect) tuples — instead
        // of loading every TestAttemptAnswer with its EAGER Question +
        // EAGER Choices (which was ~121 SQL statements for a 60-Q attempt
        // and the cause of /my/reports/per-test occasionally taking ~11 s).
        for (Object[] row : answerRepo.findTopicCorrectnessByAttempt(attempt)) {
            totalAnswered++;
            String topic = (String) row[0];
            if (topic == null || topic.isBlank()) continue;
            boolean isCorrect = (Boolean) row[1];
            int[] bucket = tally.computeIfAbsent(topic, k -> new int[]{0, 0});
            bucket[1]++;
            if (isCorrect) bucket[0]++;
            totalTagged++;
        }

        List<TopicStat> rows = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : tally.entrySet()) {
            int correct = e.getValue()[0];
            int total = e.getValue()[1];
            if (total == 0) continue; // skip areas the user didn't see this attempt
            int pct = (int) Math.round(100.0 * correct / total);
            String name = keyToName.getOrDefault(e.getKey(), e.getKey());
            rows.add(new TopicStat(e.getKey(), name, correct, total, pct));
        }

        // Best = highest accuracy; ties broken by largest sample (more
        // confidence behind the number).
        TopicStat best = rows.stream()
                .max(java.util.Comparator
                        .comparingInt(TopicStat::accuracyPercent)
                        .thenComparingInt(TopicStat::total))
                .orElse(null);
        // Worst 3 = lowest accuracy first; ties broken by largest sample
        // (a 0/2 area is less actionable than a 1/8 area at the same %).
        List<TopicStat> worst = rows.stream()
                .sorted(java.util.Comparator
                        .comparingInt(TopicStat::accuracyPercent)
                        .thenComparing(java.util.Comparator.comparingInt(TopicStat::total).reversed()))
                .limit(3)
                .toList();

        return new AttemptTopicBreakdown(rows, best, worst, totalAnswered, totalTagged);
    }

    public List<ExamSummary> summaryByExamForUser(String userEmail) {
        User u = users.findByEmailIgnoreCase(userEmail).orElse(null);
        if (u == null) return List.of();
        List<Object[]> rows = attempts.summaryByExamForUser(u);
        List<ExamSummary> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String slug = (String) r[1];
            String name = (String) r[2];
            int passPct  = r[3] == null ? 65 : ((Number) r[3]).intValue();
            long count   = ((Number) r[4]).longValue();
            int  avgScore = r[5] == null ? 0 : (int) Math.round(((Number) r[5]).doubleValue());
            int  bestScore = r[6] == null ? 0 : ((Number) r[6]).intValue();
            long passed   = r[7] == null ? 0 : ((Number) r[7]).longValue();
            long avgDur  = r[8] == null ? 0 : Math.round(((Number) r[8]).doubleValue());
            out.add(new ExamSummary(slug, name, passPct, count, avgScore, bestScore, passed, avgDur));
        }
        // sort by most attempts desc
        out.sort((a, b) -> Long.compare(b.attempts(), a.attempts()));
        return out;
    }
}
