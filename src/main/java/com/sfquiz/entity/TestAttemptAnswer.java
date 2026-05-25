package com.sfquiz.entity;

import jakarta.persistence.*;

/** One row per submitted question on a TestAttempt — records which choices
 *  the user picked and whether the resulting selection was scored correct.
 *  Drives the "View correct / View incorrect" drill-down on the My Reports
 *  per-test page so users can review the explanation for every question
 *  they got right or wrong on a past attempt. */
@Entity
@Table(name = "test_attempt_answers",
       indexes = {
           @Index(name = "idx_attempt_answer_attempt", columnList = "attempt_id"),
           @Index(name = "idx_attempt_answer_question", columnList = "question_id")
       })
public class TestAttemptAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private TestAttempt attempt;

    /** Question referenced by id only — kept eager because the detail view
     *  walks question.text / choices / explanation directly outside the
     *  attempt's own transaction. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    /** Comma-separated list of choice ids the user picked. Empty string for
     *  unanswered (then the row may not even exist — we only persist rows
     *  for questions the user submitted). */
    @Column(name = "selected_choice_ids", nullable = false, length = 500)
    private String selectedChoiceIds = "";

    /** True when the user's selection matched the correct-choice set at
     *  the time of the attempt. Snapshotted so later edits to the question
     *  don't retroactively change scoring. */
    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public TestAttempt getAttempt() { return attempt; }
    public void setAttempt(TestAttempt attempt) { this.attempt = attempt; }
    public Question getQuestion() { return question; }
    public void setQuestion(Question question) { this.question = question; }
    public String getSelectedChoiceIds() { return selectedChoiceIds; }
    public void setSelectedChoiceIds(String selectedChoiceIds) {
        this.selectedChoiceIds = selectedChoiceIds == null ? "" : selectedChoiceIds;
    }
    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }
}
