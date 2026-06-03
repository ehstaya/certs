package com.sfquiz.service;

import com.sfquiz.dto.QuestionDto;
import com.sfquiz.dto.SubmitRequest;
import com.sfquiz.dto.SubmitResponse;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ExamTopic;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.ExamTopicRepository;
import com.sfquiz.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizService.class);

    private final QuestionRepository repo;
    private final ExamRepository exams;
    private final ExamTopicRepository examTopics;
    private final QuestionRandomizer randomizer;
    private final QuestionSubmitLookup submitLookup;
    private final org.springframework.cache.CacheManager cacheManager;

    public QuizService(QuestionRepository repo, ExamRepository exams, ExamTopicRepository examTopics,
                       QuestionRandomizer randomizer,
                       QuestionSubmitLookup submitLookup,
                       org.springframework.cache.CacheManager cacheManager) {
        this.repo = repo;
        this.exams = exams;
        this.examTopics = examTopics;
        this.randomizer = randomizer;
        this.submitLookup = submitLookup;
        this.cacheManager = cacheManager;
    }

    /** Cached read of the (id, topic) projection for the sampler.
     *  Caffeine cache, 60 s TTL — admin edits to the bank are rare
     *  vs. user launches per minute. */
    @SuppressWarnings("unchecked")
    private List<Object[]> idTopicRowsCached(String slug) {
        org.springframework.cache.Cache cache =
                cacheManager.getCache(com.sfquiz.config.CacheConfig.EXAM_ID_TOPIC);
        if (cache != null) {
            org.springframework.cache.Cache.ValueWrapper w = cache.get(slug);
            if (w != null) return (List<Object[]>) w.get();
        }
        List<Object[]> fresh = repo.findIdAndTopicForSampling(slug, Question.Status.APPROVED);
        if (cache != null) cache.put(slug, fresh);
        return fresh;
    }

    /** Load Questions for the given IDs, preferring cached QuestionDtos
     *  where possible. The output order matches {@code ids} exactly.
     *  Cache misses fall back to a single findAllById call on the remainder
     *  and populate BOTH the dto cache and the submit-lookup cache for
     *  next time.
     *
     *  A dto cache hit but submit cache miss (TTLs slightly out of sync
     *  or someone evicted just one) counts as a miss so we reload from
     *  DB and keep both caches in lockstep. Otherwise the user would hit
     *  the slow path on submit even though the launch was fast. */
    private List<QuestionDto> loadQuestionDtosOrdered(List<Long> ids, Map<String, String> nameMap) {
        org.springframework.cache.Cache cache =
                cacheManager.getCache(com.sfquiz.config.CacheConfig.QUESTION_DTO);
        Map<Long, QuestionDto> byId = new HashMap<>(ids.size());
        List<Long> misses = new ArrayList<>();
        if (cache != null) {
            for (Long id : ids) {
                org.springframework.cache.Cache.ValueWrapper w = cache.get(id);
                if (w != null && submitLookup.isCached(id)) {
                    byId.put(id, (QuestionDto) w.get());
                } else {
                    misses.add(id);
                }
            }
        } else {
            misses.addAll(ids);
        }
        if (!misses.isEmpty()) {
            // Single IN-list SELECT for everything not in cache.
            List<Question> fetched = repo.findAllById(misses);
            for (Question q : fetched) {
                // Pre-warm submit cache from the loaded entity too.
                submitLookup.preload(q);
                QuestionDto dto = randomizer.randomize(q, nameMap);
                byId.put(q.getId(), dto);
                if (cache != null) cache.put(q.getId(), dto);
            }
        }
        // Output in the order of the input ids; skip any id that didn't
        // resolve (e.g. question got retired between sample + load).
        List<QuestionDto> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            QuestionDto dto = byId.get(id);
            if (dto != null) out.add(dto);
        }
        return out;
    }

    /** Replay the exact question set captured on a previous attempt — the
     *  "Retake same test" flow. Loads each Question by id (in stored
     *  order), drops any that were retired or hard-deleted since the
     *  attempt was recorded, then runs the same name-substitution
     *  randomizer so company aliases stay consistent within this
     *  re-session. Caller is responsible for ownership / permission. */
    public List<QuestionDto> listForRetake(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return List.of();
        // Serve from the QuestionDto cache where possible; fall back to
        // a single findAllById for the misses. Order matches input.
        Map<String, String> nameMap = randomizer.buildSessionNameMap();
        return loadQuestionDtosOrdered(questionIds, nameMap);
    }

    /** Returns a topic-weighted random sample sized to {@code exam.questionsPerSession}.
     *  Falls back to a uniform random sample if topics aren't defined or no questions
     *  are tagged yet. PENDING/REJECTED questions are never served.
     *
     *  Two-stage fetch: a lightweight (id, topic) projection drives the
     *  sampling, then we load the full Question + EAGER Choices only for
     *  the ~60 selected IDs. Previous design loaded the entire approved
     *  bank with its choices via a JOIN producing ~2000 rows for the
     *  Salesforce Admin 400-question case — the dominant cost of starting
     *  a practice session. */
    public List<QuestionDto> listForExam(String examSlug) {
        Exam exam = exams.findBySlug(examSlug).orElse(null);
        if (exam == null) return List.of();
        int sessionSize = Math.max(1, exam.getQuestionsPerSession());

        // Stage 1: lightweight (id, topic) tuples — drives the sampler
        // without loading question text / choices for the full bank.
        // Cached 60 s so back-to-back launches of the same exam don't
        // re-fire this scan against a possibly-stale pool.
        List<Object[]> idTopicRows = idTopicRowsCached(examSlug);
        if (idTopicRows.isEmpty()) return List.of();

        List<ExamTopic> topics = examTopics.findByExamOrderBySortOrderAscIdAsc(exam);
        List<Long> selectedIds = topics.isEmpty()
                ? uniformSampleIds(idTopicRows, sessionSize)
                : weightedSampleIds(idTopicRows, topics, sessionSize);
        if (selectedIds.isEmpty()) return List.of();

        // Stage 2: load the full QuestionDto for each sampled id —
        // cached where possible so a re-launch within the TTL skips
        // the IN-list SELECT entirely. Misses fall back to a single
        // findAllById and warm the cache for next time.
        Map<String, String> nameMap = randomizer.buildSessionNameMap();
        List<QuestionDto> sampledDtos = loadQuestionDtosOrdered(selectedIds, nameMap);
        // findAllById doesn't preserve input order — re-shuffle so topics
        // aren't clustered (was the original "Final shuffle" step).
        Collections.shuffle(sampledDtos);
        return sampledDtos;
    }

    private List<Long> uniformSampleIds(List<Object[]> idTopicRows, int n) {
        List<Long> ids = new ArrayList<>(idTopicRows.size());
        for (Object[] row : idTopicRows) ids.add((Long) row[0]);
        Collections.shuffle(ids);
        return ids.subList(0, Math.min(n, ids.size()));
    }

    private List<Long> weightedSampleIds(List<Object[]> idTopicRows,
                                         List<ExamTopic> topics, int sessionSize) {
        // Bucket approved-question ids by their topic key.
        Map<String, List<Long>> byTopic = new HashMap<>();
        List<Long> allIds = new ArrayList<>(idTopicRows.size());
        for (Object[] row : idTopicRows) {
            Long id = (Long) row[0];
            String key = (String) row[1];
            allIds.add(id);
            if (key == null || key.isBlank()) continue;
            byTopic.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
        }

        LinkedHashSet<Long> picked = new LinkedHashSet<>();
        int totalWeight = topics.stream().mapToInt(ExamTopic::getWeightPercent).sum();
        if (totalWeight <= 0) totalWeight = 100;

        for (ExamTopic t : topics) {
            int target = (int) Math.round(sessionSize * (t.getWeightPercent() / (double) totalWeight));
            List<Long> bucket = byTopic.getOrDefault(t.getTopicKey(), List.of());
            if (bucket.isEmpty()) continue;
            List<Long> shuffled = new ArrayList<>(bucket);
            Collections.shuffle(shuffled);
            for (int i = 0; i < Math.min(target, shuffled.size()); i++) {
                picked.add(shuffled.get(i));
            }
        }

        // Top-up from any unpicked id when topic weights under-shoot.
        if (picked.size() < sessionSize) {
            List<Long> remainder = new ArrayList<>();
            for (Long id : allIds) if (!picked.contains(id)) remainder.add(id);
            Collections.shuffle(remainder);
            for (Long id : remainder) {
                if (picked.size() >= sessionSize) break;
                picked.add(id);
            }
        }

        log.debug("weightedSampleIds: picked {} of {} approved (target session={})",
                picked.size(), allIds.size(), sessionSize);
        List<Long> out = new ArrayList<>(picked);
        return out.size() > sessionSize ? out.subList(0, sessionSize) : out;
    }

    public SubmitResponse submit(Long questionId, SubmitRequest req) {
        // The correct-choice set + explanation + helpUrl are fetched
        // through the QuestionSubmitLookup proxy so they're served from
        // an in-memory 5-minute cache. A user mid-test can keep
        // submitting answers even through brief Heroku Postgres
        // connectivity blips — the DB is only touched on the first
        // submit for each question (or after the TTL elapses).
        QuestionSubmitLookup.SubmitView view = submitLookup.load(questionId);

        Set<Long> correctIds = new HashSet<>(view.correctChoiceIds());
        Set<Long> selectedIds = req.getSelectedChoiceIds() == null
                ? new HashSet<>()
                : new HashSet<>(req.getSelectedChoiceIds());

        SubmitResponse r = new SubmitResponse();
        r.setCorrect(correctIds.equals(selectedIds));
        r.setCorrectChoiceIds(List.copyOf(correctIds));
        r.setSelectedChoiceIds(List.copyOf(selectedIds));
        r.setExplanation(view.explanation());
        r.setHelpUrl(view.helpUrl());
        return r;
    }
}
