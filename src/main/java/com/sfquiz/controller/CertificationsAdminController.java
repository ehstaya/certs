package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
import com.sfquiz.service.DomainAdminService;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    public CertificationsAdminController(ExamService examService,
                                         UserService users,
                                         DomainAdminService domainAdmins) {
        this.examService = examService;
        this.users = users;
        this.domainAdmins = domainAdmins;
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

    /** Create a new certification. Topic breakdown can be added later via
     *  the existing seed/topic flow — this endpoint only stamps the basic
     *  Exam row so the cert appears in the assignment matrix immediately. */
    @PostMapping("/new")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String slug,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false, defaultValue = "60") int questionsPerSession,
                         @RequestParam(required = false, defaultValue = "90") int durationMinutes,
                         @RequestParam(required = false, defaultValue = "65") int passingScorePercent,
                         RedirectAttributes flash) {
        Exam created = examService.createExam(name, slug, description,
                questionsPerSession, durationMinutes, passingScorePercent);
        flash.addFlashAttribute("certMessage",
                "Certification '" + created.getName() + "' created. Assign a domain admin below to start managing it.");
        return "redirect:/admin/certifications";
    }

    /** Surface create-cert validation errors (duplicate slug, blank name, etc.)
     *  as a one-shot flash banner instead of a 500. */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String onCertError(RuntimeException exc, RedirectAttributes flash) {
        flash.addFlashAttribute("certError", exc.getMessage());
        return "redirect:/admin/certifications";
    }
}
