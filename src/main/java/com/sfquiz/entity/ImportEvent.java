package com.sfquiz.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Audit log of every push from the crawler agent to /admin/questions/import.
 * Lets admins see at a glance: "agent posted N questions, K were imported,
 * the rest were dupes" — without diving into agent logs.
 */
@Entity
@Table(name = "import_events")
public class ImportEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "exam_slug", length = 128)
    private String examSlug;

    @Column(name = "imported_count", nullable = false)
    private int importedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    /** JSON array of strings — short reason snippets returned to the crawler. */
    @Column(name = "skipped_texts_json", columnDefinition = "TEXT")
    private String skippedTextsJson;

    /** Who/what produced this event — currently always "crawler". */
    @Column(name = "source", length = 64)
    private String source;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getExamSlug() { return examSlug; }
    public void setExamSlug(String examSlug) { this.examSlug = examSlug; }
    public int getImportedCount() { return importedCount; }
    public void setImportedCount(int importedCount) { this.importedCount = importedCount; }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
    public String getSkippedTextsJson() { return skippedTextsJson; }
    public void setSkippedTextsJson(String skippedTextsJson) { this.skippedTextsJson = skippedTextsJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
