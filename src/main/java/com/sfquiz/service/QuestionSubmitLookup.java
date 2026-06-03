package com.sfquiz.service;

import com.sfquiz.entity.Choice;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.QuestionRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/** Tiny indirection that wraps a cacheable read of the "submit response
 *  shape" for one question — the correct-choice set + explanation +
 *  helpUrl. Lives in its own bean so QuizService can call into it
 *  through the Spring proxy (self-invocation in the same class would
 *  bypass @Cacheable).
 *
 *  Why cache this? On a brief Heroku Postgres connectivity blip — like
 *  the 17:31 incident where the pool went to total=0 for ~2 minutes —
 *  every submit returned 500. A 5-minute TTL on this lookup means the
 *  user mid-test can keep going from memory as long as their question
 *  was loaded recently (which it is on the launch fetch). Admin edits
 *  to a question are eventually consistent within 5 minutes — and
 *  the QuestionAdminService mutation paths can call evict() to clear
 *  immediately for the few that matter most. */
@Service
public class QuestionSubmitLookup {

    private final QuestionRepository repo;
    private final CacheManager cacheManager;

    public QuestionSubmitLookup(QuestionRepository repo, CacheManager cacheManager) {
        this.repo = repo;
        this.cacheManager = cacheManager;
    }

    public record SubmitView(
            List<Long> correctChoiceIds,
            String explanation,
            String helpUrl
    ) {}

    @Cacheable(value = com.sfquiz.config.CacheConfig.SUBMIT_LOOKUP, key = "#questionId")
    public SubmitView load(Long questionId) {
        Question q = repo.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown question id: " + questionId));
        List<Long> correctIds = q.getChoices().stream()
                .filter(Choice::isCorrect)
                .map(Choice::getId)
                .toList();
        return new SubmitView(correctIds, q.getExplanation(), q.getHelpUrl());
    }

    /** Invalidate the cached entry — call after an admin edits a question
     *  so the next submit reflects the new correct-choice set. */
    @CacheEvict(value = com.sfquiz.config.CacheConfig.SUBMIT_LOOKUP, key = "#questionId")
    public void evict(Long questionId) {
        // body intentionally empty — the annotation does the work
    }

    /** Pre-warm the cache directly from an already-loaded Question entity
     *  (no DB round-trip). Called from QuizService.listForExam +
     *  listForRetake at quiz launch so every submit during the test is a
     *  cache hit — the user never pays for a stale-pool stall mid-question
     *  even after they idle the page for several minutes. */
    public void preload(Question q) {
        if (q == null || q.getId() == null) return;
        Cache cache = cacheManager.getCache(com.sfquiz.config.CacheConfig.SUBMIT_LOOKUP);
        if (cache == null) return;
        List<Long> correctIds = q.getChoices().stream()
                .filter(Choice::isCorrect)
                .map(Choice::getId)
                .toList();
        cache.put(q.getId(), new SubmitView(correctIds, q.getExplanation(), q.getHelpUrl()));
    }
}
