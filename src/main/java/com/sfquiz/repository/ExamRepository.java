package com.sfquiz.repository;

import com.sfquiz.entity.Exam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {

    Optional<Exam> findBySlug(String slug);

    List<Exam> findByActiveTrueOrderBySortOrderAscNameAsc();
}
