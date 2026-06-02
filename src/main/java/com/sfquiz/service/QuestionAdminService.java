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
    private final com.sfquiz.repository.QuestionActionRepository questionActions;

    public QuestionAdminService(QuestionRepository repo, ExamRepository exams,
                                ImportEventRepository importEvents, ObjectMapper json,
                                ExplanationEnricher enricher,
                                com.sfquiz.repository.QuestionActionRepository questionActions) {
        this.repo = repo;
        this.exams = exams;
        this.importEvents = importEvents;
        this.json = json;
        this.enricher = enricher;
        this.questionActions = questionActions;
    }

    /** Append one row to the action log. Cheap, non-blocking — never
     *  throws even if persistence fails (logs and moves on); the audit
     *  log is best-effort. */
    private void logAction(com.sfquiz.entity.Question q,
                           com.sfquiz.entity.QuestionAction.Action action,
                           String adminEmail) {
        try {
            com.sfquiz.entity.QuestionAction a = new com.sfquiz.entity.QuestionAction();
            a.setQuestionId(q != null ? q.getId() : null);
            a.setAdminEmail(adminEmail == null ? "(unknown)" : adminEmail);
            a.setExamSlug(q != null && q.getExam() != null ? q.getExam().getSlug() : null);
            a.setAction(action);
            a.setOccurredAt(java.time.Instant.now());
            questionActions.save(a);
        } catch (Exception ex) {
            log.warn("Failed to log {} on q={} by={}: {}", action,
                    q == null ? null : q.getId(), adminEmail, ex.getMessage());
        }
    }

    public record ImportResult(int imported, int skipped, List<String> skippedTexts) {}

    /** Crawler entry-point — imports questions stamped as originating from the
     *  crawler service account. Use {@link #importExam(ImportExamRequest, String)}
     *  when an upload path needs to credit the uploader. */
    @Transactional
    public ImportResult importExam(ImportExamRequest req) {
        return importExam(req, "crawler");
    }

    /** Upserts the target exam and imports its questions as PENDING for review.
     *  {@code creatorEmail} is stamped on every new {@link Question} so the
     *  bank-progress report can credit the originator (uploader, crawler, etc.). */
    @Transactional
    public ImportResult importExam(ImportExamRequest req, String creatorEmail) {
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
            q.setCreatedAt(java.time.Instant.now());
            q.setCreatedByEmail(creatorEmail);
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
        return recentImportEventViewsScoped(null);
    }

    /** Domain-scoped variant — only events whose exam slug is in
     *  {@code managedSlugs} survive. Pass {@code null} or a set containing
     *  the {@code "*"} wildcard to skip the filter (super-admin behavior). */
    public List<ImportEventView> recentImportEventViewsScoped(Set<String> managedSlugs) {
        List<ImportEvent> rows = recentImportEvents();
        // Resolve the scope to a non-null set we can safely query each row
        // against. Null OR a wildcard set means "no filter" -> empty set
        // here, and we check that case via the `scoped` flag below.
        Set<String> filter = (managedSlugs == null || managedSlugs.contains("*"))
                ? Collections.emptySet()
                : managedSlugs;
        boolean scoped = !filter.isEmpty();
        List<ImportEventView> out = new ArrayList<>(rows.size());
        for (ImportEvent ev : rows) {
            if (scoped && !filter.contains(ev.getExamSlug())) continue;
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
        logAction(q, com.sfquiz.entity.QuestionAction.Action.RETIRE, adminEmail);
    }

    /** Permanent removal — cascades to delete the question's choices. The
     *  QuestionAction event is logged FIRST so the audit row outlives the
     *  question itself; questionId in the action stays as a bare long that
     *  no longer dereferences but tells you "row #427 was permanently
     *  deleted by Jane at this time." */
    @Transactional
    public void permanentlyDelete(Long id, String adminEmail) {
        Question q = get(id);
        log.warn("Permanently deleting question id={} number={} (status={}, retiredBy={}) by={}",
                id, q.getNumber(), q.getStatus(), q.getRetiredByEmail(), adminEmail);
        logAction(q, com.sfquiz.entity.QuestionAction.Action.PERMANENT_DELETE, adminEmail);
        repo.delete(q);
    }

    /** Restore a retired question to the live bank. */
    @Transactional
    public void restore(Long id, String adminEmail) {
        Question q = get(id);
        log.info("Restoring question id={} number={} (was RETIRED, retiredBy={}) by={}",
                id, q.getNumber(), q.getRetiredByEmail(), adminEmail);
        q.setStatus(Question.Status.APPROVED);
        q.setRetiredByEmail(null);
        q.setRetiredAt(null);
        repo.save(q);
        logAction(q, com.sfquiz.entity.QuestionAction.Action.RESTORE, adminEmail);
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
        // DB-level pagination — previous code loaded the entire bank
        // (~500 approved questions × EAGER choices = ~2500-row JOIN) just
        // to slice 20 rows in memory. That was the /admin/questions/approved
        // 10-second hit when SUPERADMIN browsed without an exam filter.
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(
                        page, pageSize,
                        org.springframework.data.domain.Sort.by("number").ascending());
        boolean hasExam = examSlug != null && !examSlug.isBlank();
        org.springframework.data.domain.Page<Question> pg;
        if (hasExam) {
            // Controller already gates access via requireCanManage when an
            // exam slug is pinned, so the manager-scope filter is redundant
            // here regardless of role.
            pg = repo.findByExamSlugAndStatus(examSlug, status, pageable);
        } else if (managedSlugs == null || managedSlugs.contains("*")) {
            // SUPERADMIN (or callers passing null/wildcard) — no scope.
            pg = repo.findByStatus(status, pageable);
        } else if (managedSlugs.isEmpty()) {
            return new PagedApproved(List.of(), 0, 1, 0, pageSize);
        } else {
            pg = repo.findByStatusAndExamSlugIn(status, managedSlugs, pageable);
        }
        int totalPages = Math.max(1, pg.getTotalPages());
        int actualPage = Math.min(page, totalPages - 1);
        return new PagedApproved(pg.getContent(), actualPage, totalPages, pg.getTotalElements(), pageSize);
    }

    public long retiredCount() {
        return repo.countByStatus(Question.Status.RETIRED);
    }

    @Transactional
    public void approve(Long id, String adminEmail) {
        Question q = get(id);
        q.setStatus(Question.Status.APPROVED);
        repo.save(q);
        logAction(q, com.sfquiz.entity.QuestionAction.Action.APPROVE, adminEmail);

        // Override-on-approve: this question was flagged at import time as a
        // duplicate of an existing question (its text matched). Approving
        // the new version means "the new wording wins" — retire the
        // previously approved original so the live bank holds exactly one
        // copy and learners don't see both. We only override APPROVED
        // originals; if the original was already RETIRED / REJECTED /
        // PENDING, leave it alone (no live duplication to clean up).
        Long origId = q.getDuplicateOfId();
        if (origId != null) {
            repo.findById(origId).ifPresent(orig -> {
                if (orig.getStatus() == Question.Status.APPROVED && !orig.getId().equals(q.getId())) {
                    orig.setStatus(Question.Status.RETIRED);
                    orig.setRetiredByEmail(adminEmail);
                    orig.setRetiredAt(java.time.Instant.now());
                    repo.save(orig);
                    logAction(orig, com.sfquiz.entity.QuestionAction.Action.RETIRE, adminEmail);
                    log.info("override-on-approve: q#{} approved -> retired prior q#{} (same text, replaced by re-upload)",
                            q.getNumber(), orig.getNumber());
                }
            });
        }

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
    public void reject(Long id, String adminEmail) {
        Question q = get(id);
        q.setStatus(Question.Status.REJECTED);
        repo.save(q);
        logAction(q, com.sfquiz.entity.QuestionAction.Action.REJECT, adminEmail);
    }

    /** Take an APPROVED question out of the live bank and put it back into
     *  the pending-review queue. Used from the verifier-feedback report when
     *  an admin wants a domain admin to re-validate the question without
     *  retiring it outright. */
    @Transactional
    public void sendBackToReview(Long id, String adminEmail) {
        Question q = get(id);
        if (q.getStatus() == Question.Status.PENDING) return; // already there
        log.info("Sending question id={} back to review (was {}) by={}", id, q.getStatus(), adminEmail);
        q.setStatus(Question.Status.PENDING);
        repo.save(q);
        logAction(q, com.sfquiz.entity.QuestionAction.Action.SEND_BACK, adminEmail);
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
        // Help URL + explanation are both mandatory for compose-question:
        // together they form the audit trail proving the content came from
        // a legitimate vendor source (Trailhead, help.salesforce.com, etc.)
        // rather than a leaked exam dump. The browser enforces this
        // client-side via the `required` attribute; these guards catch
        // scripted POSTs that bypass the form.
        if (helpUrl == null || helpUrl.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Help URL is required — link to the official vendor doc that backs this question.");
        }
        String trimmedUrl = helpUrl.trim();
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "Help URL must be a full http(s):// link to a vendor doc.");
        }
        if (explanation == null || explanation.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Explanation is required — describe why the correct choice is correct, referencing the official docs.");
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
        q.setCreatedAt(java.time.Instant.now());
        q.setCreatedByEmail(creatorEmail);
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
        logAction(q, com.sfquiz.entity.QuestionAction.Action.MANUAL_CREATE, creatorEmail);
        return new ManualCreateResult(q.getId(), nextNumber, exam.getSlug());
    }

    @Transactional
    public void update(Long id, String type, String text, String explanation, String helpUrl,
                       List<String> choiceTexts, List<Integer> correctIndexes, String adminEmail) {
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
        logAction(q, com.sfquiz.entity.QuestionAction.Action.EDIT, adminEmail);
    }

    public List<Question> pending() {
        return repo.findByStatusOrderByNumber(Question.Status.PENDING);
    }

    /** Paged + scoped pending list — same shape as {@link PagedApproved}
     *  so the template can render a consistent pager. */
    public PagedApproved pendingPageScoped(int page, int pageSize, Set<String> managedSlugs) {
        return paged(null, Question.Status.PENDING, page, pageSize, managedSlugs);
    }

    /** Same as {@link #pendingPageScoped} but with an optional exam-slug
     *  filter. {@code examSlug} = null/blank → unfiltered; otherwise
     *  only PENDING rows on that cert appear. The exam filter is applied
     *  on top of the manager-scope filter, so a domain admin can never
     *  see another cert's queue even by pinning its slug. */
    public PagedApproved pendingPageScopedByExam(String examSlug, int page, int pageSize,
                                                 Set<String> managedSlugs) {
        return paged(examSlug, Question.Status.PENDING, page, pageSize, managedSlugs);
    }

    /** Per-cert pending counts for the certs in {@code managedSlugs}.
     *  Used to drive the "filter by certification" dropdown on
     *  /admin/questions: each option shows the cert name + how many
     *  questions are currently waiting on it. Order matches {@code
     *  examOptions} below. */
    public java.util.LinkedHashMap<String, Long> pendingCountsByExamScoped(Set<String> managedSlugs) {
        // One GROUP BY query — previous code loaded every PENDING question
        // with its EAGER choices just to tally them in memory.
        Set<String> filter = (managedSlugs == null || managedSlugs.contains("*"))
                ? java.util.Collections.emptySet()
                : managedSlugs;
        boolean scoped = !filter.isEmpty();
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>();
        for (Object[] row : repo.countByStatusGroupedByExamSlug(Question.Status.PENDING)) {
            String slug = (String) row[0];
            if (slug == null) continue;
            if (scoped && !filter.contains(slug)) continue;
            out.put(slug, ((Number) row[1]).longValue());
        }
        return out;
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
        if (managedSlugs.isEmpty()) return 0L;
        // Single-query scoped count — previous code loaded every question
        // of this status with its EAGER choices, then filtered + counted
        // in memory. With ~500 approved questions × 5 choices, that's a
        // 2500-row JOIN result per call, fired three times on every
        // /admin/questions render (PENDING / APPROVED / RETIRED).
        return repo.countByStatusAndExamSlugIn(status, managedSlugs);
    }

    public List<Question> recentApproved(int limit) {
        return recentApprovedScoped(limit, null);
    }

    /** Domain-scoped variant — only approved questions whose exam slug is
     *  in {@code managedSlugs}. Null or wildcard set = no scope filter.
     *
     *  Previous code loaded the entire approved bank (~500 questions ×
     *  EAGER choices) just to reverse + take 10. Now: a derived top-10
     *  query at the DB level. The {@code limit} parameter is honored
     *  only as a ceiling — callers ask for 10, we always return ≤ 10. */
    public List<Question> recentApprovedScoped(int limit, Set<String> managedSlugs) {
        Set<String> filter = (managedSlugs == null || managedSlugs.contains("*"))
                ? Collections.emptySet()
                : managedSlugs;
        boolean scoped = !filter.isEmpty();
        List<Question> top10 = scoped
                ? (filter.isEmpty() ? List.of()
                                    : repo.findTop10ByStatusAndExamSlugInOrderByIdDesc(Question.Status.APPROVED, filter))
                : repo.findTop10ByStatusOrderByIdDesc(Question.Status.APPROVED);
        return top10.size() > limit ? top10.subList(0, limit) : top10;
    }

    // --- helpers ---------------------------------------------------------------

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
