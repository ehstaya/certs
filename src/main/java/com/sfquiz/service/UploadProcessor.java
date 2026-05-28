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

        // Pull every scalar we'll need later into locals up-front so we can
        // drop the entity reference (and its byte[] content) as soon as
        // possible. On a 512 MB Heroku dyno, keeping a 25 MB PDF pinned in
        // heap through the 15–30 s Claude call is the difference between
        // staying under the cap and an R14.
        String originalName  = u.getOriginalName();
        String examSlug      = u.getExamSlug();
        String uploaderEmail = u.getUploadedByEmail();

        TextExtractor.Kind kind = TextExtractor.classify(originalName);
        if (kind == TextExtractor.Kind.UNSUPPORTED) {
            markSkipped(uploadId, "unsupported file extension");
            return;
        }

        List<ImportQuestionRequest> drafts;
        try {
            if (kind == TextExtractor.Kind.IMAGE) {
                String mime = TextExtractor.imageMime(originalName);
                byte[] bytes = u.getContent();
                u = null;  // release the entity (and the byte[] held inside it ASAP after the call)
                drafts = anthropic.extractFromImage(bytes, mime, uploadId);
                bytes = null;
            } else {
                // Extract text from the bytes, then free the bytes BEFORE the
                // Claude call so we don't hold both in memory simultaneously.
                byte[] bytes = u.getContent();
                u = null;
                String text = texts.extractText(originalName, bytes);
                bytes = null;
                if (text == null || text.isBlank()) {
                    markSkipped(uploadId, "could not extract any text from file");
                    return;
                }
                // Dump-check is now advisory, not blocking. Flag the upload
                // so the user sees a yellow warning and the admin review queue
                // shows a "dump-suspected" badge, but let the questions flow
                // through to the queue — admin makes the final call.
                String dumpDecision = anthropic.dumpCheck(text, uploadId);
                if ("dump".equals(dumpDecision)) {
                    log.warn("upload id={} flagged as possible exam dump — flowing to admin review with warning", uploadId);
                    markDumpSuspected(uploadId);
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

        String slug = (examSlug == null || examSlug.isBlank())
                ? DEFAULT_EXAM_SLUG
                : examSlug;

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
        QuestionAdminService.ImportResult result = questions.importExam(req, uploaderEmail);
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

    /** Stamp the upload row so the user and admin both see a warning that
     *  this upload was flagged as possible leaked exam content. The
     *  questions are still extracted and queued — this is advisory only.
     *  Short tx so the listing page sees the flag as soon as it's set. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDumpSuspected(Long id) {
        uploads.findById(id).ifPresent(u -> {
            u.setDumpSuspected(true);
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
