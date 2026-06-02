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

    /** Single-query "approved count per exam id" — used by the listings
     *  page + topbar /api/exams call to avoid the per-exam countByExamAndStatus
     *  N+1 that was firing once per page load. Returns Object[] tuples of
     *  (examId :: Long, approvedCount :: Long). */
    @Query("SELECT q.exam.id, COUNT(q) FROM Question q " +
           "WHERE q.status = com.sfquiz.entity.Question.Status.APPROVED " +
           "GROUP BY q.exam.id")
    List<Object[]> countApprovedByExam();

    /** Targeted single-column write used by the topic classifier batch.
     *  Each call runs in its own short auto-transaction (Spring Data's
     *  default for @Modifying), so a long backfill never holds one
     *  connection while making 200+ Claude API calls — and user submits
     *  no longer queue up on a saturated pool. */
    @Modifying
    @Query("UPDATE Question q SET q.topic = :topic WHERE q.id = :id")
    int updateTopic(@Param("id") Long id, @Param("topic") String topic);

    /** Per-topic counts in one GROUP BY — used by ExamService.listTopics so
     *  the topic-info panel doesn't fire one heavy COUNT-via-load-all-entities
     *  query per topic (the N+1 that was costing /api/exams/{slug}/topics
     *  ~2 s on Salesforce Admin's 400-question bank). */
    @Query("SELECT q.topic, COUNT(q) FROM Question q " +
           "WHERE q.exam = :exam AND q.status = :status AND q.topic IS NOT NULL " +
           "GROUP BY q.topic")
    List<Object[]> countByExamAndStatusGroupedByTopic(
            @Param("exam") Exam exam, @Param("status") Question.Status status);

    /** Lightweight (id, topic) tuples for the topic-weighted sampler in
     *  QuizService.listForExam. Avoids loading every approved question's
     *  full entity + EAGER choices just to bucket by topic and pick 60.
     *  Returns Object[] {Long id, String topic}. */
    @Query("SELECT q.id, q.topic FROM Question q " +
           "WHERE q.exam.slug = :slug AND q.status = :status")
    List<Object[]> findIdAndTopicForSampling(
            @Param("slug") String slug, @Param("status") Question.Status status);

    /** Per-exam-slug counts for one status in a single GROUP BY — used by
     *  the /admin/questions page's "Filter by certification" dropdown.
     *  Previous code loaded every PENDING Question with its EAGER choices
     *  just to tally by slug in memory. */
    @Query("SELECT q.exam.slug, COUNT(q) FROM Question q " +
           "WHERE q.status = :status AND q.exam IS NOT NULL " +
           "GROUP BY q.exam.slug")
    List<Object[]> countByStatusGroupedByExamSlug(@Param("status") Question.Status status);

    /** Single-query scoped count — drops the need for non-superadmin
     *  admins to load every question of a status into memory just to
     *  filter + count. SUPERADMINs use countByStatus directly. */
    @Query("SELECT COUNT(q) FROM Question q " +
           "WHERE q.status = :status AND q.exam.slug IN :slugs")
    long countByStatusAndExamSlugIn(
            @Param("status") Question.Status status,
            @Param("slugs") java.util.Collection<String> slugs);

    /** Top-N recent approved by id desc — used by the /admin/questions
     *  "Recently approved" panel. Limited at the DB so we don't fetch
     *  the whole bank just to take the head. EAGER Choices still come
     *  along for each row but the row count is bounded to ~10. */
    List<Question> findTop10ByStatusOrderByIdDesc(Question.Status status);

    List<Question> findTop10ByStatusAndExamSlugInOrderByIdDesc(
            Question.Status status, java.util.Collection<String> slugs);

    /** Paged + filtered listings for the /admin/questions/approved and
     *  /admin/questions/retired pages. Spring Data slices at the DB so
     *  we don't load the entire bank just to take a 20-row window.
     *  countBy* variants give the page-total without a second full scan. */
    org.springframework.data.domain.Page<Question> findByStatus(
            Question.Status status, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Question> findByExamSlugAndStatus(
            String slug, Question.Status status, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Question> findByStatusAndExamSlugIn(
            Question.Status status, java.util.Collection<String> slugs,
            org.springframework.data.domain.Pageable pageable);

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
