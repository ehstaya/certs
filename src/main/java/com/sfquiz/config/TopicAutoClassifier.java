package com.sfquiz.config;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.service.TopicClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Periodically scans every active exam and asks Claude to assign a topic to
 *  any APPROVED question that's still missing one — but only once the cert's
 *  approved-bank crosses {@link #threshold} (default 100) questions, since
 *  the per-area breakdown on the per-test report isn't useful below that
 *  signal density.
 *
 *  Sweeps every {@code app.topic-classify.sweep-cron} (defaults to once a
 *  day at 04:10 UTC, well clear of the auto-approver and the recovery loop).
 *  TopicClassifier itself stops early if the daily Anthropic budget is
 *  exhausted, so a long backfill is naturally rate-limited across days. */
@Component
public class TopicAutoClassifier {

    private static final Logger log = LoggerFactory.getLogger(TopicAutoClassifier.class);

    private final ExamRepository exams;
    private final QuestionRepository questions;
    private final TopicClassifier classifier;
    private final int threshold;

    public TopicAutoClassifier(ExamRepository exams,
                               QuestionRepository questions,
                               TopicClassifier classifier,
                               @Value("${app.topic-classify.threshold:100}") int threshold) {
        this.exams = exams;
        this.questions = questions;
        this.classifier = classifier;
        this.threshold = threshold;
    }

    /** Daily sweep — see class javadoc. Override the cron via
     *  {@code app.topic-classify.sweep-cron} if needed. */
    @Scheduled(cron = "${app.topic-classify.sweep-cron:0 10 4 * * *}", zone = "UTC")
    public void sweep() {
        try {
            doSweep();
        } catch (Exception ex) {
            log.warn("topic-classify sweep failed: {}", ex.getMessage(), ex);
        }
    }

    void doSweep() {
        List<Exam> activeExams = exams.findByActiveTrueOrderBySortOrderAscNameAsc();
        for (Exam e : activeExams) {
            long approved = questions.countByExamAndStatus(e, Question.Status.APPROVED);
            if (approved < threshold) {
                log.debug("topic-classify: skipping {} ({} approved < threshold {})",
                        e.getSlug(), approved, threshold);
                continue;
            }
            List<Question> untagged = questions.findByExamAndStatusAndTopicIsNull(e, Question.Status.APPROVED);
            if (untagged.isEmpty()) {
                log.debug("topic-classify: {} already fully tagged ({} approved)", e.getSlug(), approved);
                continue;
            }
            log.info("topic-classify: {} crossed threshold (approved={}, untagged={}) — classifying",
                    e.getSlug(), approved, untagged.size());
            TopicClassifier.BatchResult result = classifier.classifyUntaggedFor(e.getSlug());
            log.info("topic-classify: {} done — {}", e.getSlug(), result.message());
        }
    }
}
