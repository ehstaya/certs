package com.sfquiz.controller;

import com.sfquiz.dto.SubmitRequest;
import com.sfquiz.dto.SubmitResponse;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.QuizService;
import com.sfquiz.service.TestAttemptService;
import com.sfquiz.service.VoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class QuizController {

    private final QuizService service;
    private final VoteService votes;
    private final TestAttemptService attempts;
    private final ExamService exams;

    public QuizController(QuizService service, VoteService votes,
                          TestAttemptService attempts, ExamService exams) {
        this.service = service;
        this.votes = votes;
        this.attempts = attempts;
        this.exams = exams;
    }

    @PostMapping("/questions/{id}/submit")
    public ResponseEntity<SubmitResponse> submit(@PathVariable Long id, @RequestBody SubmitRequest req) {
        return ResponseEntity.ok(service.submit(id, req));
    }

    /** Read current vote stats for a question (no auth-side info if anonymous). */
    @GetMapping("/questions/{id}/vote")
    public ResponseEntity<VoteService.VoteStats> voteStats(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(votes.statsFor(id, auth == null ? null : auth.getName()));
    }

    /** Cast (or toggle) a thumbs-up/-down vote on a question. Verifiers must
     *  supply a non-blank reason — the service throws IllegalArgumentException
     *  if they don't, which Spring's default handler turns into a 400. */
    @PostMapping("/questions/{id}/vote")
    public ResponseEntity<?> vote(@PathVariable Long id,
                                  @RequestBody VoteRequest req,
                                  Authentication auth) {
        if (auth == null || auth.getName() == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            return ResponseEntity.ok(votes.vote(id, auth.getName(), req.value(), req.reason()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        }
    }

    public record VoteRequest(int value, String reason) {}

    /** Retake-same-test: return the exam metadata + the EXACT set of
     *  questions served on a previous attempt, in the original order. The
     *  attempt must belong to the signed-in user (admins can replay any
     *  attempt for support purposes). Used by /index.html?retake=<id>
     *  so a user can practise the same 60 questions repeatedly.
     *
     *  Response shape matches /api/exams/{slug}/questions so the frontend
     *  init() code can use the same parsing. */
    @GetMapping("/test-attempts/{id}/retake-questions")
    public ResponseEntity<com.sfquiz.dto.ExamQuestionsResponse> retakeQuestions(
            @PathVariable Long id, Authentication auth) {
        if (auth == null || auth.getName() == null) return ResponseEntity.status(401).build();
        com.sfquiz.entity.TestAttempt attempt = attempts.findOwnedById(id, auth.getName());
        if (attempt == null) return ResponseEntity.status(404).build();
        // resolveRetakeQuestionIds tries the snapshot first and falls back
        // to the per-answer rows for legacy attempts — so every historical
        // attempt with at least one submitted answer is retakeable.
        java.util.List<Long> ids = attempts.resolveRetakeQuestionIds(attempt);
        if (ids.isEmpty()) {
            // Genuinely nothing recorded — pre-feature attempt with zero
            // submitted answers. 410 GONE; the client redirects to a
            // fresh sample on the same exam.
            return ResponseEntity.status(410).build();
        }
        return ResponseEntity.ok(new com.sfquiz.dto.ExamQuestionsResponse(
                exams.toDto(attempt.getExam()),
                service.listForRetake(ids)));
    }

    /** Record a completed test attempt for the signed-in user. Called from
     *  quiz.js after the user clicks Finish (or the timer auto-expires and
     *  they confirm). Drives the "My reports" dashboard. */
    @PostMapping("/test-attempts")
    public ResponseEntity<Void> recordAttempt(@RequestBody AttemptRequest req, Authentication auth) {
        if (auth == null || auth.getName() == null) {
            return ResponseEntity.status(401).build();
        }
        Instant started  = Instant.parse(req.startedAt());
        Instant finished = Instant.parse(req.finishedAt());
        java.util.List<TestAttemptService.AnswerDetail> answers = new java.util.ArrayList<>();
        if (req.answers() != null) {
            for (AttemptAnswerRequest a : req.answers()) {
                if (a == null || a.questionId() == null) continue;
                answers.add(new TestAttemptService.AnswerDetail(
                        a.questionId(),
                        a.selectedChoiceIds() == null ? java.util.List.of() : a.selectedChoiceIds(),
                        a.correct()));
            }
        }
        attempts.record(auth.getName(), new TestAttemptService.RecordRequest(
                req.examSlug(), started, finished,
                req.totalQuestions(), req.correctCount(),
                req.incorrectCount(), req.unansweredCount(),
                req.questionIds() == null ? java.util.List.of() : req.questionIds(),
                req.mode(),
                answers));
        return ResponseEntity.noContent().build();
    }

    public record AttemptRequest(
            String examSlug,
            String startedAt,
            String finishedAt,
            int totalQuestions,
            int correctCount,
            int incorrectCount,
            int unansweredCount,
            java.util.List<Long> questionIds,
            String mode,
            java.util.List<AttemptAnswerRequest> answers
    ) {}

    /** Per-question answer snapshot from the client. {@code correct} is
     *  re-validated server-side in TestAttemptService.record(). */
    public record AttemptAnswerRequest(
            Long questionId,
            java.util.List<Long> selectedChoiceIds,
            boolean correct
    ) {}

    /** Save a practice test in progress so the user can resume later.
     *  Optional {@code id} body field — if present we update that SAVED
     *  row (keeping its sequence number + display name); if absent we
     *  create a new one. Returns the persisted attempt so the client
     *  can stash the assigned id for subsequent saves. */
    @PostMapping("/test-attempts/save")
    public ResponseEntity<SaveAttemptResponse> saveAttempt(@RequestBody SaveAttemptRequest req,
                                                           Authentication auth) {
        if (auth == null || auth.getName() == null) return ResponseEntity.status(401).build();
        java.time.Instant started = req.startedAt() == null ? null : Instant.parse(req.startedAt());
        TestAttemptService.SaveRequest svc = new TestAttemptService.SaveRequest(
                req.examSlug(), started, req.totalQuestions(),
                req.questionIds() == null ? java.util.List.of() : req.questionIds(),
                req.mode(), req.savedStateJson());
        com.sfquiz.entity.TestAttempt saved = attempts.saveAttempt(auth.getName(), req.id(), svc);
        return ResponseEntity.ok(new SaveAttemptResponse(
                saved.getId(),
                saved.getDisplayName() == null ? "" : saved.getDisplayName(),
                saved.getSequenceNumber() == null ? 0 : saved.getSequenceNumber()));
    }

    /** Finalize a SAVED test — converts it to FINISHED with the actual
     *  score + per-question answer detail. Mirrors {@link #recordAttempt}
     *  but updates the existing row rather than inserting a new one. */
    @PostMapping("/test-attempts/{id}/finish")
    public ResponseEntity<Void> finishSavedAttempt(@PathVariable Long id,
                                                   @RequestBody AttemptRequest req,
                                                   Authentication auth) {
        if (auth == null || auth.getName() == null) return ResponseEntity.status(401).build();
        Instant started  = req.startedAt() == null ? null : Instant.parse(req.startedAt());
        Instant finished = req.finishedAt() == null ? null : Instant.parse(req.finishedAt());
        java.util.List<TestAttemptService.AnswerDetail> answers = new java.util.ArrayList<>();
        if (req.answers() != null) {
            for (AttemptAnswerRequest a : req.answers()) {
                if (a == null || a.questionId() == null) continue;
                answers.add(new TestAttemptService.AnswerDetail(
                        a.questionId(),
                        a.selectedChoiceIds() == null ? java.util.List.of() : a.selectedChoiceIds(),
                        a.correct()));
            }
        }
        attempts.finishSavedAttempt(auth.getName(), id, new TestAttemptService.RecordRequest(
                req.examSlug(), started, finished,
                req.totalQuestions(), req.correctCount(),
                req.incorrectCount(), req.unansweredCount(),
                req.questionIds() == null ? java.util.List.of() : req.questionIds(),
                req.mode(),
                answers));
        return ResponseEntity.noContent().build();
    }

    /** Load a saved test's snapshot for client-side resume. Returns the
     *  examSlug, questionIds, opaque savedStateJson the client wrote on
     *  the last save, and the assigned displayName. */
    @GetMapping("/test-attempts/{id}/saved-state")
    public ResponseEntity<SavedStateResponse> savedState(@PathVariable Long id, Authentication auth) {
        if (auth == null || auth.getName() == null) return ResponseEntity.status(401).build();
        com.sfquiz.entity.TestAttempt a;
        try {
            a = attempts.loadSavedForResume(auth.getName(), id);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(410).build();   // attempt is finished
        } catch (org.springframework.security.access.AccessDeniedException ex) {
            return ResponseEntity.status(403).build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(new SavedStateResponse(
                a.getId(),
                a.getExam() == null ? "" : a.getExam().getSlug(),
                a.getDisplayName() == null ? "" : a.getDisplayName(),
                a.getSavedStateJson(),
                a.getQuestionIds()));
    }

    /** Delete a SAVED attempt — for the Saved-tests list's Delete button. */
    @PostMapping("/test-attempts/{id}/delete-saved")
    public ResponseEntity<Void> deleteSaved(@PathVariable Long id, Authentication auth) {
        if (auth == null || auth.getName() == null) return ResponseEntity.status(401).build();
        attempts.deleteSavedAttempt(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }

    public record SaveAttemptRequest(
            Long id,                     // null on first save, set on subsequent saves
            String examSlug,
            String startedAt,
            int totalQuestions,
            java.util.List<Long> questionIds,
            String mode,
            String savedStateJson
    ) {}
    public record SaveAttemptResponse(Long id, String displayName, int sequenceNumber) {}
    public record SavedStateResponse(
            Long id, String examSlug, String displayName,
            String savedStateJson, String questionIds
    ) {}
}
