package com.sfquiz.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** A (user, exam) pair declaring that {@code user} is the domain admin for
 *  {@code exam}. A user with role=ADMIN must have at least one of these
 *  rows to actually wield any admin power — without an assignment they can
 *  see the admin nav but every operation is rejected.
 *
 *  SUPERADMINs implicitly govern every exam and don't need rows here.
 *
 *  This is a separate entity (rather than @ManyToMany on User) so we can
 *  track when each assignment was made and by whom, which is useful for
 *  audit and the upcoming "who assigned this domain admin?" view. */
@Entity
@Table(name = "domain_admin_assignments",
       uniqueConstraints = @UniqueConstraint(name = "uk_domain_admin_user_exam",
                                             columnNames = {"user_id", "exam_id"}),
       indexes = {
           @Index(name = "idx_domain_admin_user", columnList = "user_id"),
           @Index(name = "idx_domain_admin_exam", columnList = "exam_id")
       })
public class DomainAdminAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "assigned_by_email", length = 200)
    private String assignedByEmail;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    public String getAssignedByEmail() { return assignedByEmail; }
    public void setAssignedByEmail(String assignedByEmail) { this.assignedByEmail = assignedByEmail; }
}
