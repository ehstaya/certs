package com.sfquiz.config;

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

    private final UserService users;

    @Value("${app.admin.email:admin@local}")
    private String adminEmail;

    @Value("${app.admin.password:ChangeMe123!}")
    private String adminPassword;

    @Value("${app.admin.full-name:Default Admin}")
    private String adminFullName;

    public AdminBootstrap(UserService users) {
        this.users = users;
    }

    @Override
    public void run(String... args) {
        if (users.adminExists()) {
            log.info("Admin account already exists; skipping bootstrap.");
            return;
        }
        users.createAdmin(adminEmail, adminFullName, adminPassword);
        log.warn("\n*****************************************************\n" +
                 "Created default ADMIN account.\n" +
                 "  Email:    {}\n" +
                 "  Password: {}\n" +
                 "Change this password after first sign-in.\n" +
                 "*****************************************************",
                 adminEmail, adminPassword);
    }
}
