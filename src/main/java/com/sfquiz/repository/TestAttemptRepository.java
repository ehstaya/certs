package com.sfquiz.repository;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.TestAttempt;
import com.sfquiz.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestAttemptRepository extends JpaRepository<TestAttempt, Long> {

    List<TestAttempt> findByUserOrderByFinishedAtDesc(User user);

    List<TestAttempt> findByUserAndExamOrderByFinishedAtAsc(User user, Exam exam);

    /** Per-exam summary stats for one user: attempts, average score, pass count. */
    @Query("SELECT a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent, " +
           "       COUNT(a.id), AVG(a.scorePercent), MAX(a.scorePercent), " +
           "       SUM(CASE WHEN a.passed THEN 1 ELSE 0 END), " +
           "       AVG(a.durationSeconds) " +
           "FROM TestAttempt a WHERE a.user = :user " +
           "GROUP BY a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent")
    List<Object[]> summaryByExamForUser(@Param("user") User user);

    /** Per-user, per-exam summary across the whole platform — drives the admin
     *  "All-user scores" report. Returns one row per (user, exam) pair the user
     *  has attempted. Each row carries user identity + per-exam aggregates. */
    @Query("SELECT a.user.id, a.user.email, a.user.fullName, a.user.role, " +
           "       a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent, " +
           "       COUNT(a.id), AVG(a.scorePercent), MAX(a.scorePercent), " +
           "       SUM(CASE WHEN a.passed THEN 1 ELSE 0 END), " +
           "       MAX(a.finishedAt) " +
           "FROM TestAttempt a " +
           "GROUP BY a.user.id, a.user.email, a.user.fullName, a.user.role, " +
           "         a.exam.id, a.exam.slug, a.exam.name, a.exam.passingScorePercent " +
           "ORDER BY a.user.email ASC, a.exam.slug ASC")
    List<Object[]> allUserExamSummaries();
}
