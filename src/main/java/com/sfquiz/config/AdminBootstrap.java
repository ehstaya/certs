package com.sfquiz.config;

import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.service.DomainAdminService;
import com.sfquiz.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String BOOT_ACTOR = "system.bootstrap";

    private final UserService users;
    private final DomainAdminService domainAdmins;

    @Value("${app.admin.email:admin@local}")
    private String adminEmail;

    @Value("${app.admin.password:ChangeMe123!}")
    private String adminPassword;

    @Value("${app.admin.full-name:Default Admin}")
    private String adminFullName;

    public AdminBootstrap(UserService users, DomainAdminService domainAdmins) {
        this.users = users;
        this.domainAdmins = domainAdmins;
    }

    @Override
    public void run(String... args) {
        // Step 1: ensure at least one SUPERADMIN exists. The configured
        // bootstrap email is always brought to SUPERADMIN on every boot
        // (idempotent) so the operator can't accidentally lock themselves out.
        if (users.superAdminExists()) {
            boolean changed = users.ensureSuperAdminByEmail(adminEmail);
            if (changed) {
                log.warn("Bootstrap: promoted {} to SUPERADMIN (was a lower role).", adminEmail);
            } else {
                log.info("Bootstrap: SUPERADMIN already present, no action needed.");
            }
        } else if (users.findByEmail(adminEmail).isPresent()) {
            users.ensureSuperAdminByEmail(adminEmail);
            log.warn("Bootstrap: existing user {} promoted to SUPERADMIN (no super admin existed).", adminEmail);
        } else {
            users.createSuperAdmin(adminEmail, adminFullName, adminPassword);
            log.warn("\n*****************************************************\n" +
                     "Created default SUPERADMIN account.\n" +
                     "  Email:    {}\n" +
                     "  Password: {}\n" +
                     "Change this password after first sign-in.\n" +
                     "*****************************************************",
                     adminEmail, adminPassword);
        }

        // Step 2: backfill existing ADMINs (not SUPERADMIN) with assignments
        // to every active exam. Preserves their pre-multi-domain access; safe
        // to re-run because we only act on ADMINs that currently have zero
        // assignments (newly-created domain admins keep their assignments).
        int touched = 0;
        for (User u : users.listByRole(UserRole.ADMIN)) {
            if (domainAdmins.examSlugsFor(u).isEmpty()) {
                int added = domainAdmins.backfillAllExams(u, BOOT_ACTOR);
                if (added > 0) {
                    log.warn("Bootstrap: backfilled {} domain assignments for legacy ADMIN {}", added, u.getEmail());
                    touched++;
                }
            }
        }
        if (touched > 0) {
            log.warn("Bootstrap: backfilled domain assignments for {} legacy ADMIN(s).", touched);
        }
    }
}
