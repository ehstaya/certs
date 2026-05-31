package com.sfquiz.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One completed practice session for one user on one exam. Recorded when
 *  the user clicks Finish (or the timer expires and they confirm finish).
 *  Drives the user-facing "My reports" dashboard. */
@Entity
@Table(name = "test_attempts",
       indexes = {
           @Index(name = "idx_attempt_user", columnList = "user_id"),
           @Index(name = "idx_attempt_user_exam", columnList = "user_id,exam_id")
       })
public class TestAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Eager so the My-reports templates can render `attempt.exam.name`
    // outside of the service's transaction without a LazyInitializationException.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "total_questions", nullable = false)
    private int totalQuestions;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "incorrect_count", nullable = false)
    private int incorrectCount;

    @Column(name = "unanswered_count", nullable = false)
    private int unansweredCount;

    /** 0–100. Computed as round(correctCount / totalQuestions * 100). */
    @Column(name = "score_percent", nullable = false)
    private int scorePercent;

    /** Snapshot of the exam's passing score at the time of the attempt — so
     *  later cut-score changes don't retroactively reclassify old attempts. */
    @Column(name = "passing_score_percent", nullable = false)
    private int passingScorePercent;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    /** Comma-separated list of question IDs that made up this attempt, in
     *  the order they were originally served. Drives the "Retake same test"
     *  flow — when the user wants to retake this exact set, we re-fetch
     *  these specific question rows instead of taking a fresh random sample
     *  from the bank. Nullable on legacy rows (pre-feature attempts can't
     *  be retaken — the link is hidden in the UI for those). */
    @Column(name = "question_ids", columnDefinition = "TEXT")
    private String questionIds;

    /** Two delivery modes for the test:
     *    PRACTICE — open-book style. Per-question feedback (correct/wrong
     *               banner + explanation + help URL + vote bar) shows
     *               immediately after submit. User can navigate freely,
     *               skip, retest. This is the default for legacy rows.
     *    EXAM     — real-exam simulation. No mid-test feedback, no votes,
     *               no skipping, no going back. Sequential answer-then-
     *               next flow. Score and full review only after Finish. */
    public enum Mode { PRACTICE, EXAM }

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 16, nullable = false)
    @org.hibernate.annotations.ColumnDefault("'PRACTICE'")
    private Mode mode = Mode.PRACTICE;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public int getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(int totalQuestions) { this.totalQuestions = totalQuestions; }
    public int getCorrectCount() { return correctCount; }
    public void setCorrectCount(int correctCount) { this.correctCount = correctCount; }
    public int getIncorrectCount() { return incorrectCount; }
    public void setIncorrectCount(int incorrectCount) { this.incorrectCount = incorrectCount; }
    public int getUnansweredCount() { return unansweredCount; }
    public void setUnansweredCount(int unansweredCount) { this.unansweredCount = unansweredCount; }
    public int getScorePercent() { return scorePercent; }
    public void setScorePercent(int scorePercent) { this.scorePercent = scorePercent; }
    public int getPassingScorePercent() { return passingScorePercent; }
    public void setPassingScorePercent(int passingScorePercent) { this.passingScorePercent = passingScorePercent; }
    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
    public String getQuestionIds() { return questionIds; }
    public void setQuestionIds(String questionIds) { this.questionIds = questionIds; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode == null ? Mode.PRACTICE : mode; }
}
