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

    public List<TestAttempt> listForUser(String userEmail) {
        return users.findByEmailIgnoreCase(userEmail)
                .map(attempts::findByUserOrderByFinishedAtDesc)
                .orElse(List.of());
    }

    public List<TestAttempt> trendForUserAndExam(String userEmail, String examSlug) {
        User u = users.findByEmailIgnoreCase(userEmail).orElse(null);
        Exam e = exams.findBySlug(examSlug).orElse(null);
        if (u == null || e == null) return List.of();
        return attempts.findByUserAndExamOrderByFinishedAtAsc(u, e);
    }

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
        for (TestAttemptAnswer r : answerRepo.findByAttemptOrderByIdAsc(attempt)) {
            totalAnswered++;
            Question q = r.getQuestion();
            if (q == null) continue;
            String topic = q.getTopic();
            if (topic == null || topic.isBlank()) continue;
            int[] bucket = tally.computeIfAbsent(topic, k -> new int[]{0, 0});
            bucket[1]++;
            if (r.isCorrect()) bucket[0]++;
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
