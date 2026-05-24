package com.sfquiz.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sfquiz.dto.ImportQuestionRequest;
import com.sfquiz.dto.ImportQuestionRequest.ImportChoiceRequest;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Writes a 2-3 sentence explanation + a vendor-doc reference URL for any
 *  question whose source material didn't include one. Uses Claude Haiku for
 *  cost; budget-guarded via {@link CostMeter}. */
@Service
public class ExplanationEnricher {

    private static final Logger log = LoggerFactory.getLogger(ExplanationEnricher.class);

    /** Canonical documentation roots per exam slug. Used to nudge Claude
     *  toward citing the right vendor site. Unlisted exams fall back to
     *  "official product documentation". */
    private static final Map<String, List<String>> VENDOR_DOCS = Map.of(
            "salesforce-admin",            List.of("https://help.salesforce.com/", "https://trailhead.salesforce.com/", "https://developer.salesforce.com/docs/"),
            "agentforce-specialist",       List.of("https://help.salesforce.com/s/articleView?id=ai.agents_overview.htm", "https://trailhead.salesforce.com/content/learn/modules/agentforce-prompt-fundamentals"),
            "data-cloud-consultant",       List.of("https://help.salesforce.com/s/articleView?id=sf.c360_a_data_cloud.htm", "https://trailhead.salesforce.com/content/learn/trails/get-started-with-salesforce-data-cloud"),
            "mulesoft-platform-architect", List.of("https://docs.mulesoft.com/", "https://developer.mulesoft.com/"),
            "aws-saa",                     List.of("https://docs.aws.amazon.com/", "https://aws.amazon.com/architecture/"),
            "togaf",                       List.of("https://pubs.opengroup.org/togaf-standard/")
    );

    private static final Pattern JSON_OBJ = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final ObjectMapper json;
    private final CostMeter costs;
    private final QuestionRepository questions;
    private final ExamRepository exams;
    private final String classifyModel;
    private final AnthropicClient client;
    private final boolean enabled;

    public ExplanationEnricher(ObjectMapper json,
                               CostMeter costs,
                               QuestionRepository questions,
                               ExamRepository exams,
                               @Value("${anthropic.api-key:}") String apiKey,
                               @Value("${anthropic.model.classify:claude-haiku-4-5}") String classifyModel) {
        this.json = json;
        this.costs = costs;
        this.questions = questions;
        this.exams = exams;
        this.classifyModel = classifyModel;
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            this.enabled = false;
        } else {
            this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            this.enabled = true;
        }
    }

    /** Returns a copy of the request with explanation + helpUrl populated when
     *  they were missing. If enrichment can't be done (no API key, budget gone,
     *  call fails), returns the input unchanged so the upload still proceeds. */
    public ImportQuestionRequest enrich(ImportQuestionRequest q, String examSlug, Long uploadId) {
        if (q == null) return null;
        boolean needsExplanation = isBlank(q.explanation());
        boolean needsUrl = isBlank(q.helpUrl());
        if (!needsExplanation && !needsUrl) return q;
        if (!enabled || costs.budgetExhausted()) return q;

        try {
            Enriched e = callClaude(q, examSlug, uploadId);
            if (e == null) return q;
            String newExplanation = needsExplanation && !isBlank(e.explanation) ? e.explanation : q.explanation();
            String newUrl = needsUrl && !isBlank(e.helpUrl) ? e.helpUrl : q.helpUrl();
            return new ImportQuestionRequest(q.n(), q.type(), q.text(), newUrl, newExplanation, q.sourceUrl(), q.choices());
        } catch (Exception ex) {
            log.warn("enrich failed for question (uploadId={}): {}", uploadId, ex.getMessage());
            return q;
        }
    }

    /** Fire-and-forget enrichment for a single approved question. Called from
     *  the approve flow only when the admin left the explanation blank — they
     *  get an instant response, the agent fills in the explanation a few
     *  seconds later. No-ops if Anthropic isn't configured, the budget is
     *  exhausted, the question has been deleted, or already has an explanation. */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enrichOneAsync(Long questionId) {
        if (questionId == null) return;
        Question q = questions.findById(questionId).orElse(null);
        if (q == null) return;
        boolean needsExpl = isBlank(q.getExplanation());
        boolean needsUrl = isBlank(q.getHelpUrl());
        if (!needsExpl && !needsUrl) return;
        if (!enabled) { log.debug("enrichOneAsync: API key not set, skipping id={}", questionId); return; }
        if (costs.budgetExhausted()) { log.info("enrichOneAsync: budget exhausted, skipping id={}", questionId); return; }
        String slug = q.getExam() != null ? q.getExam().getSlug() : "salesforce-admin";
        try {
            Enriched e = callClaudeFromEntity(q, slug);
            if (e == null) return;
            if (needsExpl && !isBlank(e.explanation)) q.setExplanation(e.explanation);
            if (needsUrl && !isBlank(e.helpUrl)) q.setHelpUrl(e.helpUrl);
            questions.save(q);
            log.info("agent-enriched question id={} after admin approval (exam={})", questionId, slug);
        } catch (Exception ex) {
            log.warn("enrichOneAsync failed for question id={}: {}", questionId, ex.getMessage());
        }
    }

    /** Admin-triggered backfill: fill in explanation/helpUrl for every approved
     *  question in an exam that's still missing them. Bounded by the daily
     *  Anthropic budget; safe to re-run. */
    @Transactional
    public BatchResult enrichExisting(String examSlug) {
        Exam exam = exams.findBySlug(examSlug).orElse(null);
        if (exam == null) return new BatchResult(0, 0, "Exam '" + examSlug + "' not found");
        if (!enabled) return new BatchResult(0, 0, "ANTHROPIC_API_KEY is not set on this dyno");

        List<Question> all = questions.findByExamSlugAndStatusOrderByNumber(examSlug, Question.Status.APPROVED);
        int enriched = 0, failed = 0;
        for (Question dbQ : all) {
            boolean needsExpl = isBlank(dbQ.getExplanation());
            boolean needsUrl = isBlank(dbQ.getHelpUrl());
            if (!needsExpl && !needsUrl) continue;
            if (costs.budgetExhausted()) {
                return new BatchResult(enriched, failed,
                        "Daily Anthropic budget exhausted after enriching " + enriched +
                        " — re-run after UTC midnight");
            }
            try {
                Enriched e = callClaudeFromEntity(dbQ, examSlug);
                if (e == null) { failed++; continue; }
                if (needsExpl && !isBlank(e.explanation)) dbQ.setExplanation(e.explanation);
                if (needsUrl && !isBlank(e.helpUrl)) dbQ.setHelpUrl(e.helpUrl);
                questions.save(dbQ);
                enriched++;
            } catch (Exception ex) {
                log.warn("backfill enrich failed for question id={}: {}", dbQ.getId(), ex.getMessage());
                failed++;
            }
        }
        return new BatchResult(enriched, failed,
                "Enriched " + enriched + " question(s)" +
                (failed > 0 ? " (" + failed + " failed)" : ""));
    }

    public record BatchResult(int enriched, int failed, String message) {}

    // --- internals ---------------------------------------------------------

    private record Enriched(String explanation, String helpUrl) {}

    private Enriched callClaude(ImportQuestionRequest q, String examSlug, Long uploadId) {
        String userMsg = buildUserMessage(q.text(),
                q.choices() == null ? List.of() : q.choices(),
                examSlug);
        return invoke(userMsg, examSlug, uploadId);
    }

    private Enriched callClaudeFromEntity(Question q, String examSlug) {
        List<ImportChoiceRequest> choices = q.getChoices().stream()
                .map(c -> new ImportChoiceRequest(c.getLabel(), c.getText(), c.isCorrect()))
                .collect(Collectors.toList());
        String userMsg = buildUserMessage(q.getText(), choices, examSlug);
        return invoke(userMsg, examSlug, null);
    }

    private Enriched invoke(String userMsg, String examSlug, Long uploadId) {
        String system = buildSystemPrompt(examSlug);
        MessageCreateParams params = MessageCreateParams.builder()
                .model(classifyModel)
                .maxTokens(400L)
                .system(system)
                .addUserMessage(userMsg)
                .build();
        Message reply = client.messages().create(params);
        recordUsage("enrich-explanation", reply, uploadId);
        String raw = firstText(reply);
        Matcher m = JSON_OBJ.matcher(raw);
        if (!m.find()) return null;
        try {
            JsonNode node = json.readTree(m.group());
            String exp = node.path("explanation").asText(null);
            String url = node.path("helpUrl").asText(null);
            return new Enriched(exp, url);
        } catch (Exception ex) {
            log.debug("enrich: JSON parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt(String examSlug) {
        List<String> docs = VENDOR_DOCS.getOrDefault(examSlug, List.of("the vendor's official product documentation"));
        StringBuilder sb = new StringBuilder();
        sb.append("You write short, accurate explanations for certification practice questions.\n\n");
        sb.append("Given a question, its multiple-choice options, and which option(s) are marked correct, write:\n");
        sb.append("- A 2–3 sentence explanation of WHY the correct answer is right (and, briefly, why the most plausible distractor is wrong if it helps clarity).\n");
        sb.append("- A 'helpUrl' pointing to the most relevant official documentation page from this list of canonical roots:\n");
        for (String d : docs) sb.append("  • ").append(d).append("\n");
        sb.append("\nIf you don't know an exact deep link, use the closest documentation root from the list above. ");
        sb.append("Never invent a URL on a different domain. Never reference unofficial blogs.\n\n");
        sb.append("Reply with ONLY a JSON object — no prose, no markdown fences:\n");
        sb.append("{\"explanation\": \"<2-3 sentences>\", \"helpUrl\": \"<https://… official doc URL>\"}");
        return sb.toString();
    }

    private String buildUserMessage(String text, List<ImportChoiceRequest> choices, String examSlug) {
        StringBuilder sb = new StringBuilder();
        sb.append("Exam: ").append(examSlug).append("\n\n");
        sb.append("Question:\n").append(text == null ? "" : text).append("\n\nChoices:\n");
        for (ImportChoiceRequest c : choices) {
            sb.append(c.correct() ? "[CORRECT] " : "          ")
              .append(c.text() == null ? "" : c.text()).append("\n");
        }
        return sb.toString();
    }

    private void recordUsage(String purpose, Message reply, Long uploadId) {
        try {
            long in = reply.usage().inputTokens();
            long out = reply.usage().outputTokens();
            long cacheW = unwrapLong(reply.usage().cacheCreationInputTokens());
            long cacheR = unwrapLong(reply.usage().cacheReadInputTokens());
            costs.record(purpose, classifyModel, in, out, cacheW, cacheR, uploadId);
        } catch (Exception ignored) {}
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
        for (ContentBlock b : reply.content()) {
            b.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
