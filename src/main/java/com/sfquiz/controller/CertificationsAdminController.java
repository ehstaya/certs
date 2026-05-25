package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ExamTopic;
import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
import com.sfquiz.repository.ExamTopicRepository;
import com.sfquiz.service.DomainAdminService;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.TopicBreakdownParser;
import com.sfquiz.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ExamTopicRepository examTopics;

    public CertificationsAdminController(ExamService examService,
                                         UserService users,
                                         DomainAdminService domainAdmins,
                                         TopicBreakdownParser breakdownParser,
                                         ExamTopicRepository examTopics) {
        this.examService = examService;
        this.users = users;
        this.domainAdmins = domainAdmins;
        this.breakdownParser = breakdownParser;
        this.examTopics = examTopics;
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
        // Up-front check: neither input supplied. Reject before hitting the
        // parser so we give a precise error message that points at the form.
        boolean haveText  = breakdownText  != null && !breakdownText.isBlank();
        boolean haveImage = breakdownImage != null && !breakdownImage.isEmpty();
        if (!haveText && !haveImage) {
            throw new IllegalArgumentException(
                    "Topic breakdown is required. Paste the per-topic % weights or upload a screenshot of the score-breakdown table.");
        }

        List<TopicBreakdownParser.TopicWeight> topics = parseBreakdown(breakdownText, breakdownImage);

        // Parsed but came back empty — the input was malformed or Claude
        // declined to extract anything. Tell the operator instead of saving
        // a topic-less cert behind their back.
        if (topics.isEmpty()) {
            throw new IllegalArgumentException(
                    "Could not extract any topic + percentage pairs from the breakdown you supplied. " +
                    "Format each row as 'Topic name: 20%' (one per line), or upload a clearer screenshot of the breakdown table.");
        }

        Exam created = examService.createExam(name, slug, description,
                questionsPerSession, durationMinutes, passingScorePercent,
                topics);

        int sum = topics.stream().mapToInt(TopicBreakdownParser.TopicWeight::weightPercent).sum();
        StringBuilder msg = new StringBuilder("Certification '")
                .append(created.getName())
                .append("' created. Persisted ").append(topics.size())
                .append(" topic(s) totalling ").append(sum).append("%.");
        if (sum < 95 || sum > 105) {
            msg.append(" ⚠ weights don't add up to 100 — double-check the breakdown.");
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

    /** Edit form for an existing cert. Slug is immutable but every other
     *  field is editable, and the super admin can optionally republish the
     *  topic breakdown by pasting fresh text or uploading a new screenshot
     *  (existing topics are wiped and replaced on save). */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Exam exam = examService.findById(id);
        if (exam == null) {
            throw new IllegalArgumentException("Unknown certification: " + id);
        }
        List<ExamTopic> topics = examTopics.findByExamOrderBySortOrderAscIdAsc(exam);
        model.addAttribute("exam", exam);
        model.addAttribute("topics", topics);
        return "admin-cert-edit";
    }

    /** Update an existing cert. {@code breakdown} / {@code breakdownImage}
     *  are optional — when supplied, the parsed topic list replaces the
     *  exam's existing ExamTopic rows atomically. When omitted, the
     *  breakdown is left alone. */
    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false, defaultValue = "60") int questionsPerSession,
                         @RequestParam(required = false, defaultValue = "90") int durationMinutes,
                         @RequestParam(required = false, defaultValue = "65") int passingScorePercent,
                         @RequestParam(name = "active", required = false, defaultValue = "false") boolean active,
                         @RequestParam(name = "breakdown", required = false) String breakdownText,
                         @RequestParam(name = "breakdownImage", required = false) MultipartFile breakdownImage,
                         RedirectAttributes flash) {
        // Republish flow only fires when the operator actually provided new
        // breakdown content — saving the form with both blank leaves the
        // existing topic mix in place.
        boolean haveText  = breakdownText  != null && !breakdownText.isBlank();
        boolean haveImage = breakdownImage != null && !breakdownImage.isEmpty();
        List<TopicBreakdownParser.TopicWeight> topics = List.of();
        if (haveText || haveImage) {
            topics = parseBreakdown(breakdownText, breakdownImage);
            if (topics.isEmpty()) {
                throw new IllegalArgumentException(
                        "You supplied a breakdown but no topics could be parsed. " +
                        "Format each row as 'Topic name: 20%' (one per line), or upload a clearer screenshot.");
            }
        }
        Exam updated = examService.updateExam(id, name, description,
                questionsPerSession, durationMinutes, passingScorePercent,
                active, topics);

        StringBuilder msg = new StringBuilder("Certification '")
                .append(updated.getName())
                .append("' updated");
        if (!topics.isEmpty()) {
            int sum = topics.stream().mapToInt(TopicBreakdownParser.TopicWeight::weightPercent).sum();
            msg.append(" and republished with ").append(topics.size())
               .append(" topic(s) totalling ").append(sum).append("%");
            if (sum < 95 || sum > 105) {
                msg.append(" ⚠ weights don't add up to 100 — double-check the new breakdown");
            }
        }
        msg.append(".");
        flash.addFlashAttribute("certMessage", msg.toString());
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
