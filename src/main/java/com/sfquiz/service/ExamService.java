package com.sfquiz.service;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.dto.ExamTopicDto;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ExamTopic;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.ExamTopicRepository;
import com.sfquiz.repository.QuestionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExamService {

    private final ExamRepository exams;
    private final QuestionRepository questions;
    private final ExamTopicRepository examTopics;

    public ExamService(ExamRepository exams, QuestionRepository questions, ExamTopicRepository examTopics) {
        this.exams = exams;
        this.questions = questions;
        this.examTopics = examTopics;
    }

    /** Topics for the info page. The per-topic question counts use the live bank
     *  so the UI can show "you have N approved questions in this topic". */
    public List<ExamTopicDto> listTopics(String slug) {
        Exam exam = exams.findBySlug(slug).orElse(null);
        if (exam == null) return List.of();
        List<ExamTopic> topics = examTopics.findByExamOrderBySortOrderAscIdAsc(exam);
        int perSession = Math.max(1, exam.getQuestionsPerSession());
        List<ExamTopicDto> out = new ArrayList<>(topics.size());
        for (ExamTopic t : topics) {
            int forSession = (int) Math.round(perSession * (t.getWeightPercent() / 100.0));
            long approved = questions.findByExamAndStatusAndTopic(exam, Question.Status.APPROVED, t.getTopicKey()).size();
            out.add(new ExamTopicDto(
                    t.getTopicKey(), t.getName(), t.getWeightPercent(),
                    forSession, approved, approved));
        }
        return out;
    }

    /** All active exams, for the picker. Empty exams are still listed so users
     *  can upload study material against them — the JS picker can mark them
     *  as "no questions yet" if it wants to. */
    public List<ExamDto> listActive() {
        return exams.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(e -> ExamDto.from(e, questions.countByExamAndStatus(e, Question.Status.APPROVED)))
                .toList();
    }

    public Exam getBySlug(String slug) {
        return exams.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown exam: " + slug));
    }

    public ExamDto toDto(Exam e) {
        return ExamDto.from(e, questions.countByExamAndStatus(e, Question.Status.APPROVED));
    }

    /** Create a new certification. SUPERADMIN-only path; the controller
     *  enforces auth via {@code /admin/certifications/**} guard. Slug is
     *  derived from the name when not supplied. Refuses duplicates so the
     *  unique constraint never explodes mid-request. */
    public Exam createExam(String name, String slug, String description,
                           int questionsPerSession, int durationMinutes,
                           int passingScorePercent) {
        return createExam(name, slug, description,
                questionsPerSession, durationMinutes, passingScorePercent,
                java.util.List.of());
    }

    /** Create a new cert AND seed its topic breakdown in one transaction.
     *  Empty/null {@code topics} just creates the cert without weights
     *  (matches the basic flow). Duplicate-slug check stays at the front
     *  so we never leave a half-built exam if the validation fires. */
    public Exam createExam(String name, String slug, String description,
                           int questionsPerSession, int durationMinutes,
                           int passingScorePercent,
                           List<TopicBreakdownParser.TopicWeight> topics) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Certification name is required.");
        }
        String trimmedName = name.trim();
        String resolvedSlug = (slug == null || slug.isBlank()) ? slugify(trimmedName) : slug.trim().toLowerCase();
        if (resolvedSlug.isBlank()) {
            throw new IllegalArgumentException("Could not derive a valid slug from the name. Please provide one explicitly.");
        }
        if (exams.findBySlug(resolvedSlug).isPresent()) {
            throw new IllegalArgumentException("A certification with slug '" + resolvedSlug + "' already exists.");
        }
        Exam e = new Exam();
        e.setSlug(resolvedSlug);
        e.setName(trimmedName);
        e.setDescription(description == null ? "" : description.trim());
        e.setQuestionsPerSession(clamp(questionsPerSession, 5, 200, 60));
        e.setDurationMinutes(clamp(durationMinutes, 5, 600, 90));
        e.setPassingScorePercent(clamp(passingScorePercent, 1, 100, 65));
        e.setActive(true);
        e.setSortOrder(100);
        Exam saved = exams.save(e);

        // Topic breakdown — drop the parsed rows in alongside the new cert.
        // De-dupe on topicKey within the same exam so two near-identical
        // names ("User Setup" / "User Setup ") don't blow the unique index.
        if (topics != null && !topics.isEmpty()) {
            java.util.Set<String> seenKeys = new java.util.HashSet<>();
            int order = 10;
            for (TopicBreakdownParser.TopicWeight t : topics) {
                if (t == null || t.topicKey() == null || t.topicKey().isBlank()) continue;
                if (!seenKeys.add(t.topicKey())) continue;
                ExamTopic et = new ExamTopic();
                et.setExam(saved);
                et.setTopicKey(t.topicKey());
                et.setName(t.name());
                et.setWeightPercent(t.weightPercent());
                et.setSortOrder(order);
                order += 10;
                examTopics.save(et);
            }
        }
        return saved;
    }

    /** All exams (active and otherwise) for the management list. */
    public List<Exam> listAllOrdered() {
        return exams.findAll().stream()
                .sorted((a, b) -> {
                    int s = Integer.compare(a.getSortOrder(), b.getSortOrder());
                    return s != 0 ? s : a.getName().compareToIgnoreCase(b.getName());
                })
                .toList();
    }

    private static int clamp(int v, int min, int max, int fallback) {
        if (v <= 0) return fallback;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    /** Lowercase, ASCII-safe slug from a free-text name. Replaces runs of
     *  non-alphanumeric chars with single hyphens, strips leading/trailing
     *  hyphens. {@code "Salesforce Admin (CRT-101)"} → {@code "salesforce-admin-crt-101"}. */
    private static String slugify(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase().trim();
        String dashed = lower.replaceAll("[^a-z0-9]+", "-");
        return dashed.replaceAll("^-+|-+$", "");
    }
}
