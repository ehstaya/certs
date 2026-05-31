package com.sfquiz.repository;

import com.sfquiz.entity.StudyUpload;
import com.sfquiz.entity.StudyUpload.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface StudyUploadRepository extends JpaRepository<StudyUpload, Long> {

    List<StudyUpload> findTop50ByOrderByUploadedAtDesc();

    List<StudyUpload> findTop50ByUploadedByEmailIgnoreCaseOrderByUploadedAtDesc(String email);

    /** Active = not archived. Listing-only callers should use the
     *  *Summary projection variants below so the heavy {@code content}
     *  byte[] column isn't loaded — it was being pulled on every 4-s
     *  poll of /uploads/api/status, saturating the DB pool. */
    List<StudyUpload> findTop50ByArchivedFalseOrderByUploadedAtDesc();

    List<StudyUpload> findTop50ByUploadedByEmailIgnoreCaseAndArchivedFalseOrderByUploadedAtDesc(String email);

    /** Projection variants — these emit a column-pruned SELECT (no content
     *  bytes). The {@code Summary} infix is treated as descriptive text by
     *  the Spring Data parser; the {@link Pageable} arg supplies the row
     *  limit (call with {@code PageRequest.of(0, 50)}). Use these for any
     *  code path that just renders metadata or ships it to the browser. */
    List<StudyUploadSummary> findSummaryByArchivedFalseOrderByUploadedAtDesc(Pageable pageable);

    List<StudyUploadSummary> findSummaryByUploadedByEmailIgnoreCaseAndArchivedFalseOrderByUploadedAtDesc(
            String email, Pageable pageable);

    long countByArchivedTrue();

    long countByUploadedByEmailIgnoreCaseAndArchivedTrue(String email);

    /** Archived listing — for the dedicated 'Archived files' view that
     *  surfaces what was hidden from the main page. */
    List<StudyUpload> findTop100ByArchivedTrueOrderByUploadedAtDesc();

    List<StudyUpload> findTop100ByUploadedByEmailIgnoreCaseAndArchivedTrueOrderByUploadedAtDesc(String email);

    List<StudyUploadSummary> findSummaryByArchivedTrueOrderByUploadedAtDesc(Pageable pageable);

    List<StudyUploadSummary> findSummaryByUploadedByEmailIgnoreCaseAndArchivedTrueOrderByUploadedAtDesc(
            String email, Pageable pageable);

    List<StudyUpload> findByStatusAndUploadedAtBefore(Status status, Instant before);

    List<StudyUpload> findByStatus(Status status);

    /** Most recent prior upload with the same content hash, used to detect
     *  re-uploads of the same file. We scope to the same target exam so an
     *  identical file uploaded against two different certs is treated as
     *  two unrelated submissions. */
    java.util.Optional<StudyUpload> findFirstByContentHashAndExamSlugOrderByUploadedAtDesc(
            String contentHash, String examSlug);
}
