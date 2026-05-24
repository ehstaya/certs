package com.sfquiz.dto;

/** Public-facing topic metadata for the topic-info page. */
public record ExamTopicDto(
        String key,
        String name,
        int weightPercent,
        int questionsForSession,
        long approvedInBank,
        long taggedInBank
) {}
