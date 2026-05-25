package com.sfquiz.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Tiny key/value store for small bits of platform state that don't fit on a
 *  domain entity — currently used to track when the last Slack reminder was
 *  posted so the scheduler can enforce the 24-hour cadence across restarts. */
@Entity
@Table(name = "app_settings", uniqueConstraints = @UniqueConstraint(name = "uk_app_settings_key", columnNames = "key_name"))
public class AppSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_name", nullable = false, length = 100)
    private String keyName;

    @Column(name = "value", length = 2000)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
