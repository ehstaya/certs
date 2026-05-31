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
     *  as "no questions yet" if it wants to.
     *
     *  Was N+1 (one COUNT(*) per exam). Now: 2 queries — the exam list, plus
     *  a single GROUP BY for approved counts — assembled in-memory. Hit on
     *  every page that calls /api/exams (i.e. essentially every page), so
     *  this was the dominant cost on signed-in navigation. */
    public List<ExamDto> listActive() {
        List<Exam> activeExams = exams.findByActiveTrueOrderBySortOrderAscNameAsc();
        java.util.Map<Long, Long> approvedByExamId = new java.util.HashMap<>();
        for (Object[] row : questions.countApprovedByExam()) {
            approvedByExamId.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return activeExams.stream()
                .map(e -> ExamDto.from(e, approvedByExamId.getOrDefault(e.getId(), 0L)))
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

    /** Update basic metadata on an existing cert. Slug is immutable —
     *  changing it would orphan persisted questions, attempts, votes, and
     *  domain-admin assignments that key off it. Optional {@code newTopics}
     *  replaces every {@link ExamTopic} for the exam atomically; pass
     *  {@code null} or empty to keep the existing breakdown. Used by the
     *  super-admin Edit + Republish flow. */
    @org.springframework.transaction.annotation.Transactional
    public Exam updateExam(Long id, String name, String description,
                           int questionsPerSession, int durationMinutes,
                           int passingScorePercent,
                           boolean active,
                           List<TopicBreakdownParser.TopicWeight> newTopics) {
        if (id == null) throw new IllegalArgumentException("Cert id is required.");
        Exam e = exams.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown certification: " + id));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Certification name cannot be blank.");
        }
        e.setName(name.trim());
        e.setDescription(description == null ? "" : description.trim());
        e.setQuestionsPerSession(clamp(questionsPerSession, 5, 200, e.getQuestionsPerSession()));
        e.setDurationMinutes(clamp(durationMinutes, 5, 600, e.getDurationMinutes()));
        e.setPassingScorePercent(clamp(passingScorePercent, 1, 100, e.getPassingScorePercent()));
        e.setActive(active);
        exams.save(e);

        if (newTopics != null && !newTopics.isEmpty()) {
            replaceTopicsFor(e, newTopics);
        }
        return e;
    }

    /** Wipe every existing ExamTopic for {@code exam} and re-insert from the
     *  supplied list. Stays in one transaction so a partial failure can't
     *  leave the cert with a frankenstein topic mix. */
    @org.springframework.transaction.annotation.Transactional
    public void replaceTopicsFor(Exam exam, List<TopicBreakdownParser.TopicWeight> topics) {
        if (exam == null) return;
        List<ExamTopic> existing = examTopics.findByExamOrderBySortOrderAscIdAsc(exam);
        for (ExamTopic et : existing) {
            examTopics.delete(et);
        }
        if (topics == null) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        int order = 10;
        for (TopicBreakdownParser.TopicWeight t : topics) {
            if (t == null || t.topicKey() == null || t.topicKey().isBlank()) continue;
            if (!seen.add(t.topicKey())) continue;
            ExamTopic et = new ExamTopic();
            et.setExam(exam);
            et.setTopicKey(t.topicKey());
            et.setName(t.name());
            et.setWeightPercent(t.weightPercent());
            et.setSortOrder(order);
            order += 10;
            examTopics.save(et);
        }
    }

    public Exam findById(Long id) {
        if (id == null) return null;
        return exams.findById(id).orElse(null);
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
