package com.sfquiz.controller;

import com.sfquiz.dto.ImportExamRequest;
import com.sfquiz.entity.Question;
import com.sfquiz.entity.User;
import com.sfquiz.service.AuthorizationService;
import com.sfquiz.service.QuestionAdminService;
import com.sfquiz.service.TopicClassifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/admin/questions")
public class QuestionAdminController {

    private final QuestionAdminService questions;
    private final TopicClassifier topicClassifier;
    private final com.sfquiz.service.ExplanationEnricher enricher;
    private final com.sfquiz.service.ExamService examService;
    private final AuthorizationService authz;

    public QuestionAdminController(QuestionAdminService questions,
                                   TopicClassifier topicClassifier,
                                   com.sfquiz.service.ExplanationEnricher enricher,
                                   com.sfquiz.service.ExamService examService,
                                   AuthorizationService authz) {
        this.questions = questions;
        this.topicClassifier = topicClassifier;
        this.enricher = enricher;
        this.examService = examService;
        this.authz = authz;
    }

    /** Resolves the slugs the current caller is allowed to manage. SUPERADMIN
     *  gets the wildcard ("*") set; ADMINs get only their explicitly-assigned
     *  exam slugs. Returns an empty set for non-admin callers (which means
     *  every list comes back empty — defense in depth). */
    private Set<String> managed(Authentication auth) {
        User u = authz.currentUser(auth).orElse(null);
        return authz.managedExamSlugs(u);
    }

    private void requireCanManage(Authentication auth, Question q) {
        User u = authz.currentUser(auth).orElse(null);
        if (!authz.canManageQuestion(u, q)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not a domain admin for this exam.");
        }
    }

    private void requireCanManage(Authentication auth, String examSlug) {
        User u = authz.currentUser(auth).orElse(null);
        if (!authz.canManageExam(u, examSlug)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not a domain admin for that exam.");
        }
    }

    @GetMapping
    public String review(@RequestParam(name = "page", defaultValue = "0") int page,
                         Authentication auth, Model model) {
        Set<String> mine = managed(auth);
        QuestionAdminService.PagedApproved pg = questions.pendingPageScoped(page, 20, mine);
        model.addAttribute("pending", pg.rows());
        model.addAttribute("page", pg.page());
        model.addAttribute("totalPages", pg.totalPages());
        model.addAttribute("totalElements", pg.totalElements());
        model.addAttribute("pageSize", pg.pageSize());
        model.addAttribute("pendingCount", questions.pendingCountScoped(mine));
        model.addAttribute("approvedCount", questions.approvedCountScoped(mine));
        model.addAttribute("retiredCount", questions.retiredCountScoped(mine));
        model.addAttribute("recentApproved", questions.recentApprovedScoped(10, mine));
        model.addAttribute("recentImports", questions.recentImportEventViewsScoped(mine));
        model.addAttribute("managedSlugs", mine);
        model.addAttribute("isSuperAdmin", AuthorizationService.managesAllExams(mine));
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
     *  question review queue so it stays focused. Only renders action
     *  buttons for the certs the caller actually governs; super admins
     *  see every active cert. */
    @GetMapping("/maintenance")
    public String maintenance(Authentication auth, Model model) {
        Set<String> mine = managed(auth);
        List<com.sfquiz.dto.ExamDto> allActive = examService.listActive();
        List<com.sfquiz.dto.ExamDto> manageable = AuthorizationService.managesAllExams(mine)
                ? allActive
                : allActive.stream().filter(e -> mine.contains(e.slug())).toList();
        model.addAttribute("manageableExams", manageable);
        return "question-maintenance";
    }

    /** One-shot admin action: classify every untagged approved question for an
     *  exam (default salesforce-admin) using Claude Haiku. Idempotent — re-runs
     *  just skip already-tagged rows. Bounded by the daily Anthropic budget. */
    @PostMapping("/classify-topics")
    public String classifyTopics(@RequestParam(name = "examSlug", defaultValue = "salesforce-admin") String examSlug,
                                 Authentication auth,
                                 RedirectAttributes flash) {
        requireCanManage(auth, examSlug);
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
                                     Authentication auth,
                                     RedirectAttributes flash) {
        requireCanManage(auth, examSlug);
        com.sfquiz.service.ExplanationEnricher.BatchResult result = enricher.enrichExisting(examSlug);
        flash.addFlashAttribute("enrichMessage", result.message());
        return "redirect:/admin/questions/maintenance";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, Authentication auth) {
        Question q = questions.get(id);
        requireCanManage(auth, q);
        questions.approve(id, auth == null ? null : auth.getName());
        return "redirect:/admin/questions?approved";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, Authentication auth) {
        Question q = questions.get(id);
        requireCanManage(auth, q);
        questions.reject(id, auth == null ? null : auth.getName());
        return "redirect:/admin/questions?rejected";
    }

    /** Bulk approve / reject — drains the selected pending questions in a
     *  single round-trip from the review page. Each id is independently
     *  scope-checked against the caller's managed certs so a domain admin
     *  who governs only cert A can't accidentally (or via crafted form
     *  POST) act on questions for cert B. Unknown ids are silently
     *  skipped — they may have been deleted between page load and submit. */
    @PostMapping("/bulk-approve")
    public String bulkApprove(@RequestParam(name = "ids", required = false) List<Long> ids,
                              Authentication auth,
                              RedirectAttributes flash) {
        int approved = doBulkAction(ids, auth, /*approve=*/ true);
        flash.addFlashAttribute("bulkMessage",
                approved == 0 ? "No questions were approved (selection was empty or out of scope)."
                              : "Approved " + approved + " question" + (approved == 1 ? "" : "s") + ".");
        return "redirect:/admin/questions";
    }

    @PostMapping("/bulk-reject")
    public String bulkReject(@RequestParam(name = "ids", required = false) List<Long> ids,
                             Authentication auth,
                             RedirectAttributes flash) {
        int rejected = doBulkAction(ids, auth, /*approve=*/ false);
        flash.addFlashAttribute("bulkMessage",
                rejected == 0 ? "No questions were rejected (selection was empty or out of scope)."
                              : "Rejected " + rejected + " question" + (rejected == 1 ? "" : "s") + ".");
        return "redirect:/admin/questions";
    }

    /** Shared bulk-action loop — returns the number of ids that actually
     *  applied. Skips silently on missing rows and on rows the caller
     *  doesn't govern (defense in depth — the UI only renders checkboxes
     *  for in-scope rows, but a hand-crafted POST could include others). */
    private int doBulkAction(List<Long> ids, Authentication auth, boolean approve) {
        if (ids == null || ids.isEmpty()) return 0;
        User caller = authz.currentUser(auth).orElse(null);
        String adminEmail = auth == null ? null : auth.getName();
        int count = 0;
        for (Long id : ids) {
            if (id == null) continue;
            Question q;
            try {
                q = questions.get(id);
            } catch (IllegalArgumentException notFound) {
                continue;
            }
            if (!authz.canManageQuestion(caller, q)) continue;
            if (approve) questions.approve(id, adminEmail);
            else         questions.reject(id, adminEmail);
            count++;
        }
        return count;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Authentication auth, Model model) {
        Question q = questions.get(id);
        requireCanManage(auth, q);
        model.addAttribute("q", q);
        return "question-edit";
    }

    /** Browse approved questions — exam filter + pagination, with Edit / Delete
     *  controls per row so admins can fix or remove anything that landed wrong.
     *  Domain admins only ever see exams they govern; SUPERADMIN sees every
     *  exam. The exam dropdown is filtered to match. */
    @GetMapping("/approved")
    public String approved(@RequestParam(name = "exam", defaultValue = "") String exam,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           Authentication auth,
                           Model model) {
        Set<String> mine = managed(auth);
        // If the URL pins a specific exam, gate access to it.
        if (exam != null && !exam.isBlank()) {
            requireCanManage(auth, exam);
        }
        QuestionAdminService.PagedApproved pg = questions.approvedPageScoped(exam, page, 20, mine);
        model.addAttribute("rows", pg.rows());
        model.addAttribute("page", pg.page());
        model.addAttribute("totalPages", pg.totalPages());
        model.addAttribute("totalElements", pg.totalElements());
        model.addAttribute("pageSize", pg.pageSize());
        model.addAttribute("exam", exam);
        // Dropdown shows only exams the caller can manage.
        List<com.sfquiz.dto.ExamDto> allExams = examService.listActive();
        model.addAttribute("exams", AuthorizationService.managesAllExams(mine)
                ? allExams
                : allExams.stream().filter(e -> mine.contains(e.slug())).toList());
        return "approved-questions";
    }

    /** "Delete" from the approved view = retire. The question moves into the
     *  RETIRED queue where the retiring admin (or any admin) can later restore
     *  it, edit + restore, or actually permanently delete it. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(name = "returnTo", required = false) String returnTo,
                         Authentication auth,
                         RedirectAttributes flash) {
        Question q = questions.get(id);
        requireCanManage(auth, q);
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
                           Authentication auth,
                           RedirectAttributes flash) {
        Question q = questions.get(id);
        requireCanManage(auth, q);
        questions.sendBackToReview(id, auth == null ? null : auth.getName());
        flash.addFlashAttribute("sentBackId", id);
        if (returnTo != null && returnTo.startsWith("/admin/")) {
            return "redirect:" + returnTo;
        }
        return "redirect:/admin/questions";
    }

    /** Restore a RETIRED question back to APPROVED. */
    @PostMapping("/{id}/restore")
    public String restore(@PathVariable Long id,
                          Authentication auth,
                          RedirectAttributes flash) {
        Question q = questions.get(id);
        requireCanManage(auth, q);
        questions.restore(id, auth == null ? null : auth.getName());
        flash.addFlashAttribute("restoredId", id);
        return "redirect:/admin/questions/retired";
    }

    /** Permanent deletion — only available from the retired queue. */
    @PostMapping("/{id}/permanently-delete")
    public String permanentlyDelete(@PathVariable Long id,
                                    Authentication auth,
                                    RedirectAttributes flash) {
        Question q = questions.get(id);
        requireCanManage(auth, q);
        questions.permanentlyDelete(id, auth == null ? null : auth.getName());
        flash.addFlashAttribute("permanentlyDeletedId", id);
        return "redirect:/admin/questions/retired";
    }

    /** Retired queue — paged, filterable, scoped to the caller's managed exams. */
    @GetMapping("/retired")
    public String retired(@RequestParam(name = "exam", defaultValue = "") String exam,
                          @RequestParam(name = "page", defaultValue = "0") int page,
                          Authentication auth,
                          Model model) {
        Set<String> mine = managed(auth);
        if (exam != null && !exam.isBlank()) {
            requireCanManage(auth, exam);
        }
        QuestionAdminService.PagedApproved pg = questions.retiredPageScoped(exam, page, 20, mine);
        model.addAttribute("rows", pg.rows());
        model.addAttribute("page", pg.page());
        model.addAttribute("totalPages", pg.totalPages());
        model.addAttribute("totalElements", pg.totalElements());
        model.addAttribute("pageSize", pg.pageSize());
        model.addAttribute("exam", exam);
        List<com.sfquiz.dto.ExamDto> allExams = examService.listActive();
        model.addAttribute("exams", AuthorizationService.managesAllExams(mine)
                ? allExams
                : allExams.stream().filter(e -> mine.contains(e.slug())).toList());
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
                       @RequestParam(name = "action", required = false) String action,
                       Authentication auth) {
        Question q = questions.get(id);
        requireCanManage(auth, q);
        String adminEmail = auth == null ? null : auth.getName();
        questions.update(id, type, text, explanation, helpUrl, choiceText, correct, adminEmail);
        if ("save-approve".equalsIgnoreCase(action)) {
            questions.approve(id, adminEmail);
            return "redirect:/admin/questions?approved";
        }
        if ("save-restore".equalsIgnoreCase(action)) {
            questions.restore(id, adminEmail);
            return "redirect:/admin/questions/retired?updated";
        }
        return "redirect:/admin/questions?updated";
    }
}
