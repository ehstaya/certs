package com.sfquiz.repository;

import com.sfquiz.entity.StudyUpload;
import com.sfquiz.entity.StudyUpload.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface StudyUploadRepository extends JpaRepository<StudyUpload, Long> {

    List<StudyUpload> findTop50ByOrderByUploadedAtDesc();

    List<StudyUpload> findTop50ByUploadedByEmailIgnoreCaseOrderByUploadedAtDesc(String email);

    List<StudyUpload> findByStatusAndUploadedAtBefore(Status status, Instant before);

    List<StudyUpload> findByStatus(Status status);

    /** Most recent prior upload with the same content hash, used to detect
     *  re-uploads of the same file. We scope to the same target exam so an
     *  identical file uploaded against two different certs is treated as
     *  two unrelated submissions. */
    java.util.Optional<StudyUpload> findFirstByContentHashAndExamSlugOrderByUploadedAtDesc(
            String contentHash, String examSlug);
}
