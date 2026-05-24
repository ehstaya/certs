package com.sfquiz.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@sfquiz.local}")
    private String fromAddress;

    @Value("${app.mail.console-fallback:true}")
    private boolean consoleFallback;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @PostConstruct
    void info() {
        log.info("EmailService ready. From={}, consoleFallback={}", fromAddress, consoleFallback);
    }

    public void send(String to, String subject, String body) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        try {
            mailSender.send(msg);
            log.info("Sent email to={} subject=\"{}\"", to, subject);
        } catch (Exception e) {
            log.warn("Mail send failed (to={}): {}", to, e.getMessage());
            if (!consoleFallback) {
                throw new IllegalStateException("Mail send failed", e);
            }
        }
        if (consoleFallback) {
            log.info("\n========== EMAIL ==========\nFrom: {}\nTo: {}\nSubject: {}\n\n{}\n===========================\n",
                    fromAddress, to, subject, body);
        }
    }
}
