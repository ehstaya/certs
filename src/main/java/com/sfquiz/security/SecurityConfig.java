package com.sfquiz.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authProvider(CustomUserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(encoder);
        return p;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   DaoAuthenticationProvider authProvider) throws Exception {
        http
            .authenticationProvider(authProvider)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/login", "/logout",
                        "/forgot-password",
                        "/reset-password",
                        "/css/**", "/js/**", "/images/**", "/webjars/**",
                        "/favicon.ico", "/error"
                ).permitAll()
                // Self-signup is disabled — accounts are issued by the
                // SUPERADMIN via /admin/users. Both the sign-in link and
                // the route itself are removed so a stale bookmark or
                // crawled URL can't create accounts behind your back.
                .requestMatchers("/register").denyAll()
                // User-management, cross-cert reports, and certification
                // management are all SUPERADMIN-only — domain admins can
                // only manage questions on the exam(s) they govern.
                .requestMatchers("/admin/users/**").hasRole("SUPERADMIN")
                .requestMatchers("/admin/certifications/**").hasRole("SUPERADMIN")
                // Reports are open to BOTH ADMIN and SUPERADMIN. Domain admins
                // see results auto-scoped to the cert(s) they govern
                // (enforced server-side by AuthorizationService inside each
                // ReportsController handler). Super admins see everything.
                .requestMatchers("/admin/reports/**").hasAnyRole("ADMIN", "SUPERADMIN")
                // The /admin landing itself is the super-admin dashboard.
                // Domain admins land on /admin/questions directly via the
                // top nav and never see /admin.
                .requestMatchers("/admin").hasRole("SUPERADMIN")
                // /admin/questions/** (the review queue) is the only admin
                // area open to domain admins. Per-question scoping (which
                // exam they govern) is enforced server-side by
                // AuthorizationService.canManageQuestion / canManageExam.
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "SUPERADMIN")
                // /uploads/** is open to any authenticated user — non-admins
                // can submit study material; the controller scopes the listing
                // and enforces ownership on delete/retry/download.
                .requestMatchers("/uploads/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("email")
                .passwordParameter("password")
                // Role-aware landing: SUPERADMIN goes to /admin (their
                // dashboard), everyone else (USER, VERIFIER, ADMIN) lands
                // on the exam picker. Domain admins reach their review
                // queue from the top-nav "Questions" link — they get to
                // see the user-facing /exams.html first so they can
                // self-test too.
                .successHandler((req, res, authn) -> {
                    boolean isSuper = authn.getAuthorities().stream()
                            .anyMatch(a -> "ROLE_SUPERADMIN".equals(a.getAuthority()));
                    res.sendRedirect(isSuper ? "/admin" : "/");
                })
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .addFilterAfter(new ForceChangePasswordFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
