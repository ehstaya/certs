package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.entity.Question;
import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.repository.QuestionVoteRepository;
import com.sfquiz.repository.TestAttemptRepository;
import com.sfquiz.service.AuthorizationService;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.VoteService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Set;

import com.sfquiz.entity.TestAttempt;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final AuthorizationService authz;

    public ReportsController(VoteService voteService,
                             ExamService examService,
                             QuestionRepository questions,
                             QuestionVoteRepository votes,
                             TestAttemptRepository attempts,
                             AuthorizationService authz) {
        this.voteService = voteService;
        this.examService = examService;
        this.questions = questions;
        this.votes = votes;
        this.attempts = attempts;
        this.authz = authz;
    }

    /** Returns the exams the calling user is allowed to see in the reports
     *  section. SUPERADMIN sees every active exam; domain admins see only
     *  the certs they govern. Empty result means "no access" — the caller
     *  will see empty cards / empty tables, never another user's data. */
    private List<ExamDto> examsVisibleTo(Authentication auth) {
        User u = authz.currentUser(auth).orElse(null);
        Set<String> managed = authz.managedExamSlugs(u);
        List<ExamDto> all = examService.listActive();
        if (AuthorizationService.managesAllExams(managed)) return all;
        return all.stream().filter(e -> managed.contains(e.slug())).toList();
    }

    /** True if the caller is a domain admin (ADMIN role, not SUPERADMIN).
     *  Used to swap the report title/intro for scoped views. */
    private boolean isDomainAdmin(Authentication auth) {
        return authz.currentUser(auth)
                .map(u -> u.getRole() == UserRole.ADMIN)
                .orElse(false);
    }

    /** Count questions with a given status, scoped to a slug set. Empty
     *  slug set means "no access" → 0. Used by the dashboard cards. */
    private long countByStatusScoped(Question.Status status, Set<String> slugs) {
        if (slugs == null || slugs.isEmpty()) return 0;
        return questions.findByStatusOrderByNumber(status).stream()
                .filter(q -> q.getExam() != null && slugs.contains(q.getExam().getSlug()))
                .count();
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
     *  cross-platform stats + quick links into the detail reports.
     *  Domain admins see the same shape of dashboard scoped to the cert(s)
     *  they govern; super admins see every active exam. */
    @GetMapping
    public String dashboard(Authentication auth, Model model) {
        List<ExamDto> exams = examsVisibleTo(auth);
        // Question counts are by-status, scoped to the caller's exam slugs
        // for domain admins so the "Pending review" card never surfaces
        // questions on someone else's cert.
        java.util.Set<String> slugSet = exams.stream().map(ExamDto::slug)
                .collect(java.util.stream.Collectors.toSet());
        long active = exams.size();
        long approved = countByStatusScoped(Question.Status.APPROVED, slugSet);
        long pending  = countByStatusScoped(Question.Status.PENDING,  slugSet);
        long retired  = countByStatusScoped(Question.Status.RETIRED,  slugSet);

        // Per-exam vote totals. For domain admins we only sum their certs.
        long up = 0, down = 0, totalVotes = 0;
        List<VoteService.ExamQualityReport> perExam = new ArrayList<>();
        for (ExamDto e : exams) {
            VoteService.ExamQualityReport r = voteService.buildReport(e.slug());
            perExam.add(r);
            up += r.up();
            down += r.down();
            totalVotes += r.totalVotes();
        }
        int pct = (up + down) > 0 ? (int) Math.round(100.0 * up / (up + down)) : 0;
        perExam.sort((a, b) -> Integer.compare(b.approxPercentUp(), a.approxPercentUp()));

        model.addAttribute("scopedToCerts", isDomainAdmin(auth));

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
     *  Supports filtering by exam, voter (verifier), and reason text.
     *  Domain admins see only their cert(s); super admins see everything. */
    @GetMapping("/verifier-feedback")
    public String verifierFeedback(@RequestParam(name = "exam", required = false) String exam,
                                   @RequestParam(name = "voter", required = false) String voter,
                                   @RequestParam(name = "reason", required = false) String reason,
                                   Authentication auth,
                                   Model model) {
        List<ExamDto> exams = examsVisibleTo(auth);
        java.util.Set<String> visible = exams.stream().map(ExamDto::slug)
                .collect(java.util.stream.Collectors.toSet());
        // If the caller pinned a specific exam that isn't in their visible
        // set, drop the filter — they shouldn't see the data either way.
        if (exam != null && !exam.isBlank() && !visible.contains(exam)) {
            exam = null;
        }
        List<VoteService.VerifierFeedbackEntry> rows = voteService.verifierFeedback(exam);
        // For domain admins, further filter rows to their cert set even when
        // no exam filter is supplied (they shouldn't see other certs' rows).
        if (isDomainAdmin(auth)) {
            rows = rows.stream()
                    .filter(r -> r.examSlug() != null && visible.contains(r.examSlug()))
                    .toList();
        }

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
                             Authentication auth,
                             Model model) {
        List<ExamDto> exams = examsVisibleTo(auth);
        java.util.Set<String> visibleSlugs = exams.stream().map(ExamDto::slug)
                .collect(java.util.stream.Collectors.toSet());

        UserRole roleFilter = null;
        if (role != null && !role.isBlank()) {
            try { roleFilter = UserRole.valueOf(role.trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) { /* leave null = "all roles" */ }
        }
        String examFilter = (exam == null || exam.isBlank()) ? null : exam.trim();
        // If the URL pins a slug the caller can't see, drop the filter — we
        // still let them browse their visible set below.
        if (examFilter != null && !visibleSlugs.contains(examFilter)) examFilter = null;

        List<UserExamScore> rows = new ArrayList<>();
        for (Object[] r : attempts.allUserExamSummaries()) {
            UserRole rowRole = (UserRole) r[3];
            String slug = (String) r[5];
            if (!visibleSlugs.contains(slug)) continue;   // domain-admin scope
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

    /** One weekly bucket on the cert-trend chart. */
    public record CertTrendWeek(
            LocalDate weekStart,   // Monday of the bucket
            int attempts,          // count in the bucket
            int avgScorePercent,
            int passRatePercent,
            int distinctUsers
    ) {}

    /** Per-cert score trend across the whole user base — drives the admin
     *  "is this exam improving or declining?" view. Aggregates all attempts
     *  for one exam into weekly buckets so admins can spot training needs. */
    @GetMapping("/cert-trend")
    public String certTrend(@RequestParam(name = "exam", required = false) String exam,
                            Authentication auth,
                            Model model) {
        List<ExamDto> exams = examsVisibleTo(auth);
        // Default to the caller's first visible exam if none supplied (or the
        // slug is bogus / out of scope for a domain admin).
        final String requested = exam;
        String resolved = exam;
        if (requested == null || requested.isBlank() || exams.stream().noneMatch(e -> e.slug().equals(requested))) {
            resolved = exams.isEmpty() ? null : exams.get(0).slug();
        }
        exam = resolved;
        final String slug = resolved;
        ExamDto examMeta = exams.stream().filter(e -> e.slug().equals(slug)).findFirst().orElse(null);
        int passingPct = examMeta != null ? examMeta.passingScorePercent() : 65;

        List<TestAttempt> chronological = (slug == null)
                ? List.of()
                : attempts.findByExamSlugChronological(slug);

        // Bucket by ISO week (Monday → Sunday). Map preserves insertion order
        // so the first week with any data is the leftmost bucket on the chart.
        record Bucket(int count, double scoreSum, int passes, java.util.Set<Long> userIds) {
            Bucket add(int score, boolean passed, Long userId) {
                java.util.Set<Long> u = new java.util.HashSet<>(userIds);
                if (userId != null) u.add(userId);
                return new Bucket(count + 1, scoreSum + score, passes + (passed ? 1 : 0), u);
            }
        }
        LinkedHashMap<LocalDate, Bucket> buckets = new LinkedHashMap<>();
        for (TestAttempt a : chronological) {
            LocalDate week = a.getFinishedAt()
                    .atZone(ZoneOffset.UTC).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            Long uid = a.getUser() == null ? null : a.getUser().getId();
            buckets.merge(week, new Bucket(1, a.getScorePercent(), a.isPassed() ? 1 : 0,
                            uid == null ? java.util.Set.of() : java.util.Set.of(uid)),
                    (oldB, newB) -> oldB.add(a.getScorePercent(), a.isPassed(), uid));
        }

        List<CertTrendWeek> weeks = new ArrayList<>();
        for (Map.Entry<LocalDate, Bucket> e : buckets.entrySet()) {
            Bucket b = e.getValue();
            int avg = (int) Math.round(b.scoreSum() / Math.max(1, b.count()));
            int pass = (int) Math.round(100.0 * b.passes() / Math.max(1, b.count()));
            weeks.add(new CertTrendWeek(e.getKey(), b.count(), avg, pass, b.userIds().size()));
        }

        // Chart geometry — line-first, identical look-and-feel to the
        // user trend so admins don't have to re-learn anything.
        int n = weeks.size();
        int slots = Math.max(5, ((n + 4) / 5) * 5);
        int chartW = 720, chartH = 240, padL = 44, padR = 18, padT = 18, padB = 44;
        StringBuilder points = new StringBuilder();
        List<int[]> markers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CertTrendWeek w = weeks.get(i);
            int x = padL + (int) Math.round((double) (chartW - padL - padR) * i / Math.max(1, slots - 1));
            int y = padT + (int) Math.round((chartH - padT - padB) * (1.0 - w.avgScorePercent() / 100.0));
            if (i > 0) points.append(' ');
            points.append(x).append(',').append(y);
            markers.add(new int[]{x, y, w.avgScorePercent(),
                    w.avgScorePercent() >= passingPct ? 1 : 0, w.attempts(), w.passRatePercent(), w.distinctUsers()});
        }
        List<int[]> xTicks = new ArrayList<>();
        List<String> xLabels = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            int x = padL + (int) Math.round((double) (chartW - padL - padR) * i / Math.max(1, slots - 1));
            xTicks.add(new int[]{x, i + 1});
            xLabels.add(i < n ? weeks.get(i).weekStart().toString() : "");
        }
        int passY = padT + (int) Math.round((chartH - padT - padB) * (1.0 - passingPct / 100.0));

        // Direction call: did the last week's avg improve vs. the first?
        // Coarse but admins want a one-glance signal.
        String direction = "flat";
        int deltaPct = 0;
        if (n >= 2) {
            int first = weeks.get(0).avgScorePercent();
            int last = weeks.get(n - 1).avgScorePercent();
            deltaPct = last - first;
            if (deltaPct >= 3)        direction = "improving";
            else if (deltaPct <= -3)  direction = "declining";
        }
        int totalAttempts = weeks.stream().mapToInt(CertTrendWeek::attempts).sum();
        int overallAvg = chronological.isEmpty() ? 0
                : (int) Math.round(chronological.stream().mapToInt(TestAttempt::getScorePercent).average().orElse(0));
        int overallPassRate = chronological.isEmpty() ? 0
                : (int) Math.round(100.0 * chronological.stream().mapToInt(a -> a.isPassed() ? 1 : 0).sum() / chronological.size());

        model.addAttribute("exams", exams);
        model.addAttribute("exam", exam == null ? "" : exam);
        model.addAttribute("examName", examMeta == null ? "—" : examMeta.name());
        model.addAttribute("passingPct", passingPct);
        model.addAttribute("weeks", weeks);
        model.addAttribute("totalAttempts", totalAttempts);
        model.addAttribute("overallAvg", overallAvg);
        model.addAttribute("overallPassRate", overallPassRate);
        model.addAttribute("direction", direction);
        model.addAttribute("deltaPct", deltaPct);
        model.addAttribute("chartW", chartW);
        model.addAttribute("chartH", chartH);
        model.addAttribute("padL", padL);
        model.addAttribute("padR", padR);
        model.addAttribute("padT", padT);
        model.addAttribute("padB", padB);
        model.addAttribute("polyPoints", points.toString());
        model.addAttribute("markers", markers);
        model.addAttribute("xTicks", xTicks);
        model.addAttribute("xLabels", xLabels);
        model.addAttribute("slots", slots);
        model.addAttribute("passY", passY);
        model.addAttribute("section", "cert-trend");
        return "cert-trend-report";
    }

    /** Per-question quality (thumbs up/down) report + cross-exam leaderboard.
     *  For domain admins both the per-exam dropdown and the leaderboard are
     *  scoped to the cert(s) they govern. */
    @GetMapping("/quality")
    public String quality(@RequestParam(name = "exam", required = false) String exam,
                          Authentication auth,
                          Model model) {
        List<ExamDto> exams = examsVisibleTo(auth);
        java.util.Set<String> visible = exams.stream().map(ExamDto::slug)
                .collect(java.util.stream.Collectors.toSet());
        // If the URL pins an out-of-scope slug, fall back to the caller's
        // first visible exam instead of revealing data they shouldn't see.
        if (exam == null || exam.isBlank() || !visible.contains(exam)) {
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
