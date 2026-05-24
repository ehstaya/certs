package com.sfquiz.service;

import com.sfquiz.dto.ImportExamRequest;
import com.sfquiz.dto.ImportQuestionRequest;
import com.sfquiz.entity.StudyUpload;
import com.sfquiz.entity.StudyUpload.Status;
import com.sfquiz.repository.StudyUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Orchestrates the per-upload extraction pipeline. Runs asynchronously off the
 *  HTTP request thread so uploads stay snappy; a scheduled sweep recovers
 *  uploads stuck in PROCESSING (e.g. dyno restart). */
@Service
public class UploadProcessor {

    private static final Logger log = LoggerFactory.getLogger(UploadProcessor.class);

    /** Fallback for legacy upload rows where examSlug was never set. */
    private static final String DEFAULT_EXAM_SLUG = "salesforce-admin";

    private final StudyUploadRepository uploads;
    private final TextExtractor texts;
    private final AnthropicExtractor anthropic;
    private final QuestionAdminService questions;
    private final CostMeter costs;
    private final AdminNotifier adminNotifier;
    private final int stuckAfterMinutes;

    public UploadProcessor(StudyUploadRepository uploads,
                           TextExtractor texts,
                           AnthropicExtractor anthropic,
                           QuestionAdminService questions,
                           CostMeter costs,
                           AdminNotifier adminNotifier,
                           @Value("${app.extraction.stuck-after-minutes:5}") int stuckAfterMinutes) {
        this.uploads = uploads;
        this.texts = texts;
        this.anthropic = anthropic;
        this.questions = questions;
        this.costs = costs;
        this.adminNotifier = adminNotifier;
        this.stuckAfterMinutes = stuckAfterMinutes;
    }

    /** Trigger asynchronous processing of one upload. Returns immediately. */
    @Async
    public void processAsync(Long uploadId) {
        try {
            process(uploadId);
        } catch (Exception e) {
            log.error("unhandled error processing upload {}: {}", uploadId, e.getMessage(), e);
            markFailed(uploadId, "unhandled: " + e.getMessage());
        }
    }

    /** Recover uploads that were left in PROCESSING (likely a dyno restart). */
    @Scheduled(fixedDelayString = "${app.extraction.recovery-interval-ms:60000}",
               initialDelayString = "30000")
    public void recoverStuckUploads() {
        Instant cutoff = Instant.now().minus(stuckAfterMinutes, ChronoUnit.MINUTES);
        List<StudyUpload> stuck = uploads.findByStatusAndUploadedAtBefore(Status.PROCESSING, cutoff);
        for (StudyUpload u : stuck) {
            log.warn("recovering stuck upload id={} name={} stuck since {}", u.getId(), u.getOriginalName(), u.getUploadedAt());
            markPending(u.getId());
            processAsync(u.getId());
        }
        // Also: anything still in PENDING (e.g. enqueued just before a restart, so the async never ran).
        for (StudyUpload u : uploads.findByStatus(Status.PENDING)) {
            log.info("retrying pending upload id={} name={}", u.getId(), u.getOriginalName());
            processAsync(u.getId());
        }
    }

    // -------------------------------------------------------------------------
    // Core pipeline
    // -------------------------------------------------------------------------

    private void process(Long uploadId) {
        StudyUpload u = uploads.findById(uploadId).orElse(null);
        if (u == null) {
            log.warn("processAsync: upload {} not found", uploadId);
            return;
        }

        if (!anthropic.enabled()) {
            markFailed(uploadId, "ANTHROPIC_API_KEY is not set on this dyno — set it via `heroku config:set` and re-upload");
            return;
        }
        if (costs.budgetExhausted()) {
            markFailed(uploadId, "Daily Anthropic budget ($" + costs.dailyBudgetUsd() + ") is exhausted — resumes at UTC midnight");
            return;
        }

        markProcessing(uploadId);

        TextExtractor.Kind kind = TextExtractor.classify(u.getOriginalName());
        if (kind == TextExtractor.Kind.UNSUPPORTED) {
            markSkipped(uploadId, "unsupported file extension");
            return;
        }

        List<ImportQuestionRequest> drafts;
        try {
            if (kind == TextExtractor.Kind.IMAGE) {
                String mime = TextExtractor.imageMime(u.getOriginalName());
                drafts = anthropic.extractFromImage(u.getContent(), mime, uploadId);
            } else {
                String text = texts.extractText(u.getOriginalName(), u.getContent());
                if (text == null || text.isBlank()) {
                    markSkipped(uploadId, "could not extract any text from file");
                    return;
                }
                if (u.isDumpCheckOverride()) {
                    log.warn("upload id={} extracting WITHOUT dump-check (admin override)", uploadId);
                } else {
                    String dumpDecision = anthropic.dumpCheck(text, uploadId);
                    if ("dump".equals(dumpDecision)) {
                        markSkipped(uploadId, "content flagged as exam dump — refusing to import (admin can override)");
                        return;
                    }
                }
                drafts = anthropic.extractFromText(text, uploadId);
            }
        } catch (Exception e) {
            log.error("anthropic call failed for upload {}: {}", uploadId, e.toString(), e);
            markFailed(uploadId, "Anthropic call failed: " + e.getMessage());
            return;
        }

        if (drafts.isEmpty()) {
            markDone(uploadId, 0, 0);
            return;
        }

        String slug = (u.getExamSlug() == null || u.getExamSlug().isBlank())
                ? DEFAULT_EXAM_SLUG
                : u.getExamSlug();

        // Note: explanations are intentionally NOT auto-enriched here. The admin
        // is the first pass — they can write their own explanation while editing
        // the pending question, and only if they approve it blank does the
        // enricher kick in (see QuestionAdminService.approve).

        // The exam already exists (seeded on startup) so leave name/description
        // null — QuestionAdminService.importExam() upserts and reuses metadata.
        ImportExamRequest req = new ImportExamRequest(
                slug, null,
                null, null, null, null, null,
                drafts
        );
        QuestionAdminService.ImportResult result = questions.importExam(req);
        markDone(uploadId, drafts.size(), result.imported());
    }

    // -------------------------------------------------------------------------
    // Status transitions — each in its own short transaction
    // -------------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(Long id) {
        uploads.findById(id).ifPresent(u -> {
            u.setStatus(Status.PROCESSING);
            uploads.save(u);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPending(Long id) {
        uploads.findById(id).ifPresent(u -> {
            u.setStatus(Status.PENDING);
            uploads.save(u);
        });
    }

    /** Persist the dump-check-override flag in its own short transaction so
     *  the subsequent async processAsync() sees the updated row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setDumpCheckOverride(Long id, boolean override) {
        uploads.findById(id).ifPresent(u -> {
            u.setDumpCheckOverride(override);
            uploads.save(u);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(Long id, int extracted, int imported) {
        uploads.findById(id).ifPresent(u -> {
            u.setStatus(Status.DONE);
            u.setProcessedAt(Instant.now());
            u.setQuestionsExtracted(extracted);
            u.setQuestionsImported(imported);
            u.setError(null);
            uploads.save(u);
            adminNotifier.notifyExtractionDone(u.getOriginalName(), extracted, imported);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long id, String error) {
        uploads.findById(id).ifPresent(u -> {
            u.setStatus(Status.FAILED);
            u.setProcessedAt(Instant.now());
            u.setError(truncate(error));
            uploads.save(u);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSkipped(Long id, String reason) {
        uploads.findById(id).ifPresent(u -> {
            u.setStatus(Status.SKIPPED);
            u.setProcessedAt(Instant.now());
            u.setError(truncate(reason));
            uploads.save(u);
        });
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
