package com.sfquiz.service;

import com.sfquiz.entity.QuestionAction;
import com.sfquiz.entity.QuestionAction.Action;
import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.repository.QuestionActionRepository;
import com.sfquiz.repository.QuestionRepository;
import com.sfquiz.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Aggregations for the admin-activity reports:
 *
 *   • Domain admin's "My reports" — what I personally approved / rejected / edited / …
 *   • Super-admin "Question bank progress" — totals + per-cert + per-admin
 *     + per-contributor breakdowns, all over a configurable time window.
 *
 *  All queries are date-range scoped and optionally restricted to a set of
 *  exam slugs (super-admin gets the wildcard "*" set, which skips the filter). */
@Service
public class AdminActivityService {

    private final QuestionActionRepository actions;
    private final QuestionRepository questions;
    private final UserRepository users;
    private final AuthorizationService authz;

    public AdminActivityService(QuestionActionRepository actions, QuestionRepository questions,
                                UserRepository users, AuthorizationService authz) {
        this.actions = actions;
        this.questions = questions;
        this.users = users;
        this.authz = authz;
    }

    /** Counts keyed by Action type. Action enum values that didn't appear in
     *  the time window come back as 0 so templates can render every column
     *  without null-checks. */
    public static final class ActionCounts {
        private final EnumMap<Action, Long> counts = new EnumMap<>(Action.class);

        public ActionCounts() {
            for (Action a : Action.values()) counts.put(a, 0L);
        }

        public long get(Action a) { return counts.getOrDefault(a, 0L); }
        public void add(Action a, long delta) { counts.merge(a, delta, Long::sum); }

        // Convenience getters for Thymeleaf (uses bean-style property access).
        public long getApprove()         { return get(Action.APPROVE); }
        public long getReject()          { return get(Action.REJECT); }
        public long getEdit()            { return get(Action.EDIT); }
        public long getRetire()          { return get(Action.RETIRE); }
        public long getRestore()         { return get(Action.RESTORE); }
        public long getPermanentDelete() { return get(Action.PERMANENT_DELETE); }
        public long getSendBack()        { return get(Action.SEND_BACK); }
        public long getManualCreate()    { return get(Action.MANUAL_CREATE); }
        public long getTotal() {
            long s = 0L;
            for (Long v : counts.values()) s += v;
            return s;
        }
    }

    /** Per-row in the "per admin" breakdown table. {@code fullName} +
     *  {@code roleLabel} + {@code area} let super admins see who the
     *  active admins are at a glance — name first, with the area (cert
     *  slugs they govern, or "All certs" for SUPERADMINs) for context.
     *  Falls back to email if a User row can't be found for the email
     *  (e.g. the admin account was deleted after they acted). */
    public record AdminRow(String adminEmail, String fullName, String roleLabel,
                           String area, ActionCounts counts) {
        /** Convenience: prefer the full name, fall back to email so the
         *  template never renders a blank cell. */
        public String displayName() {
            return (fullName != null && !fullName.isBlank()) ? fullName : adminEmail;
        }
    }

    /** Per-row in the "per cert" breakdown table. */
    public record CertRow(String examSlug, ActionCounts counts, long uploaded) {}

    /** Per-row in the "per contributor" upload breakdown. */
    public record ContributorRow(String email, long uploaded) {}

    /** Domain admin's personal report ("My reports → as domain admin"). */
    public record MyAdminReport(ActionCounts counts, List<QuestionAction> recent,
                                Instant from, Instant to) {}

    /** Super-admin's question-bank progress report. */
    public record BankProgressReport(
            Instant from, Instant to,
            ActionCounts totals,
            long uploadedTotal,
            List<AdminRow> perAdmin,
            List<CertRow> perCert,
            List<ContributorRow> perContributor) {}

    /** ----- Domain-admin personal view ---------------------------------- */

    public MyAdminReport myReport(String adminEmail, Instant from, Instant to) {
        ActionCounts counts = new ActionCounts();
        for (Object[] row : actions.aggregateByActionForAdmin(adminEmail, from, to)) {
            Action a = (Action) row[0];
            long c = ((Number) row[1]).longValue();
            counts.add(a, c);
        }
        List<QuestionAction> recent = actions.recentForAdmin(adminEmail, from, to, PageRequest.of(0, 50));
        return new MyAdminReport(counts, recent, from, to);
    }

    /** ----- Super-admin bank-progress view ------------------------------ */

    public BankProgressReport bankProgress(Instant from, Instant to, Set<String> managedSlugs,
                                           String filterAdminEmail) {
        boolean scoped = managedSlugs != null && !managedSlugs.contains("*");
        Collection<String> slugs = scoped ? managedSlugs : List.of("__unused__");

        // Per-admin × action grid.
        Map<String, ActionCounts> byAdmin = new LinkedHashMap<>();
        for (Object[] row : actions.aggregateByAdminAndAction(from, to, scoped, slugs)) {
            String email = (String) row[0];
            Action a = (Action) row[1];
            long c = ((Number) row[2]).longValue();
            if (filterAdminEmail != null && !filterAdminEmail.isBlank()
                    && !filterAdminEmail.equalsIgnoreCase(email)) {
                continue;
            }
            byAdmin.computeIfAbsent(email, k -> new ActionCounts()).add(a, c);
        }
        // Stable: sort by total descending, then email. Enrich each row with
        // the admin's full name + role + governed-cert "area" so super admins
        // can see "Tariq Yaqub (SUPERADMIN — All certs)" vs.
        // "Jane Doe (ADMIN — aws-saa, salesforce-admin)" at a glance.
        List<AdminRow> perAdmin = new ArrayList<>();
        for (var e : byAdmin.entrySet()) {
            String email = e.getKey();
            User u = (email == null) ? null : users.findByEmailIgnoreCase(email).orElse(null);
            String fullName = (u != null) ? u.getFullName() : null;
            String roleLabel;
            String area;
            if (u == null) {
                roleLabel = "(unknown)";
                area = "—";
            } else if (u.getRole() == UserRole.SUPERADMIN) {
                roleLabel = "SUPERADMIN";
                area = "All certs";
            } else if (u.getRole() == UserRole.ADMIN) {
                roleLabel = "ADMIN";
                Set<String> assigned = authz.managedExamSlugs(u);
                area = (assigned == null || assigned.isEmpty())
                        ? "(no certs assigned)"
                        : String.join(", ", new java.util.TreeSet<>(assigned));
            } else {
                roleLabel = u.getRole().name();
                area = "—";
            }
            perAdmin.add(new AdminRow(email, fullName, roleLabel, area, e.getValue()));
        }
        perAdmin.sort((x, y) -> Long.compare(y.counts().getTotal(), x.counts().getTotal()));

        // Per-cert × action grid + uploaded counts.
        Map<String, ActionCounts> byCertActions = new TreeMap<>();
        for (Object[] row : actions.aggregateByExamAndAction(from, to, scoped, slugs)) {
            String slug = (String) row[0];
            Action a = (Action) row[1];
            long c = ((Number) row[2]).longValue();
            byCertActions.computeIfAbsent(slug == null ? "(unknown)" : slug,
                    k -> new ActionCounts()).add(a, c);
        }
        Map<String, Long> byCertUploaded = new HashMap<>();
        for (Object[] row : questions.uploadedByExamInRange(from, to, scoped, slugs)) {
            String slug = (String) row[0];
            long c = ((Number) row[1]).longValue();
            byCertUploaded.put(slug == null ? "(unknown)" : slug, c);
            // Make sure the slug appears in the per-cert row list even if no
            // actions happened on it during the window.
            byCertActions.computeIfAbsent(slug == null ? "(unknown)" : slug, k -> new ActionCounts());
        }
        List<CertRow> perCert = new ArrayList<>();
        for (var e : byCertActions.entrySet()) {
            perCert.add(new CertRow(e.getKey(), e.getValue(), byCertUploaded.getOrDefault(e.getKey(), 0L)));
        }

        // Per-contributor upload breakdown.
        List<ContributorRow> perContributor = new ArrayList<>();
        for (Object[] row : questions.uploadedByCreatorInRange(from, to, scoped, slugs)) {
            String email = (String) row[0];
            long c = ((Number) row[1]).longValue();
            perContributor.add(new ContributorRow(email == null ? "(unknown)" : email, c));
        }
        perContributor.sort((x, y) -> Long.compare(y.uploaded(), x.uploaded()));

        long uploadedTotal = questions.countUploadedInRange(from, to, scoped, slugs);

        // Totals row = sum of all admin counts.
        ActionCounts totals = new ActionCounts();
        for (AdminRow r : perAdmin) {
            for (Action a : Action.values()) totals.add(a, r.counts().get(a));
        }

        return new BankProgressReport(from, to, totals, uploadedTotal,
                perAdmin, perCert, perContributor);
    }

    /** ----- Date-range helpers ------------------------------------------ */

    /** Parse "YYYY-MM-DD" → start-of-day UTC. Falls back to {@code fallback}
     *  if the input is null/blank/unparseable so the report still renders
     *  something useful on a fresh form load. */
    public static Instant parseStart(String iso, Instant fallback) {
        if (iso == null || iso.isBlank()) return fallback;
        try {
            return LocalDate.parse(iso).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** Parse "YYYY-MM-DD" → start of the NEXT day UTC, so the range is
     *  inclusive on the end date. */
    public static Instant parseEndExclusive(String iso, Instant fallback) {
        if (iso == null || iso.isBlank()) return fallback;
        try {
            return LocalDate.parse(iso).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** Default range = last 30 days, ending today (inclusive). */
    public static Instant defaultFrom() {
        return LocalDate.now(ZoneOffset.UTC).minusDays(30).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
    public static Instant defaultTo() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** All admin emails that appear in QuestionAction rows over the given window.
     *  Used to populate the "filter by admin" dropdown on the bank-progress report. */
    public List<String> distinctAdminEmails(Instant from, Instant to, Set<String> managedSlugs) {
        boolean scoped = managedSlugs != null && !managedSlugs.contains("*");
        Collection<String> slugs = scoped ? managedSlugs : List.of("__unused__");
        List<String> out = new ArrayList<>();
        for (Object[] row : actions.aggregateByAdminAndAction(from, to, scoped, slugs)) {
            String email = (String) row[0];
            if (email != null && !out.contains(email)) out.add(email);
        }
        Collections.sort(out);
        return out;
    }
}
