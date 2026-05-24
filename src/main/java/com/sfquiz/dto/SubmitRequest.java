package com.sfquiz.dto;

import java.util.List;

public class SubmitRequest {
    private List<Long> selectedChoiceIds;

    public List<Long> getSelectedChoiceIds() { return selectedChoiceIds; }
    public void setSelectedChoiceIds(List<Long> selectedChoiceIds) { this.selectedChoiceIds = selectedChoiceIds; }
}
