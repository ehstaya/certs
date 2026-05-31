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
        java.util.List<Long> ids = parseQuestionIds(attempt.getQuestionIds());
        if (ids.isEmpty()) {
            // Pre-feature attempts (or any row with an empty snapshot) can't
            // be retaken. 410 GONE signals "this resource existed but the
            // data needed for retake is gone" — the UI hides the link for
            // these so callers shouldn't see it in practice.
            return ResponseEntity.status(410).build();
        }
        return ResponseEntity.ok(new com.sfquiz.dto.ExamQuestionsResponse(
                exams.toDto(attempt.getExam()),
                service.listForRetake(ids)));
    }

    /** Parse the comma-separated id snapshot off TestAttempt.questionIds. */
    private static java.util.List<Long> parseQuestionIds(String csv) {
        if (csv == null || csv.isBlank()) return java.util.List.of();
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try { out.add(Long.parseLong(t)); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return out;
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
            java.util.List<AttemptAnswerRequest> answers
    ) {}

    /** Per-question answer snapshot from the client. {@code correct} is
     *  re-validated server-side in TestAttemptService.record(). */
    public record AttemptAnswerRequest(
            Long questionId,
            java.util.List<Long> selectedChoiceIds,
            boolean correct
    ) {}
}
