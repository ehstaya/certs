package com.sfquiz.repository;

import com.sfquiz.entity.Question;
import com.sfquiz.entity.QuestionVote;
import com.sfquiz.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuestionVoteRepository extends JpaRepository<QuestionVote, Long> {

    Optional<QuestionVote> findByQuestionAndUser(Question question, User user);

    long countByQuestionAndVoteValue(Question question, int voteValue);

    /** Every vote that has a non-blank reason — for the verifier-feedback
     *  admin report. JOIN FETCH hydrates question + its exam + voter inside
     *  the transaction so the controller/template can touch them after the
     *  session closes (avoids LazyInitializationException on q.getNumber()
     *  etc.). */
    @Query("SELECT v FROM QuestionVote v " +
           "JOIN FETCH v.question q " +
           "JOIN FETCH q.exam " +
           "JOIN FETCH v.user " +
           "WHERE v.reason IS NOT NULL AND LENGTH(TRIM(v.reason)) > 0 " +
           "AND (:examSlug IS NULL OR q.exam.slug = :examSlug) " +
           "ORDER BY v.votedAt DESC")
    List<QuestionVote> findVotesWithReasons(@Param("examSlug") String examSlug);

    /** Distinct, non-blank reason strings across every reasoned vote.
     *  Drives the reason-filter dropdown on the verifier-feedback report. */
    @Query("SELECT DISTINCT TRIM(v.reason) FROM QuestionVote v " +
           "WHERE v.reason IS NOT NULL AND LENGTH(TRIM(v.reason)) > 0 " +
           "ORDER BY TRIM(v.reason) ASC")
    List<String> distinctReasons();

    /** Aggregate by question for an entire exam, in one query. */
    @Query("SELECT v.question.id, SUM(CASE WHEN v.voteValue > 0 THEN 1 ELSE 0 END), " +
           "                      SUM(CASE WHEN v.voteValue < 0 THEN 1 ELSE 0 END) " +
           "FROM QuestionVote v WHERE v.question.exam.slug = :slug " +
           "GROUP BY v.question.id")
    List<Object[]> aggregateByExamSlug(@Param("slug") String slug);

    /** Aggregate across an entire exam — totals only. Returns a single row
     *  (always — aggregates return one row even with zero matches). Declared
     *  as List<Object[]> because Spring Data JPA wraps Object[] returns into
     *  an outer Object[][] which silently breaks element access. */
    @Query("SELECT " +
           " SUM(CASE WHEN v.voteValue > 0 THEN 1 ELSE 0 END), " +
           " SUM(CASE WHEN v.voteValue < 0 THEN 1 ELSE 0 END), " +
           " COUNT(DISTINCT v.question.id), " +
           " COUNT(v.id) " +
           "FROM QuestionVote v WHERE v.question.exam.slug = :slug")
    List<Object[]> examTotals(@Param("slug") String slug);

    /** Same totals shape as {@link #examTotals} but bucketed per slug so
     *  the /admin/reports dashboard's per-exam summary can be built from
     *  ONE query instead of one buildReport call per exam (6 exams × 3
     *  queries = 18 round trips on every dashboard render).
     *  Row tuple: (slug, up, down, distinctVotedQuestions, totalVotes). */
    @Query("SELECT v.question.exam.slug, " +
           " SUM(CASE WHEN v.voteValue > 0 THEN 1 ELSE 0 END), " +
           " SUM(CASE WHEN v.voteValue < 0 THEN 1 ELSE 0 END), " +
           " COUNT(DISTINCT v.question.id), " +
           " COUNT(v.id) " +
           "FROM QuestionVote v " +
           "WHERE v.question.exam.slug IN :slugs " +
           "GROUP BY v.question.exam.slug")
    List<Object[]> examTotalsForSlugs(@Param("slugs") java.util.Collection<String> slugs);
}
