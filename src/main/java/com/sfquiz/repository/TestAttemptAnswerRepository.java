package com.sfquiz.repository;

import com.sfquiz.entity.TestAttempt;
import com.sfquiz.entity.TestAttemptAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestAttemptAnswerRepository extends JpaRepository<TestAttemptAnswer, Long> {

    List<TestAttemptAnswer> findByAttemptOrderByIdAsc(TestAttempt attempt);

    /** Bulk delete of every answer row for one attempt — used by the
     *  finish-saved + delete-saved paths so we don't pay the N+1 cost
     *  of loading 60 entities just to delete them one at a time. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM TestAttemptAnswer a WHERE a.attempt = :attempt")
    int deleteAllByAttempt(@Param("attempt") TestAttempt attempt);

    /** Projection used by the per-test report's "Performance by area" panel.
     *  Returns just (question.topic, isCorrect) tuples in one query — avoids
     *  loading the EAGER Question + EAGER Choices for every answer row,
     *  which on a 60-question attempt was ~121 SQL statements (1 base +
     *  60 Questions + 60 Choices) per page load. */
    @Query("SELECT a.question.topic, a.correct " +
           "FROM TestAttemptAnswer a " +
           "WHERE a.attempt = :attempt")
    List<Object[]> findTopicCorrectnessByAttempt(@Param("attempt") TestAttempt attempt);
}
