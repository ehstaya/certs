package com.sfquiz.service;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.dto.ExamTopicDto;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ExamTopic;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.ExamTopicRepository;
import com.sfquiz.repository.QuestionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExamService {

    private final ExamRepository exams;
    private final QuestionRepository questions;
    private final ExamTopicRepository examTopics;

    public ExamService(ExamRepository exams, QuestionRepository questions, ExamTopicRepository examTopics) {
        this.exams = exams;
        this.questions = questions;
        this.examTopics = examTopics;
    }

    /** Topics for the info page. The per-topic question counts use the live bank
     *  so the UI can show "you have N approved questions in this topic". */
    public List<ExamTopicDto> listTopics(String slug) {
        Exam exam = exams.findBySlug(slug).orElse(null);
        if (exam == null) return List.of();
        List<ExamTopic> topics = examTopics.findByExamOrderBySortOrderAscIdAsc(exam);
        int perSession = Math.max(1, exam.getQuestionsPerSession());
        List<ExamTopicDto> out = new ArrayList<>(topics.size());
        for (ExamTopic t : topics) {
            int forSession = (int) Math.round(perSession * (t.getWeightPercent() / 100.0));
            long approved = questions.findByExamAndStatusAndTopic(exam, Question.Status.APPROVED, t.getTopicKey()).size();
            out.add(new ExamTopicDto(
                    t.getTopicKey(), t.getName(), t.getWeightPercent(),
                    forSession, approved, approved));
        }
        return out;
    }

    /** All active exams, for the picker. Empty exams are still listed so users
     *  can upload study material against them — the JS picker can mark them
     *  as "no questions yet" if it wants to. */
    public List<ExamDto> listActive() {
        return exams.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(e -> ExamDto.from(e, questions.countByExamAndStatus(e, Question.Status.APPROVED)))
                .toList();
    }

    public Exam getBySlug(String slug) {
        return exams.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown exam: " + slug));
    }

    public ExamDto toDto(Exam e) {
        return ExamDto.from(e, questions.countByExamAndStatus(e, Question.Status.APPROVED));
    }
}
