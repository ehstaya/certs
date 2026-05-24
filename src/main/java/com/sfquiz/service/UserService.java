package com.sfquiz.service;

import com.sfquiz.entity.PasswordResetToken;
import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
import com.sfquiz.repository.PasswordResetTokenRepository;
import com.sfquiz.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final char[] TEMP_PW_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private static final int TEMP_PW_LENGTH = 12;
    private static final long RESET_TOKEN_HOURS = 1;

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final EmailService email;
    private final AdminNotifier adminNotifier;

    /** Direct query repos used to clean up FK-bearing rows before deleting a
     *  user. Injected lazily via setter to avoid making the User-management
     *  service depend on the whole quiz/voting stack at construction. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sfquiz.repository.TestAttemptRepository testAttempts;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sfquiz.repository.QuestionVoteRepository questionVotes;
    private final SecureRandom rng = new SecureRandom();

    @Value("${app.base-url:http://localhost:8095}")
    private String baseUrl;

    public UserService(UserRepository users,
                       PasswordResetTokenRepository tokens,
                       PasswordEncoder encoder,
                       EmailService email,
                       AdminNotifier adminNotifier) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.email = email;
        this.adminNotifier = adminNotifier;
    }

    public Optional<User> findByEmail(String email) {
        return users.findByEmailIgnoreCase(email);
    }

    public List<User> listPending() {
        return users.findByStatusOrderByCreatedAtAsc(UserStatus.PENDING);
    }

    public List<User> listAll() {
        return users.findAllByOrderByCreatedAtDesc();
    }

    /** Admin shortcut: create an ACTIVE user in one step (skips the
     *  /register-then-approve dance) with a temp password the admin can share
     *  manually. The user must change the password on first sign-in. */
    public PasswordIssued createUserByAdmin(String emailAddr, String fullName, UserRole role) {
        if (emailAddr == null || emailAddr.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        String normalized = emailAddr.trim().toLowerCase();
        if (!normalized.contains("@") || normalized.contains(" ")) {
            throw new IllegalArgumentException("That doesn't look like a valid email address.");
        }
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new IllegalArgumentException("An account with that email already exists.");
        }
        String tempPassword = generateTempPassword();
        User u = new User();
        u.setEmail(normalized);
        u.setFullName(fullName == null ? "" : fullName.trim());
        u.setRole(role == null ? UserRole.USER : role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(encoder.encode(tempPassword));
        u.setMustChangePassword(true);
        u.setCreatedAt(Instant.now());
        u.setApprovedAt(Instant.now());
        users.save(u);
        log.info("Admin created user {} (role={}, id={})", u.getEmail(), u.getRole(), u.getId());
        return new PasswordIssued(u, tempPassword);
    }

    public User register(String emailAddr, String fullName) {
        String normalized = emailAddr.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new IllegalArgumentException("An account with that email already exists.");
        }
        User u = new User();
        u.setEmail(normalized);
        u.setFullName(fullName == null ? "" : fullName.trim());
        u.setRole(UserRole.USER);
        u.setStatus(UserStatus.PENDING);
        u.setMustChangePassword(false);
        u.setCreatedAt(Instant.now());
        users.save(u);
        log.info("Registered new user: {}", normalized);
        adminNotifier.notifyNewRegistration(u.getEmail(), u.getFullName());
        return u;
    }

    /** Result of an admin password operation (approve / reset). The plaintext
     *  temp password is returned so the admin UI can display it once — it is
     *  NEVER persisted in plaintext. */
    public record PasswordIssued(User user, String tempPassword) {}

    public PasswordIssued approve(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getStatus() == UserStatus.ACTIVE) {
            throw new IllegalStateException("User is already active");
        }
        String tempPassword = generateTempPassword();
        u.setPasswordHash(encoder.encode(tempPassword));
        u.setStatus(UserStatus.ACTIVE);
        u.setMustChangePassword(true);
        u.setApprovedAt(Instant.now());
        users.save(u);

        String body = "Hi " + safeName(u) + ",\n\n" +
                "Your account on Salesforce Admin Quiz has been approved.\n\n" +
                "Sign in here: " + baseUrl + "/login\n" +
                "Email:           " + u.getEmail() + "\n" +
                "Temporary password: " + tempPassword + "\n\n" +
                "You will be required to set a new password on first sign-in.\n";
        // Best-effort email; failures don't block the approval — the admin can
        // share the temp password directly from the /admin page banner.
        try {
            email.send(u.getEmail(), "Your Salesforce Admin Quiz account is approved", body);
        } catch (Exception ex) {
            log.warn("Approval email to {} could not be sent ({}); password is shown in admin UI instead",
                    u.getEmail(), ex.getMessage());
        }
        log.info("Approved user {} (id={})", u.getEmail(), u.getId());
        return new PasswordIssued(u, tempPassword);
    }

    /** Generate a new temp password for an ACTIVE user (e.g. they lost theirs).
     *  Forces the user to change it on next sign-in. */
    public PasswordIssued resetPasswordByAdmin(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE users can have their password reset.");
        }
        String tempPassword = generateTempPassword();
        u.setPasswordHash(encoder.encode(tempPassword));
        u.setMustChangePassword(true);
        users.save(u);

        String body = "Hi " + safeName(u) + ",\n\n" +
                "An admin issued a new temporary password for your Salesforce Admin Quiz account.\n\n" +
                "Sign in here: " + baseUrl + "/login\n" +
                "Email:           " + u.getEmail() + "\n" +
                "Temporary password: " + tempPassword + "\n\n" +
                "You will be required to set a new password on first sign-in.\n";
        try {
            email.send(u.getEmail(), "Salesforce Admin Quiz — new temporary password", body);
        } catch (Exception ex) {
            log.warn("Reset email to {} could not be sent ({}); password is shown in admin UI instead",
                    u.getEmail(), ex.getMessage());
        }
        log.info("Admin reset password for user {} (id={})", u.getEmail(), u.getId());
        return new PasswordIssued(u, tempPassword);
    }

    public User reject(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        u.setStatus(UserStatus.REJECTED);
        users.save(u);
        log.info("Rejected user {}", u.getEmail());
        return u;
    }

    public User disable(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        u.setStatus(UserStatus.DISABLED);
        users.save(u);
        log.info("Disabled user {}", u.getEmail());
        return u;
    }

    public User reactivate(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        u.setStatus(UserStatus.ACTIVE);
        users.save(u);
        log.info("Reactivated user {}", u.getEmail());
        return u;
    }

    public void changePassword(String email, String currentPassword, String newPassword) {
        User u = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getPasswordHash() == null || !encoder.matches(currentPassword, u.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        validatePasswordStrength(newPassword);
        if (encoder.matches(newPassword, u.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from the current one.");
        }
        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(false);
        users.save(u);
        log.info("Password changed for {}", u.getEmail());
    }

    public void initiatePasswordReset(String emailAddr) {
        String normalized = emailAddr.trim().toLowerCase();
        Optional<User> opt = users.findByEmailIgnoreCase(normalized);
        if (opt.isEmpty() || opt.get().getStatus() != UserStatus.ACTIVE) {
            log.info("Password reset requested for unknown/inactive email: {}", normalized);
            return; // silent — don't leak which emails exist
        }
        User u = opt.get();
        String token = UUID.randomUUID().toString().replace("-", "");
        PasswordResetToken prt = new PasswordResetToken();
        prt.setUser(u);
        prt.setToken(token);
        prt.setExpiresAt(Instant.now().plus(RESET_TOKEN_HOURS, ChronoUnit.HOURS));
        prt.setUsed(false);
        tokens.save(prt);

        String link = baseUrl + "/reset-password?token=" + token;
        String body = "Hi " + safeName(u) + ",\n\n" +
                "We received a request to reset your password.\n" +
                "Use the link below within " + RESET_TOKEN_HOURS + " hour(s):\n\n" +
                link + "\n\n" +
                "If you did not request this, you can safely ignore this email.\n";
        email.send(u.getEmail(), "Reset your Salesforce Admin Quiz password", body);
    }

    public void resetPasswordWithToken(String token, String newPassword) {
        PasswordResetToken prt = tokens.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("This reset link is invalid or has already been used."));
        if (prt.isUsed()) {
            throw new IllegalArgumentException("This reset link has already been used.");
        }
        if (prt.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("This reset link has expired. Please request a new one.");
        }
        validatePasswordStrength(newPassword);
        User u = prt.getUser();
        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(false);
        if (u.getStatus() == UserStatus.PENDING) {
            // Belt and suspenders — should not happen
            u.setStatus(UserStatus.ACTIVE);
        }
        users.save(u);
        prt.setUsed(true);
        tokens.save(prt);
        log.info("Password reset via token for {}", u.getEmail());
    }

    /** "Admin-level" = ADMIN or SUPERADMIN. Used by AdminBootstrap to decide
     *  whether the initial admin still needs to be seeded. */
    public boolean adminExists() {
        return users.existsByRole(UserRole.ADMIN) || users.existsByRole(UserRole.SUPERADMIN);
    }

    public boolean superAdminExists() {
        return users.existsByRole(UserRole.SUPERADMIN);
    }

    private long adminOrSuperCount() {
        return users.countByRole(UserRole.ADMIN) + users.countByRole(UserRole.SUPERADMIN);
    }

    /** Promote an ACTIVE user to ADMIN so they can approve questions / manage users.
     *  Idempotent: returns immediately if the user is already ADMIN. */
    /** Hard-delete a user account permanently — works for USER, VERIFIER, and
     *  ADMIN. Guarded against self-delete and last-admin-delete so the system
     *  can't be locked out. Cascades to: password-reset tokens, that user's
     *  votes, and that user's test attempts. Free-text email references on
     *  StudyUpload / Question.retiredByEmail are NOT touched (they're audit
     *  trail, not relational FKs). */
    @org.springframework.transaction.annotation.Transactional
    public void deleteUser(Long userId, String callerEmail) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (callerEmail != null && callerEmail.equalsIgnoreCase(u.getEmail())) {
            throw new IllegalStateException("You can't delete your own account.");
        }
        if ((u.getRole() == UserRole.ADMIN || u.getRole() == UserRole.SUPERADMIN)
                && adminOrSuperCount() <= 1) {
            throw new IllegalStateException("Can't delete the last remaining admin / super admin.");
        }
        // Clean up FK-bearing rows first so the User delete doesn't blow up
        // on a constraint violation.
        if (questionVotes != null) {
            for (com.sfquiz.entity.QuestionVote v : questionVotes.findAll()) {
                if (v.getUser() != null && u.getId().equals(v.getUser().getId())) {
                    questionVotes.delete(v);
                }
            }
        }
        if (testAttempts != null) {
            for (com.sfquiz.entity.TestAttempt a : testAttempts.findByUserOrderByFinishedAtDesc(u)) {
                testAttempts.delete(a);
            }
        }
        // Password-reset tokens: delete by user via the repo.
        tokens.deleteAll(tokens.findAll().stream()
                .filter(t -> t.getUser() != null && u.getId().equals(t.getUser().getId()))
                .toList());
        log.warn("Deleting user {} (id={}, role={}) by {}", u.getEmail(), u.getId(), u.getRole(), callerEmail);
        users.delete(u);
    }

    public User promoteToAdmin(Long userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getRole() == UserRole.ADMIN) {
            return u;  // already an admin — no-op
        }
        if (u.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE users can be promoted to admin.");
        }
        u.setRole(UserRole.ADMIN);
        users.save(u);

        String body = "Hi " + safeName(u) + ",\n\n"
                + "You've been granted admin access on Salesforce Admin Quiz. "
                + "You can now approve crawler-imported questions and manage users.\n\n"
                + "Question review:  " + baseUrl + "/admin/questions\n"
                + "User admin:       " + baseUrl + "/admin\n";
        email.send(u.getEmail(), "You're now an admin on Salesforce Admin Quiz", body);
        log.info("Promoted user {} to ADMIN", u.getEmail());
        return u;
    }

    /** Demote an admin back to a regular user. Refuses to demote the caller or
     *  the last admin/super admin (would lock the system out). SUPERADMIN can
     *  also be demoted via this method (handled identically). */
    public User demoteFromAdmin(Long userId, String callerEmail) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getRole() != UserRole.ADMIN && u.getRole() != UserRole.SUPERADMIN) {
            return u;  // already not an admin
        }
        if (callerEmail != null && callerEmail.equalsIgnoreCase(u.getEmail())) {
            throw new IllegalStateException("You can't demote yourself.");
        }
        if (adminOrSuperCount() <= 1) {
            throw new IllegalStateException("Can't demote the last remaining admin / super admin.");
        }
        u.setRole(UserRole.USER);
        users.save(u);
        log.info("Demoted user {} to USER (by {})", u.getEmail(), callerEmail);
        return u;
    }

    /** Promote an ACTIVE user (typically an ADMIN) to SUPERADMIN. Only the
     *  caller-side controller should invoke this, and only when the caller is
     *  themselves a SUPERADMIN — the controller enforces that via
     *  /admin/users/** routing rules. Idempotent. */
    public User promoteToSuperAdmin(Long userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getRole() == UserRole.SUPERADMIN) {
            return u; // already super admin
        }
        if (u.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE users can be promoted to super admin.");
        }
        u.setRole(UserRole.SUPERADMIN);
        users.save(u);
        log.warn("Promoted user {} to SUPERADMIN", u.getEmail());
        return u;
    }

    /** Step a SUPERADMIN down to plain ADMIN (still admin-capable but no
     *  longer able to manage users or assign domain admins). Refuses to
     *  step down the caller or the last super admin (would orphan the
     *  user-management UI). */
    public User demoteSuperAdminToAdmin(Long userId, String callerEmail) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getRole() != UserRole.SUPERADMIN) {
            return u; // not a super admin
        }
        if (callerEmail != null && callerEmail.equalsIgnoreCase(u.getEmail())) {
            throw new IllegalStateException("You can't change your own super-admin status.");
        }
        if (users.countByRole(UserRole.SUPERADMIN) <= 1) {
            throw new IllegalStateException("Can't demote the last super admin — promote someone else first.");
        }
        u.setRole(UserRole.ADMIN);
        users.save(u);
        log.warn("Demoted SUPERADMIN {} → ADMIN (by {})", u.getEmail(), callerEmail);
        return u;
    }

    public User createAdmin(String emailAddr, String fullName, String password) {
        return upsertWithRole(emailAddr, fullName, password, UserRole.ADMIN);
    }

    /** Create / upsert the bootstrap SUPERADMIN. Called from AdminBootstrap
     *  on a fresh DB so there's always exactly one super admin able to assign
     *  domain admins. */
    public User createSuperAdmin(String emailAddr, String fullName, String password) {
        return upsertWithRole(emailAddr, fullName, password, UserRole.SUPERADMIN);
    }

    private User upsertWithRole(String emailAddr, String fullName, String password, UserRole role) {
        String normalized = emailAddr.trim().toLowerCase();
        User u = users.findByEmailIgnoreCase(normalized).orElseGet(User::new);
        u.setEmail(normalized);
        u.setFullName(fullName);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setMustChangePassword(false);
        u.setPasswordHash(encoder.encode(password));
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setApprovedAt(Instant.now());
        return users.save(u);
    }

    /** Idempotent: ensures the user identified by {@code emailAddr} is a
     *  SUPERADMIN. Used by AdminBootstrap on every boot so the configured
     *  bootstrap email keeps super-admin access even if it was demoted by
     *  hand. No-op if the user doesn't exist. */
    public boolean ensureSuperAdminByEmail(String emailAddr) {
        if (emailAddr == null || emailAddr.isBlank()) return false;
        Optional<User> opt = users.findByEmailIgnoreCase(emailAddr.trim());
        if (opt.isEmpty()) return false;
        User u = opt.get();
        if (u.getRole() == UserRole.SUPERADMIN) return false;
        u.setRole(UserRole.SUPERADMIN);
        if (u.getStatus() != UserStatus.ACTIVE) {
            u.setStatus(UserStatus.ACTIVE);
        }
        users.save(u);
        log.warn("Bootstrap: ensured {} is a SUPERADMIN", u.getEmail());
        return true;
    }

    public List<User> listByRole(UserRole role) {
        return users.findAll().stream().filter(u -> u.getRole() == role).toList();
    }

    private void validatePasswordStrength(String pw) {
        if (pw == null || pw.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        if (pw.length() > 100) {
            throw new IllegalArgumentException("Password is too long.");
        }
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PW_LENGTH);
        for (int i = 0; i < TEMP_PW_LENGTH; i++) {
            sb.append(TEMP_PW_ALPHABET[rng.nextInt(TEMP_PW_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private String safeName(User u) {
        return (u.getFullName() == null || u.getFullName().isBlank()) ? "there" : u.getFullName();
    }
}
