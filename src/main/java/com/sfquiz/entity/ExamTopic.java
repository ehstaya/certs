package com.sfquiz.entity;

import jakarta.persistence.*;

/** One weighted topic on an exam (e.g. "Configuration and Setup — 15%" for
 *  Salesforce Administrator). Topics are seeded per exam; questions are
 *  tagged with one topic key (see {@link Question#getTopic()}). The session
 *  sampler uses these weights to assemble a per-session question mix that
 *  matches the official exam breakdown. */
@Entity
@Table(name = "exam_topics",
       uniqueConstraints = @UniqueConstraint(name = "uk_exam_topic_key",
                                             columnNames = {"exam_id", "topic_key"}))
public class ExamTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    /** Stable short identifier, e.g. "config-setup". Referenced by Question.topic. */
    @Column(name = "topic_key", nullable = false, length = 64)
    private String topicKey;

    /** Display name, e.g. "Configuration and Setup". */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Percent weight 0–100. Sum across an exam's topics should equal 100. */
    @Column(name = "weight_percent", nullable = false)
    private int weightPercent;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }
    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getWeightPercent() { return weightPercent; }
    public void setWeightPercent(int weightPercent) { this.weightPercent = weightPercent; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
