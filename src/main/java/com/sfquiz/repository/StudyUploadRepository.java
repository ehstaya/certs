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
}
