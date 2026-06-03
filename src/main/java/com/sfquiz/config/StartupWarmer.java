package com.sfquiz.config;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.Question;
import com.sfquiz.entity.User;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.ExamTopicRepository;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.repository.TestAttemptRepository;
import com.sfquiz.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Touches the hot read paths at startup so the first user request after a
 *  deploy doesn't pay for:
 *    - Hikari acquiring its first DB connection (TLS handshake to Heroku
 *      Postgres can be ~500 ms cold).
 *    - Hibernate planning / caching the queries used on every page load
 *      (notably ExamService.listActive's GROUP BY).
 *    - Spring's transaction infrastructure being lazy.
 *
 *  Runs after the app is ready and asynchronously so the dyno can flip to
 *  "up" without waiting on the warmup. Errors are swallowed — warmup is
 *  best-effort, never load-bearing.
 *
 *  DispatcherServlet eager init lives in application.properties
 *  (spring.mvc.servlet.load-on-startup=1) — this class handles the data
 *  layer half of the same problem. */
@Component
public class StartupWarmer {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmer.class);

    private final ExamRepository exams;
    private final QuestionRepository questions;
    private final TestAttemptRepository attempts;
    private final UserRepository users;
    private final ExamTopicRepository examTopics;

    public StartupWarmer(ExamRepository exams, QuestionRepository questions,
                         TestAttemptRepository attempts,
                         UserRepository users, ExamTopicRepository examTopics) {
        this.exams = exams;
        this.questions = questions;
        this.attempts = attempts;
        this.users = users;
        this.examTopics = examTopics;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    @Transactional(readOnly = true)
    public void warmUp() {
        long t0 = System.currentTimeMillis();
        try {
            // /api/exams + topbar dropdown
            List<Exam> activeExams = exams.findByActiveTrueOrderBySortOrderAscNameAsc();
            questions.countApprovedByExam();
            attempts.count();

            // Pick a real user + exam if we have them so the per-user JPQL
            // queries below have actual params to plan against. If neither
            // exists this is a brand-new dyno; skip silently.
            User sampleUser = users.findAll().stream().findFirst().orElse(null);
            Exam sampleExam = activeExams.isEmpty() ? null : activeExams.get(0);

            if (sampleUser != null && sampleExam != null) {
                // /my/reports/per-test + /my/reports/trend hit this on the
                // default-exam path. Plan caches by JPQL string, so even
                // running it once with a user who has no rows warms the
                // plan for everyone else.
                attempts.findByUserAndExamAndStatusOrderByFinishedAtAsc(
                        sampleUser, sampleExam,
                        com.sfquiz.entity.TestAttempt.Status.FINISHED);
                // /my/reports dashboard's GROUP BY (per-exam summary for one user).
                attempts.summaryByExamForUser(sampleUser);
                // Topic-info panel + per-attempt breakdown read these on
                // every per-test render.
                examTopics.findByExamOrderBySortOrderAscIdAsc(sampleExam);
                questions.countByExamAndStatusGroupedByTopic(sampleExam, Question.Status.APPROVED);
                questions.findIdAndTopicForSampling(sampleExam.getSlug(), Question.Status.APPROVED);
            }

            log.info("StartupWarmer: warmed core + user-report query plans in {} ms ({} exam(s))",
                    System.currentTimeMillis() - t0, activeExams.size());
        } catch (Exception ex) {
            // Best-effort — a warmup failure should never block the dyno.
            log.warn("StartupWarmer: skipped after {} ms: {}",
                    System.currentTimeMillis() - t0, ex.getMessage());
        }
    }
}
