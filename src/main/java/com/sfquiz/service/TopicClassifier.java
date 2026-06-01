package com.sfquiz.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ExamTopic;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.ExamTopicRepository;
import com.sfquiz.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Uses Claude Haiku to assign one of the exam's topics to each question that
 *  doesn't have one yet. Admin-triggered (POST /admin/classify-topics) so the
 *  one-time backfill cost is opt-in. Future newly-extracted questions can be
 *  classified the same way. */
@Service
public class TopicClassifier {

    private static final Logger log = LoggerFactory.getLogger(TopicClassifier.class);

    private final QuestionRepository questions;
    private final ExamRepository exams;
    private final ExamTopicRepository examTopics;
    private final CostMeter costs;
    private final String classifyModel;
    private final AnthropicClient client;
    private final boolean enabled;

    public TopicClassifier(QuestionRepository questions,
                           ExamRepository exams,
                           ExamTopicRepository examTopics,
                           CostMeter costs,
                           @Value("${anthropic.api-key:}") String apiKey,
                           @Value("${anthropic.model.classify:claude-haiku-4-5}") String classifyModel) {
        this.questions = questions;
        this.exams = exams;
        this.examTopics = examTopics;
        this.costs = costs;
        this.classifyModel = classifyModel;
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            this.enabled = false;
        } else {
            this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            this.enabled = true;
        }
    }

    public record BatchResult(int classified, int skipped, int failed, String message) {}

    /** Classify every untagged APPROVED question for the given exam. Stops early
     *  if the daily Anthropic budget is exhausted; safe to re-run later.
     *
     *  Critical: this method is NOT transactional. The previous design
     *  wrapped the whole batch in one @Transactional, holding a single DB
     *  connection across 200+ Claude calls (~2 s each) — and every
     *  questions.save() left a dirty entity in the Hibernate session, so
     *  each subsequent operation got more expensive. Concurrent user
     *  submits queued up waiting for the pool, producing the 8-23 s
     *  service times seen in router logs.
     *
     *  Now: each repo call (the initial untagged read, the per-question
     *  findById, the targeted updateTopic) runs in its own short auto-
     *  transaction. The Claude roundtrip in between holds nothing. The
     *  connection is back in the pool well under a second per question. */
    public BatchResult classifyUntaggedFor(String examSlug) {
        Exam exam = exams.findBySlug(examSlug).orElse(null);
        if (exam == null) return new BatchResult(0, 0, 0, "Exam '" + examSlug + "' not found");
        List<ExamTopic> topics = examTopics.findByExamOrderBySortOrderAscIdAsc(exam);
        if (topics.isEmpty()) {
            return new BatchResult(0, 0, 0, "No topics defined for exam '" + examSlug + "' — seed topics first");
        }
        if (!enabled) {
            return new BatchResult(0, 0, 0, "ANTHROPIC_API_KEY is not set on this dyno");
        }

        List<Long> untaggedIds = questions
                .findByExamAndStatusAndTopicIsNull(exam, Question.Status.APPROVED)
                .stream().map(Question::getId).collect(Collectors.toList());
        if (untaggedIds.isEmpty()) {
            return new BatchResult(0, 0, 0, "All approved questions already have a topic");
        }

        String system = buildSystemPrompt(topics);
        int classified = 0, skipped = 0, failed = 0;
        for (Long qid : untaggedIds) {
            if (costs.budgetExhausted()) {
                return new BatchResult(classified, skipped, failed,
                        "Daily Anthropic budget exhausted after " + classified +
                        " of " + untaggedIds.size() + " — re-run after UTC midnight");
            }
            try {
                // Load happens in its own short txn (auto from JpaRepository).
                // Question.choices is EAGER so the choices collection is
                // initialized before the session closes — classifyOne can
                // safely read it on the detached entity.
                Question q = questions.findById(qid).orElse(null);
                if (q == null) { skipped++; continue; }
                String key = classifyOne(q, topics, system);
                if (key == null) {
                    failed++;
                } else {
                    // Targeted UPDATE — single row, single short txn, no
                    // dirty-entity accumulation in any session.
                    questions.updateTopic(qid, key);
                    classified++;
                }
            } catch (Exception ex) {
                log.warn("classify failed for question id={}: {}", qid, ex.getMessage());
                failed++;
            }
        }
        return new BatchResult(classified, skipped, failed,
                "Classified " + classified + " of " + untaggedIds.size() +
                (failed > 0 ? " (" + failed + " failed)" : ""));
    }

    private String classifyOne(Question q, List<ExamTopic> topics, String system) {
        String body = q.getText() == null ? "" : q.getText();
        if (q.getChoices() != null && !q.getChoices().isEmpty()) {
            String choices = q.getChoices().stream()
                    .map(c -> "- " + c.getText())
                    .collect(Collectors.joining("\n"));
            body = body + "\n\nChoices:\n" + choices;
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(classifyModel)
                .maxTokens(40L)
                .system(system)
                .addUserMessage(body)
                .build();
        Message reply = client.messages().create(params);
        recordUsage(q.getId(), reply);
        String raw = firstText(reply).trim().toLowerCase(Locale.ROOT);
        for (ExamTopic t : topics) {
            if (raw.contains(t.getTopicKey().toLowerCase(Locale.ROOT))) {
                return t.getTopicKey();
            }
        }
        log.debug("classify: unrecognized reply '{}' for question id={}", raw, q.getId());
        return null;
    }

    private String buildSystemPrompt(List<ExamTopic> topics) {
        StringBuilder sb = new StringBuilder();
        sb.append("You classify Salesforce certification practice questions into one of the following official exam topics.\n\n");
        sb.append("Topics (key — name):\n");
        for (ExamTopic t : topics) {
            sb.append("- ").append(t.getTopicKey()).append(" — ").append(t.getName()).append("\n");
        }
        sb.append("\nGiven the question text below, reply with ONLY the topic key (the part before the em-dash) that best fits. ");
        sb.append("Do not output any other text. If unsure, pick the closest match — never invent a new key.");
        return sb.toString();
    }

    private void recordUsage(Long questionId, Message reply) {
        try {
            long in = reply.usage().inputTokens();
            long out = reply.usage().outputTokens();
            long cacheW = unwrapLong(reply.usage().cacheCreationInputTokens());
            long cacheR = unwrapLong(reply.usage().cacheReadInputTokens());
            costs.record("classify-topic", classifyModel, in, out, cacheW, cacheR, null);
        } catch (Exception e) {
            log.debug("usage record failed for question {}: {}", questionId, e.getMessage());
        }
    }

    private static long unwrapLong(Object maybe) {
        if (maybe == null) return 0;
        if (maybe instanceof Long l) return l;
        if (maybe instanceof java.util.Optional<?> opt) {
            return opt.map(v -> ((Number) v).longValue()).orElse(0L);
        }
        if (maybe instanceof Number n) return n.longValue();
        return 0;
    }

    private static String firstText(Message reply) {
        if (reply.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : reply.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString();
    }
}
