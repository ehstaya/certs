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
                     @RequestParam(name = "resume", required = false) String resume,
                     @RequestParam(name = "restart", required = false) String restart,
                     @RequestParam(name = "exam", required = false) String exam,
                     @RequestParam(name = "mode", required = false) String mode,
                     HttpServletResponse response) throws IOException {
        // If the URL carries retake / resume / exam, forward straight to
        // the quiz with the same query string — bypass the exam picker
        // and the topic-info splash so a click on Resume on the per-test
        // page lands directly in the test.
        // mode (practice|exam) rides along so an exam-mode launch keeps
        // its lock-down UI.
        String modeQs = (mode != null && !mode.isBlank())
                ? "&mode=" + java.net.URLEncoder.encode(mode.trim(),
                        java.nio.charset.StandardCharsets.UTF_8)
                : "";
        if (retake != null && !retake.isBlank()) {
            response.sendRedirect("/index.html?retake=" +
                    java.net.URLEncoder.encode(retake.trim(),
                            java.nio.charset.StandardCharsets.UTF_8) + modeQs);
            return;
        }
        if (resume != null && !resume.isBlank()) {
            // restart=1 means "Retake from start" — same question set,
            // cleared answers. Pass it through so quiz.js init can skip
            // the answer-rehydration step.
            String restartQs = ("1".equals(restart) || "true".equalsIgnoreCase(restart))
                    ? "&restart=1" : "";
            response.sendRedirect("/index.html?resume=" +
                    java.net.URLEncoder.encode(resume.trim(),
                            java.nio.charset.StandardCharsets.UTF_8) + restartQs + modeQs);
            return;
        }
        if (exam != null && !exam.isBlank()) {
            response.sendRedirect("/index.html?exam=" +
                    java.net.URLEncoder.encode(exam.trim(),
                            java.nio.charset.StandardCharsets.UTF_8) + modeQs);
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
