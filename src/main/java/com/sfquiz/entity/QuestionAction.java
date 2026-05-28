package com.sfquiz.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One row per admin action on a question — drives the "admin activity"
 *  reports (per-domain-admin counts of approved / rejected / edited / etc.
 *  and the super-admin cross-admin breakdown).
 *
 *  Deliberately decoupled from {@link Question}: we keep the questionId as
 *  a raw long so the event survives permanent deletes (auditors can still
 *  see "Jane permanently deleted question #427 on cert X two months ago"
 *  even after the question row is gone). Same reason {@link #examSlug} is
 *  stored on the row rather than walked through Question.exam — the
 *  question may not exist anymore. */
@Entity
@Table(name = "question_actions",
       indexes = {
           @Index(name = "idx_qaction_admin", columnList = "admin_email"),
           @Index(name = "idx_qaction_exam",  columnList = "exam_slug"),
           @Index(name = "idx_qaction_when",  columnList = "occurred_at")
       })
public class QuestionAction {

    public enum Action {
        /** PENDING → APPROVED. */
        APPROVE,
        /** PENDING → REJECTED. */
        REJECT,
        /** Question content / choices / metadata changed (any status). */
        EDIT,
        /** APPROVED → RETIRED (soft delete). */
        RETIRE,
        /** RETIRED → APPROVED. */
        RESTORE,
        /** Row removed from the DB. */
        PERMANENT_DELETE,
        /** APPROVED → PENDING (verifier-feedback flow). */
        SEND_BACK,
        /** Brand-new question created via the compose-question form. */
        MANUAL_CREATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "admin_email", nullable = false, length = 200)
    private String adminEmail;

    @Column(name = "exam_slug", length = 64)
    private String examSlug;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private Action action;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getAdminEmail() { return adminEmail; }
    public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }
    public String getExamSlug() { return examSlug; }
    public void setExamSlug(String examSlug) { this.examSlug = examSlug; }
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
