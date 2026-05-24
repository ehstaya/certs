package com.sfquiz.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "exams", uniqueConstraints = @UniqueConstraint(name = "uk_exam_slug", columnNames = "slug"))
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** URL-safe identifier, e.g. "salesforce-admin", "aws-saa", "togaf". */
    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    /** How many questions to draw per practice session. */
    @Column(name = "questions_per_session", nullable = false)
    private int questionsPerSession = 60;

    /** Practice timer length in minutes. */
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 90;

    /** Score (0–100) at or above which the test counts as passed. Per-exam
     *  because each Salesforce / MuleSoft cert publishes its own cut score.
     *  ColumnDefault("65") ensures Hibernate emits {@code DEFAULT 65} in the
     *  ALTER, so existing exam rows get backfilled atomically when this
     *  column is added to an already-populated table. */
    @ColumnDefault("65")
    @Column(name = "passing_score_percent", nullable = false)
    private int passingScorePercent = 65;

    /** Hidden from the picker when false (without deleting questions). */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getQuestionsPerSession() { return questionsPerSession; }
    public void setQuestionsPerSession(int questionsPerSession) { this.questionsPerSession = questionsPerSession; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public int getPassingScorePercent() { return passingScorePercent; }
    public void setPassingScorePercent(int passingScorePercent) { this.passingScorePercent = passingScorePercent; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
