package com.sfquiz.controller;

import com.sfquiz.dto.ExamDto;
import com.sfquiz.entity.TestAttempt;
import com.sfquiz.service.ExamService;
import com.sfquiz.service.TestAttemptService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

/** Personal-reports section for the signed-in user — a parallel of the admin
 *  reports section. Each report renders inside the same left-tree layout. */
@Controller
@RequestMapping("/my/reports")
public class UserReportsController {

    private final TestAttemptService attempts;
    private final ExamService examService;

    public UserReportsController(TestAttemptService attempts, ExamService examService) {
        this.attempts = attempts;
        this.examService = examService;
    }

    private static String currentEmail(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new AccessDeniedException("Not signed in");
        return auth.getName();
    }

    /** Dashboard landing — per-exam summary + recent attempts. */
    @GetMapping
    public String dashboard(Authentication auth, Model model) {
        String email = currentEmail(auth);
        List<TestAttemptService.ExamSummary> summary = attempts.summaryByExamForUser(email);
        List<TestAttempt> recent = attempts.listForUser(email);
        if (recent.size() > 10) recent = recent.subList(0, 10);

        // Totals across all attempts.
        long totalAttempts = summary.stream().mapToLong(TestAttemptService.ExamSummary::attempts).sum();
        long totalPassed = summary.stream().mapToLong(TestAttemptService.ExamSummary::passed).sum();
        int avgScore = (int) summary.stream()
                .filter(s -> s.attempts() > 0)
                .mapToInt(TestAttemptService.ExamSummary::averageScore)
                .average().orElse(0);

        model.addAttribute("summary", summary);
        model.addAttribute("recent", recent);
        model.addAttribute("totalAttempts", totalAttempts);
        model.addAttribute("totalPassed", totalPassed);
        model.addAttribute("overallAvgScore", avgScore);
        model.addAttribute("section", "dashboard");
        return "my-reports-dashboard";
    }

    /** Per-test details (full attempt list for a chosen exam). */
    @GetMapping("/per-test")
    public String perTest(Authentication auth,
                          @RequestParam(name = "exam", required = false) String exam,
                          Model model) {
        String email = currentEmail(auth);
        List<ExamDto> exams = examService.listActive();
        if (exam == null || exam.isBlank()) {
            // Default to first exam the user has actually attempted, else first active.
            List<TestAttemptService.ExamSummary> summary = attempts.summaryByExamForUser(email);
            exam = summary.isEmpty()
                    ? (exams.isEmpty() ? null : exams.get(0).slug())
                    : summary.get(0).slug();
        }
        List<TestAttempt> rows = (exam == null) ? List.of() : attempts.trendForUserAndExam(email, exam);
        // Reverse so newest first in the table view.
        List<TestAttempt> newestFirst = new ArrayList<>(rows);
        java.util.Collections.reverse(newestFirst);
        model.addAttribute("rows", newestFirst);
        model.addAttribute("exam", exam);
        model.addAttribute("exams", exams);
        // Per-exam aggregate for the header cards.
        int avg = 0, best = 0, passedCount = 0;
        long totalDuration = 0;
        int totalCorrect = 0, totalIncorrect = 0, totalUnanswered = 0, totalQuestions = 0;
        for (TestAttempt a : rows) {
            avg += a.getScorePercent();
            best = Math.max(best, a.getScorePercent());
            if (a.isPassed()) passedCount++;
            totalDuration += a.getDurationSeconds();
            totalCorrect += a.getCorrectCount();
            totalIncorrect += a.getIncorrectCount();
            totalUnanswered += a.getUnansweredCount();
            totalQuestions += a.getTotalQuestions();
        }
        if (!rows.isEmpty()) avg /= rows.size();
        model.addAttribute("attemptCount", rows.size());
        model.addAttribute("avgScore", avg);
        model.addAttribute("bestScore", best);
        model.addAttribute("passedCount", passedCount);
        model.addAttribute("avgDuration", rows.isEmpty() ? 0 : totalDuration / rows.size());
        model.addAttribute("totalCorrect", totalCorrect);
        model.addAttribute("totalIncorrect", totalIncorrect);
        model.addAttribute("totalUnanswered", totalUnanswered);
        model.addAttribute("totalQuestions", totalQuestions);
        model.addAttribute("section", "per-test");
        return "my-reports-per-test";
    }

    /** Score trend over time vs. passing score, per exam. */
    @GetMapping("/trend")
    public String trend(Authentication auth,
                        @RequestParam(name = "exam", required = false) String exam,
                        Model model) {
        String email = currentEmail(auth);
        List<ExamDto> exams = examService.listActive();
        if (exam == null || exam.isBlank()) {
            List<TestAttemptService.ExamSummary> summary = attempts.summaryByExamForUser(email);
            exam = summary.isEmpty()
                    ? (exams.isEmpty() ? null : exams.get(0).slug())
                    : summary.get(0).slug();
        }
        final String slug = exam;
        List<TestAttempt> trend = (slug == null) ? List.of() : attempts.trendForUserAndExam(email, slug);
        int passingPct = trend.isEmpty()
                ? exams.stream().filter(e -> e.slug().equals(slug)).findFirst().map(ExamDto::passingScorePercent).orElse(65)
                : trend.get(0).getPassingScorePercent();
        // X axis is sized in fixed "slots" — minimum 5 attempts, then grown in
        // steps of 5 as the user accumulates more. The line therefore stays
        // anchored on the left and the empty slots to the right give a visual
        // hint of how far they have to go before the axis expands again.
        int n = trend.size();
        int slots = Math.max(5, ((n + 4) / 5) * 5);

        int chartW = 720, chartH = 240, padL = 44, padR = 18, padT = 18, padB = 36;
        StringBuilder points = new StringBuilder();
        List<int[]> markers = new ArrayList<>(); // [x, y, score, pass?, attemptNumber]
        for (int i = 0; i < n; i++) {
            int x = padL + (int) Math.round((double) (chartW - padL - padR) * i / Math.max(1, slots - 1));
            int y = padT + (int) Math.round((chartH - padT - padB) * (1.0 - trend.get(i).getScorePercent() / 100.0));
            if (i > 0) points.append(' ');
            points.append(x).append(',').append(y);
            markers.add(new int[]{x, y, trend.get(i).getScorePercent(), trend.get(i).isPassed() ? 1 : 0, i + 1});
        }
        // Slot ticks (1..slots) along the X axis so users can count attempts.
        List<int[]> xTicks = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            int x = padL + (int) Math.round((double) (chartW - padL - padR) * i / Math.max(1, slots - 1));
            xTicks.add(new int[]{x, i + 1});
        }
        int passY = padT + (int) Math.round((chartH - padT - padB) * (1.0 - passingPct / 100.0));
        model.addAttribute("trend", trend);
        model.addAttribute("exam", exam);
        model.addAttribute("exams", exams);
        model.addAttribute("passingPct", passingPct);
        model.addAttribute("chartW", chartW);
        model.addAttribute("chartH", chartH);
        model.addAttribute("padL", padL);
        model.addAttribute("padR", padR);
        model.addAttribute("padT", padT);
        model.addAttribute("padB", padB);
        model.addAttribute("polyPoints", points.toString());
        model.addAttribute("markers", markers);
        model.addAttribute("xTicks", xTicks);
        model.addAttribute("slots", slots);
        model.addAttribute("passY", passY);
        model.addAttribute("section", "trend");
        return "my-reports-trend";
    }

    /** Drill-down into a single attempt: shows every question the user
     *  answered, the choices they picked, the correct choices, and the
     *  explanation. {@code filter=correct|incorrect|all} controls which
     *  questions are listed; the per-test report links to this with the
     *  filter pre-applied. Ownership is enforced server-side by the
     *  service (AccessDeniedException if the attempt belongs to someone
     *  else). */
    @GetMapping("/attempts/{id}")
    public String attemptDetail(Authentication auth,
                                @PathVariable Long id,
                                @RequestParam(name = "filter", defaultValue = "all") String filter,
                                @RequestParam(name = "page", defaultValue = "1") int page,
                                @RequestParam(name = "size", defaultValue = "10") int size,
                                Model model) {
        String email = currentEmail(auth);
        TestAttemptService.AttemptDetailView detail =
                attempts.attemptDetail(email, id, filter, page, size);
        model.addAttribute("detail", detail);
        // Normalize the filter for the template — only correct/incorrect get
        // a focused title; anything else falls back to "all".
        String norm = "all";
        if ("correct".equalsIgnoreCase(filter)) norm = "correct";
        else if ("incorrect".equalsIgnoreCase(filter)) norm = "incorrect";
        model.addAttribute("filter", norm);
        model.addAttribute("section", "per-test");
        return "my-reports-attempt-detail";
    }

}
