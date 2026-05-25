package com.sfquiz.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sfquiz.dto.ImportExamRequest;
import com.sfquiz.dto.ImportQuestionRequest;
import com.sfquiz.dto.ImportQuestionRequest.ImportChoiceRequest;
import com.sfquiz.entity.Choice;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ImportEvent;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.ImportEventRepository;
import com.sfquiz.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Import / approve / reject / edit of crawler-supplied questions. */
@Service
public class QuestionAdminService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAdminService.class);

    private final QuestionRepository repo;
    private final ExamRepository exams;
    private final ImportEventRepository importEvents;
    private final ObjectMapper json;
    private final ExplanationEnricher enricher;

    public QuestionAdminService(QuestionRepository repo, ExamRepository exams,
                                ImportEventRepository importEvents, ObjectMapper json,
                                ExplanationEnricher enricher) {
        this.repo = repo;
        this.exams = exams;
        this.importEvents = importEvents;
        this.json = json;
        this.enricher = enricher;
    }

    public record ImportResult(int imported, int skipped, List<String> skippedTexts) {}

    /** Upserts the target exam and imports its questions as PENDING for review. */
    @Transactional
    public ImportResult importExam(ImportExamRequest req) {
        if (req == null || req.slug() == null || req.slug().isBlank()) {
            return new ImportResult(0, 0, List.of("missing exam slug"));
        }
        Exam exam = upsertExam(req);

        List<ImportQuestionRequest> list = req.questions() == null ? List.of() : req.questions();
        int imported = 0, skipped = 0;
        List<String> skippedTexts = new ArrayList<>();
        int nextNumber = repo.findFirstByExamOrderByNumberDesc(exam).map(q -> q.getNumber() + 1).orElse(1);

        // Build a one-shot normalized-text → existing-Question map so we can
        // tag potential duplicates without N queries per draft.
        java.util.Map<String, Question> existingByNorm = new java.util.HashMap<>();
        for (Question existing : repo.findAllActiveByExam(exam)) {
            existingByNorm.putIfAbsent(normalizeText(existing.getText()), existing);
        }

        for (ImportQuestionRequest r : list) {
            String reason = validate(r);
            if (reason != null) {
                skipped++;
                skippedTexts.add(snippet(r) + " — " + reason);
                continue;
            }
            String text = r.text().trim();
            String normalized = normalizeText(text);
            Question dup = existingByNorm.get(normalized);

            Question q = new Question();
            q.setExam(exam);
            q.setStatus(Question.Status.PENDING);
            q.setNumber(nextNumber++);
            q.setType(parseType(r.type()));
            q.setText(text);
            q.setExplanation(blankToNull(r.explanation()));
            q.setHelpUrl(blankToNull(r.helpUrl()));
            q.setSourceUrl(blankToNull(r.sourceUrl()));
            // Tag with the existing question's id so the admin sees a warning
            // badge during review. Admin decides whether to keep as a variant
            // (approve) or reject as a true duplicate.
            if (dup != null) q.setDuplicateOfId(dup.getId());
            int i = 0;
            for (ImportChoiceRequest c : r.choices()) {
                Choice ch = new Choice();
                ch.setLabel(c.label() != null && !c.label().isBlank() ? c.label().trim() : letterFor(i));
                ch.setText(c.text().trim());
                ch.setCorrect(c.correct());
                q.addChoice(ch);
                i++;
            }
            repo.save(q);
            imported++;
            if (dup != null) {
                skippedTexts.add(snippet(r) + " — possible duplicate of #" + dup.getNumber() + " (kept as PENDING for review)");
            }
        }
        log.info("question import: exam='{}' imported={}, skipped={}", exam.getSlug(), imported, skipped);
        recordImportEvent(exam.getSlug(), imported, skipped, skippedTexts);
        return new ImportResult(imported, skipped, skippedTexts);
    }

    /** Lowercase + collapse all whitespace runs to a single space for the
     *  duplicate-similarity comparison. Used both at import time and on
     *  display for "show me potential duplicates". */
    private static String normalizeText(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private void recordImportEvent(String examSlug, int imported, int skipped, List<String> skippedTexts) {
        ImportEvent ev = new ImportEvent();
        ev.setOccurredAt(Instant.now());
        ev.setExamSlug(examSlug);
        ev.setImportedCount(imported);
        ev.setSkippedCount(skipped);
        ev.setSource("crawler");
        try {
            ev.setSkippedTextsJson(json.writeValueAsString(skippedTexts));
        } catch (JsonProcessingException e) {
            log.warn("failed to serialize skippedTexts for ImportEvent: {}", e.getMessage());
            ev.setSkippedTextsJson("[]");
        }
        importEvents.save(ev);
    }

    public List<ImportEvent> recentImportEvents() {
        return importEvents.findTop20ByOrderByOccurredAtDesc();
    }

    /** Pre-parsed view of an ImportEvent so Thymeleaf doesn't have to deal with JSON. */
    public record ImportEventView(Long id, Instant occurredAt, String examSlug,
                                  int imported, int skipped, List<String> skippedTexts) {
        public boolean allSkipped() { return imported == 0 && skipped > 0; }
    }

    public List<ImportEventView> recentImportEventViews() {
        List<ImportEvent> rows = recentImportEvents();
        List<ImportEventView> out = new ArrayList<>(rows.size());
        for (ImportEvent ev : rows) {
            List<String> texts = List.of();
            try {
                String s = ev.getSkippedTextsJson();
                if (s != null && !s.isBlank()) {
                    texts = json.readValue(s, json.getTypeFactory().constructCollectionType(List.class, String.class));
                }
            } catch (Exception e) {
                texts = List.of();
            }
            out.add(new ImportEventView(ev.getId(), ev.getOccurredAt(), ev.getExamSlug(),
                    ev.getImportedCount(), ev.getSkippedCount(), texts));
        }
        return out;
    }

    private Exam upsertExam(ImportExamRequest req) {
        Exam e = exams.findBySlug(req.slug()).orElseGet(Exam::new);
        boolean isNew = e.getId() == null;
        e.setSlug(req.slug());
        if (req.name() != null && !req.name().isBlank()) e.setName(req.name());
        else if (isNew) e.setName(req.slug());
        if (req.description() != null) e.setDescription(req.description());
        if (req.questionsPerSession() != null) e.setQuestionsPerSession(req.questionsPerSession());
        if (req.durationMinutes() != null) e.setDurationMinutes(req.durationMinutes());
        if (req.sortOrder() != null) e.setSortOrder(req.sortOrder());
        if (req.active() != null) e.setActive(req.active());
        return exams.save(e);
    }

    /** Soft-delete: move an approved question to the RETIRED queue, recording
     *  which admin pulled it back. The retiring admin (or any admin) can later
     *  restore it or hard-delete it. */
    @Transactional
    public void retire(Long id, String adminEmail) {
        Question q = get(id);
        log.info("Retiring question id={} number={} (was status={}) by={}",
                id, q.getNumber(), q.getStatus(), adminEmail);
        q.setStatus(Question.Status.RETIRED);
        q.setRetiredByEmail(adminEmail);
        q.setRetiredAt(java.time.Instant.now());
        repo.save(q);
    }

    /** Permanent removal — only valid from the retired queue. Cascades to
     *  delete the question's choices. */
    @Transactional
    public void permanentlyDelete(Long id) {
        Question q = get(id);
        log.warn("Permanently deleting question id={} number={} (status={}, retiredBy={})",
                id, q.getNumber(), q.getStatus(), q.getRetiredByEmail());
        repo.delete(q);
    }

    /** Restore a retired question to the live bank. */
    @Transactional
    public void restore(Long id) {
        Question q = get(id);
        log.info("Restoring question id={} number={} (was RETIRED, retiredBy={})",
                id, q.getNumber(), q.getRetiredByEmail());
        q.setStatus(Question.Status.APPROVED);
        q.setRetiredByEmail(null);
        q.setRetiredAt(null);
        repo.save(q);
    }

    public record PagedApproved(List<Question> rows, int page, int totalPages, long totalElements, int pageSize) {}

    /** Page through approved questions, optionally filtered to one exam. */
    public PagedApproved approvedPage(String examSlug, int page, int pageSize) {
        return paged(examSlug, Question.Status.APPROVED, page, pageSize, null);
    }

    /** Page through retired questions, optionally filtered to one exam. */
    public PagedApproved retiredPage(String examSlug, int page, int pageSize) {
        return paged(examSlug, Question.Status.RETIRED, page, pageSize, null);
    }

    /** Domain-scoped variant — only rows whose exam slug is in {@code managedSlugs}
     *  survive. Pass {@code null} (or a set containing the {@code "*"} wildcard)
     *  to skip the scope filter (super-admin behavior). */
    public PagedApproved approvedPageScoped(String examSlug, int page, int pageSize, Set<String> managedSlugs) {
        return paged(examSlug, Question.Status.APPROVED, page, pageSize, managedSlugs);
    }

    public PagedApproved retiredPageScoped(String examSlug, int page, int pageSize, Set<String> managedSlugs) {
        return paged(examSlug, Question.Status.RETIRED, page, pageSize, managedSlugs);
    }

    private PagedApproved paged(String examSlug, Question.Status status, int page, int pageSize,
                                Set<String> managedSlugs) {
        if (pageSize <= 0) pageSize = 20;
        if (page < 0) page = 0;
        List<Question> all = (examSlug == null || examSlug.isBlank())
                ? repo.findByStatusOrderByNumber(status)
                : repo.findByExamSlugAndStatusOrderByNumber(examSlug, status);
        if (managedSlugs != null && !managedSlugs.contains("*")) {
            all = all.stream()
                    .filter(q -> q.getExam() != null && managedSlugs.contains(q.getExam().getSlug()))
                    .toList();
        }
        long total = all.size();
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        if (page >= totalPages) page = totalPages - 1;
        int from = Math.min(page * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new PagedApproved(all.subList(from, to), page, totalPages, total, pageSize);
    }

    public long retiredCount() {
        return repo.countByStatus(Question.Status.RETIRED);
    }

    @Transactional
    public void approve(Long id) {
        Question q = get(id);
        q.setStatus(Question.Status.APPROVED);
        repo.save(q);
        // Admin is the first pass for explanations. If they approved this
        // question without supplying one, hand it off to Claude to generate
        // one from the vendor's docs in the background — the admin already
        // had their chance to edit.
        boolean needsEnrichment = (q.getExplanation() == null || q.getExplanation().isBlank())
                || (q.getHelpUrl() == null || q.getHelpUrl().isBlank());
        if (needsEnrichment) {
            enricher.enrichOneAsync(id);
        }
    }

    @Transactional
    public void reject(Long id) {
        setStatus(id, Question.Status.REJECTED);
    }

    /** Take an APPROVED question out of the live bank and put it back into
     *  the pending-review queue. Used from the verifier-feedback report when
     *  an admin wants a domain admin to re-validate the question without
     *  retiring it outright. */
    @Transactional
    public void sendBackToReview(Long id) {
        Question q = get(id);
        if (q.getStatus() == Question.Status.PENDING) return; // already there
        log.info("Sending question id={} back to review (was {})", id, q.getStatus());
        q.setStatus(Question.Status.PENDING);
        repo.save(q);
    }

    public Question get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown question id: " + id));
    }

    /** Result of a manual question create — id of the new row + the
     *  number we assigned within the exam. */
    public record ManualCreateResult(Long id, int number, String examSlug) {}

    /** Create one PENDING question from scratch (the "compose question"
     *  form). Mirrors {@link #update}'s field-validation logic but inserts
     *  a fresh row instead of mutating an existing one. The new question
     *  goes straight into the review queue — same approval flow as
     *  scraper-imported and screenshot-extracted questions. */
    @Transactional
    public ManualCreateResult createManual(String examSlug,
                                           String type,
                                           String text,
                                           String explanation,
                                           String helpUrl,
                                           List<String> choiceTexts,
                                           List<Integer> correctIndexes,
                                           String creatorEmail) {
        if (examSlug == null || examSlug.isBlank()) {
            throw new IllegalArgumentException("Please pick a certification.");
        }
        Exam exam = exams.findBySlug(examSlug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown certification: " + examSlug));
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Question text is required.");
        }

        // Validate the choice set up-front so we never persist a half-baked
        // question. SINGLE = exactly one correct; MULTI = at least 2.
        Question.Type qType = parseType(type);
        List<String> texts = choiceTexts == null ? List.of() : choiceTexts;
        List<Integer> correct = correctIndexes == null ? List.of() : correctIndexes;
        int validCount = 0;
        int validCorrect = 0;
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null || t.isBlank()) continue;
            validCount++;
            if (correct.contains(i)) validCorrect++;
        }
        if (validCount < 2) {
            throw new IllegalArgumentException("Need at least 2 choices.");
        }
        if (qType == Question.Type.SINGLE && validCorrect != 1) {
            throw new IllegalArgumentException("A SINGLE question must have exactly one correct answer.");
        }
        if (qType == Question.Type.MULTI && validCorrect < 2) {
            throw new IllegalArgumentException("A MULTI question must have at least two correct answers.");
        }

        // Find next per-exam number — same logic as the bulk import path.
        int nextNumber = repo.findFirstByExamOrderByNumberDesc(exam)
                .map(q -> q.getNumber() + 1).orElse(1);

        // Duplicate check — non-blocking. If we find a near-identical text
        // already in the bank, tag the new row so the reviewer sees a warning.
        String normalized = normalizeText(text);
        Question dup = null;
        for (Question existing : repo.findAllActiveByExam(exam)) {
            if (normalizeText(existing.getText()).equals(normalized)) { dup = existing; break; }
        }

        Question q = new Question();
        q.setExam(exam);
        q.setStatus(Question.Status.PENDING);
        q.setNumber(nextNumber);
        q.setType(qType);
        q.setText(text.trim());
        q.setExplanation(blankToNull(explanation));
        q.setHelpUrl(blankToNull(helpUrl));
        // Stamp a sourceUrl-like marker so reviewers see who composed it.
        if (creatorEmail != null && !creatorEmail.isBlank()) {
            q.setSourceUrl("manual:" + creatorEmail);
        }
        if (dup != null) q.setDuplicateOfId(dup.getId());

        // Add the non-blank choices in their submitted order, auto-labelled.
        int label = 0;
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null || t.isBlank()) continue;
            Choice ch = new Choice();
            ch.setLabel(letterFor(label++));
            ch.setText(t.trim());
            ch.setCorrect(correct.contains(i));
            q.addChoice(ch);
        }
        repo.save(q);
        log.info("Manual question created: exam='{}' #{} by={} type={} choices={}",
                exam.getSlug(), nextNumber, creatorEmail, qType, label);
        return new ManualCreateResult(q.getId(), nextNumber, exam.getSlug());
    }

    @Transactional
    public void update(Long id, String type, String text, String explanation, String helpUrl,
                       List<String> choiceTexts, List<Integer> correctIndexes) {
        Question q = get(id);
        Question.Status priorStatus = q.getStatus();
        q.setType(parseType(type));
        if (text != null && !text.isBlank()) {
            q.setText(text.trim());
        }
        q.setExplanation(blankToNull(explanation));
        q.setHelpUrl(blankToNull(helpUrl));
        q.clearChoices();
        List<Integer> correct = correctIndexes == null ? List.of() : correctIndexes;
        List<String> texts = choiceTexts == null ? List.of() : choiceTexts;
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null || t.isBlank()) {
                continue;
            }
            Choice ch = new Choice();
            ch.setLabel(letterFor(q.getChoices().size()));
            ch.setText(t.trim());
            ch.setCorrect(correct.contains(i));
            q.addChoice(ch);
        }
        // Editing a live question pulls it out of the bank and back into the
        // review queue. The admin (or another admin) re-approves it through
        // the normal flow — matches the user-upload → review pipeline.
        if (priorStatus == Question.Status.APPROVED) {
            q.setStatus(Question.Status.PENDING);
            log.info("Question id={} edit pulled it back to PENDING for re-review", id);
        }
        repo.save(q);
    }

    public List<Question> pending() {
        return repo.findByStatusOrderByNumber(Question.Status.PENDING);
    }

    /** Domain-scoped pending list — drops rows whose exam slug isn't in
     *  {@code managedSlugs}. Null or "*"-wildcard set means "no filter". */
    public List<Question> pendingScoped(Set<String> managedSlugs) {
        List<Question> all = pending();
        if (managedSlugs == null || managedSlugs.contains("*")) return all;
        return all.stream()
                .filter(q -> q.getExam() != null && managedSlugs.contains(q.getExam().getSlug()))
                .toList();
    }

    public long pendingCount() {
        return repo.countByStatus(Question.Status.PENDING);
    }

    public long approvedCount() {
        return repo.countByStatus(Question.Status.APPROVED);
    }

    /** Counts filtered to the slugs the caller manages. SUPERADMIN ("*") gets
     *  the unscoped totals. */
    public long pendingCountScoped(Set<String> managedSlugs)  { return countScoped(Question.Status.PENDING,  managedSlugs); }
    public long approvedCountScoped(Set<String> managedSlugs) { return countScoped(Question.Status.APPROVED, managedSlugs); }
    public long retiredCountScoped(Set<String> managedSlugs)  { return countScoped(Question.Status.RETIRED,  managedSlugs); }

    private long countScoped(Question.Status status, Set<String> managedSlugs) {
        if (managedSlugs == null || managedSlugs.contains("*")) return repo.countByStatus(status);
        return repo.findByStatusOrderByNumber(status).stream()
                .filter(q -> q.getExam() != null && managedSlugs.contains(q.getExam().getSlug()))
                .count();
    }

    public List<Question> recentApproved(int limit) {
        List<Question> approved = new ArrayList<>(repo.findByStatusOrderByNumber(Question.Status.APPROVED));
        Collections.reverse(approved);
        return approved.size() > limit ? approved.subList(0, limit) : approved;
    }

    // --- helpers ---------------------------------------------------------------

    private void setStatus(Long id, Question.Status status) {
        Question q = get(id);
        q.setStatus(status);
        repo.save(q);
    }

    private static String validate(ImportQuestionRequest r) {
        if (r == null) return "null entry";
        if (r.text() == null || r.text().trim().length() < 10) return "text too short";
        if (r.choices() == null || r.choices().size() < 2) return "fewer than 2 choices";
        for (ImportChoiceRequest c : r.choices()) {
            if (c == null || c.text() == null || c.text().isBlank()) return "blank choice text";
        }
        long correct = r.choices().stream().filter(ImportChoiceRequest::correct).count();
        if (correct < 1) return "no correct choice";
        Question.Type t = parseTypeSafe(r.type());
        if (t == null) return "bad type (expected SINGLE or MULTI)";
        if (t == Question.Type.SINGLE && correct > 1) return "single-answer question with >1 correct choice";
        return null;
    }

    private static Question.Type parseType(String s) {
        Question.Type t = parseTypeSafe(s);
        return t == null ? Question.Type.SINGLE : t;
    }

    private static Question.Type parseTypeSafe(String s) {
        if (s == null) return null;
        try {
            return Question.Type.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String letterFor(int index) {
        return String.valueOf((char) ('A' + Math.min(index, 25)));
    }

    private static String snippet(ImportQuestionRequest r) {
        if (r == null || r.text() == null) return "(null)";
        String t = r.text().trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }
}
