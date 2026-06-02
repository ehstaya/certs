package com.sfquiz.controller;

import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
import com.sfquiz.service.DomainAdminService;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.SlackNotifier;
import com.sfquiz.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserService users;
    private final DomainAdminService domainAdmins;
    private final ExamService examService;
    private final SlackNotifier slack;

    public AdminController(UserService users, DomainAdminService domainAdmins,
                           ExamService examService, SlackNotifier slack) {
        this.users = users;
        this.domainAdmins = domainAdmins;
        this.examService = examService;
        this.slack = slack;
    }

    /** SUPERADMIN dashboard — single landing for platform-wide admin
     *  operations. Domain admins never see this page (path guarded to
     *  SUPERADMIN by SecurityConfig); they go straight to
     *  /admin/questions when they click "Questions" in the top nav. */
    @GetMapping
    public String dashboard(Model model) {
        // Headline counts for the dashboard cards.
        List<User> all = users.listAll();
        long activeUserCount    = all.stream().filter(u -> u.getStatus() == UserStatus.ACTIVE).count();
        long pendingUserCount   = all.stream().filter(u -> u.getStatus() == UserStatus.PENDING).count();
        long domainAdminCount   = all.stream().filter(u -> u.getStatus() == UserStatus.ACTIVE)
                                     .filter(u -> u.getRole() == UserRole.ADMIN).count();
        long activeExamCount    = examService.listActive().size();

        model.addAttribute("activeUserCount", activeUserCount);
        model.addAttribute("pendingUserCount", pendingUserCount);
        model.addAttribute("domainAdminCount", domainAdminCount);
        model.addAttribute("activeExamCount", activeExamCount);

        model.addAttribute("slackConfigured", slack.isConfigured());
        model.addAttribute("slackMentionsLive", slack.mentionsAreLive());
        model.addAttribute("slackLastPostedAt", slack.lastPostedAt());
        return "admin-dashboard";
    }

    /** User-management page (was at /admin before the dashboard split).
     *  All POST endpoints under /admin/users/* continue to redirect here. */
    @GetMapping("/users")
    public String userAdministration(Model model) {
        List<User> all = users.listAll();
        List<User> active = all.stream().filter(u -> u.getStatus() == UserStatus.ACTIVE).toList();
        model.addAttribute("pending", all.stream().filter(u -> u.getStatus() == UserStatus.PENDING).toList());
        model.addAttribute("active",  active);
        model.addAttribute("rejected",all.stream().filter(u -> u.getStatus() == UserStatus.REJECTED).toList());
        model.addAttribute("disabled",all.stream().filter(u -> u.getStatus() == UserStatus.DISABLED).toList());

        // Per-ADMIN-user assignment count so the user table can show
        // "manages N cert(s)" next to the Manage certs button.
        java.util.Map<Long, Integer> assignmentCounts = new java.util.HashMap<>();
        for (User u : active) {
            if (u.getRole() == UserRole.ADMIN) {
                assignmentCounts.put(u.getId(), domainAdmins.examSlugsFor(u).size());
            }
        }
        model.addAttribute("assignmentCounts", assignmentCounts);

        return "admin-users";
    }

    /** Per-user assignment page — a focused screen showing a single ADMIN
     *  with checkboxes for every active certification. Saving posts to the
     *  existing /users/{id}/domain-assignments endpoint. This is the page
     *  super admins reach via the "Manage certs" button on each ADMIN row
     *  of the user table (the cross-cert matrix on /admin/certifications
     *  is kept as a complementary "all admins at once" view). */
    @GetMapping("/users/{id}/assignments")
    public String userAssignments(@PathVariable Long id, Model model) {
        User u = users.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        model.addAttribute("user", u);
        model.addAttribute("activeExams", examService.listActive());
        model.addAttribute("assignedSlugs",
                new java.util.HashSet<>(domainAdmins.examSlugsFor(u)));
        return "admin-user-assignments";
    }

    /** Manually fire a Slack digest message. SUPERADMIN-only via the
     *  /admin/users/** path guard. Useful right after configuring the
     *  webhook so the operator can confirm the URL works. */
    @PostMapping("/users/slack-test")
    public String slackTest(RedirectAttributes flash) {
        if (!slack.isConfigured()) {
            flash.addFlashAttribute("adminError",
                    "Slack webhook URL is not configured. Set the SLACK_WEBHOOK_URL env var (or app.slack.webhook-url property) and restart.");
            return "redirect:/admin";
        }
        boolean ok = slack.postDigestForce("manual-test from /admin");
        if (ok) {
            flash.addFlashAttribute("adminMessage",
                    "Slack test message posted. Check the channel — if you don't see it, double-check the webhook URL.");
        } else {
            flash.addFlashAttribute("adminError",
                    "Slack POST failed. Webhook URL likely invalid or revoked — see the server logs for details.");
        }
        return "redirect:/admin";
    }

    @PostMapping("/users/new")
    public String createUser(@RequestParam String email,
                             @RequestParam(required = false) String fullName,
                             @RequestParam(required = false, defaultValue = "USER") String role,
                             RedirectAttributes flash) {
        UserRole r;
        try {
            r = UserRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            r = UserRole.USER;
        }
        UserService.PasswordIssued result = users.createUserByAdmin(email, fullName, r);
        flash.addFlashAttribute("issuedEmail", result.user().getEmail());
        flash.addFlashAttribute("issuedFullName", result.user().getFullName());
        flash.addFlashAttribute("issuedPassword", result.tempPassword());
        flash.addFlashAttribute("issuedReason", "created");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes flash) {
        UserService.PasswordIssued result = users.approve(id);
        flash.addFlashAttribute("issuedEmail", result.user().getEmail());
        flash.addFlashAttribute("issuedFullName", result.user().getFullName());
        flash.addFlashAttribute("issuedPassword", result.tempPassword());
        flash.addFlashAttribute("issuedReason", "approved");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/reset-password")
    public String resetPassword(@PathVariable Long id, RedirectAttributes flash) {
        UserService.PasswordIssued result = users.resetPasswordByAdmin(id);
        flash.addFlashAttribute("issuedEmail", result.user().getEmail());
        flash.addFlashAttribute("issuedFullName", result.user().getFullName());
        flash.addFlashAttribute("issuedPassword", result.tempPassword());
        flash.addFlashAttribute("issuedReason", "reset");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/reject")
    public String reject(@PathVariable Long id) {
        users.reject(id);
        return "redirect:/admin/users?rejected";
    }

    @PostMapping("/users/{id}/disable")
    public String disable(@PathVariable Long id) {
        users.disable(id);
        return "redirect:/admin/users?disabled";
    }

    @PostMapping("/users/{id}/reactivate")
    public String reactivate(@PathVariable Long id) {
        users.reactivate(id);
        return "redirect:/admin/users?reactivated";
    }

    @PostMapping("/users/{id}/promote")
    public String promote(@PathVariable Long id) {
        users.promoteToAdmin(id);
        return "redirect:/admin/users?promoted";
    }

    @PostMapping("/users/{id}/promote-superadmin")
    public String promoteSuperAdmin(@PathVariable Long id) {
        users.promoteToSuperAdmin(id);
        return "redirect:/admin/users?promotedSuper";
    }

    @PostMapping("/users/{id}/promote-verifier")
    public String promoteVerifier(@PathVariable Long id) {
        users.promoteToVerifier(id);
        return "redirect:/admin/users?promotedVerifier";
    }

    @PostMapping("/users/{id}/demote-verifier")
    public String demoteVerifier(@PathVariable Long id, Authentication auth) {
        users.demoteVerifierToUser(id, auth == null ? null : auth.getName());
        return "redirect:/admin/users?demotedVerifier";
    }

    @PostMapping("/users/{id}/demote-superadmin")
    public String demoteSuperAdmin(@PathVariable Long id, Authentication auth) {
        users.demoteSuperAdminToAdmin(id, auth == null ? null : auth.getName());
        return "redirect:/admin/users?demotedSuper";
    }

    @PostMapping("/users/{id}/demote")
    public String demote(@PathVariable Long id, Authentication auth) {
        User demoted = users.demoteFromAdmin(id, auth == null ? null : auth.getName());
        // Demoting out of ADMIN/SUPERADMIN role wipes their domain assignments
        // so they don't linger as orphan rows — if they're re-promoted later
        // the SUPERADMIN must explicitly re-assign exam access.
        if (demoted != null && demoted.getRole() != UserRole.ADMIN && demoted.getRole() != UserRole.SUPERADMIN) {
            domainAdmins.clearAssignments(demoted);
        }
        return "redirect:/admin/users?demoted";
    }

    /** Replace the set of exams this admin user governs. SUPERADMIN-only;
     *  enforced by /admin/users/** path rule in SecurityConfig. The form
     *  posts a list of checked exam slugs as repeated {@code exams} params;
     *  any slug not in the list is removed. */
    /** Replace the set of exams an ADMIN user governs. Lives under
     *  /admin/users/** so the existing SUPERADMIN guard applies; the form
     *  is rendered on /admin/certifications, which is where this redirect
     *  lands the user on success. */
    @PostMapping("/users/{id}/domain-assignments")
    public String setDomainAssignments(@PathVariable Long id,
                                       @RequestParam(name = "exams", required = false) List<String> exams,
                                       Authentication auth,
                                       RedirectAttributes flash) {
        String byEmail = auth == null ? null : auth.getName();
        domainAdmins.replaceAssignments(id, exams, byEmail);
        flash.addFlashAttribute("adminMessage", "Domain assignments updated.");
        return "redirect:/admin/certifications";
    }

    /** Permanent deletion of any user — USER, VERIFIER, ADMIN, or SUPERADMIN
     *  (the service has self-delete + last-admin guards). */
    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable Long id, Authentication auth,
                             RedirectAttributes flash) {
        users.deleteUser(id, auth == null ? null : auth.getName());
        flash.addFlashAttribute("adminMessage", "User permanently deleted.");
        return "redirect:/admin/users";
    }

    /** Surface promote/demote safety errors (last admin, demote self, etc.)
     *  as a one-shot flash message on the user page instead of a 500. */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String onAdminError(RuntimeException exc, RedirectAttributes flash) {
        flash.addFlashAttribute("adminError", exc.getMessage());
        return "redirect:/admin/users";
    }
}
