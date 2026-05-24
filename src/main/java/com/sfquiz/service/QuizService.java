package com.sfquiz.service;

import com.sfquiz.dto.QuestionDto;
import com.sfquiz.dto.SubmitRequest;
import com.sfquiz.dto.SubmitResponse;
import com.sfquiz.entity.Choice;
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

    public QuizService(QuestionRepository repo, ExamRepository exams, ExamTopicRepository examTopics,
                       QuestionRandomizer randomizer) {
        this.repo = repo;
        this.exams = exams;
        this.examTopics = examTopics;
        this.randomizer = randomizer;
    }

    /** Returns a topic-weighted random sample sized to {@code exam.questionsPerSession}.
     *  Falls back to a uniform random sample if topics aren't defined or no questions
     *  are tagged yet. PENDING/REJECTED questions are never served. */
    public List<QuestionDto> listForExam(String examSlug) {
        Exam exam = exams.findBySlug(examSlug).orElse(null);
        if (exam == null) return List.of();
        int sessionSize = Math.max(1, exam.getQuestionsPerSession());

        List<Question> approved = repo.findByExamSlugAndStatusOrderByNumber(examSlug, Question.Status.APPROVED);
        if (approved.isEmpty()) return List.of();

        List<ExamTopic> topics = examTopics.findByExamOrderBySortOrderAscIdAsc(exam);
        List<Question> sampled = topics.isEmpty()
                ? uniformSample(approved, sessionSize)
                : weightedSample(approved, topics, sessionSize);

        // Final shuffle so topics aren't presented in clusters.
        Collections.shuffle(sampled);

        // Build one name-substitution map for the whole session so the same
        // canonical company maps to the same alias across every question.
        Map<String, String> nameMap = randomizer.buildSessionNameMap();
        return sampled.stream()
                .map(q -> randomizer.randomize(q, nameMap))
                .collect(Collectors.toList());
    }

    private List<Question> uniformSample(List<Question> pool, int n) {
        List<Question> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(n, shuffled.size()));
    }

    private List<Question> weightedSample(List<Question> approved, List<ExamTopic> topics, int sessionSize) {
        // Bucket approved questions by their topic key.
        Map<String, List<Question>> byTopic = new HashMap<>();
        for (Question q : approved) {
            String key = q.getTopic();
            if (key == null || key.isBlank()) continue;
            byTopic.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }

        // First pass: take ~floor(weight% * sessionSize) per topic.
        LinkedHashSet<Question> picked = new LinkedHashSet<>();
        int totalWeight = topics.stream().mapToInt(ExamTopic::getWeightPercent).sum();
        if (totalWeight <= 0) totalWeight = 100;

        for (ExamTopic t : topics) {
            int target = (int) Math.round(sessionSize * (t.getWeightPercent() / (double) totalWeight));
            List<Question> bucket = byTopic.getOrDefault(t.getTopicKey(), List.of());
            if (bucket.isEmpty()) continue;
            List<Question> shuffled = new ArrayList<>(bucket);
            Collections.shuffle(shuffled);
            for (int i = 0; i < Math.min(target, shuffled.size()); i++) {
                picked.add(shuffled.get(i));
            }
        }

        // Second pass: if under-shot (e.g. a topic has too few questions, or many
        // are still untagged), fill remaining slots from anything not yet picked.
        if (picked.size() < sessionSize) {
            List<Question> remainder = new ArrayList<>();
            for (Question q : approved) if (!picked.contains(q)) remainder.add(q);
            Collections.shuffle(remainder);
            for (Question q : remainder) {
                if (picked.size() >= sessionSize) break;
                picked.add(q);
            }
        }

        log.debug("weightedSample: picked {} of {} approved (target session={})",
                picked.size(), approved.size(), sessionSize);
        List<Question> out = new ArrayList<>(picked);
        return out.size() > sessionSize ? out.subList(0, sessionSize) : out;
    }

    public SubmitResponse submit(Long questionId, SubmitRequest req) {
        Question q = repo.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown question id: " + questionId));

        Set<Long> correctIds = q.getChoices().stream()
                .filter(Choice::isCorrect)
                .map(Choice::getId)
                .collect(Collectors.toSet());

        Set<Long> selectedIds = req.getSelectedChoiceIds() == null
                ? new HashSet<>()
                : new HashSet<>(req.getSelectedChoiceIds());

        SubmitResponse r = new SubmitResponse();
        r.setCorrect(correctIds.equals(selectedIds));
        r.setCorrectChoiceIds(List.copyOf(correctIds));
        r.setSelectedChoiceIds(List.copyOf(selectedIds));
        r.setExplanation(q.getExplanation());
        r.setHelpUrl(q.getHelpUrl());
        return r;
    }
}
