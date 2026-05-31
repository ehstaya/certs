package com.sfquiz.repository;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    Question findByExamAndNumber(Exam exam, Integer number);

    List<Question> findByExamSlugAndStatusOrderByNumber(String slug, Question.Status status);

    List<Question> findByExamAndStatusAndTopic(Exam exam, Question.Status status, String topic);

    List<Question> findByExamAndStatusAndTopicIsNull(Exam exam, Question.Status status);

    List<Question> findByStatusOrderByNumber(Question.Status status);

    /** Auto-approval sweep: PENDING questions whose createdAt is older than
     *  the supplied cutoff. Legacy rows with null createdAt are EXCLUDED on
     *  purpose — we only auto-approve modern, time-stamped uploads so a
     *  pre-tracking row left in PENDING for forever doesn't suddenly flip. */
    @Query("SELECT q FROM Question q " +
           "WHERE q.status = com.sfquiz.entity.Question.Status.PENDING " +
           "AND q.createdAt IS NOT NULL " +
           "AND q.createdAt < :cutoff " +
           "ORDER BY q.createdAt ASC")
    List<Question> findStalePending(@Param("cutoff") java.time.Instant cutoff);

    long countByStatus(Question.Status status);

    long countByExamAndStatus(Exam exam, Question.Status status);

    Optional<Question> findFirstByExamOrderByNumberDesc(Exam exam);

    boolean existsByExamAndText(Exam exam, String text);

    /** All non-rejected questions for an exam — used by the service-side
     *  normalized-text dedup check at import time. Cheap because the bank is
     *  capped at a few thousand rows per exam. */
    @Query("SELECT q FROM Question q WHERE q.exam = :exam " +
           "AND q.status <> com.sfquiz.entity.Question.Status.REJECTED")
    List<Question> findAllActiveByExam(@Param("exam") Exam exam);

    /** Backfill a status onto rows that predate the `status` column (used on startup). */
    @Modifying
    @Query("UPDATE Question q SET q.status = ?1 WHERE q.status IS NULL")
    int backfillStatus(Question.Status status);

    /** Bank-progress report: total questions whose createdAt falls in
     *  [start, end), optionally restricted to a set of exam slugs.
     *  Legacy/pre-tracking rows (createdAt IS NULL) are excluded. */
    @Query("SELECT COUNT(q.id) FROM Question q " +
           "WHERE q.createdAt >= :start AND q.createdAt < :end " +
           "AND (:scoped = false OR q.exam.slug IN :slugs)")
    long countUploadedInRange(@Param("start") java.time.Instant start,
                              @Param("end")   java.time.Instant end,
                              @Param("scoped") boolean scoped,
                              @Param("slugs") java.util.Collection<String> slugs);

    /** Per-exam-slug "uploaded in time range" aggregate for the bank-progress report. */
    @Query("SELECT q.exam.slug, COUNT(q.id) FROM Question q " +
           "WHERE q.createdAt >= :start AND q.createdAt < :end " +
           "AND (:scoped = false OR q.exam.slug IN :slugs) " +
           "GROUP BY q.exam.slug")
    List<Object[]> uploadedByExamInRange(@Param("start") java.time.Instant start,
                                         @Param("end")   java.time.Instant end,
                                         @Param("scoped") boolean scoped,
                                         @Param("slugs") java.util.Collection<String> slugs);

    /** Per-contributor "uploaded in time range" aggregate. */
    @Query("SELECT q.createdByEmail, COUNT(q.id) FROM Question q " +
           "WHERE q.createdAt >= :start AND q.createdAt < :end " +
           "AND (:scoped = false OR q.exam.slug IN :slugs) " +
           "GROUP BY q.createdByEmail")
    List<Object[]> uploadedByCreatorInRange(@Param("start") java.time.Instant start,
                                            @Param("end")   java.time.Instant end,
                                            @Param("scoped") boolean scoped,
                                            @Param("slugs") java.util.Collection<String> slugs);

    /** Assign exam-less questions (predating the exam column) to a default exam. */
    @Modifying
    @Query("UPDATE Question q SET q.exam = :exam WHERE q.exam IS NULL")
    int backfillExam(@Param("exam") Exam exam);
}
