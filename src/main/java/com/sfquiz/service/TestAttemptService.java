package com.sfquiz.service;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.TestAttempt;
import com.sfquiz.entity.User;
import com.sfquiz.repository.ExamRepository;
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
    private final ExamRepository exams;
    private final UserRepository users;

    public TestAttemptService(TestAttemptRepository attempts, ExamRepository exams, UserRepository users) {
        this.attempts = attempts;
        this.exams = exams;
        this.users = users;
    }

    /** Server-validated input shape from the quiz UI's finalize-test POST. */
    public record RecordRequest(
            String examSlug,
            Instant startedAt,
            Instant finishedAt,
            int totalQuestions,
            int correctCount,
            int incorrectCount,
            int unansweredCount
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
        log.info("Recorded attempt user={} exam={} score={}% passed={} duration={}s",
                userEmail, e.getSlug(), score, a.isPassed(), duration);
        return a;
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
