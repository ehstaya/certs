package com.sfquiz.service;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.Question;
import com.sfquiz.entity.TestAttempt;
import com.sfquiz.entity.TestAttemptAnswer;
import com.sfquiz.entity.User;
import com.sfquiz.repository.ExamRepository;
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

    public TestAttemptService(TestAttemptRepository attempts,
                              TestAttemptAnswerRepository answerRepo,
                              ExamRepository exams,
                              UserRepository users,
                              QuestionRepository questions) {
        this.attempts = attempts;
        this.answerRepo = answerRepo;
        this.exams = exams;
        this.users = users;
        this.questions = questions;
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

    /** Server-validated input shape from the quiz UI's finalize-test POST. */
    public record RecordRequest(
            String examSlug,
            Instant startedAt,
            Instant finishedAt,
            int totalQuestions,
            int correctCount,
            int incorrectCount,
            int unansweredCount,
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
        return new AttemptDetailView(
                a.getId(),
                a.getExam() == null ? "" : a.getExam().getSlug(),
                a.getExam() == null ? "" : a.getExam().getName(),
                a.getTotalQuestions(), a.getCorrectCount(),
                a.getIncorrectCount(), a.getUnansweredCount(),
                a.getScorePercent(), a.isPassed(),
                a.getFinishedAt(), items,
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
