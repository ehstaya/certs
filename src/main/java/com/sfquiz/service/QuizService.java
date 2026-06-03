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
import java.util.stream.Collectors;

@Service
public class QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizService.class);

    private final QuestionRepository repo;
    private final ExamRepository exams;
    private final ExamTopicRepository examTopics;
    private final QuestionRandomizer randomizer;
    private final QuestionSubmitLookup submitLookup;

    public QuizService(QuestionRepository repo, ExamRepository exams, ExamTopicRepository examTopics,
                       QuestionRandomizer randomizer,
                       QuestionSubmitLookup submitLookup) {
        this.repo = repo;
        this.exams = exams;
        this.examTopics = examTopics;
        this.randomizer = randomizer;
        this.submitLookup = submitLookup;
    }

    /** Replay the exact question set captured on a previous attempt — the
     *  "Retake same test" flow. Loads each Question by id (in stored
     *  order), drops any that were retired or hard-deleted since the
     *  attempt was recorded, then runs the same name-substitution
     *  randomizer so company aliases stay consistent within this
     *  re-session. Caller is responsible for ownership / permission. */
    public List<QuestionDto> listForRetake(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return List.of();
        // Build a one-shot id → Question map so we serve them in the
        // attempt's original order even though findAllById returns them
        // in whatever order JPA likes.
        List<Question> rows = repo.findAllById(questionIds);
        Map<Long, Question> byId = new HashMap<>(rows.size());
        for (Question q : rows) byId.put(q.getId(), q);

        List<Question> ordered = new ArrayList<>(questionIds.size());
        for (Long id : questionIds) {
            Question q = byId.get(id);
            // Skip retired/rejected — keep approved ones. A question that
            // was approved at the time of the original attempt may since
            // have been retired; we just drop those from the retake.
            if (q == null) continue;
            if (q.getStatus() != Question.Status.APPROVED) continue;
            ordered.add(q);
        }
        Map<String, String> nameMap = randomizer.buildSessionNameMap();
        return ordered.stream()
                .map(q -> randomizer.randomize(q, nameMap))
                .collect(Collectors.toList());
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
        List<Object[]> idTopicRows = repo.findIdAndTopicForSampling(examSlug, Question.Status.APPROVED);
        if (idTopicRows.isEmpty()) return List.of();

        List<ExamTopic> topics = examTopics.findByExamOrderBySortOrderAscIdAsc(exam);
        List<Long> selectedIds = topics.isEmpty()
                ? uniformSampleIds(idTopicRows, sessionSize)
                : weightedSampleIds(idTopicRows, topics, sessionSize);
        if (selectedIds.isEmpty()) return List.of();

        // Stage 2: load the full entities (with EAGER choices) for just
        // the 60 sampled IDs. JpaRepository.findAllById issues a single
        // IN-list SELECT.
        List<Question> sampled = repo.findAllById(selectedIds);
        // findAllById doesn't preserve input order — re-shuffle so topics
        // aren't clustered (was the original "Final shuffle" step).
        Collections.shuffle(sampled);

        // Build one name-substitution map for the whole session so the same
        // canonical company maps to the same alias across every question.
        Map<String, String> nameMap = randomizer.buildSessionNameMap();
        return sampled.stream()
                .map(q -> randomizer.randomize(q, nameMap))
                .collect(Collectors.toList());
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
