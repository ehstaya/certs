package com.sfquiz.controller;

import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
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

    public AdminController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public String dashboard(Model model) {
        List<User> all = users.listAll();
        model.addAttribute("pending", all.stream().filter(u -> u.getStatus() == UserStatus.PENDING).toList());
        model.addAttribute("active",  all.stream().filter(u -> u.getStatus() == UserStatus.ACTIVE).toList());
        model.addAttribute("rejected",all.stream().filter(u -> u.getStatus() == UserStatus.REJECTED).toList());
        model.addAttribute("disabled",all.stream().filter(u -> u.getStatus() == UserStatus.DISABLED).toList());
        return "admin";
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

    @PostMapping("/users/{id}/demote")
    public String demote(@PathVariable Long id, Authentication auth) {
        users.demoteFromAdmin(id, auth == null ? null : auth.getName());
        return "redirect:/admin?demoted";
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
