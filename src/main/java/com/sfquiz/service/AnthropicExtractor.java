package com.sfquiz.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sfquiz.dto.ImportQuestionRequest;
import com.sfquiz.dto.ImportQuestionRequest.ImportChoiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calls Claude to (a) screen content for exam-dump material and
 *  (b) extract practice questions. Mirrors the Python sf-cert-crawler pipeline's
 *  classify + extract steps, with the same prompts. */
@Service
public class AnthropicExtractor {

    private static final Logger log = LoggerFactory.getLogger(AnthropicExtractor.class);

    private static final String EXTRACTOR_SYSTEM = """
            You extract Salesforce certification study questions from text or images, for a learner's personal practice database.

            Return ONLY a JSON array (no prose, no markdown fences). Each element:
            {
              "question_text": "<the question prompt, verbatim or lightly cleaned of noise>",
              "type": "SINGLE" | "MULTI",
              "options": [
                {"text": "<choice text>", "correct": true|false},
                ...
              ],
              "explanation": "<the source's explanation>" or null,
              "help_url": "<official Trailhead or Help doc URL the source provides>" or null
            }

            Rules:
            - Do NOT invent answers or explanations. If the source does not state the correct answer, omit the item entirely. NEVER guess.
            - "SINGLE" = exactly one option has correct=true. "MULTI" = two or more options have correct=true.
            - Every item must have at least 2 options.
            - Only include genuine practice/study questions. Skip article prose, navigation, ads, comments, author bios.
            - If there are no questions at all, return [].
            - Output ONLY the JSON array.
            """;

    private static final String DUMP_CHECK_SYSTEM = """
            You are a content-integrity check for a personal Salesforce study tool.

            Given text that appears to contain certification questions, decide whether it looks like LEAKED or PAID real-exam content versus legitimate user-generated study material (practice questions written for study, Trailhead-style "check your understanding", forum discussion, blog practice sets). Anything that claims to contain "real exam questions", "actual exam questions", "exam dumps", "verified answers", or that reads like a transcription of a live certification exam is a dump. Material explicitly written and published as practice — even if hard or extensive — is clean.

            Reply with ONLY one word: clean | suspicious | dump
            """;

    private final ObjectMapper json;
    private final CostMeter costs;
    private final String extractModel;
    private final String classifyModel;
    private final AnthropicClient client;
    private final boolean enabled;

    public AnthropicExtractor(ObjectMapper json,
                              CostMeter costs,
                              @Value("${anthropic.api-key:}") String apiKey,
                              @Value("${anthropic.model.extract:claude-sonnet-4-6}") String extractModel,
                              @Value("${anthropic.model.classify:claude-haiku-4-5}") String classifyModel) {
        this.json = json;
        this.costs = costs;
        this.extractModel = extractModel;
        this.classifyModel = classifyModel;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY is not set — extraction will be skipped for all uploads");
            this.client = null;
            this.enabled = false;
        } else {
            this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            this.enabled = true;
        }
    }

    public boolean enabled() { return enabled; }

    /** "dump" / "suspicious" / "clean". Calls Haiku with a short reply (~20 tokens). */
    public String dumpCheck(String text, Long uploadId) {
        if (!enabled) return "clean"; // fail-open if no key configured
        String user = "TEXT:\n" + truncate(text, 14_000);
        MessageCreateParams params = MessageCreateParams.builder()
                .model(classifyModel)
                .maxTokens(20L)
                .system(DUMP_CHECK_SYSTEM)
                .addUserMessage(user)
                .build();
        Message reply = client.messages().create(params);
        recordUsage("dump-check", classifyModel, reply, uploadId);
        String raw = firstText(reply).trim().toLowerCase(Locale.ROOT);
        if (raw.contains("dump")) return "dump";
        if (raw.contains("suspicious")) return "suspicious";
        if (raw.contains("clean")) return "clean";
        log.warn("dump-check: unrecognized reply '{}' — treating as suspicious", raw);
        return "suspicious";
    }

    /** Cap output tokens generously so a PDF that yields ~100 questions
     *  doesn't get truncated mid-object (4000 was hitting the ceiling and
     *  the truncated JSON failed to parse, dropping every question on the
     *  floor). Sonnet 4.6 supports up to 64k output tokens; 16k is roughly
     *  ~120 questions and still well under the budget per call. */
    private static final long EXTRACT_MAX_TOKENS = 16_000L;

    /** How much of the source text we send to Claude in a single call. A
     *  typical 50-question practice PDF is 80–120k characters; the old
     *  24k cap silently dropped ~75% of the file, which is why "uploads
     *  weren't extracting the whole PDF." Sonnet 4.6 has a 200k *token*
     *  input window (~600k characters), so 150k chars is comfortably
     *  within capacity and keeps room for the system prompt + JSON output.
     *  Files larger than this still get truncated; the next iteration
     *  should chunk them into multiple calls. */
    private static final int EXTRACT_MAX_INPUT_CHARS = 150_000;

    /** Extract questions from plain text. */
    public List<ImportQuestionRequest> extractFromText(String text, Long uploadId) {
        if (!enabled) return List.of();
        if (text != null && text.length() > EXTRACT_MAX_INPUT_CHARS) {
            log.warn("extract: source text is {} chars, truncating to {} for Claude " +
                     "(questions past that point will be missed — consider chunking)",
                     text.length(), EXTRACT_MAX_INPUT_CHARS);
        }
        String user = "TEXT:\n" + truncate(text, EXTRACT_MAX_INPUT_CHARS);
        MessageCreateParams params = MessageCreateParams.builder()
                .model(extractModel)
                .maxTokens(EXTRACT_MAX_TOKENS)
                .system(EXTRACTOR_SYSTEM)
                .addUserMessage(user)
                .build();
        Message reply = client.messages().create(params);
        recordUsage("extract-text", extractModel, reply, uploadId);
        return parseQuestions(firstText(reply));
    }

    /** Extract questions from a screenshot/image via Claude vision. */
    public List<ImportQuestionRequest> extractFromImage(byte[] imageBytes, String mediaType, Long uploadId) {
        if (!enabled) return List.of();
        String base64 = Base64.getEncoder().encodeToString(imageBytes);

        ContentBlockParam imageBlock = ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(toMediaType(mediaType))
                        .data(base64)
                        .build())
                .build());

        ContentBlockParam textBlock = ContentBlockParam.ofText(TextBlockParam.builder()
                .text("Extract every practice question visible in the attached image. " +
                        "If the image isn't a practice question, return [].")
                .build());

        MessageCreateParams params = MessageCreateParams.builder()
                .model(extractModel)
                .maxTokens(EXTRACT_MAX_TOKENS)
                .system(EXTRACTOR_SYSTEM)
                .addUserMessageOfBlockParams(List.of(imageBlock, textBlock))
                .build();
        Message reply = client.messages().create(params);
        recordUsage("extract-image", extractModel, reply, uploadId);
        return parseQuestions(firstText(reply));
    }

    private static Base64ImageSource.MediaType toMediaType(String mime) {
        if (mime == null) return Base64ImageSource.MediaType.IMAGE_JPEG;
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> Base64ImageSource.MediaType.IMAGE_JPEG;
        };
    }

    private void recordUsage(String purpose, String model, Message reply, Long uploadId) {
        try {
            long in = reply.usage().inputTokens();
            long out = reply.usage().outputTokens();
            long cacheW = unwrapLong(reply.usage().cacheCreationInputTokens());
            long cacheR = unwrapLong(reply.usage().cacheReadInputTokens());
            costs.record(purpose, model, in, out, cacheW, cacheR, uploadId);
        } catch (Exception e) {
            log.warn("failed to record usage for {}: {}", purpose, e.getMessage());
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

    private String firstText(Message reply) {
        if (reply.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : reply.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString();
    }

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*]", Pattern.DOTALL);

    private List<ImportQuestionRequest> parseQuestions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();

        // Pull out the JSON array payload. If Claude was truncated by the
        // max-tokens cap there may be no closing ']', so fall back to "from
        // the first '[' to end-of-string" before giving up.
        String payload;
        Matcher m = JSON_ARRAY.matcher(raw);
        if (m.find()) {
            payload = m.group();
        } else {
            int start = raw.indexOf('[');
            if (start < 0) {
                log.warn("extract: no JSON array found in reply ({} chars)", raw.length());
                return List.of();
            }
            payload = raw.substring(start);
        }

        // First attempt — clean parse.
        List<ImportQuestionRequest> out = tryParseArray(payload);
        if (!out.isEmpty()) return out;

        // Salvage path — Claude got cut off mid-object. Truncate to the
        // last complete object boundary and close the array so we keep
        // every question that DID make it through before the cap.
        String salvaged = salvageTruncatedArray(payload);
        if (salvaged != null) {
            out = tryParseArray(salvaged);
            if (!out.isEmpty()) {
                log.info("extract: salvaged {} questions from truncated reply", out.size());
                return out;
            }
        }
        log.warn("extract: JSON parse failed and nothing could be salvaged ({} chars)", payload.length());
        return out;
    }

    private List<ImportQuestionRequest> tryParseArray(String payload) {
        List<ImportQuestionRequest> out = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(payload);
            if (!arr.isArray()) return List.of();
            for (JsonNode node : arr) {
                ImportQuestionRequest q = nodeToQuestion(node);
                if (q != null) out.add(q);
            }
        } catch (Exception ignored) {
            // Swallow — caller decides whether to attempt salvage.
        }
        return out;
    }

    /** When Claude's reply is truncated mid-object, find the last '}' that
     *  was followed by a ',' (i.e. the end of a complete question object
     *  inside the array), drop everything after it, and append ']' so the
     *  prefix parses as a valid JSON array. Returns null if no such
     *  boundary exists (the reply was cut off in the very first object). */
    private static String salvageTruncatedArray(String payload) {
        for (int i = payload.length() - 1; i >= 0; i--) {
            if (payload.charAt(i) != '}') continue;
            int j = i + 1;
            while (j < payload.length() && Character.isWhitespace(payload.charAt(j))) j++;
            if (j < payload.length() && payload.charAt(j) == ',') {
                return payload.substring(0, i + 1) + "]";
            }
        }
        return null;
    }

    private ImportQuestionRequest nodeToQuestion(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String text = textOrNull(node.get("question_text"));
        if (text == null || text.length() < 10) return null;

        JsonNode optsNode = node.get("options");
        if (optsNode == null || !optsNode.isArray() || optsNode.size() < 2) return null;

        List<ImportChoiceRequest> choices = new ArrayList<>();
        int correctCount = 0;
        for (JsonNode opt : optsNode) {
            String ctext = textOrNull(opt.get("text"));
            if (ctext == null) return null;
            boolean correct = opt.path("correct").asBoolean(false);
            if (correct) correctCount++;
            choices.add(new ImportChoiceRequest(null, ctext, correct));
        }
        if (correctCount < 1) return null;

        String typeRaw = textOrNull(node.get("type"));
        String type = typeRaw == null ? null : typeRaw.toUpperCase(Locale.ROOT);
        if (!"SINGLE".equals(type) && !"MULTI".equals(type)) {
            type = correctCount > 1 ? "MULTI" : "SINGLE";
        }
        if ("SINGLE".equals(type) && correctCount > 1) {
            type = "MULTI";
        }

        return new ImportQuestionRequest(
                null,
                type,
                text,
                textOrNull(node.get("help_url")),
                textOrNull(node.get("explanation")),
                null,
                choices
        );
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (!n.isTextual()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String text, int maxChars) {
        return text == null || text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n…[truncated]";
    }
}
