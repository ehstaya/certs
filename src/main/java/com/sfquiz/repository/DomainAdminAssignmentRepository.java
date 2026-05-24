package com.sfquiz.repository;

import com.sfquiz.entity.DomainAdminAssignment;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DomainAdminAssignmentRepository extends JpaRepository<DomainAdminAssignment, Long> {

    List<DomainAdminAssignment> findByUser(User user);

    List<DomainAdminAssignment> findByExam(Exam exam);

    Optional<DomainAdminAssignment> findByUserAndExam(User user, Exam exam);

    boolean existsByUserAndExam(User user, Exam exam);

    void deleteByUserAndExam(User user, Exam exam);

    void deleteByUser(User user);

    /** Just the exam slugs a user manages — drives the per-request scope filter
     *  without forcing a full association load on every check. */
    @Query("SELECT a.exam.slug FROM DomainAdminAssignment a WHERE a.user = :user")
    List<String> findExamSlugsByUser(@Param("user") User user);
}
