package com.sfquiz.repository;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.TestAttempt;
import com.sfquiz.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestAttemptRepository extends JpaRepository<TestAttempt, Long> {

    /** Status-agnostic — used by the delete-user cascade. Avoid for
     *  reporting paths; those should always scope to FINISHED. */
    List<TestAttempt> findByUserOrderByFinishedAtDesc(User user);

    /** Finished-only listings (the My-reports dashboard + trend chart treat
     *  SAVED rows as separate cards / sections). FINISHED is the default
     *  status on legacy rows so historical attempts keep showing up. */
    List<TestAttempt> findByUserAndStatusOrderByFinishedAtDesc(
            User user, com.sfquiz.entity.TestAttempt.Status status);

    List<TestAttempt> findByUserAndExamAndStatusOrderByFinishedAtAsc(
            User user, Exam exam, com.sfquiz.entity.TestAttempt.Status status);

    /** SAVED attempts for one user + exam — for the per-test page's
     *  Saved-tests section. */
    List<TestAttempt> findByUserAndExamAndStatusOrderByFinishedAtDesc(
            User user, Exam exam, com.sfquiz.entity.TestAttempt.Status status);

    /** Per-exam summary stats for one user: attempts, average score, pass count.
     *  FINISHED-only so a saved-but-not-finished session doesn't dilute averages
     *  with a zero score. */
    @Query("SELECT a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent, " +
           "       COUNT(a.id), AVG(a.scorePercent), MAX(a.scorePercent), " +
           "       SUM(CASE WHEN a.passed THEN 1 ELSE 0 END), " +
           "       AVG(a.durationSeconds) " +
           "FROM TestAttempt a WHERE a.user = :user " +
           "AND a.status = com.sfquiz.entity.TestAttempt$Status.FINISHED " +
           "GROUP BY a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent")
    List<Object[]> summaryByExamForUser(@Param("user") User user);

    /** Per-user, per-exam summary across the whole platform — drives the admin
     *  "All-user scores" report. FINISHED-only for the same reason. */
    @Query("SELECT a.user.id, a.user.email, a.user.fullName, a.user.role, " +
           "       a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent, " +
           "       COUNT(a.id), AVG(a.scorePercent), MAX(a.scorePercent), " +
           "       SUM(CASE WHEN a.passed THEN 1 ELSE 0 END), " +
           "       MAX(a.finishedAt) " +
           "FROM TestAttempt a " +
           "WHERE a.status = com.sfquiz.entity.TestAttempt$Status.FINISHED " +
           "GROUP BY a.user.id, a.user.email, a.user.fullName, a.user.role, " +
           "         a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent " +
           "ORDER BY a.user.email ASC, a.exam.slug ASC")
    List<Object[]> allUserExamSummaries();

    /** Same shape as {@link #allUserExamSummaries} but scoped to one
     *  delivery mode. FINISHED-only. */
    @Query("SELECT a.user.id, a.user.email, a.user.fullName, a.user.role, " +
           "       a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent, " +
           "       COUNT(a.id), AVG(a.scorePercent), MAX(a.scorePercent), " +
           "       SUM(CASE WHEN a.passed THEN 1 ELSE 0 END), " +
           "       MAX(a.finishedAt) " +
           "FROM TestAttempt a " +
           "WHERE a.status = com.sfquiz.entity.TestAttempt$Status.FINISHED " +
           "  AND (:filterByMode = false OR a.mode = :mode) " +
           "GROUP BY a.user.id, a.user.email, a.user.fullName, a.user.role, " +
           "         a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent " +
           "ORDER BY a.user.email ASC, a.exam.slug ASC")
    List<Object[]> allUserExamSummariesByMode(@Param("filterByMode") boolean filterByMode,
                                              @Param("mode") com.sfquiz.entity.TestAttempt.Mode mode);

    /** Every FINISHED attempt for one exam, chronological. Drives the admin
     *  cert-trend chart — we bucket the result by week in the service. */
    @Query("SELECT a FROM TestAttempt a WHERE a.exam.slug = :slug " +
           "AND a.status = com.sfquiz.entity.TestAttempt$Status.FINISHED " +
           "ORDER BY a.finishedAt ASC")
    List<TestAttempt> findByExamSlugChronological(@Param("slug") String slug);

    /** Highest per-user sequence number — used to assign "FirstName #N"
     *  display names on the next save/finish. */
    @Query("SELECT COALESCE(MAX(a.sequenceNumber), 0) FROM TestAttempt a WHERE a.user = :user")
    int findMaxSequenceForUser(@Param("user") User user);
}
