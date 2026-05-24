package com.sfquiz.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One row per Anthropic API call. Used to enforce app.extraction.daily-budget-usd
 *  and to give the admin a paper trail of what extraction is costing. */
@Entity
@Table(name = "cost_ledger", indexes = {
        @Index(name = "idx_cost_day", columnList = "day"),
})
public class CostLedger {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "purpose", length = 32)
    private String purpose;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cache_creation_tokens", nullable = false)
    private long cacheCreationTokens;

    @Column(name = "cache_read_tokens", nullable = false)
    private long cacheReadTokens;

    @Column(name = "usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal usd;

    @Column(name = "upload_id")
    private Long uploadId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getCacheCreationTokens() { return cacheCreationTokens; }
    public void setCacheCreationTokens(long cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public BigDecimal getUsd() { return usd; }
    public void setUsd(BigDecimal usd) { this.usd = usd; }
    public Long getUploadId() { return uploadId; }
    public void setUploadId(Long uploadId) { this.uploadId = uploadId; }
}
