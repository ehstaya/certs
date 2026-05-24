package com.sfquiz.service;

import com.sfquiz.entity.DomainAdminAssignment;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.repository.DomainAdminAssignmentRepository;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Manages the (user, exam) join table that records which exams each ADMIN
 *  user is allowed to govern. SUPERADMINs implicitly govern everything and
 *  don't need rows here. */
@Service
@Transactional
public class DomainAdminService {

    private static final Logger log = LoggerFactory.getLogger(DomainAdminService.class);

    private final DomainAdminAssignmentRepository assignments;
    private final UserRepository users;
    private final ExamRepository exams;

    public DomainAdminService(DomainAdminAssignmentRepository assignments,
                              UserRepository users,
                              ExamRepository exams) {
        this.assignments = assignments;
        this.users = users;
        this.exams = exams;
    }

    public List<String> examSlugsFor(User u) {
        if (u == null) return List.of();
        return assignments.findExamSlugsByUser(u);
    }

    public List<DomainAdminAssignment> assignmentsFor(User u) {
        if (u == null) return List.of();
        return assignments.findByUser(u);
    }

    /** Bulk replace: after this call the user is assigned to exactly the
     *  supplied slugs and nothing else. Unknown slugs are silently dropped.
     *  Caller must have already verified the actor is a SUPERADMIN. */
    public void replaceAssignments(Long userId, Collection<String> wantedSlugs, String byEmail) {
        if (userId == null) throw new IllegalArgumentException("User id is required");
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getRole() != UserRole.ADMIN && u.getRole() != UserRole.SUPERADMIN) {
            throw new IllegalStateException(
                    "Only ADMIN or SUPERADMIN users can hold domain assignments — promote first.");
        }

        Set<String> want = new HashSet<>();
        if (wantedSlugs != null) {
            for (String s : wantedSlugs) {
                if (s != null && !s.isBlank()) want.add(s.trim());
            }
        }

        List<DomainAdminAssignment> existing = assignments.findByUser(u);
        Set<String> have = new HashSet<>();
        for (DomainAdminAssignment a : existing) {
            String slug = a.getExam() == null ? null : a.getExam().getSlug();
            if (slug == null) continue;
            if (!want.contains(slug)) {
                assignments.delete(a);
                log.info("Removed domain-admin assignment {} from {} (by {})", slug, u.getEmail(), byEmail);
            } else {
                have.add(slug);
            }
        }

        for (String slug : want) {
            if (have.contains(slug)) continue;
            Exam exam = exams.findBySlug(slug).orElse(null);
            if (exam == null) continue;
            DomainAdminAssignment a = new DomainAdminAssignment();
            a.setUser(u);
            a.setExam(exam);
            a.setAssignedAt(Instant.now());
            a.setAssignedByEmail(byEmail);
            assignments.save(a);
            log.info("Added domain-admin assignment {} to {} (by {})", slug, u.getEmail(), byEmail);
        }
    }

    /** Wipe every assignment for a user — used when a user is demoted out of
     *  ADMIN role or deleted, so no orphan rows are left. */
    public void clearAssignments(User u) {
        if (u == null) return;
        assignments.deleteByUser(u);
    }

    /** Ensure {@code u} is assigned to every active exam. Used by the boot
     *  migration to preserve pre-multi-domain ADMINs' access. */
    public int backfillAllExams(User u, String byEmail) {
        if (u == null) return 0;
        List<Exam> all = exams.findAll();
        int added = 0;
        for (Exam e : all) {
            if (e == null || e.getSlug() == null) continue;
            if (!assignments.existsByUserAndExam(u, e)) {
                DomainAdminAssignment a = new DomainAdminAssignment();
                a.setUser(u);
                a.setExam(e);
                a.setAssignedAt(Instant.now());
                a.setAssignedByEmail(byEmail);
                assignments.save(a);
                added++;
            }
        }
        return added;
    }
}
