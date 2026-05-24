package com.sfquiz.dto;

import java.util.List;

/** Payload for GET /api/exams/{slug}/questions — exam metadata plus its questions. */
public record ExamQuestionsResponse(
        ExamDto exam,
        List<QuestionDto> questions
) {}
