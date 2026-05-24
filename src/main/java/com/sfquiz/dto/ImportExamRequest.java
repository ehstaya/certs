package com.sfquiz.dto;

import java.util.List;

/** Exam-scoped import payload — same shape as a seed/*.json file.
 *  Posting this to /admin/questions/import upserts the exam and adds its
 *  questions as PENDING for admin review. */
public record ImportExamRequest(
        String slug,
        String name,
        String description,
        Integer questionsPerSession,
        Integer durationMinutes,
        Integer sortOrder,
        Boolean active,
        List<ImportQuestionRequest> questions
) {}
