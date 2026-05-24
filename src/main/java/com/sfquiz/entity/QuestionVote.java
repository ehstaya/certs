package com.sfquiz.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One user's thumbs-up / thumbs-down vote on one question. Upserted —
 *  a user has at most one vote per question and can toggle / change it. */
@Entity
@Table(name = "question_votes",
       uniqueConstraints = @UniqueConstraint(name = "uk_vote_question_user",
                                             columnNames = {"question_id", "user_id"}))
public class QuestionVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** +1 for thumbs-up, -1 for thumbs-down. */
    @Column(name = "vote_value", nullable = false)
    private int voteValue;

    /** Reason for the vote. Required when the voter has role VERIFIER —
     *  free-form string but typically chosen from a predefined list in the
     *  UI (e.g. "matches real-world questions", "answer is not correct"). */
    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Question getQuestion() { return question; }
    public void setQuestion(Question question) { this.question = question; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public int getVoteValue() { return voteValue; }
    public void setVoteValue(int voteValue) { this.voteValue = voteValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getVotedAt() { return votedAt; }
    public void setVotedAt(Instant votedAt) { this.votedAt = votedAt; }
}
