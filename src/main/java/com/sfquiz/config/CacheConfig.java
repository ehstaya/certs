package com.sfquiz.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/** Tiny Caffeine cache for the few read-mostly endpoints that dominate
 *  practice-launch latency: /api/exams and /api/exams/{slug}/topics.
 *  Both change rarely (admin edits to exam metadata + topic weights),
 *  so a short TTL is a clean win — the first hit warms the cache, every
 *  subsequent launch in the next minute skips the DB entirely.
 *
 *  Per-cache TTLs are tuned conservatively. If an admin edits a topic
 *  weight, the worst case is a minute of stale weights for users
 *  already mid-launch — acceptable for the latency win. */
@Configuration
public class CacheConfig {

    public static final String EXAMS = "exams.listActive";
    public static final String TOPICS = "exams.listTopics";
    /** Question correctness payload keyed by question id. 5 min TTL is
     *  enough to ride out any brief Heroku Postgres connectivity blip
     *  mid-test without making admin edits to a question linger past a
     *  few minutes. Bigger size (1024) since this is per-question. */
    public static final String SUBMIT_LOOKUP = "questions.submitLookup";

    @Bean
    public CacheManager cacheManager() {
        // Default cache (EXAMS + TOPICS) — short 60 s TTL.
        CaffeineCacheManager mgr = new CaffeineCacheManager(EXAMS, TOPICS);
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(64));
        // Override SUBMIT_LOOKUP with a longer TTL (5 min) so a brief
        // Heroku Postgres blip mid-test still serves question metadata
        // from memory. Bigger size cap since this is per-question.
        mgr.registerCustomCache(SUBMIT_LOOKUP, Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1024)
                .build());
        return mgr;
    }
}
