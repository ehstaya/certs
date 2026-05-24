package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.entity.Question;
import com.sfquiz.entity.UserRole;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.repository.QuestionVoteRepository;
import com.sfquiz.repository.TestAttemptRepository;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.VoteService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Admin reports section. The landing page is a Dashboard; individual report
 *  views live underneath. The left-side tree in each report template lets the
 *  admin jump between them — drop a new view here + a new tree item to extend. */
@Controller
@RequestMapping("/admin/reports")
public class ReportsController {

    private final VoteService voteService;
    private final ExamService examService;
    private final QuestionRepository questions;
    private final QuestionVoteRepository votes;
    private final TestAttemptRepository attempts;

    public ReportsController(VoteService voteService,
                             ExamService examService,
                             QuestionRepository questions,
                             QuestionVoteRepository votes,
                             TestAttemptRepository attempts) {
        this.voteService = voteService;
        this.examService = examService;
        this.questions = questions;
        this.votes = votes;
        this.attempts = attempts;
    }

    public record DashboardStats(
            long activeExams,
            long approvedQuestions,
            long pendingQuestions,
            long retiredQuestions,
            long totalVotes,
            long thumbsUp,
            long thumbsDown,
            int overallPercentUp
    ) {}

    /** Default landing for the reports section — high-level dashboard with
     *  cross-platform stats + quick links into the detail reports. */
    @GetMapping
    public String dashboard(Model model) {
        List<ExamDto> exams = examService.listActive();
        long active = exams.size();
        long approved = questions.countByStatus(Question.Status.APPROVED);
        long pending  = questions.countByStatus(Question.Status.PENDING);
        long retired  = questions.countByStatus(Question.Status.RETIRED);
        long totalVotes = votes.count();

        // Aggregate vote totals across the whole bank.
        long up = 0, down = 0;
        List<VoteService.ExamQualityReport> perExam = new ArrayList<>();
        for (ExamDto e : exams) {
            VoteService.ExamQualityReport r = voteService.buildReport(e.slug());
            perExam.add(r);
            up += r.up();
            down += r.down();
        }
        int pct = (up + down) > 0 ? (int) Math.round(100.0 * up / (up + down)) : 0;
        perExam.sort((a, b) -> Integer.compare(b.approxPercentUp(), a.approxPercentUp()));

        model.addAttribute("stats", new DashboardStats(
                active, approved, pending, retired, totalVotes, up, down, pct));
        model.addAttribute("perExam", perExam);
        model.addAttribute("exams", exams);
        model.addAttribute("section", "dashboard");
        return "reports-dashboard";
    }

    /** Summary record for the verifier-feedback report header: totals after
     *  filters are applied so the admin can see the up/down split at a glance. */
    public record FeedbackTotals(long up, long down, long rows, int distinctVoters) {
        public long total() { return up + down; }
        public int percentUp() {
            return total() == 0 ? 0 : (int) Math.round(100.0 * up / total());
        }
    }

    /** Verifier-feedback report. Lists every vote with a reason; admin can
     *  retire a question or send it back to the review queue from each row.
     *  Supports filtering by exam, voter (verifier), and reason text. */
    @GetMapping("/verifier-feedback")
    public String verifierFeedback(@RequestParam(name = "exam", required = false) String exam,
                                   @RequestParam(name = "voter", required = false) String voter,
                                   @RequestParam(name = "reason", required = false) String reason,
                                   Model model) {
        List<ExamDto> exams = examService.listActive();
        List<VoteService.VerifierFeedbackEntry> rows = voteService.verifierFeedback(exam);

        // Dropdown sources are built from ALL reasoned votes (regardless of
        // the current filters) so admins can still pivot to a different voter
        // or reason after applying an exam filter. Counts on the voter
        // dropdown also stay stable for the same reason.
        List<VoteService.FeedbackVoter> voters = voteService.feedbackVoters();
        List<String> reasons = voteService.feedbackReasons();

        final String voterFilter  = (voter == null  || voter.isBlank())  ? null : voter.trim();
        final String reasonFilter = (reason == null || reason.isBlank()) ? null : reason.trim();
        if (voterFilter != null) {
            rows = rows.stream()
                    .filter(r -> voterFilter.equalsIgnoreCase(r.voterEmail()))
                    .toList();
        }
        if (reasonFilter != null) {
            rows = rows.stream()
                    .filter(r -> r.reason() != null && reasonFilter.equalsIgnoreCase(r.reason().trim()))
                    .toList();
        }

        long up = 0, down = 0;
        java.util.Set<String> distinctVoterEmails = new java.util.HashSet<>();
        for (VoteService.VerifierFeedbackEntry r : rows) {
            if (r.voteValue() > 0) up++;
            else if (r.voteValue() < 0) down++;
            if (r.voterEmail() != null) distinctVoterEmails.add(r.voterEmail());
        }

        model.addAttribute("exams", exams);
        model.addAttribute("exam", exam == null ? "" : exam);
        model.addAttribute("voters", voters);
        model.addAttribute("voter", voterFilter == null ? "" : voterFilter);
        model.addAttribute("reasons", reasons);
        model.addAttribute("reason", reasonFilter == null ? "" : reasonFilter);
        model.addAttribute("rows", rows);
        model.addAttribute("totals", new FeedbackTotals(up, down, rows.size(), distinctVoterEmails.size()));
        model.addAttribute("section", "verifier-feedback");
        return "verifier-feedback-report";
    }

    /** One row per (user, exam) — drives the All-user scores admin report. */
    public record UserExamScore(
            Long userId,
            String email,
            String fullName,
            UserRole role,
            String examSlug,
            String examName,
            int passingScorePercent,
            long attempts,
            int avgScorePercent,
            int bestScorePercent,
            long passCount,
            Instant lastAttemptAt
    ) {
        public boolean everPassed() { return passCount > 0; }
        public int passRatePercent() {
            return attempts == 0 ? 0 : (int) Math.round(100.0 * passCount / attempts);
        }
    }

    /** Scores across every user — admin view. Surfaces test-attempt aggregates
     *  per (user, exam) so the admin can see how each verifier, user, and
     *  fellow admin is performing. Optional role + exam filters. */
    @GetMapping("/scores")
    public String userScores(@RequestParam(name = "role", required = false) String role,
                             @RequestParam(name = "exam", required = false) String exam,
                             Model model) {
        List<ExamDto> exams = examService.listActive();

        UserRole roleFilter = null;
        if (role != null && !role.isBlank()) {
            try { roleFilter = UserRole.valueOf(role.trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) { /* leave null = "all roles" */ }
        }
        String examFilter = (exam == null || exam.isBlank()) ? null : exam.trim();

        List<UserExamScore> rows = new ArrayList<>();
        for (Object[] r : attempts.allUserExamSummaries()) {
            UserRole rowRole = (UserRole) r[3];
            String slug = (String) r[5];
            if (roleFilter != null && rowRole != roleFilter) continue;
            if (examFilter != null && !examFilter.equals(slug)) continue;

            long attemptCount = ((Number) r[8]).longValue();
            int avgScore = r[9] == null ? 0 : (int) Math.round(((Number) r[9]).doubleValue());
            int bestScore = r[10] == null ? 0 : ((Number) r[10]).intValue();
            long passCount = r[11] == null ? 0L : ((Number) r[11]).longValue();
            Instant lastAt = (Instant) r[12];

            rows.add(new UserExamScore(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    (String) r[2],
                    rowRole,
                    slug,
                    (String) r[6],
                    r[7] == null ? 0 : ((Number) r[7]).intValue(),
                    attemptCount, avgScore, bestScore, passCount, lastAt));
        }
        // Default sort: by email then by exam — same as the SQL order, but the
        // role/exam filter passes may have skipped rows so we re-stabilise here.
        rows.sort(Comparator
                .comparing((UserExamScore u) -> u.email() == null ? "" : u.email().toLowerCase())
                .thenComparing(UserExamScore::examSlug));

        model.addAttribute("rows", rows);
        model.addAttribute("exams", exams);
        model.addAttribute("exam", examFilter == null ? "" : examFilter);
        model.addAttribute("role", roleFilter == null ? "" : roleFilter.name());
        model.addAttribute("section", "scores");
        return "all-user-scores-report";
    }

    /** Per-question quality (thumbs up/down) report + cross-exam leaderboard. */
    @GetMapping("/quality")
    public String quality(@RequestParam(name = "exam", required = false) String exam, Model model) {
        List<ExamDto> exams = examService.listActive();
        if (exam == null || exam.isBlank()) {
            exam = exams.isEmpty() ? null : exams.get(0).slug();
        }
        model.addAttribute("exams", exams);
        model.addAttribute("exam", exam);
        if (exam != null) {
            model.addAttribute("report", voteService.buildReport(exam));
        }
        List<VoteService.ExamQualityReport> all = new ArrayList<>();
        for (ExamDto e : exams) {
            all.add(voteService.buildReport(e.slug()));
        }
        all.sort((a, b) -> Integer.compare(b.approxPercentUp(), a.approxPercentUp()));
        model.addAttribute("leaderboard", all);
        model.addAttribute("section", "quality");
        return "quality-report";
    }
}
