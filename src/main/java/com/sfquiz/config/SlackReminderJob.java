package com.sfquiz.config;

import com.sfquiz.service.SlackNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly scheduled task: asks SlackNotifier to post a pending-review digest
 *  if (a) there are pending questions, and (b) more than 24 hours have
 *  elapsed since the last successful post. SlackNotifier owns the throttle
 *  logic — this class just nudges it on a cadence that's fast enough to
 *  detect "first new question after a quiet period" within an hour. */
@Component
public class SlackReminderJob {

    private static final Logger log = LoggerFactory.getLogger(SlackReminderJob.class);

    private final SlackNotifier slack;

    public SlackReminderJob(SlackNotifier slack) {
        this.slack = slack;
    }

    /** Run hourly at the top of the hour. SlackNotifier enforces the actual
     *  24h cadence so this can run as often as we like without spamming. */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void tick() {
        try {
            boolean posted = slack.postDigestIfDue();
            if (posted) {
                log.info("SlackReminderJob: posted pending-review digest.");
            }
        } catch (Exception ex) {
            log.warn("SlackReminderJob: tick failed: {}", ex.getMessage());
        }
    }
}
