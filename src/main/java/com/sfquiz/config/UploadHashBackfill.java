package com.sfquiz.config;

import com.sfquiz.entity.StudyUpload;
import com.sfquiz.repository.StudyUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** One-shot startup task: compute and persist {@code content_hash} for every
 *  {@link StudyUpload} row that was created before the hash column existed.
 *  Without this, the "re-upload detected" pill never lights up against
 *  uploads that happened before the duplicate-detection deploy — the lookup
 *  filters by {@code content_hash = ?} and pre-deploy rows have NULL.
 *
 *  Memory-safe: streams through rows one at a time so only a single upload's
 *  byte[] is in heap at any moment. Safe to re-run on every boot — does
 *  nothing on rows whose hash is already set. */
@Component
@Order(50) // After Hibernate schema migration, before DataSeeder
public class UploadHashBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UploadHashBackfill.class);

    private final StudyUploadRepository uploads;

    public UploadHashBackfill(StudyUploadRepository uploads) {
        this.uploads = uploads;
    }

    @Override
    public void run(String... args) {
        // Pull the full row set but only the ids first — we hydrate one
        // row at a time inside the loop so we don't pin every byte[] in
        // memory simultaneously.
        List<Long> ids = uploads.findAll().stream()
                .filter(u -> u.getContentHash() == null && u.getContent() != null)
                .map(StudyUpload::getId)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        log.info("UploadHashBackfill: computing content_hash for {} legacy upload(s)", ids.size());
        int done = 0;
        for (Long id : ids) {
            if (id == null) continue;
            final Long uploadId = id;
            try {
                uploads.findById(uploadId).ifPresent(u -> {
                    if (u.getContentHash() != null || u.getContent() == null) return;
                    u.setContentHash(sha256(u.getContent()));
                    uploads.save(u);
                });
                done++;
            } catch (Exception e) {
                log.warn("UploadHashBackfill: failed to hash upload id={}: {}", id, e.getMessage());
            }
        }
        log.info("UploadHashBackfill: backfilled {} of {} upload(s)", done, ids.size());
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
