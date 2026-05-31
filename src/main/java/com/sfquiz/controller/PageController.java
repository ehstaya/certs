package com.sfquiz.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.ui.Model;

import java.io.IOException;

@Controller
public class PageController {

    @GetMapping("/")
    public void root(@RequestParam(name = "retake", required = false) String retake,
                     @RequestParam(name = "exam", required = false) String exam,
                     HttpServletResponse response) throws IOException {
        // If the URL carries a retake or exam param, the caller wants to
        // launch the quiz directly — forward to index.html with the same
        // query string instead of bouncing them through the exam picker.
        // (Without this the picker eats the param and the Retake button
        // looks broken.)
        if (retake != null && !retake.isBlank()) {
            response.sendRedirect("/index.html?retake=" +
                    java.net.URLEncoder.encode(retake.trim(),
                            java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (exam != null && !exam.isBlank()) {
            response.sendRedirect("/index.html?exam=" +
                    java.net.URLEncoder.encode(exam.trim(),
                            java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        // Authenticated users with no explicit target land on the exam picker.
        response.sendRedirect("/exams.html");
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        @RequestParam(required = false) String registered,
                        @RequestParam(required = false) String reset,
                        Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "redirect:/";
        }
        if (error != null) model.addAttribute("error", "Invalid email or password.");
        if (logout != null) model.addAttribute("info", "You have been signed out.");
        if (registered != null) model.addAttribute("info",
                "Registration submitted. An admin will review your request — you'll get an email once approved.");
        if (reset != null) model.addAttribute("info", "Password updated. Please sign in with your new password.");
        return "login";
    }
}
