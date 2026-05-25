package com.sfquiz.repository;

import com.sfquiz.entity.TestAttempt;
import com.sfquiz.entity.TestAttemptAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestAttemptAnswerRepository extends JpaRepository<TestAttemptAnswer, Long> {

    List<TestAttemptAnswer> findByAttemptOrderByIdAsc(TestAttempt attempt);
}
