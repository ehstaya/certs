package com.sfquiz.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sfquiz.entity.AppSetting;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.AppSettingRepository;
import com.sfquiz.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Posts a digest message to a single Slack incoming webhook whenever there
 *  are pending questions awaiting domain-admin review. The 24-hour cadence
 *  + persistence-backed throttle live here so the scheduler stays trivial.
 *
 *  Configuration:
 *    app.slack.webhook-url   — incoming-webhook URL (env: SLACK_WEBHOOK_URL).
 *                              If blank/unset the notifier is a no-op so
 *                              local/dev runs don't try to post.
 *    app.base-url            — used to build a clickable link back to the
 *                              admin review queue.
 */
@Service
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);
    private static final String SETTING_LAST_POST = "slack.last_pending_digest_at";
    private static final Duration MIN_INTERVAL = Duration.of(24, ChronoUnit.HOURS);

    private final QuestionRepository questions;
    private final AppSettingRepository settings;
    private final ObjectMapper json;

    /** First try the Spring property {@code app.slack.webhook-url} (also
     *  satisfied by env var {@code APP_SLACK_WEBHOOK_URL} via relaxed
     *  binding); fall back to the bare {@code SLACK_WEBHOOK_URL} env var
     *  that operators typically reach for first. Either works. */
    @Value("${app.slack.webhook-url:${SLACK_WEBHOOK_URL:}}")
    private String webhookUrl;

    @Value("${app.base-url:http://localhost:8095}")
    private String baseUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public SlackNotifier(QuestionRepository questions, AppSettingRepository settings, ObjectMapper json) {
        this.questions = questions;
        this.settings = settings;
        this.json = json;
    }

    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** Returns the timestamp of the most recent successful post, or null. */
    public Instant lastPostedAt() {
        return settings.findByKeyName(SETTING_LAST_POST)
                .map(AppSetting::getUpdatedAt)
                .orElse(null);
    }

    /** Posts the pending-review digest if there are pending questions AND
     *  either no prior post exists OR more than 24h have elapsed. Used by
     *  the scheduled reminder. Returns true if a message was actually sent. */
    @Transactional
    public boolean postDigestIfDue() {
        if (!isConfigured()) {
            log.debug("Slack webhook not configured — skipping digest.");
            return false;
        }
        Instant last = lastPostedAt();
        if (last != null && last.isAfter(Instant.now().minus(MIN_INTERVAL))) {
            log.debug("Slack digest throttled — last post was {}", last);
            return false;
        }
        long pending = countPending();
        if (pending == 0) {
            log.debug("Slack digest skipped — no pending questions.");
            return false;
        }
        boolean ok = postDigestNow(pending);
        if (ok) recordPostNow();
        return ok;
    }

    /** Send the digest immediately, ignoring the 24h throttle. Used by the
     *  /admin "Send test Slack message" button. */
    public boolean postDigestForce(String reason) {
        if (!isConfigured()) return false;
        long pending = countPending();
        boolean ok = postDigestNow(Math.max(pending, 0L));
        if (ok) recordPostNow();
        log.info("Slack digest force-sent ({}): pending={}, ok={}", reason, pending, ok);
        return ok;
    }

    private boolean postDigestNow(long totalPending) {
        Map<String, Long> byExam = countPendingByExam();
        String text = buildDigestText(totalPending, byExam);
        try {
            String body = json.writeValueAsString(Map.of("text", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                log.info("Slack digest posted (pending={}).", totalPending);
                return true;
            }
            log.warn("Slack digest POST returned status={} body={}", resp.statusCode(), resp.body());
            return false;
        } catch (Exception ex) {
            log.warn("Slack digest POST failed: {}", ex.getMessage());
            return false;
        }
    }

    private long countPending() {
        return questions.countByStatus(Question.Status.PENDING);
    }

    /** Pending question count broken down by exam name, sorted by largest
     *  backlog first so the most important exam leads the digest. */
    private Map<String, Long> countPendingByExam() {
        List<Question> pending = questions.findByStatusOrderByNumber(Question.Status.PENDING);
        Map<String, Long> byName = new TreeMap<>();
        for (Question q : pending) {
            String name = (q.getExam() == null) ? "(no exam)" : q.getExam().getName();
            byName.merge(name, 1L, Long::sum);
        }
        // Re-order by descending count.
        LinkedHashMap<String, Long> sorted = new LinkedHashMap<>();
        byName.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private String buildDigestText(long total, Map<String, Long> byExam) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Certification Practice Playground — pending review*\n");
        sb.append("*").append(total).append("* question");
        if (total != 1) sb.append("s");
        sb.append(" awaiting domain-admin approval:\n");
        for (Map.Entry<String, Long> e : byExam.entrySet()) {
            sb.append("• ").append(e.getKey()).append(": *").append(e.getValue()).append("*\n");
        }
        sb.append("\n→ Review queue: ").append(reviewQueueUrl());
        sb.append("\n_(You'll get another nudge in ~24h until the queue is cleared.)_");
        return sb.toString();
    }

    private String reviewQueueUrl() {
        String base = (baseUrl == null || baseUrl.isBlank()) ? "" : baseUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/admin/questions";
    }

    private void recordPostNow() {
        AppSetting s = settings.findByKeyName(SETTING_LAST_POST).orElseGet(AppSetting::new);
        s.setKeyName(SETTING_LAST_POST);
        s.setValue(Instant.now().toString());
        s.setUpdatedAt(Instant.now());
        settings.save(s);
    }
}
