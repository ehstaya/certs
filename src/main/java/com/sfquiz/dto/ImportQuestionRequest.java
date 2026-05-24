package com.sfquiz.dto;

import java.util.List;

/** Payload shape for POST /admin/questions/import (matches the seed/questions.json format).
 *  `n` is ignored — the server assigns the next free question number. */
public record ImportQuestionRequest(
        Integer n,
        String type,
        String text,
        String helpUrl,
        String explanation,
        String sourceUrl,
        List<ImportChoiceRequest> choices
) {
    public record ImportChoiceRequest(String label, String text, boolean correct) {}
}
