package com.sfquiz.repository;

import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ExamTopic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamTopicRepository extends JpaRepository<ExamTopic, Long> {

    List<ExamTopic> findByExamOrderBySortOrderAscIdAsc(Exam exam);

    Optional<ExamTopic> findByExamAndTopicKey(Exam exam, String topicKey);
}
