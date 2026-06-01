package com.sfquiz.config;

import com.sfquiz.entity.Exam;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.repository.TestAttemptRepository;
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

    public StartupWarmer(ExamRepository exams, QuestionRepository questions,
                         TestAttemptRepository attempts) {
        this.exams = exams;
        this.questions = questions;
        this.attempts = attempts;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    @Transactional(readOnly = true)
    public void warmUp() {
        long t0 = System.currentTimeMillis();
        try {
            // Same queries the topbar /api/exams hits on every page load —
            // priming them gets the connection + plan cache hot before a
            // real user lands.
            List<Exam> activeExams = exams.findByActiveTrueOrderBySortOrderAscNameAsc();
            questions.countApprovedByExam();
            // Cheap touch of the attempt table so the user-reports landing
            // page's first query is warm too.
            attempts.count();
            log.info("StartupWarmer: warmed {} exam(s), question counts, attempt count in {} ms",
                    activeExams.size(), System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            // Best-effort — a warmup failure should never block the dyno.
            log.warn("StartupWarmer: skipped after {} ms: {}",
                    System.currentTimeMillis() - t0, ex.getMessage());
        }
    }
}
