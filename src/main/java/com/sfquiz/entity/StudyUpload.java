package com.sfquiz.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** A study-material file the admin uploaded via /admin/uploads. The bytes live
 *  in this row (not the dyno filesystem, which is ephemeral on Heroku). An
 *  in-process extractor picks it up, calls Claude, and writes survivors as
 *  PENDING questions for review. */
@Entity
@Table(name = "study_uploads")
public class StudyUpload {

    public enum Status { PENDING, PROCESSING, DONE, FAILED, SKIPPED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Bytes are large; only load them when actually needed (extraction
     *  pipeline or download endpoint) — never on the listing page. */
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** Email of the user who uploaded this file. Used to scope the listing
     *  page so non-admins see only their own uploads. Nullable for legacy rows. */
    @Column(name = "uploaded_by_email", length = 191)
    private String uploadedByEmail;

    /** Which exam's question bank the extractor should route this upload to.
     *  Required at upload time so non-admin users explicitly tag what they're
     *  contributing. Nullable on legacy rows (extractor defaults to admin). */
    @Column(name = "exam_slug", length = 64)
    private String examSlug;

    /** Legacy field — was used by the old "admin overrides dump-check" flow.
     *  Kept here so existing rows / the DB column stay valid; the upload
     *  pipeline no longer reads it. Dump-suspected uploads now flow through
     *  to the admin review queue with {@link #dumpSuspected} = true instead
     *  of being blocked. */
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "dump_check_override", nullable = false)
    private boolean dumpCheckOverride = false;

    /** True when Claude's dump-check flagged this upload as possibly leaked
     *  exam content. The questions still get extracted and queued for admin
     *  review — this flag just surfaces a yellow warning on the user's
     *  upload list ("admin will review extra carefully") and lets the admin
     *  queue badge "dump-suspected" so they can apply heavier scrutiny. */
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "dump_suspected", nullable = false)
    private boolean dumpSuspected = false;

    /** SHA-256 of the file bytes — stable identifier so we can detect when
     *  the same file (or an identical copy) is re-uploaded later. Nullable
     *  on legacy rows; populated by StudyUploadController on every new
     *  upload going forward. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** When this upload's contentHash matched a prior upload's, this is
     *  the id of that earlier upload. Surfaces a "re-upload of #N" badge
     *  in the UI and lets the import pipeline auto-retire the previously
     *  approved questions when their new versions are approved — the
     *  "uploading a corrected PDF replaces the old questions" flow. */
    @Column(name = "duplicate_of_upload_id")
    private Long duplicateOfUploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "questions_extracted")
    private Integer questionsExtracted;

    @Column(name = "questions_imported")
    private Integer questionsImported;

    @Column(name = "error", length = 1024)
    private String error;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
    public String getUploadedByEmail() { return uploadedByEmail; }
    public void setUploadedByEmail(String uploadedByEmail) { this.uploadedByEmail = uploadedByEmail; }
    public String getExamSlug() { return examSlug; }
    public void setExamSlug(String examSlug) { this.examSlug = examSlug; }
    public boolean isDumpCheckOverride() { return dumpCheckOverride; }
    public void setDumpCheckOverride(boolean dumpCheckOverride) { this.dumpCheckOverride = dumpCheckOverride; }
    public boolean isDumpSuspected() { return dumpSuspected; }
    public void setDumpSuspected(boolean dumpSuspected) { this.dumpSuspected = dumpSuspected; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Long getDuplicateOfUploadId() { return duplicateOfUploadId; }
    public void setDuplicateOfUploadId(Long duplicateOfUploadId) { this.duplicateOfUploadId = duplicateOfUploadId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public Integer getQuestionsExtracted() { return questionsExtracted; }
    public void setQuestionsExtracted(Integer questionsExtracted) { this.questionsExtracted = questionsExtracted; }
    public Integer getQuestionsImported() { return questionsImported; }
    public void setQuestionsImported(Integer questionsImported) { this.questionsImported = questionsImported; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
