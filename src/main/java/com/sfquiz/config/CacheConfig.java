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

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(EXAMS, TOPICS);
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(64));
        return mgr;
    }
}
