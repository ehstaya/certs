package com.sfquiz.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * If an authenticated user must change their password (e.g. just logged in with a temp password),
 * redirect them to /change-password for any GET that isn't /change-password, /logout, or static assets.
 * POSTs to /change-password and /logout are always allowed; other writes are blocked with 403 to
 * keep the API surface clean while the user is in the forced-change state.
 */
public class ForceChangePasswordFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_EXACT = Set.of(
            "/change-password",
            "/logout"
    );

    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "/css/",
            "/js/",
            "/images/",
            "/webjars/",
            "/error",
            "/favicon"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUserDetails ud) {
            if (ud.isMustChangePassword() && !isAllowed(req)) {
                if ("GET".equalsIgnoreCase(req.getMethod())) {
                    res.sendRedirect(req.getContextPath() + "/change-password");
                    return;
                }
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "You must change your password first.");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private boolean isAllowed(HttpServletRequest req) {
        String path = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        if (ALLOWED_EXACT.contains(path)) return true;
        for (String p : ALLOWED_PREFIXES) if (path.startsWith(p)) return true;
        return false;
    }
}
