package com.sfquiz.controller;

import com.sfquiz.security.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || !(a.getPrincipal() instanceof AppUserDetails ud)) {
            return Map.of("authenticated", false);
        }
        return Map.of(
                "authenticated", true,
                "email", ud.getEmail(),
                "fullName", ud.getFullName() == null ? "" : ud.getFullName(),
                "role", ud.getUser().getRole().name(),
                "mustChangePassword", ud.isMustChangePassword()
        );
    }
}
