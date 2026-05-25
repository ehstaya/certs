package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
import com.sfquiz.service.DomainAdminService;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.TopicBreakdownParser;
import com.sfquiz.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SUPERADMIN-only management of certifications + domain-admin assignments.
 *  The path guard lives in SecurityConfig — every endpoint here assumes the
 *  caller is a super admin. Domain admins never see this page; they land on
 *  /admin/questions when they click "Questions" in the top nav. */
@Controller
@RequestMapping("/admin/certifications")
public class CertificationsAdminController {

    private final ExamService examService;
    private final UserService users;
    private final DomainAdminService domainAdmins;
    private final TopicBreakdownParser breakdownParser;

    public CertificationsAdminController(ExamService examService,
                                         UserService users,
                                         DomainAdminService domainAdmins,
                                         TopicBreakdownParser breakdownParser) {
        this.examService = examService;
        this.users = users;
        this.domainAdmins = domainAdmins;
        this.breakdownParser = breakdownParser;
    }

    @GetMapping
    public String list(Model model) {
        List<Exam> exams = examService.listAllOrdered();
        List<ExamDto> activeExams = examService.listActive();
        model.addAttribute("exams", exams);
        model.addAttribute("activeExams", activeExams);

        // Domain-admin assignment matrix — moved here from the user-admin
        // page. Each ADMIN user gets a row of exam checkboxes; SUPERADMINs
        // are shown for visibility but implicitly govern every exam.
        List<User> activeUsers = users.listAll().stream()
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> u.getRole() == UserRole.ADMIN || u.getRole() == UserRole.SUPERADMIN)
                .toList();
        Map<Long, Set<String>> assigned = new HashMap<>();
        for (User u : activeUsers) {
            assigned.put(u.getId(), new HashSet<>(domainAdmins.examSlugsFor(u)));
        }
        model.addAttribute("adminUsers", activeUsers);
        model.addAttribute("assigned", assigned);
        return "admin-certifications";
    }

    /** Create a new certification. {@code breakdown} is optional pasted-
     *  text of the cert's per-topic % weights; {@code breakdownImage} is
     *  an optional screenshot of the same. If either is supplied, the
     *  TopicBreakdownParser persists the topics alongside the new exam
     *  so the topic-info page renders the breakdown when learners pick
     *  the new cert. */
    @PostMapping("/new")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String slug,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false, defaultValue = "60") int questionsPerSession,
                         @RequestParam(required = false, defaultValue = "90") int durationMinutes,
                         @RequestParam(required = false, defaultValue = "65") int passingScorePercent,
                         @RequestParam(name = "breakdown", required = false) String breakdownText,
                         @RequestParam(name = "breakdownImage", required = false) MultipartFile breakdownImage,
                         RedirectAttributes flash) {
        List<TopicBreakdownParser.TopicWeight> topics = parseBreakdown(breakdownText, breakdownImage);

        Exam created = examService.createExam(name, slug, description,
                questionsPerSession, durationMinutes, passingScorePercent,
                topics);

        StringBuilder msg = new StringBuilder("Certification '")
                .append(created.getName())
                .append("' created.");
        if (!topics.isEmpty()) {
            int sum = topics.stream().mapToInt(TopicBreakdownParser.TopicWeight::weightPercent).sum();
            msg.append(" Persisted ").append(topics.size())
               .append(" topic(s) totalling ").append(sum).append("%.");
            if (sum < 95 || sum > 105) {
                msg.append(" ⚠ weights don't add up to 100 — double-check the breakdown.");
            }
        } else {
            msg.append(" No topic breakdown was supplied — you can re-create with one or add topics later.");
        }
        msg.append(" Assign a domain admin below to start managing it.");
        flash.addFlashAttribute("certMessage", msg.toString());
        return "redirect:/admin/certifications";
    }

    /** Pick the right parse path. Image wins if present (it's harder for
     *  the operator to upload by accident than to leave the textarea
     *  blank). On IO failure we fall back to text so a half-broken upload
     *  doesn't drop the breakdown entirely. */
    private List<TopicBreakdownParser.TopicWeight> parseBreakdown(String text, MultipartFile image) {
        if (image != null && !image.isEmpty()) {
            try {
                byte[] bytes = image.getBytes();
                List<TopicBreakdownParser.TopicWeight> rows =
                        breakdownParser.parseImage(bytes, image.getContentType());
                if (!rows.isEmpty()) return rows;
            } catch (Exception ignored) { /* fall through to text */ }
        }
        if (text != null && !text.isBlank()) {
            return breakdownParser.parseText(text);
        }
        return List.of();
    }

    /** Surface create-cert validation errors (duplicate slug, blank name, etc.)
     *  as a one-shot flash banner instead of a 500. */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String onCertError(RuntimeException exc, RedirectAttributes flash) {
        flash.addFlashAttribute("certError", exc.getMessage());
        return "redirect:/admin/certifications";
    }
}
