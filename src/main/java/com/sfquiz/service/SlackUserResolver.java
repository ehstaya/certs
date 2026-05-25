package com.sfquiz.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves an app-user email to the corresponding Slack workspace user ID
 *  via the Slack Web API {@code users.lookupByEmail}. Resolved IDs are
 *  cached in memory indefinitely (Slack user IDs never change for the
 *  lifetime of an account); negative lookups are cached for 10 minutes so
 *  a missing user doesn't hammer the API on every reminder cycle.
 *
 *  Configuration:
 *    app.slack.bot-token   — Slack bot OAuth token, "xoxb-...". Needs the
 *                            {@code users:read.email} scope (and usually
 *                            {@code users:read} as a dependency).
 *                            Also honors env {@code SLACK_BOT_TOKEN}.
 *
 *  If the token is unset the resolver is a no-op — {@link #lookup(String)}
 *  always returns empty so SlackNotifier falls back to plain-text names. */
@Service
public class SlackUserResolver {

    private static final Logger log = LoggerFactory.getLogger(SlackUserResolver.class);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(10);
    private static final URI API = URI.create("https://slack.com/api/users.lookupByEmail");

    @Value("${app.slack.bot-token:${SLACK_BOT_TOKEN:}}")
    private String botToken;

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** email -> resolved Slack user ID (or null for "looked up + missing"). */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String userId, Instant cachedAt) {}

    public SlackUserResolver(ObjectMapper json) {
        this.json = json;
    }

    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank();
    }

    /** Returns the Slack user ID for {@code email}, or empty if not configured /
     *  not found / lookup failed. Safe to call from any context. */
    public Optional<String> lookup(String email) {
        if (!isConfigured() || email == null || email.isBlank()) return Optional.empty();
        String key = email.trim().toLowerCase();
        CacheEntry hit = cache.get(key);
        if (hit != null) {
            // Cached "found" — return forever. Cached "not found" — honour TTL.
            if (hit.userId() != null) return Optional.of(hit.userId());
            if (hit.cachedAt().isAfter(Instant.now().minus(NEGATIVE_TTL))) return Optional.empty();
        }
        String resolved = fetchSlackUserId(key);
        cache.put(key, new CacheEntry(resolved, Instant.now()));
        return Optional.ofNullable(resolved);
    }

    private String fetchSlackUserId(String email) {
        try {
            URI uri = URI.create(API + "?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Slack users.lookupByEmail {} returned status {}", email, resp.statusCode());
                return null;
            }
            JsonNode body = json.readTree(resp.body());
            if (!body.path("ok").asBoolean(false)) {
                String err = body.path("error").asText("unknown");
                // users_not_found is common (the verifier/admin isn't in the Slack workspace);
                // log at debug. Anything else — missing scope, ratelimited — log at warn.
                if ("users_not_found".equals(err)) {
                    log.debug("Slack lookup: no user for {}", email);
                } else {
                    log.warn("Slack users.lookupByEmail {} not ok: {}", email, err);
                }
                return null;
            }
            String id = body.path("user").path("id").asText(null);
            if (id == null || id.isBlank()) return null;
            log.debug("Slack lookup: {} -> {}", email, id);
            return id;
        } catch (Exception ex) {
            log.warn("Slack users.lookupByEmail {} failed: {}", email, ex.getMessage());
            return null;
        }
    }

    /** For the admin UI: total cache size and how many hits were "found". */
    public int cacheSize() {
        return cache.size();
    }

    public int resolvedCount() {
        return (int) cache.values().stream().filter(e -> e.userId() != null).count();
    }
}
