package com.sfquiz.dto;

import java.util.List;

public class SubmitResponse {
    private boolean correct;
    private List<Long> correctChoiceIds;
    private List<Long> selectedChoiceIds;
    private String explanation;
    private String helpUrl;

    public SubmitResponse() {}

    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }
    public List<Long> getCorrectChoiceIds() { return correctChoiceIds; }
    public void setCorrectChoiceIds(List<Long> correctChoiceIds) { this.correctChoiceIds = correctChoiceIds; }
    public List<Long> getSelectedChoiceIds() { return selectedChoiceIds; }
    public void setSelectedChoiceIds(List<Long> selectedChoiceIds) { this.selectedChoiceIds = selectedChoiceIds; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public String getHelpUrl() { return helpUrl; }
    public void setHelpUrl(String helpUrl) { this.helpUrl = helpUrl; }
}
