package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.dto.ExamQuestionsResponse;
import com.sfquiz.dto.ExamTopicDto;
import com.sfquiz.entity.Exam;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.QuizService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exams")
@CrossOrigin
public class ExamController {

    private final ExamService exams;
    private final QuizService quiz;

    public ExamController(ExamService exams, QuizService quiz) {
        this.exams = exams;
        this.quiz = quiz;
    }

    /** Active exams (with at least one approved question) for the picker. */
    @GetMapping
    public List<ExamDto> list() {
        return exams.listActive();
    }

    /** Topic weightage breakdown for the info page (shown after exam pick). */
    @GetMapping("/{slug}/topics")
    public ResponseEntity<List<ExamTopicDto>> topics(@PathVariable String slug) {
        List<ExamTopicDto> topics = exams.listTopics(slug);
        return topics.isEmpty() ? ResponseEntity.ok(List.of()) : ResponseEntity.ok(topics);
    }

    /** Exam metadata + its approved questions for a practice session. */
    @GetMapping("/{slug}/questions")
    public ResponseEntity<ExamQuestionsResponse> questions(@PathVariable String slug) {
        Exam exam;
        try {
            exam = exams.getBySlug(slug);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ExamQuestionsResponse(
                exams.toDto(exam),
                quiz.listForExam(slug)
        ));
    }
}
