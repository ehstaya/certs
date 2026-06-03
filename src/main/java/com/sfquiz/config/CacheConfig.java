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
    /** Full QuestionDto by id — populated at quiz launch, used by every
     *  subsequent launch so the same user / coworker re-clicking
     *  Practice doesn't pay for findAllById against a cold pool. 5 min
     *  TTL matches SUBMIT_LOOKUP. */
    public static final String QUESTION_DTO = "questions.dto";
    /** Per-exam list of (id, topic) tuples driving the sampler. 60 s TTL
     *  — admin imports change this, but rarely during a session. */
    public static final String EXAM_ID_TOPIC = "exams.idTopicSampler";

    @Bean
    public CacheManager cacheManager() {
        // Default cache (EXAMS + TOPICS + EXAM_ID_TOPIC) — short 60 s TTL.
        CaffeineCacheManager mgr = new CaffeineCacheManager(EXAMS, TOPICS, EXAM_ID_TOPIC);
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(64));
        // SUBMIT_LOOKUP — 5 min TTL so a brief Heroku Postgres blip mid-test
        // still serves question metadata from memory.
        mgr.registerCustomCache(SUBMIT_LOOKUP, Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1024)
                .build());
        // QUESTION_DTO — 5 min TTL, bigger cap (per question). Lets the
        // launch path serve from memory once warmed.
        mgr.registerCustomCache(QUESTION_DTO, Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(2048)
                .build());
        return mgr;
    }
}
