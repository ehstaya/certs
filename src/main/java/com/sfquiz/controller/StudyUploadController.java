package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.entity.StudyUpload;
import com.sfquiz.repository.StudyUploadRepository;
import com.sfquiz.service.CostMeter;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.TextExtractor;
import com.sfquiz.service.UploadProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Study-material upload page. Any active user can upload — Claude extracts
 * questions which an admin then approves at /admin/questions. Non-admin users
 * only see their own uploads; admins see all of them.
 *
 * <p>Files are persisted as bytea rows in the {@code study_uploads} table.
 */
@Controller
@RequestMapping("/uploads")
public class StudyUploadController {

    private static final Logger log = LoggerFactory.getLogger(StudyUploadController.class);

    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private final StudyUploadRepository uploads;
    private final UploadProcessor processor;
    private final CostMeter costs;
    private final ExamService examService;

    public StudyUploadController(StudyUploadRepository uploads,
                                 UploadProcessor processor,
                                 CostMeter costs,
                                 ExamService examService) {
        this.uploads = uploads;
        this.processor = processor;
        this.costs = costs;
        this.examService = examService;
    }

    @GetMapping
    public String list(Authentication auth, Model model) {
        String email = currentEmail(auth);
        boolean admin = isAdmin(auth);
        List<StudyUpload> rows = admin
                ? uploads.findTop50ByOrderByUploadedAtDesc()
                : uploads.findTop50ByUploadedByEmailIgnoreCaseOrderByUploadedAtDesc(email);
        model.addAttribute("uploads", rows);
        model.addAttribute("isAdmin", admin);
        model.addAttribute("exams", examService.listActive());
        model.addAttribute("allowedExt", String.join(", ", TextExtractor.ALL_SUPPORTED));
        model.addAttribute("maxMb", MAX_BYTES / (1024 * 1024));
        model.addAttribute("budgetUsd", costs.dailyBudgetUsd());
        model.addAttribute("spentUsd", costs.spentToday());
        model.addAttribute("remainingUsd", costs.remainingToday());
        model.addAttribute("budgetExhausted", costs.budgetExhausted());
        return "uploads";
    }

    @PostMapping
    public String upload(Authentication auth,
                         @RequestParam("examSlug") String examSlug,
                         @RequestParam("file") MultipartFile[] files,
                         RedirectAttributes flash) {
        String email = currentEmail(auth);
        if (examSlug == null || examSlug.isBlank()) {
            flash.addFlashAttribute("uploadError", "Please pick which test this upload is for.");
            return "redirect:/uploads";
        }
        // Validate against the live exam list — rejects typos and inactive slugs.
        boolean known = examService.listActive().stream().anyMatch(e -> e.slug().equals(examSlug));
        if (!known) {
            flash.addFlashAttribute("uploadError", "That exam is not active or doesn't exist: " + examSlug);
            return "redirect:/uploads";
        }
        int saved = 0;
        List<String> rejected = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                String original = file.getOriginalFilename();
                if (original == null || original.isBlank()) continue;
                String safe = sanitize(original);
                if (TextExtractor.classify(safe) == TextExtractor.Kind.UNSUPPORTED) {
                    rejected.add(original + " (extension not allowed)");
                    continue;
                }
                if (file.getSize() > MAX_BYTES) {
                    rejected.add(original + " (over " + (MAX_BYTES / (1024 * 1024)) + " MB)");
                    continue;
                }
                try {
                    Long id = persist(file, safe, email, examSlug);
                    log.info("saved upload id={} {} by={} exam={} ({} bytes)",
                            id, safe, email, examSlug, file.getSize());
                    processor.processAsync(id);
                    saved++;
                } catch (IOException e) {
                    log.error("failed to save {}: {}", original, e.getMessage());
                    rejected.add(original + " (write failed: " + e.getMessage() + ")");
                }
            }
        }
        flash.addFlashAttribute("uploadedCount", saved);
        flash.addFlashAttribute("uploadedExam", examSlug);
        flash.addFlashAttribute("rejected", rejected);
        return "redirect:/uploads";
    }

    /** Paste-from-clipboard alternative to file upload. Users can copy a
     *  question (or batch of questions) from anywhere and submit it directly
     *  as text — gets persisted as a StudyUpload row and runs through the
     *  same Claude extraction + admin-approval flow as a real file upload. */
    @PostMapping("/paste")
    public String paste(Authentication auth,
                        @RequestParam("examSlug") String examSlug,
                        @RequestParam(value = "title", required = false) String title,
                        @RequestParam("content") String content,
                        RedirectAttributes flash) {
        String email = currentEmail(auth);
        if (examSlug == null || examSlug.isBlank()) {
            flash.addFlashAttribute("uploadError", "Please pick which test this is for.");
            return "redirect:/uploads";
        }
        if (content == null || content.trim().length() < 20) {
            flash.addFlashAttribute("uploadError", "Pasted text is too short — paste the full question(s) and their choices.");
            return "redirect:/uploads";
        }
        if (content.length() > MAX_BYTES) {
            flash.addFlashAttribute("uploadError", "Pasted text is too large (max " + (MAX_BYTES / (1024 * 1024)) + " MB).");
            return "redirect:/uploads";
        }
        boolean known = examService.listActive().stream().anyMatch(e -> e.slug().equals(examSlug));
        if (!known) {
            flash.addFlashAttribute("uploadError", "That exam is not active or doesn't exist: " + examSlug);
            return "redirect:/uploads";
        }
        String safeTitle = (title == null || title.isBlank()) ? "Pasted" : sanitize(title);
        if (safeTitle.length() > 80) safeTitle = safeTitle.substring(0, 80);
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String filename = safeTitle + "-" + ts + ".txt";
        try {
            Long id = persistPasted(filename, content, email, examSlug);
            log.info("saved pasted upload id={} {} by={} exam={} ({} chars)",
                    id, filename, email, examSlug, content.length());
            processor.processAsync(id);
            flash.addFlashAttribute("uploadedCount", 1);
            flash.addFlashAttribute("uploadedExam", examSlug);
        } catch (Exception e) {
            log.error("failed to save pasted text: {}", e.getMessage());
            flash.addFlashAttribute("uploadError", "Failed to save pasted text: " + e.getMessage());
        }
        return "redirect:/uploads";
    }

    @Transactional
    public Long persistPasted(String filename, String content, String uploaderEmail, String examSlug) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StudyUpload u = new StudyUpload();
        u.setOriginalName(filename);
        u.setContentType("text/plain");
        u.setSizeBytes(bytes.length);
        u.setContent(bytes);
        u.setUploadedAt(Instant.now());
        u.setUploadedByEmail(uploaderEmail);
        u.setExamSlug(examSlug);
        u.setStatus(StudyUpload.Status.PENDING);
        return uploads.save(u).getId();
    }

    @Transactional
    public Long persist(MultipartFile file, String safeName, String uploaderEmail, String examSlug) throws IOException {
        StudyUpload u = new StudyUpload();
        u.setOriginalName(safeName);
        u.setContentType(file.getContentType());
        u.setSizeBytes(file.getSize());
        u.setContent(file.getBytes());
        u.setUploadedAt(Instant.now());
        u.setUploadedByEmail(uploaderEmail);
        u.setExamSlug(examSlug);
        u.setStatus(StudyUpload.Status.PENDING);
        return uploads.save(u).getId();
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, Authentication auth) {
        StudyUpload u = uploads.findById(id).orElse(null);
        if (u == null) return "redirect:/uploads?deleted";
        checkCanMutate(u, auth);
        uploads.delete(u);
        log.info("deleted upload id={} name={} by={}", u.getId(), u.getOriginalName(), currentEmail(auth));
        return "redirect:/uploads?deleted";
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        StudyUpload u = uploads.findById(id).orElse(null);
        if (u != null) {
            checkCanMutate(u, auth);
            processor.markPending(u.getId());
            processor.processAsync(u.getId());
            flash.addFlashAttribute("retriedId", id);
        }
        return "redirect:/uploads";
    }

    /** Admin-only: set the dump-check-override flag and re-queue the upload.
     *  The extractor will skip the Claude leaked-content check and process the
     *  file anyway. Use sparingly — the dump-check is there to keep paid /
     *  leaked real-exam material out of the bank. */
    @PostMapping("/{id}/override-dump-check")
    public String overrideDumpCheck(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        if (!isAdmin(auth)) {
            throw new AccessDeniedException("Only admins can override the dump-check.");
        }
        uploads.findById(id).ifPresent(u -> {
            processor.setDumpCheckOverride(u.getId(), true);
            processor.markPending(u.getId());
            processor.processAsync(u.getId());
            log.warn("upload id={} dump-check OVERRIDDEN by admin {} — re-processing",
                    u.getId(), currentEmail(auth));
            flash.addFlashAttribute("overrideMessage",
                    "Dump-check override applied to '" + u.getOriginalName() +
                    "'. Re-extracting now — review the resulting questions carefully at /admin/questions before approving.");
        });
        return "redirect:/uploads";
    }

    /** JSON snapshot of the current user's uploads — polled by uploads.html so
     *  the status pill (Pending → Extracting… → Done / Failed / Skipped) updates
     *  in place without a full page reload. */
    @GetMapping(value = "/api/status", produces = "application/json")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> apiStatus(Authentication auth) {
        String email = currentEmail(auth);
        boolean admin = isAdmin(auth);
        List<StudyUpload> rows = admin
                ? uploads.findTop50ByOrderByUploadedAtDesc()
                : uploads.findTop50ByUploadedByEmailIgnoreCaseOrderByUploadedAtDesc(email);
        java.util.List<java.util.Map<String, Object>> dtos = new java.util.ArrayList<>(rows.size());
        for (StudyUpload u : rows) {
            java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("id", u.getId());
            r.put("originalName", u.getOriginalName());
            r.put("sizeBytes", u.getSizeBytes());
            r.put("uploadedAt", u.getUploadedAt() == null ? null : u.getUploadedAt().toString());
            r.put("uploadedByEmail", u.getUploadedByEmail());
            r.put("examSlug", u.getExamSlug());
            r.put("status", u.getStatus().name());
            r.put("questionsExtracted", u.getQuestionsExtracted());
            r.put("questionsImported", u.getQuestionsImported());
            r.put("error", u.getError());
            dtos.add(r);
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("isAdmin", admin);
        result.put("rows", dtos);
        return result;
    }

    /** Download the original uploaded file. Owner or admin only. */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, Authentication auth) {
        StudyUpload u = uploads.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        checkCanMutate(u, auth);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + u.getOriginalName() + "\"")
                .contentType(u.getContentType() != null
                        ? MediaType.parseMediaType(u.getContentType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .body(u.getContent());
    }

    // ---------- helpers ----------

    private static String currentEmail(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new AccessDeniedException("Not signed in");
        }
        return auth.getName();
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    /** Admin can act on any upload; a regular user can only act on their own. */
    private static void checkCanMutate(StudyUpload u, Authentication auth) {
        if (isAdmin(auth)) return;
        String me = currentEmail(auth);
        if (u.getUploadedByEmail() == null || !me.equalsIgnoreCase(u.getUploadedByEmail())) {
            throw new AccessDeniedException("You can only manage your own uploads.");
        }
    }

    private static String sanitize(String name) {
        String just = Paths.get(name).getFileName().toString();
        return just.replaceAll("[^A-Za-z0-9._\\- ]", "_");
    }
}
