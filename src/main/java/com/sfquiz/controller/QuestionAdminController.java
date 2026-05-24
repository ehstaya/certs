package com.sfquiz.controller;

import com.sfquiz.dto.ImportExamRequest;
import com.sfquiz.service.QuestionAdminService;
import com.sfquiz.service.TopicClassifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/questions")
public class QuestionAdminController {

    private final QuestionAdminService questions;
    private final TopicClassifier topicClassifier;
    private final com.sfquiz.service.ExplanationEnricher enricher;
    private final com.sfquiz.service.ExamService examService;

    public QuestionAdminController(QuestionAdminService questions,
                                   TopicClassifier topicClassifier,
                                   com.sfquiz.service.ExplanationEnricher enricher,
                                   com.sfquiz.service.ExamService examService) {
        this.questions = questions;
        this.topicClassifier = topicClassifier;
        this.enricher = enricher;
        this.examService = examService;
    }

    @GetMapping
    public String review(Model model) {
        model.addAttribute("pending", questions.pending());
        model.addAttribute("pendingCount", questions.pendingCount());
        model.addAttribute("approvedCount", questions.approvedCount());
        model.addAttribute("retiredCount", questions.retiredCount());
        model.addAttribute("recentApproved", questions.recentApproved(10));
        model.addAttribute("recentImports", questions.recentImportEventViews());
        return "questions";
    }

    /** Crawler / agent push endpoint. Accepts one exam in seed-file format
     *  ({slug,name,description,questionsPerSession,durationMinutes,questions:[...]}).
     *  The exam is upserted and its questions are created as PENDING for an admin
     *  to approve. Requires ADMIN role. */
    @PostMapping(value = "/import", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public Map<String, Object> importQuestions(@RequestBody ImportExamRequest req) {
        QuestionAdminService.ImportResult r = questions.importExam(req);
        return Map.of(
                "exam", req != null && req.slug() != null ? req.slug() : "",
                "imported", r.imported(),
                "skipped", r.skipped(),
                "skippedTexts", r.skippedTexts()
        );
    }

    /** Maintenance page — separates the heavy admin-only operations
     *  (topic classification, explanation backfill) from the day-to-day
     *  question review queue so it stays focused. */
    @GetMapping("/maintenance")
    public String maintenance() {
        return "question-maintenance";
    }

    /** One-shot admin action: classify every untagged approved question for an
     *  exam (default salesforce-admin) using Claude Haiku. Idempotent — re-runs
     *  just skip already-tagged rows. Bounded by the daily Anthropic budget. */
    @PostMapping("/classify-topics")
    public String classifyTopics(@RequestParam(name = "examSlug", defaultValue = "salesforce-admin") String examSlug,
                                 RedirectAttributes flash) {
        TopicClassifier.BatchResult result = topicClassifier.classifyUntaggedFor(examSlug);
        flash.addFlashAttribute("classifyMessage", result.message());
        return "redirect:/admin/questions/maintenance";
    }

    /** One-shot admin action: backfill explanation + helpUrl for every approved
     *  question in an exam that's still missing them. Uses Claude Haiku and
     *  cites the vendor's official docs (help.salesforce.com / docs.mulesoft.com
     *  / etc.) — bounded by the daily Anthropic budget; safe to re-run. */
    @PostMapping("/enrich-explanations")
    public String enrichExplanations(@RequestParam(name = "examSlug", defaultValue = "salesforce-admin") String examSlug,
                                     RedirectAttributes flash) {
        com.sfquiz.service.ExplanationEnricher.BatchResult result = enricher.enrichExisting(examSlug);
        flash.addFlashAttribute("enrichMessage", result.message());
        return "redirect:/admin/questions/maintenance";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id) {
        questions.approve(id);
        return "redirect:/admin/questions?approved";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id) {
        questions.reject(id);
        return "redirect:/admin/questions?rejected";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("q", questions.get(id));
        return "question-edit";
    }

    /** Browse approved questions — exam filter + pagination, with Edit / Delete
     *  controls per row so admins can fix or remove anything that landed wrong. */
    @GetMapping("/approved")
    public String approved(@RequestParam(name = "exam", defaultValue = "") String exam,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           Model model) {
        QuestionAdminService.PagedApproved pg = questions.approvedPage(exam, page, 20);
        model.addAttribute("rows", pg.rows());
        model.addAttribute("page", pg.page());
        model.addAttribute("totalPages", pg.totalPages());
        model.addAttribute("totalElements", pg.totalElements());
        model.addAttribute("pageSize", pg.pageSize());
        model.addAttribute("exam", exam);
        model.addAttribute("exams", examService.listActive());
        return "approved-questions";
    }

    /** "Delete" from the approved view = retire. The question moves into the
     *  RETIRED queue where the retiring admin (or any admin) can later restore
     *  it, edit + restore, or actually permanently delete it. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(name = "returnTo", required = false) String returnTo,
                         org.springframework.security.core.Authentication auth,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        String adminEmail = auth == null ? null : auth.getName();
        questions.retire(id, adminEmail);
        flash.addFlashAttribute("retiredId", id);
        if (returnTo != null && returnTo.startsWith("/admin/questions")) {
            return "redirect:" + returnTo;
        }
        return "redirect:/admin/questions/approved";
    }

    /** Send an approved question back to the review queue (used from the
     *  verifier-feedback admin report). Idempotent if already PENDING. */
    @PostMapping("/{id}/send-back")
    public String sendBack(@PathVariable Long id,
                           @RequestParam(name = "returnTo", required = false) String returnTo,
                           org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        questions.sendBackToReview(id);
        flash.addFlashAttribute("sentBackId", id);
        if (returnTo != null && returnTo.startsWith("/admin/")) {
            return "redirect:" + returnTo;
        }
        return "redirect:/admin/questions";
    }

    /** Restore a RETIRED question back to APPROVED. */
    @PostMapping("/{id}/restore")
    public String restore(@PathVariable Long id,
                          org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        questions.restore(id);
        flash.addFlashAttribute("restoredId", id);
        return "redirect:/admin/questions/retired";
    }

    /** Permanent deletion — only available from the retired queue. */
    @PostMapping("/{id}/permanently-delete")
    public String permanentlyDelete(@PathVariable Long id,
                                    org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        questions.permanentlyDelete(id);
        flash.addFlashAttribute("permanentlyDeletedId", id);
        return "redirect:/admin/questions/retired";
    }

    /** Retired queue — paged, filterable, shows who retired each row. */
    @GetMapping("/retired")
    public String retired(@RequestParam(name = "exam", defaultValue = "") String exam,
                          @RequestParam(name = "page", defaultValue = "0") int page,
                          Model model) {
        QuestionAdminService.PagedApproved pg = questions.retiredPage(exam, page, 20);
        model.addAttribute("rows", pg.rows());
        model.addAttribute("page", pg.page());
        model.addAttribute("totalPages", pg.totalPages());
        model.addAttribute("totalElements", pg.totalElements());
        model.addAttribute("pageSize", pg.pageSize());
        model.addAttribute("exam", exam);
        model.addAttribute("exams", examService.listActive());
        return "retired-questions";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @RequestParam String type,
                       @RequestParam String text,
                       @RequestParam(required = false) String explanation,
                       @RequestParam(required = false) String helpUrl,
                       @RequestParam(name = "choiceText", required = false) List<String> choiceText,
                       @RequestParam(name = "correct", required = false) List<Integer> correct,
                       @RequestParam(name = "action", required = false) String action) {
        questions.update(id, type, text, explanation, helpUrl, choiceText, correct);
        if ("save-approve".equalsIgnoreCase(action)) {
            questions.approve(id);
            return "redirect:/admin/questions?approved";
        }
        if ("save-restore".equalsIgnoreCase(action)) {
            questions.restore(id);
            return "redirect:/admin/questions/retired?updated";
        }
        return "redirect:/admin/questions?updated";
    }
}
