package com.sfquiz.service;

import com.sfquiz.entity.Question;
import com.sfquiz.entity.QuestionVote;
import com.sfquiz.entity.User;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.repository.QuestionVoteRepository;
import com.sfquiz.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class VoteService {

    private static final Logger log = LoggerFactory.getLogger(VoteService.class);

    private final QuestionVoteRepository votes;
    private final QuestionRepository questions;
    private final UserRepository users;

    public VoteService(QuestionVoteRepository votes, QuestionRepository questions, UserRepository users) {
        this.votes = votes;
        this.questions = questions;
        this.users = users;
    }

    public record VoteStats(long up, long down, int myVote, String myReason) {}

    /** Upsert: +1 for thumbs-up, -1 for thumbs-down. If the user clicks the
     *  same direction they already chose with the same reason, the vote is
     *  removed (toggle off). Verifiers MUST supply a non-blank reason. */
    @Transactional
    public VoteStats vote(Long questionId, String userEmail, int value, String reason) {
        if (value != 1 && value != -1) throw new IllegalArgumentException("vote must be +1 or -1");
        Question q = questions.findById(questionId).orElseThrow(() -> new IllegalArgumentException("Unknown question"));
        User u = users.findByEmailIgnoreCase(userEmail).orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        boolean reasonRequired = u.getRole() == com.sfquiz.entity.UserRole.VERIFIER;
        String cleanReason = reason == null ? null : reason.trim();
        if (cleanReason != null && cleanReason.isEmpty()) cleanReason = null;
        if (reasonRequired && cleanReason == null) {
            throw new IllegalArgumentException("Verifiers must pick a reason for each vote.");
        }
        if (cleanReason != null && cleanReason.length() > 200) {
            cleanReason = cleanReason.substring(0, 200);
        }

        Optional<QuestionVote> existing = votes.findByQuestionAndUser(q, u);
        if (existing.isPresent()) {
            QuestionVote v = existing.get();
            if (v.getVoteValue() == value && java.util.Objects.equals(v.getReason(), cleanReason)) {
                // Same direction + same reason → toggle off (un-vote).
                votes.delete(v);
                return statsFor(q, u);
            }
            v.setVoteValue(value);
            v.setReason(cleanReason);
            v.setVotedAt(Instant.now());
            votes.save(v);
        } else {
            QuestionVote v = new QuestionVote();
            v.setQuestion(q);
            v.setUser(u);
            v.setVoteValue(value);
            v.setReason(cleanReason);
            v.setVotedAt(Instant.now());
            votes.save(v);
        }
        return statsFor(q, u);
    }

    /** Read-only stats for one question — used by the quiz UI to render counts
     *  and highlight the user's current vote. */
    public VoteStats statsFor(Long questionId, String userEmail) {
        Question q = questions.findById(questionId).orElse(null);
        if (q == null) return new VoteStats(0, 0, 0, null);
        User u = userEmail == null ? null : users.findByEmailIgnoreCase(userEmail).orElse(null);
        return statsFor(q, u);
    }

    public record QuestionVoteRow(Long id, Integer number, String text, long up, long down, long net) {}

    public record VerifierFeedbackEntry(
            Long voteId, Long questionId, Integer questionNumber, String questionText,
            String examSlug, String examName,
            int voteValue, String reason,
            String voterEmail, String voterFullName,
            Instant votedAt
    ) {
        /** Display name for the voter — full name if set, otherwise email. */
        public String voterDisplay() {
            return (voterFullName != null && !voterFullName.isBlank()) ? voterFullName : voterEmail;
        }
    }

    /** Lightweight voter for the verifier-feedback dropdown. */
    public record FeedbackVoter(String email, String fullName, long up, long down) {
        public long total() { return up + down; }
        public String display() {
            return (fullName != null && !fullName.isBlank()) ? fullName : email;
        }
    }

    /** Verifier-feedback admin report — list of every vote that carries a
     *  reason (verifiers are forced to supply one, optional for everyone else).
     *  Use {@code examSlug = null} to see all exams. */
    public List<VerifierFeedbackEntry> verifierFeedback(String examSlug) {
        List<QuestionVote> rows = votes.findVotesWithReasons((examSlug == null || examSlug.isBlank()) ? null : examSlug);
        List<VerifierFeedbackEntry> out = new java.util.ArrayList<>(rows.size());
        for (QuestionVote v : rows) {
            Question q = v.getQuestion();
            User u = v.getUser();
            out.add(new VerifierFeedbackEntry(
                    v.getId(),
                    q == null ? null : q.getId(),
                    q == null ? null : q.getNumber(),
                    q == null ? "" : q.getText(),
                    q == null || q.getExam() == null ? "" : q.getExam().getSlug(),
                    q == null || q.getExam() == null ? "" : q.getExam().getName(),
                    v.getVoteValue(),
                    v.getReason(),
                    u == null ? "(unknown)" : u.getEmail(),
                    u == null ? "" : u.getFullName(),
                    v.getVotedAt()
            ));
        }
        return out;
    }

    /** Distinct non-blank reasons used across every reasoned vote, sorted
     *  alphabetically — drives the reason dropdown on the feedback report. */
    public List<String> feedbackReasons() {
        return votes.distinctReasons();
    }

    /** Distinct voters who have left at least one reasoned vote, with their
     *  up/down totals — drives the "filter by verifier" dropdown on the
     *  feedback report. Sorted by voter display name. */
    public List<FeedbackVoter> feedbackVoters() {
        List<QuestionVote> rows = votes.findVotesWithReasons(null);
        java.util.Map<String, FeedbackVoter> byEmail = new java.util.LinkedHashMap<>();
        for (QuestionVote v : rows) {
            User u = v.getUser();
            if (u == null) continue;
            String email = u.getEmail();
            FeedbackVoter cur = byEmail.get(email);
            long up = (cur == null ? 0 : cur.up())   + (v.getVoteValue() > 0 ? 1 : 0);
            long dn = (cur == null ? 0 : cur.down()) + (v.getVoteValue() < 0 ? 1 : 0);
            byEmail.put(email, new FeedbackVoter(email, u.getFullName(), up, dn));
        }
        List<FeedbackVoter> out = new java.util.ArrayList<>(byEmail.values());
        out.sort((a, b) -> a.display().compareToIgnoreCase(b.display()));
        return out;
    }

    public record ExamQualityReport(
            String slug,
            long up, long down, long votedQuestions, long totalVotes, int approxPercentUp,
            List<QuestionVoteRow> top,    // highest net votes
            List<QuestionVoteRow> bottom  // lowest net votes (most "thumbs-down")
    ) {}

    /** Build a quality report for one exam: totals + the top and bottom 10
     *  questions by net (up − down) vote score. */
    public ExamQualityReport buildReport(String examSlug) {
        List<Object[]> totalsList = votes.examTotals(examSlug);
        Object[] totals = totalsList.isEmpty() ? null : totalsList.get(0);
        long up = totals == null || totals[0] == null ? 0 : ((Number) totals[0]).longValue();
        long down = totals == null || totals[1] == null ? 0 : ((Number) totals[1]).longValue();
        long votedQuestions = totals == null || totals[2] == null ? 0 : ((Number) totals[2]).longValue();
        long total = totals == null || totals[3] == null ? 0 : ((Number) totals[3]).longValue();
        int pct = (up + down) <= 0 ? 0 : (int) Math.round(100.0 * up / (up + down));

        // Per-question aggregate so we can rank top/bottom.
        List<Object[]> agg = votes.aggregateByExamSlug(examSlug);
        java.util.Map<Long, long[]> byId = new java.util.HashMap<>();
        for (Object[] row : agg) {
            Long qid = ((Number) row[0]).longValue();
            long u = row[1] == null ? 0 : ((Number) row[1]).longValue();
            long d = row[2] == null ? 0 : ((Number) row[2]).longValue();
            byId.put(qid, new long[]{u, d});
        }
        List<QuestionVoteRow> rows = new java.util.ArrayList<>();
        if (!byId.isEmpty()) {
            List<Question> qs = questions.findAllById(byId.keySet());
            for (Question q : qs) {
                long[] ud = byId.get(q.getId());
                rows.add(new QuestionVoteRow(q.getId(), q.getNumber(),
                        q.getText() == null ? "" : q.getText(),
                        ud[0], ud[1], ud[0] - ud[1]));
            }
        }
        List<QuestionVoteRow> top = rows.stream()
                .sorted((a, b) -> Long.compare(b.net(), a.net()))
                .limit(10)
                .toList();
        List<QuestionVoteRow> bottom = rows.stream()
                .sorted((a, b) -> Long.compare(a.net(), b.net()))
                .limit(10)
                .toList();
        return new ExamQualityReport(examSlug, up, down, votedQuestions, total, pct, top, bottom);
    }

    private VoteStats statsFor(Question q, User u) {
        long up = votes.countByQuestionAndVoteValue(q, 1);
        long down = votes.countByQuestionAndVoteValue(q, -1);
        int mine = 0;
        String myReason = null;
        if (u != null) {
            Optional<QuestionVote> existing = votes.findByQuestionAndUser(q, u);
            if (existing.isPresent()) {
                mine = existing.get().getVoteValue();
                myReason = existing.get().getReason();
            }
        }
        return new VoteStats(up, down, mine, myReason);
    }
}
