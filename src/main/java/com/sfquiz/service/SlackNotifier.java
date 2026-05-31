package com.sfquiz.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sfquiz.entity.AppSetting;
import com.sfquiz.entity.DomainAdminAssignment;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.Question;
import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
import com.sfquiz.repository.AppSettingRepository;
import com.sfquiz.repository.DomainAdminAssignmentRepository;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.repository.UserRepository;
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
import java.util.Optional;

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
    private final DomainAdminAssignmentRepository assignments;
    private final UserRepository users;
    private final ObjectMapper json;
    private final SlackUserResolver resolver;

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

    public SlackNotifier(QuestionRepository questions,
                         AppSettingRepository settings,
                         DomainAdminAssignmentRepository assignments,
                         UserRepository users,
                         ObjectMapper json,
                         SlackUserResolver resolver) {
        this.questions = questions;
        this.settings = settings;
        this.assignments = assignments;
        this.users = users;
        this.json = json;
        this.resolver = resolver;
    }

    /** True when the optional Slack bot token is configured — real
     *  {@code <@U...>} mentions are produced; false means plain-text
     *  fallback (display name only, no notification). */
    public boolean mentionsAreLive() {
        return resolver.isConfigured();
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
    @Transactional
    public boolean postDigestForce(String reason) {
        if (!isConfigured()) return false;
        long pending = countPending();
        boolean ok = postDigestNow(Math.max(pending, 0L));
        if (ok) recordPostNow();
        log.info("Slack digest force-sent ({}): pending={}, ok={}", reason, pending, ok);
        return ok;
    }

    /** Notify the channel that the auto-approval sweep just flipped N
     *  pending questions to APPROVED after the 24h grace period. The body
     *  includes the per-cert breakdown so domain admins can scan for
     *  unexpected volume on their certs. Best-effort: a failed POST is
     *  logged but doesn't block the auto-approval that already happened. */
    public boolean postAutoApprovalNotice(int approvedCount, Map<String, Integer> perExamSlug) {
        if (!isConfigured()) return false;
        StringBuilder text = new StringBuilder();
        text.append(":robot_face: *Auto-approved ").append(approvedCount)
            .append(" pending question").append(approvedCount == 1 ? "" : "s")
            .append("* after the 24-hour review window expired.\n");
        if (perExamSlug != null && !perExamSlug.isEmpty()) {
            text.append("\nBy certification:");
            perExamSlug.forEach((slug, n) ->
                    text.append("\n• `").append(slug == null ? "(unknown)" : slug)
                        .append("` — ").append(n));
        }
        text.append("\n\nReview them in the Approved queue and retire any that look wrong.");
        try {
            String body = json.writeValueAsString(Map.of("text", text.toString()));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                log.info("Slack auto-approval notice posted (n={}).", approvedCount);
                return true;
            }
            log.warn("Slack auto-approval notice returned status={} body={}", resp.statusCode(), resp.body());
            return false;
        } catch (Exception ex) {
            log.warn("Slack auto-approval notice POST failed: {}", ex.getMessage());
            return false;
        }
    }

    private boolean postDigestNow(long totalPending) {
        Map<Exam, Long> byExam = countPendingByExamEntity();
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

    /** Pending question count broken down by exam ENTITY (not just name) so
     *  the digest builder can look up the matching domain-admin assignments
     *  per exam and ping them by name. Sorted by largest backlog first. */
    private Map<Exam, Long> countPendingByExamEntity() {
        List<Question> pending = questions.findByStatusOrderByNumber(Question.Status.PENDING);
        // Group via exam id so equal-by-pk Exam proxies don't fragment.
        Map<Long, Exam> examById = new java.util.HashMap<>();
        Map<Long, Long> countById = new java.util.HashMap<>();
        for (Question q : pending) {
            Exam ex = q.getExam();
            if (ex == null) continue;
            examById.putIfAbsent(ex.getId(), ex);
            countById.merge(ex.getId(), 1L, (a, b) -> a + b);
        }
        LinkedHashMap<Exam, Long> sorted = new LinkedHashMap<>();
        countById.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sorted.put(examById.get(e.getKey()), e.getValue()));
        return sorted;
    }

    private String buildDigestText(long total, Map<Exam, Long> byExam) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Certification Practice Playground — pending review*\n");
        sb.append("*").append(total).append("* question");
        if (total != 1) sb.append("s");
        sb.append(" awaiting domain-admin approval:\n");

        // Pre-resolve the SUPERADMIN list once — used as the fallback ping
        // when an exam has no domain admins assigned yet.
        List<User> superAdmins = activeUsersByRole(UserRole.SUPERADMIN);

        for (Map.Entry<Exam, Long> e : byExam.entrySet()) {
            Exam exam = e.getKey();
            long count = e.getValue();
            sb.append("\n*").append(exam.getName()).append("*: ").append(count).append("\n");

            List<User> domainAdmins = domainAdminsFor(exam);
            if (!domainAdmins.isEmpty()) {
                sb.append("Domain admin");
                if (domainAdmins.size() > 1) sb.append("s");
                sb.append(":");
                for (User u : domainAdmins) {
                    sb.append("\n").append(mentionFor(u));
                }
                sb.append("\n");
            } else if (!superAdmins.isEmpty()) {
                sb.append("_No domain admins assigned — pinging super admin");
                if (superAdmins.size() > 1) sb.append("s");
                sb.append(" as fallback:_");
                for (User u : superAdmins) {
                    sb.append("\n").append(mentionFor(u));
                }
                sb.append("\n");
            } else {
                sb.append("_No domain admins or super admins on file._\n");
            }
        }

        sb.append("\n→ Review queue: ").append(reviewQueueUrl());
        sb.append("\n_(You'll get another nudge in ~24h until the queue is cleared.)_");
        return sb.toString();
    }

    /** Active domain admins for an exam. ADMIN/SUPERADMIN users with an
     *  assignment row count; inactive users (disabled/rejected) are filtered
     *  out so we never ping someone who can't act on the message. */
    private List<User> domainAdminsFor(Exam exam) {
        if (exam == null) return List.of();
        List<DomainAdminAssignment> rows = assignments.findByExam(exam);
        List<User> out = new java.util.ArrayList<>();
        for (DomainAdminAssignment a : rows) {
            User u = a.getUser();
            if (u == null) continue;
            if (u.getStatus() != UserStatus.ACTIVE) continue;
            out.add(u);
        }
        // Stable name order so the digest reads the same way every time.
        out.sort((a, b) -> mentionFor(a).compareToIgnoreCase(mentionFor(b)));
        return out;
    }

    private List<User> activeUsersByRole(UserRole role) {
        List<User> out = new java.util.ArrayList<>();
        for (User u : users.findAll()) {
            if (u.getRole() == role && u.getStatus() == UserStatus.ACTIVE) {
                out.add(u);
            }
        }
        out.sort((a, b) -> mentionFor(a).compareToIgnoreCase(mentionFor(b)));
        return out;
    }

    /** Builds the @-mention string for a user. When the optional bot token is
     *  configured we resolve the user's Slack ID via {@code users.lookupByEmail}
     *  and emit a real {@code <@U...>} mention that triggers a notification;
     *  the display name is appended for human readability. Without the token
     *  we fall back to plain-text {@code @Full Name} which is just text — no
     *  ping but the admin is at least clearly named. */
    private String mentionFor(User u) {
        if (u == null) return "(unknown)";
        String display = (u.getFullName() != null && !u.getFullName().isBlank())
                ? u.getFullName().trim()
                : u.getEmail();
        Optional<String> slackId = resolver.lookup(u.getEmail());
        if (slackId.isPresent()) {
            return "<@" + slackId.get() + "> (" + display + ")";
        }
        return "@" + display;
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
