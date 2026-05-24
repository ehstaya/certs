package com.sfquiz.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Converts an uploaded file's bytes to plain text by extension. Images aren't
 *  handled here — the orchestrator routes those straight to Claude vision. */
@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    public static final Set<String> TEXT_EXT = Set.of("txt", "md", "markdown");
    public static final Set<String> HTML_EXT = Set.of("html", "htm");
    public static final Set<String> PDF_EXT  = Set.of("pdf");
    public static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");

    public static final Set<String> ALL_SUPPORTED;
    static {
        ALL_SUPPORTED = new java.util.LinkedHashSet<>();
        ALL_SUPPORTED.addAll(TEXT_EXT);
        ALL_SUPPORTED.addAll(HTML_EXT);
        ALL_SUPPORTED.addAll(PDF_EXT);
        ALL_SUPPORTED.addAll(IMAGE_EXT);
    }

    public enum Kind { TEXT, HTML, PDF, IMAGE, UNSUPPORTED }

    public static Kind classify(String filename) {
        String ext = ext(filename);
        if (TEXT_EXT.contains(ext)) return Kind.TEXT;
        if (HTML_EXT.contains(ext)) return Kind.HTML;
        if (PDF_EXT.contains(ext))  return Kind.PDF;
        if (IMAGE_EXT.contains(ext)) return Kind.IMAGE;
        return Kind.UNSUPPORTED;
    }

    /** Best-effort image MIME from extension. */
    public static String imageMime(String filename) {
        String ext = ext(filename);
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    /** Returns extracted text, or null if the kind is IMAGE/UNSUPPORTED. */
    public String extractText(String filename, byte[] bytes) {
        Kind k = classify(filename);
        try {
            return switch (k) {
                case TEXT -> new String(bytes, StandardCharsets.UTF_8);
                case HTML -> Jsoup.parse(new String(bytes, StandardCharsets.UTF_8)).text();
                case PDF -> extractPdf(bytes);
                case IMAGE, UNSUPPORTED -> null;
            };
        } catch (Exception e) {
            log.warn("text extraction failed for {}: {}", filename, e.getMessage());
            return null;
        }
    }

    private String extractPdf(byte[] bytes) throws java.io.IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private static String ext(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
