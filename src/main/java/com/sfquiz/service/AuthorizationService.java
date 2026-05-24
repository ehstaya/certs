package com.sfquiz.service;

import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.DomainAdminAssignmentRepository;
import com.sfquiz.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Central authorization helper — single source of truth for the
 *  "super admin vs. domain admin" split introduced when we moved from a
 *  flat ADMIN role to per-exam scoping. Use these helpers instead of
 *  inlining role checks in controllers/services so the rules stay
 *  consistent everywhere. */
@Service
public class AuthorizationService {

    private final UserRepository users;
    private final DomainAdminAssignmentRepository assignments;

    public AuthorizationService(UserRepository users, DomainAdminAssignmentRepository assignments) {
        this.users = users;
        this.assignments = assignments;
    }

    /** Resolves the User entity for the Spring Security principal. */
    public Optional<User> currentUser(Authentication auth) {
        if (auth == null || auth.getName() == null) return Optional.empty();
        return users.findByEmailIgnoreCase(auth.getName());
    }

    public boolean isSuperAdmin(User u) {
        return u != null && u.getRole() == UserRole.SUPERADMIN;
    }

    public boolean isAdminOrSuper(User u) {
        return u != null && (u.getRole() == UserRole.ADMIN || u.getRole() == UserRole.SUPERADMIN);
    }

    /** Exam slugs this user is allowed to manage. SUPERADMINs implicitly
     *  manage every exam — represented here by the special wildcard
     *  {@link #ALL_EXAMS}; check with {@link #managesAllExams(Set)}. */
    public static final String ALL_EXAMS = "*";

    public Set<String> managedExamSlugs(User u) {
        if (u == null) return Set.of();
        if (u.getRole() == UserRole.SUPERADMIN) return Set.of(ALL_EXAMS);
        if (u.getRole() != UserRole.ADMIN) return Set.of();
        return new HashSet<>(assignments.findExamSlugsByUser(u));
    }

    public static boolean managesAllExams(Set<String> managedSlugs) {
        return managedSlugs != null && managedSlugs.contains(ALL_EXAMS);
    }

    /** Can {@code u} manage questions on {@code examSlug}? */
    public boolean canManageExam(User u, String examSlug) {
        if (u == null || examSlug == null) return false;
        if (u.getRole() == UserRole.SUPERADMIN) return true;
        if (u.getRole() != UserRole.ADMIN) return false;
        return assignments.findExamSlugsByUser(u).contains(examSlug);
    }

    /** Convenience: can {@code u} manage {@code question}? Walks the
     *  question -> exam association. */
    public boolean canManageQuestion(User u, Question q) {
        if (u == null || q == null) return false;
        if (u.getRole() == UserRole.SUPERADMIN) return true;
        if (q.getExam() == null) return false;
        return canManageExam(u, q.getExam().getSlug());
    }

    /** Filters a list of exam slugs down to those the user is allowed to
     *  see. SUPERADMINs pass through unchanged. */
    public List<String> filterToManaged(User u, List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) return List.of();
        if (u != null && u.getRole() == UserRole.SUPERADMIN) return slugs;
        Set<String> managed = managedExamSlugs(u);
        return slugs.stream().filter(managed::contains).toList();
    }
}
