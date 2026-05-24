package com.sfquiz.dto;

import com.sfquiz.entity.Question;

import java.util.List;
import java.util.stream.Collectors;

public class QuestionDto {
    private Long id;
    private Integer number;
    private String type;
    private String text;
    private List<ChoiceDto> choices;

    public QuestionDto() {}

    public static QuestionDto from(Question q) {
        QuestionDto d = new QuestionDto();
        d.id = q.getId();
        d.number = q.getNumber();
        d.type = q.getType().name();
        d.text = q.getText();
        d.choices = q.getChoices().stream()
                .map(c -> new ChoiceDto(c.getId(), c.getLabel(), c.getText()))
                .collect(Collectors.toList());
        return d;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getNumber() { return number; }
    public void setNumber(Integer number) { this.number = number; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<ChoiceDto> getChoices() { return choices; }
    public void setChoices(List<ChoiceDto> choices) { this.choices = choices; }
}
