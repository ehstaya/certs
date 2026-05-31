package com.sfquiz.repository;

import com.sfquiz.entity.StudyUpload;

import java.time.Instant;

/** Closed Spring Data projection over {@link StudyUpload} that omits the
 *  large {@code content} byte[] column. Used for the listing page and the
 *  /uploads/api/status JSON endpoint — neither of those callers needs the
 *  file bytes, and loading them 50 rows at a time on a 4-second poll
 *  loop was saturating the DB pool with multi-MB rows.
 *
 *  Why a projection and not @Basic(fetch=LAZY)? @Basic LAZY on a byte[]
 *  only works when Hibernate bytecode enhancement is enabled, which it
 *  isn't in this project. The projection forces Hibernate to issue a
 *  column-pruned SELECT, so the bytes never leave Postgres unless a
 *  caller explicitly loads the full entity via findById (used by the
 *  download endpoint + the extraction processor). */
public interface StudyUploadSummary {
    Long getId();
    String getOriginalName();
    String getContentType();
    long getSizeBytes();
    Instant getUploadedAt();
    String getUploadedByEmail();
    String getExamSlug();
    boolean isDumpSuspected();
    String getContentHash();
    Long getDuplicateOfUploadId();
    StudyUpload.Status getStatus();
    Instant getProcessedAt();
    Integer getQuestionsExtracted();
    Integer getQuestionsImported();
    String getError();
    boolean isArchived();
}
