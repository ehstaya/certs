package com.sfquiz.dto;

import com.sfquiz.entity.Exam;

public record ExamDto(
        String slug,
        String name,
        String description,
        int questionsPerSession,
        int durationMinutes,
        int passingScorePercent,
        long questionCount
) {
    public static ExamDto from(Exam e, long questionCount) {
        return new ExamDto(
                e.getSlug(),
                e.getName(),
                e.getDescription(),
                e.getQuestionsPerSession(),
                e.getDurationMinutes(),
                e.getPassingScorePercent(),
                questionCount
        );
    }
}
