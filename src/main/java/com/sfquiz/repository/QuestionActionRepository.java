package com.sfquiz.repository;

import com.sfquiz.entity.QuestionAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface QuestionActionRepository extends JpaRepository<QuestionAction, Long> {

    /** Per-admin aggregate within a date range, optionally restricted to a
     *  set of exam slugs. Each row is {admin_email, action, count}. Returns
     *  Object[] (raw JPQL aggregate) so we don't pay for entity hydration. */
    @Query("SELECT a.adminEmail, a.action, COUNT(a.id) " +
           "FROM QuestionAction a " +
           "WHERE a.occurredAt >= :start AND a.occurredAt < :end " +
           "AND (:scoped = false OR a.examSlug IN :slugs) " +
           "GROUP BY a.adminEmail, a.action")
    List<Object[]> aggregateByAdminAndAction(@Param("start") Instant start,
                                             @Param("end")   Instant end,
                                             @Param("scoped") boolean scoped,
                                             @Param("slugs") Collection<String> slugs);

    /** Per-(cert, action) aggregate within a date range, with optional
     *  slug-set scope. Drives the "per-cert breakdown" card on the report. */
    @Query("SELECT a.examSlug, a.action, COUNT(a.id) " +
           "FROM QuestionAction a " +
           "WHERE a.occurredAt >= :start AND a.occurredAt < :end " +
           "AND (:scoped = false OR a.examSlug IN :slugs) " +
           "GROUP BY a.examSlug, a.action")
    List<Object[]> aggregateByExamAndAction(@Param("start") Instant start,
                                            @Param("end")   Instant end,
                                            @Param("scoped") boolean scoped,
                                            @Param("slugs") Collection<String> slugs);

    /** Per-action aggregate scoped to a single admin email — drives the
     *  "My reports as a domain admin" personal report. */
    @Query("SELECT a.action, COUNT(a.id) " +
           "FROM QuestionAction a " +
           "WHERE a.adminEmail = :email " +
           "AND a.occurredAt >= :start AND a.occurredAt < :end " +
           "GROUP BY a.action")
    List<Object[]> aggregateByActionForAdmin(@Param("email") String email,
                                             @Param("start") Instant start,
                                             @Param("end")   Instant end);

    /** Recent actions by one admin, newest first. Capped by the caller's
     *  PageRequest at the controller layer. */
    @Query("SELECT a FROM QuestionAction a " +
           "WHERE a.adminEmail = :email " +
           "AND a.occurredAt >= :start AND a.occurredAt < :end " +
           "ORDER BY a.occurredAt DESC")
    List<QuestionAction> recentForAdmin(@Param("email") String email,
                                        @Param("start") Instant start,
                                        @Param("end")   Instant end,
                                        org.springframework.data.domain.Pageable pageable);
}
