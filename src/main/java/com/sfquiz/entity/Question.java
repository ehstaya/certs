package com.sfquiz.entity;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "questions",
        uniqueConstraints = @UniqueConstraint(name = "uk_question_exam_number", columnNames = {"exam_id", "number"}))
public class Question {

    public enum Type { SINGLE, MULTI }

    /** Review lifecycle for crawler-imported questions. Questions seeded from
     *  seed/questions.json are APPROVED; ones POSTed to /admin/questions/import
     *  arrive as PENDING for an admin to approve / reject / edit. */
    /** PENDING — awaiting first review.
     *  APPROVED — live in the question bank, served to learners.
     *  REJECTED — rejected during first review, never served.
     *  RETIRED — was approved, an admin pulled it back to the retired queue.
     *             They can edit + restore (back to APPROVED) or hard-delete it. */
    public enum Status { PENDING, APPROVED, REJECTED, RETIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Unique per-exam (see composite constraint on @Table), not globally.
    @Column(name = "number", nullable = false)
    private Integer number;

    // Nullable at the DB level so ddl-auto=update can add the column to an
    // existing table; the migration in DataSeeder backfills NULL -> Salesforce
    // exam, and all code paths set it explicitly on new questions.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "exam_id")
    private Exam exam;

    @Column(name = "type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Type type;

    // Nullable so Hibernate's ddl-auto=update can add it to an existing table;
    // DataSeeder backfills NULL -> APPROVED on startup, and new questions always
    // get a non-null status (field default below, or explicitly set on import).
    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    private Status status = Status.APPROVED;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "help_url", length = 1024)
    private String helpUrl;

    /** Topic key linking this question to an ExamTopic for weighted sampling.
     *  Nullable on legacy rows; populated by TopicClassifier or admin edit. */
    @Column(name = "topic", length = 64)
    private String topic;

    /** Email of the admin who retired this question (status=RETIRED). Used to
     *  show "who pulled it back" in the retired queue. */
    @Column(name = "retired_by_email", length = 191)
    private String retiredByEmail;

    @Column(name = "retired_at")
    private java.time.Instant retiredAt;

    /** When this question's text matched an existing question's text at import
     *  time, this is the ID of that existing question. The admin sees a
     *  warning badge on the review card so they can decide whether to keep
     *  this as a variant or reject as a duplicate. */
    @Column(name = "duplicate_of_id")
    private Long duplicateOfId;

    /** Where a crawler-imported question came from (null for seeded questions). */
    @Column(name = "source_url", length = 1024)
    private String sourceUrl;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("label ASC")
    private List<Choice> choices = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getNumber() { return number; }
    public void setNumber(Integer number) { this.number = number; }
    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public String getHelpUrl() { return helpUrl; }
    public void setHelpUrl(String helpUrl) { this.helpUrl = helpUrl; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getRetiredByEmail() { return retiredByEmail; }
    public void setRetiredByEmail(String retiredByEmail) { this.retiredByEmail = retiredByEmail; }
    public java.time.Instant getRetiredAt() { return retiredAt; }
    public void setRetiredAt(java.time.Instant retiredAt) { this.retiredAt = retiredAt; }
    public Long getDuplicateOfId() { return duplicateOfId; }
    public void setDuplicateOfId(Long duplicateOfId) { this.duplicateOfId = duplicateOfId; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }

    public void addChoice(Choice c) {
        c.setQuestion(this);
        this.choices.add(c);
    }

    /** Empties the choices collection (orphan-removal deletes the rows on flush). */
    public void clearChoices() {
        this.choices.clear();
    }
}
