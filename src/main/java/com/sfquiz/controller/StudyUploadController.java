package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.entity.StudyUpload;
import com.sfquiz.entity.User;
import com.sfquiz.repository.StudyUploadRepository;
import com.sfquiz.service.AuthorizationService;
import com.sfquiz.service.CostMeter;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.QuestionAdminService;
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
    private final QuestionAdminService questionAdmin;
    private final AuthorizationService authz;

    public StudyUploadController(StudyUploadRepository uploads,
                                 UploadProcessor processor,
                                 CostMeter costs,
                                 ExamService examService,
                                 QuestionAdminService questionAdmin,
                                 AuthorizationService authz) {
        this.uploads = uploads;
        this.processor = processor;
        this.costs = costs;
        this.examService = examService;
        this.questionAdmin = questionAdmin;
        this.authz = authz;
    }

    /** The exams the caller is allowed to upload / compose questions for.
     *  Domain admins are restricted to certs they govern; everyone else
     *  sees every active exam (regular users contribute material to any
     *  cert; super admin doesn't compose questions, but if they land
     *  here they see all). Empty result = no allowed certs. */
    private List<ExamDto> examsAllowedFor(Authentication auth) {
        User u = authz.currentUser(auth).orElse(null);
        // Domain admins only see their assignments.
        if (u != null && u.getRole() == com.sfquiz.entity.UserRole.ADMIN) {
            java.util.Set<String> managed = authz.managedExamSlugs(u);
            return examService.listActive().stream()
                    .filter(e -> managed.contains(e.slug()))
                    .toList();
        }
        return examService.listActive();
    }

    @GetMapping
    public String list(Authentication auth, Model model) {
        String email = currentEmail(auth);
        boolean admin = isAdmin(auth);
        // Default view hides archived rows so successfully-processed
        // uploads don't clutter the page once the user has confirmed
        // they're happy with what landed in the bank.
        List<StudyUpload> rows = admin
                ? uploads.findTop50ByArchivedFalseOrderByUploadedAtDesc()
                : uploads.findTop50ByUploadedByEmailIgnoreCaseAndArchivedFalseOrderByUploadedAtDesc(email);
        long archivedCount = admin
                ? uploads.findTop100ByArchivedTrueOrderByUploadedAtDesc().size()
                : uploads.findTop100ByUploadedByEmailIgnoreCaseAndArchivedTrueOrderByUploadedAtDesc(email).size();
        model.addAttribute("uploads", rows);
        model.addAttribute("archivedCount", archivedCount);
        model.addAttribute("isAdmin", admin);
        // Scope the cert dropdown to the caller's managed certs for domain
        // admins so they can't upload material against an exam they don't
        // own. Regular users + super admins see every active exam.
        model.addAttribute("exams", examsAllowedFor(auth));
        model.addAttribute("allowedExt", String.join(", ", TextExtractor.ALL_SUPPORTED));
        model.addAttribute("maxMb", MAX_BYTES / (1024 * 1024));
        model.addAttribute("budgetUsd", costs.dailyBudgetUsd());
        model.addAttribute("spentUsd", costs.spentToday());
        model.addAttribute("remainingUsd", costs.remainingToday());
        model.addAttribute("budgetExhausted", costs.budgetExhausted());
        return "uploads";
    }

    /** Manual question composition — form view. Lives at /uploads/manual
     *  so the existing top-nav "Add questions" link covers both file/paste
     *  upload AND the manual compose. Cert dropdown is scoped to the
     *  caller's allowed exams (same rule as the upload form). */
    @GetMapping("/manual")
    public String manualForm(Authentication auth, Model model) {
        model.addAttribute("exams", examsAllowedFor(auth));
        return "manual-question";
    }

    /** Manual question submit — creates a single PENDING question via
     *  QuestionAdminService.createManual. Choice rows arrive as parallel
     *  arrays {@code choiceText[]} + {@code correct[]} (the latter is the
     *  list of indexes ticked correct, matching the existing edit form).
     *  Validation errors come back via flash and the form re-renders. */
    @PostMapping("/manual")
    public String submitManual(Authentication auth,
                               @RequestParam("examSlug") String examSlug,
                               @RequestParam("type") String type,
                               @RequestParam("text") String text,
                               @RequestParam(name = "explanation", required = false) String explanation,
                               @RequestParam(name = "helpUrl", required = false) String helpUrl,
                               @RequestParam(name = "choiceText", required = false) List<String> choiceText,
                               @RequestParam(name = "correct", required = false) List<Integer> correctIdx,
                               RedirectAttributes flash) {
        // Defence in depth — domain admins can't compose a question on a
        // cert they don't manage even if they POST the slug directly.
        boolean allowed = examsAllowedFor(auth).stream().anyMatch(e -> e.slug().equals(examSlug));
        if (!allowed) {
            flash.addFlashAttribute("manualError",
                    "You're not authorised to add questions to that certification.");
            return "redirect:/uploads/manual";
        }
        try {
            QuestionAdminService.ManualCreateResult result = questionAdmin.createManual(
                    examSlug, type, text, explanation, helpUrl,
                    choiceText, correctIdx, currentEmail(auth));
            flash.addFlashAttribute("manualMessage",
                    "Question #" + result.number() + " for '" + result.examSlug()
                            + "' submitted for review. A domain admin will approve, edit, or reject it shortly.");
            return "redirect:/uploads/manual";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("manualError", ex.getMessage());
            return "redirect:/uploads/manual";
        }
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
        List<String> duplicateOfPrior = new ArrayList<>();
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
                    PersistResult r = persist(file, safe, email, examSlug);
                    log.info("saved upload id={} {} by={} exam={} ({} bytes) duplicateOf={}",
                            r.id, safe, email, examSlug, file.getSize(), r.duplicateOfUploadId);
                    processor.processAsync(r.id);
                    saved++;
                    if (r.duplicateOfUploadId != null) {
                        duplicateOfPrior.add(safe + " (re-upload of #" + r.duplicateOfUploadId + ")");
                    }
                } catch (IOException e) {
                    log.error("failed to save {}: {}", original, e.getMessage());
                    rejected.add(original + " (write failed: " + e.getMessage() + ")");
                }
            }
        }
        flash.addFlashAttribute("uploadedCount", saved);
        flash.addFlashAttribute("uploadedExam", examSlug);
        flash.addFlashAttribute("rejected", rejected);
        if (!duplicateOfPrior.isEmpty()) {
            flash.addFlashAttribute("duplicateUploads", duplicateOfPrior);
        }
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

    /** Result of persisting one upload — id of the new row and (if any) the
     *  id of the earlier upload it duplicates so the caller can flash a
     *  "re-upload of #N" notice without re-querying. */
    public record PersistResult(Long id, Long duplicateOfUploadId) {}

    @Transactional
    public PersistResult persist(MultipartFile file, String safeName, String uploaderEmail, String examSlug) throws IOException {
        byte[] bytes = file.getBytes();
        String hash = sha256(bytes);
        // Re-upload detection: same hash + same target cert → flag this row
        // so the UI shows a "duplicate of #N" badge and the per-question
        // override-on-approve path can retire stale approved versions.
        Long priorUploadId = uploads
                .findFirstByContentHashAndExamSlugOrderByUploadedAtDesc(hash, examSlug)
                .map(StudyUpload::getId)
                .orElse(null);

        StudyUpload u = new StudyUpload();
        u.setOriginalName(safeName);
        u.setContentType(file.getContentType());
        u.setSizeBytes(file.getSize());
        u.setContent(bytes);
        u.setContentHash(hash);
        u.setDuplicateOfUploadId(priorUploadId);
        u.setUploadedAt(Instant.now());
        u.setUploadedByEmail(uploaderEmail);
        u.setExamSlug(examSlug);
        u.setStatus(StudyUpload.Status.PENDING);
        Long savedId = uploads.save(u).getId();
        return new PersistResult(savedId, priorUploadId);
    }

    /** SHA-256 of file bytes, hex-encoded — used as a stable file identity
     *  for duplicate-detection. We don't need cryptographic strength, but
     *  SHA-256 is already on the JVM classpath and avoids collision
     *  surprises that MD5 / CRC32 would risk on a growing corpus. */
    private static String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available in every JRE since 1.4; this
            // branch is unreachable but the API forces us to handle it.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    /** Archive a successfully-processed upload — flips the archived flag
     *  so the row drops out of the main /uploads view but stays in the
     *  database (byte content + question-action audit trail intact).
     *  Retrievable from the dedicated /uploads/archived view. */
    @PostMapping("/{id}/archive")
    public String archive(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        StudyUpload u = uploads.findById(id).orElse(null);
        if (u == null) return "redirect:/uploads";
        checkCanMutate(u, auth);
        u.setArchived(true);
        uploads.save(u);
        log.info("archived upload id={} name={} by={}", u.getId(), u.getOriginalName(), currentEmail(auth));
        flash.addFlashAttribute("archivedId", id);
        return "redirect:/uploads";
    }

    /** Restore an archived upload back into the main view. */
    @PostMapping("/{id}/unarchive")
    public String unarchive(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        StudyUpload u = uploads.findById(id).orElse(null);
        if (u == null) return "redirect:/uploads/archived";
        checkCanMutate(u, auth);
        u.setArchived(false);
        uploads.save(u);
        flash.addFlashAttribute("unarchivedId", id);
        return "redirect:/uploads/archived";
    }

    /** Dedicated archived-uploads view — same chrome as /uploads but
     *  lists only archived rows, with an Unarchive action so admins
     *  can pull anything back to the main page if they need to. */
    @GetMapping("/archived")
    public String archivedList(Authentication auth, Model model) {
        String email = currentEmail(auth);
        boolean admin = isAdmin(auth);
        List<StudyUpload> rows = admin
                ? uploads.findTop100ByArchivedTrueOrderByUploadedAtDesc()
                : uploads.findTop100ByUploadedByEmailIgnoreCaseAndArchivedTrueOrderByUploadedAtDesc(email);
        model.addAttribute("uploads", rows);
        model.addAttribute("isAdmin", admin);
        return "uploads-archived";
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

    /** JSON snapshot of the current user's uploads — polled by uploads.html so
     *  the status pill (Pending → Extracting… → Done / Failed / Skipped) updates
     *  in place without a full page reload. Archived rows are filtered out. */
    @GetMapping(value = "/api/status", produces = "application/json")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> apiStatus(Authentication auth) {
        String email = currentEmail(auth);
        boolean admin = isAdmin(auth);
        List<StudyUpload> rows = admin
                ? uploads.findTop50ByArchivedFalseOrderByUploadedAtDesc()
                : uploads.findTop50ByUploadedByEmailIgnoreCaseAndArchivedFalseOrderByUploadedAtDesc(email);
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
            r.put("dumpSuspected", u.isDumpSuspected());
            r.put("duplicateOfUploadId", u.getDuplicateOfUploadId());
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
