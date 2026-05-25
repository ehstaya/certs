package com.sfquiz.controller;

import com.sfquiz.config.ServerInfo;
import com.sfquiz.security.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MeController {

    private final ServerInfo serverInfo;

    public MeController(ServerInfo serverInfo) {
        this.serverInfo = serverInfo;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        // bootId is on every response so the client can detect a server restart
        // and wipe any persisted countdown/timer state that's now stale.
        Map<String, Object> out = new LinkedHashMap<>();
        if (a == null || !a.isAuthenticated() || !(a.getPrincipal() instanceof AppUserDetails ud)) {
            out.put("authenticated", false);
            out.put("bootId", serverInfo.getBootId());
            return out;
        }
        out.put("authenticated", true);
        out.put("email", ud.getEmail());
        out.put("fullName", ud.getFullName() == null ? "" : ud.getFullName());
        out.put("role", ud.getUser().getRole().name());
        out.put("mustChangePassword", ud.isMustChangePassword());
        out.put("bootId", serverInfo.getBootId());
        return out;
    }
}
