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

/** Parses a "score breakdown" (the per-topic % weights that ship with most
 *  certification study guides) into structured {@link TopicWeight} rows so
 *  the super admin can lock them in at cert-creation time.
 *
 *  Two input modes:
 *    - Pasted text — usually copy/pasted from the cert's official study
 *      guide. A simple regex pulls out "Name … N%" pairs locally; if that
 *      yields no rows we fall back to Claude for messy/free-form text.
 *    - Screenshot — the operator uploads a PNG/JPEG of the cert's score
 *      breakdown table. Always goes through Claude vision.
 *
 *  If the Anthropic API key isn't set, the regex path still works for
 *  clean pasted text; image parsing returns an empty list with a warning. */
@Service
public class TopicBreakdownParser {

    private static final Logger log = LoggerFactory.getLogger(TopicBreakdownParser.class);

    /** One parsed row — {@code name} comes straight from the breakdown,
     *  {@code weightPercent} is 0–100, {@code topicKey} is a stable slug
     *  derived from the name (lowercase / hyphens) so Question.topic can
     *  reference it. */
    public record TopicWeight(String topicKey, String name, int weightPercent) {}

    private static final String SYSTEM_PROMPT = """
            You convert certification study-guide content into JSON.

            Return ONLY a JSON array (no prose, no markdown fences). Each element:
            {
              "name": "<topic display name, properly cased>",
              "weight_percent": <integer 1..100>
            }

            Rules:
            - Extract every topic + its percent weight visible in the input.
            - Weights must be integers. Round if needed.
            - Skip any header rows ("Topic", "Weight"), totals, blanks.
            - The sum SHOULD be close to 100; do NOT invent a row to make it
              add up — just return what's actually in the source.
            - If the input doesn't look like a score breakdown, return [].
            - Output ONLY the JSON array.
            """;

    /** Local regex covers ~80% of pasted formats — saves a Claude call.
     *  Matches lines like:
     *    Configuration and Setup: 20%
     *    User Setup … 7 %
     *    User Setup - 7
     *  Tolerant of stray separators (-, :, …, tabs) and trailing % sign. */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^\\s*([^\\d\\n][^\\n]*?)\\s*[\\-:…\\t]\\s*(\\d{1,3})\\s*%?\\s*$",
            Pattern.MULTILINE);

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*]", Pattern.DOTALL);

    private final ObjectMapper json;
    private final AnthropicClient client;
    private final boolean enabled;
    private final String classifyModel;

    public TopicBreakdownParser(ObjectMapper json,
                                @Value("${anthropic.api-key:}") String apiKey,
                                @Value("${anthropic.model.extract:claude-sonnet-4-6}") String extractModel,
                                @Value("${anthropic.model.classify:claude-haiku-4-5}") String classifyModel) {
        this.json = json;
        this.classifyModel = classifyModel;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY is not set — TopicBreakdownParser will use regex-only text path; image uploads will be ignored.");
            this.client = null;
            this.enabled = false;
        } else {
            this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            this.enabled = true;
        }
    }

    /** Parse breakdown from one or both inputs. Image takes precedence if
     *  provided; otherwise the text path runs. */
    public List<TopicWeight> parse(String text, byte[] imageBytes, String imageMime) {
        if (imageBytes != null && imageBytes.length > 0) {
            return parseImage(imageBytes, imageMime);
        }
        return parseText(text);
    }

    public List<TopicWeight> parseText(String text) {
        if (text == null || text.isBlank()) return List.of();
        // Try the local regex first — fast + free for the typical case.
        List<TopicWeight> rows = regexExtract(text);
        if (!rows.isEmpty()) {
            log.info("TopicBreakdownParser: regex matched {} row(s) from pasted text", rows.size());
            return rows;
        }
        if (!enabled) {
            log.warn("TopicBreakdownParser: regex found 0 rows and Claude is disabled — returning empty.");
            return List.of();
        }
        return claudeExtract(buildTextRequest(text), "topic-breakdown-text");
    }

    public List<TopicWeight> parseImage(byte[] imageBytes, String mime) {
        if (!enabled) {
            log.warn("TopicBreakdownParser: image upload received but Claude is disabled — returning empty.");
            return List.of();
        }
        return claudeExtract(buildImageRequest(imageBytes, mime), "topic-breakdown-image");
    }

    /* ===================== local regex path ===================== */

    private List<TopicWeight> regexExtract(String text) {
        List<TopicWeight> out = new ArrayList<>();
        Matcher m = LINE_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            // Strip leading bullets / numbering noise.
            name = name.replaceFirst("^[\\-\\*•\\d\\.\\)\\s]+", "").trim();
            if (name.isEmpty()) continue;
            int weight;
            try { weight = Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { continue; }
            if (weight < 1 || weight > 100) continue;
            // Drop obvious header rows.
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("topic") || lower.equals("weight") || lower.startsWith("total")) continue;
            out.add(new TopicWeight(slugify(name), name, weight));
        }
        return out;
    }

    /* ===================== Claude paths ===================== */

    private MessageCreateParams buildTextRequest(String text) {
        return MessageCreateParams.builder()
                .model(classifyModel)
                .maxTokens(2000L)
                .system(SYSTEM_PROMPT)
                .addUserMessage(text)
                .build();
    }

    private MessageCreateParams buildImageRequest(byte[] imageBytes, String mime) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ContentBlockParam imageBlock = ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(toMediaType(mime))
                        .data(base64)
                        .build())
                .build());
        ContentBlockParam textBlock = ContentBlockParam.ofText(TextBlockParam.builder()
                .text("Extract every topic and its percent weight visible in this score-breakdown image. Output the JSON array per the system prompt.")
                .build());
        return MessageCreateParams.builder()
                .model(classifyModel)
                .maxTokens(2000L)
                .system(SYSTEM_PROMPT)
                .addUserMessageOfBlockParams(List.of(imageBlock, textBlock))
                .build();
    }

    private List<TopicWeight> claudeExtract(MessageCreateParams params, String purpose) {
        if (client == null) return List.of();
        try {
            Message reply = client.messages().create(params);
            String raw = firstText(reply);
            log.info("TopicBreakdownParser ({}): Claude returned {} chars", purpose, raw == null ? 0 : raw.length());
            return parseJson(raw);
        } catch (Exception ex) {
            log.warn("TopicBreakdownParser ({}) Claude call failed: {}", purpose, ex.getMessage());
            return List.of();
        }
    }

    private String firstText(Message reply) {
        if (reply.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : reply.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString();
    }

    private List<TopicWeight> parseJson(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Matcher m = JSON_ARRAY.matcher(raw);
        if (!m.find()) return List.of();
        try {
            JsonNode arr = json.readTree(m.group());
            if (!arr.isArray()) return List.of();
            List<TopicWeight> out = new ArrayList<>();
            for (JsonNode row : arr) {
                String name = row.path("name").asText("").trim();
                int weight = row.path("weight_percent").asInt(0);
                if (name.isEmpty() || weight < 1 || weight > 100) continue;
                out.add(new TopicWeight(slugify(name), name, weight));
            }
            return out;
        } catch (Exception ex) {
            log.warn("TopicBreakdownParser: failed to parse Claude JSON: {}", ex.getMessage());
            return List.of();
        }
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

    /** Lowercase, ASCII-safe slug for the topic key. */
    private static String slugify(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT).trim();
        String dashed = lower.replaceAll("[^a-z0-9]+", "-");
        return dashed.replaceAll("^-+|-+$", "");
    }
}
