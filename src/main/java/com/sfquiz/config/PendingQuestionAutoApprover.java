package com.sfquiz.config;

import com.sfquiz.entity.Question;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.service.QuestionAdminService;
import com.sfquiz.service.SlackNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hourly sweep that auto-approves any PENDING question whose createdAt
 *  is older than {@link #autoApproveAfterHours} (default 24h). Implements
 *  the "if admins don't review within 24h, ship it" policy.
 *
 *  Each auto-approval flows through {@link QuestionAdminService#approve}
 *  so the existing rails fire — APPROVE action gets logged with the
 *  pseudo-admin email "(auto-approve)", and the Claude enricher still
 *  fills in missing explanation/helpUrl in the background.
 *
 *  After the sweep, posts a single summary message to Slack with the
 *  per-cert breakdown so domain admins know what just landed in their
 *  live banks without their explicit sign-off. */
@Component
public class PendingQuestionAutoApprover {

    private static final Logger log = LoggerFactory.getLogger(PendingQuestionAutoApprover.class);

    private final QuestionRepository questions;
    private final QuestionAdminService admin;
    private final SlackNotifier slack;
    private final long autoApproveAfterHours;

    /** Pseudo-email stamped on auto-approval QuestionAction rows so the
     *  admin-activity report can distinguish system actions from human
     *  ones. Treated as a sentinel — do NOT use as a real account. */
    public static final String AUTO_APPROVER_EMAIL = "(auto-approve)";

    public PendingQuestionAutoApprover(QuestionRepository questions,
                                       QuestionAdminService admin,
                                       SlackNotifier slack,
                                       @Value("${app.auto-approve.after-hours:24}") long autoApproveAfterHours) {
        this.questions = questions;
        this.admin = admin;
        this.slack = slack;
        this.autoApproveAfterHours = autoApproveAfterHours;
    }

    /** Run hourly at :15 past the hour — offset from the SlackReminderJob
     *  digest at :00 so the two don't contend for the DB pool or send
     *  back-to-back Slack messages. */
    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
    public void sweep() {
        try {
            doSweep();
        } catch (Exception ex) {
            // Never let a bad sweep crash the scheduler — log and move on.
            log.warn("PendingQuestionAutoApprover: sweep failed: {}", ex.getMessage(), ex);
        }
    }

    void doSweep() {
        Instant cutoff = Instant.now().minus(autoApproveAfterHours, ChronoUnit.HOURS);
        List<Question> stale = questions.findStalePending(cutoff);
        if (stale.isEmpty()) {
            log.debug("PendingQuestionAutoApprover: no PENDING questions older than {}h",
                    autoApproveAfterHours);
            return;
        }

        log.info("PendingQuestionAutoApprover: auto-approving {} PENDING question(s) older than {}h",
                stale.size(), autoApproveAfterHours);

        // Group by exam slug so we can show admins what landed where in
        // the Slack summary. LinkedHashMap preserves first-seen order.
        Map<String, Integer> perExamSlug = new LinkedHashMap<>();
        int approved = 0;
        for (Question q : stale) {
            String slug = q.getExam() == null ? null : q.getExam().getSlug();
            try {
                admin.approve(q.getId(), AUTO_APPROVER_EMAIL);
                approved++;
                perExamSlug.merge(slug, 1, Integer::sum);
            } catch (Exception ex) {
                // Approve failures are best-effort: log and continue with
                // the next question. The next sweep will retry survivors.
                log.warn("PendingQuestionAutoApprover: failed to approve q#{}: {}",
                        q.getId(), ex.getMessage());
            }
        }

        if (approved > 0) {
            slack.postAutoApprovalNotice(approved, perExamSlug);
        }
    }
}
