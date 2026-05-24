package com.sfquiz.service;

import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
import com.sfquiz.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/** Sends one-off notifications to every active admin. Async so callers aren't
 *  blocked on SMTP — failures are logged but never thrown. */
@Service
public class AdminNotifier {

    private static final Logger log = LoggerFactory.getLogger(AdminNotifier.class);

    private final UserRepository users;
    private final EmailService email;
    private final String baseUrl;

    public AdminNotifier(UserRepository users,
                         EmailService email,
                         @Value("${app.base-url:http://localhost:8095}") String baseUrl) {
        this.users = users;
        this.email = email;
        this.baseUrl = baseUrl;
    }

    /** New user just hit /register and is waiting for approval at /admin. */
    @Async
    public void notifyNewRegistration(String registeredEmail, String fullName) {
        List<User> admins = activeAdmins();
        if (admins.isEmpty()) return;
        String subject = "[SF Admin Quiz] New registration pending: " + registeredEmail;
        String body = "A new user just registered and is waiting for approval.\n\n"
                + "Email:  " + registeredEmail + "\n"
                + "Name:   " + (fullName == null || fullName.isBlank() ? "(not provided)" : fullName) + "\n\n"
                + "Approve or reject here: " + baseUrl + "/admin\n";
        for (User a : admins) {
            email.send(a.getEmail(), subject, body);
        }
    }

    /** Extraction for an upload finished with at least one new pending question. */
    @Async
    public void notifyExtractionDone(String filename, int extracted, int imported) {
        if (imported <= 0) return; // skip noise: duplicates or empty extractions
        List<User> admins = activeAdmins();
        if (admins.isEmpty()) return;
        String subject = "[SF Admin Quiz] " + imported + " new question(s) from " + filename;
        StringBuilder body = new StringBuilder()
                .append("Extraction finished for upload: ").append(filename).append("\n\n")
                .append("Imported as pending: ").append(imported).append("\n");
        if (extracted > imported) {
            body.append("Extracted total:     ").append(extracted)
                .append("  (").append(extracted - imported).append(" skipped — already present or invalid)\n");
        }
        body.append("\nReview & approve: ").append(baseUrl).append("/admin/questions\n");
        for (User a : admins) {
            email.send(a.getEmail(), subject, body.toString());
        }
    }

    private List<User> activeAdmins() {
        try {
            return users.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE);
        } catch (Exception e) {
            log.warn("activeAdmins lookup failed: {}", e.getMessage());
            return List.of();
        }
    }
}
