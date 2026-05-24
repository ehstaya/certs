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

    /** Admin override: when true, UploadProcessor skips the Claude dump-check
     *  step that ordinarily marks leaked-exam content as SKIPPED. Used when an
     *  admin reviews a flagged upload and decides to force-extract anyway. */
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "dump_check_override", nullable = false)
    private boolean dumpCheckOverride = false;

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
