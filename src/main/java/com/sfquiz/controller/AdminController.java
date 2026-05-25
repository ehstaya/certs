package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @GetMapping
    public String dashboard(Model model) {
        List<User> all = users.listAll();
        List<User> active = all.stream().filter(u -> u.getStatus() == UserStatus.ACTIVE).toList();
        model.addAttribute("pending", all.stream().filter(u -> u.getStatus() == UserStatus.PENDING).toList());
        model.addAttribute("active",  active);
        model.addAttribute("rejected",all.stream().filter(u -> u.getStatus() == UserStatus.REJECTED).toList());
        model.addAttribute("disabled",all.stream().filter(u -> u.getStatus() == UserStatus.DISABLED).toList());

        // Per-user list of exam-slugs they currently govern (drives the
        // domain-admin assignment matrix on the page). Empty for non-admins.
        List<ExamDto> activeExams = examService.listActive();
        Map<Long, Set<String>> assigned = new HashMap<>();
        for (User u : active) {
            if (u.getRole() == UserRole.ADMIN || u.getRole() == UserRole.SUPERADMIN) {
                assigned.put(u.getId(), new HashSet<>(domainAdmins.examSlugsFor(u)));
            }
        }
        model.addAttribute("activeExams", activeExams);
        model.addAttribute("assigned", assigned);

        // Slack integration status — drives the small "Slack" card on /admin
        // so the super admin can see at a glance whether the webhook is set,
        // when the last digest was posted, and trigger a manual test.
        model.addAttribute("slackConfigured", slack.isConfigured());
        model.addAttribute("slackLastPostedAt", slack.lastPostedAt());
        return "admin";
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
        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes flash) {
        UserService.PasswordIssued result = users.approve(id);
        flash.addFlashAttribute("issuedEmail", result.user().getEmail());
        flash.addFlashAttribute("issuedFullName", result.user().getFullName());
        flash.addFlashAttribute("issuedPassword", result.tempPassword());
        flash.addFlashAttribute("issuedReason", "approved");
        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/reset-password")
    public String resetPassword(@PathVariable Long id, RedirectAttributes flash) {
        UserService.PasswordIssued result = users.resetPasswordByAdmin(id);
        flash.addFlashAttribute("issuedEmail", result.user().getEmail());
        flash.addFlashAttribute("issuedFullName", result.user().getFullName());
        flash.addFlashAttribute("issuedPassword", result.tempPassword());
        flash.addFlashAttribute("issuedReason", "reset");
        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/reject")
    public String reject(@PathVariable Long id) {
        users.reject(id);
        return "redirect:/admin?rejected";
    }

    @PostMapping("/users/{id}/disable")
    public String disable(@PathVariable Long id) {
        users.disable(id);
        return "redirect:/admin?disabled";
    }

    @PostMapping("/users/{id}/reactivate")
    public String reactivate(@PathVariable Long id) {
        users.reactivate(id);
        return "redirect:/admin?reactivated";
    }

    @PostMapping("/users/{id}/promote")
    public String promote(@PathVariable Long id) {
        users.promoteToAdmin(id);
        return "redirect:/admin?promoted";
    }

    @PostMapping("/users/{id}/promote-superadmin")
    public String promoteSuperAdmin(@PathVariable Long id) {
        users.promoteToSuperAdmin(id);
        return "redirect:/admin?promotedSuper";
    }

    @PostMapping("/users/{id}/demote-superadmin")
    public String demoteSuperAdmin(@PathVariable Long id, Authentication auth) {
        users.demoteSuperAdminToAdmin(id, auth == null ? null : auth.getName());
        return "redirect:/admin?demotedSuper";
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
        return "redirect:/admin?demoted";
    }

    /** Replace the set of exams this admin user governs. SUPERADMIN-only;
     *  enforced by /admin/users/** path rule in SecurityConfig. The form
     *  posts a list of checked exam slugs as repeated {@code exams} params;
     *  any slug not in the list is removed. */
    @PostMapping("/users/{id}/domain-assignments")
    public String setDomainAssignments(@PathVariable Long id,
                                       @RequestParam(name = "exams", required = false) List<String> exams,
                                       Authentication auth,
                                       RedirectAttributes flash) {
        String byEmail = auth == null ? null : auth.getName();
        domainAdmins.replaceAssignments(id, exams, byEmail);
        flash.addFlashAttribute("adminMessage", "Domain assignments updated.");
        return "redirect:/admin";
    }

    /** Permanent deletion of any user — USER, VERIFIER, or ADMIN. Guarded by
     *  the service against self-delete and last-admin-delete (those throw
     *  IllegalStateException → flash banner via the @ExceptionHandler below). */
    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable Long id, Authentication auth,
                             RedirectAttributes flash) {
        users.deleteUser(id, auth == null ? null : auth.getName());
        flash.addFlashAttribute("adminMessage", "User permanently deleted.");
        return "redirect:/admin";
    }

    /** Surface promote/demote safety errors (last admin, demote self, etc.) as a
     *  one-shot flash message on /admin instead of a 500 page. */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String onAdminError(RuntimeException exc, RedirectAttributes flash) {
        flash.addFlashAttribute("adminError", exc.getMessage());
        return "redirect:/admin";
    }
}
