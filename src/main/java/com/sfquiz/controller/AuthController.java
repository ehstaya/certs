package com.sfquiz.controller;

import com.sfquiz.security.AppUserDetails;
import com.sfquiz.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    /* ---------- Registration ---------- */

    @GetMapping("/register")
    public String registerForm(Model model) {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String email,
                           @RequestParam(required = false) String fullName,
                           Model model) {
        try {
            users.register(email, fullName);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("emailValue", email);
            model.addAttribute("nameValue", fullName);
            return "register";
        }
        return "redirect:/login?registered";
    }

    /* ---------- Forgot password ---------- */

    @GetMapping("/forgot-password")
    public String forgotForm() {
        return "forgot";
    }

    @PostMapping("/forgot-password")
    public String forgot(@RequestParam String email, Model model) {
        users.initiatePasswordReset(email);
        // Always show same message — don't disclose which emails exist.
        model.addAttribute("info", "If an active account exists for that email, a reset link has been sent.");
        return "forgot";
    }

    /* ---------- Reset password (via token) ---------- */

    @GetMapping("/reset-password")
    public String resetForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "reset";
    }

    @PostMapping("/reset-password")
    public String reset(@RequestParam String token,
                        @RequestParam String newPassword,
                        @RequestParam String confirmPassword,
                        Model model) {
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("token", token);
            model.addAttribute("error", "Passwords do not match.");
            return "reset";
        }
        try {
            users.resetPasswordWithToken(token, newPassword);
        } catch (IllegalArgumentException e) {
            model.addAttribute("token", token);
            model.addAttribute("error", e.getMessage());
            return "reset";
        }
        return "redirect:/login?reset";
    }

    /* ---------- Change password (forced or voluntary) ---------- */

    @GetMapping("/change-password")
    public String changeForm(Model model) {
        AppUserDetails me = currentUser();
        if (me == null) return "redirect:/login";
        model.addAttribute("forced", me.isMustChangePassword());
        return "change-password";
    }

    @PostMapping("/change-password")
    public String change(@RequestParam String currentPassword,
                         @RequestParam String newPassword,
                         @RequestParam String confirmPassword,
                         HttpServletRequest request,
                         Model model) {
        AppUserDetails me = currentUser();
        if (me == null) return "redirect:/login";
        model.addAttribute("forced", me.isMustChangePassword());

        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "New passwords do not match.");
            return "change-password";
        }
        try {
            users.changePassword(me.getEmail(), currentPassword, newPassword);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "change-password";
        }
        // Force fresh login so the new authentication picks up mustChangePassword=false.
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return "redirect:/login?reset";
    }

    private AppUserDetails currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) return null;
        if (a.getPrincipal() instanceof AppUserDetails ud) return ud;
        return null;
    }
}
