package com.sfquiz.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sfquiz.entity.Choice;
import com.sfquiz.entity.Exam;
import com.sfquiz.entity.ExamTopic;
import com.sfquiz.entity.Question;
import com.sfquiz.repository.ExamRepository;
import com.sfquiz.repository.ExamTopicRepository;
import com.sfquiz.repository.QuestionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Multi-exam seeder. Scans seed/*.json — each file is one exam:
 *   { slug, name, description, questionsPerSession, durationMinutes, sortOrder, questions:[...] }
 * Exams are upserted by slug; questions upserted by (exam, number). Choices are
 * only written on the initial insert so their IDs stay stable for live sessions.
 *
 * Migration of pre-multi-exam databases: legacy questions (exam_id NULL) are
 * assigned to the "salesforce-admin" exam, and the old global-unique index on
 * questions.number is dropped in favour of the composite (exam_id, number).
 */
@Component
@Order(10)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String DEFAULT_EXAM_SLUG = "salesforce-admin";

    private final QuestionRepository repo;
    private final ExamRepository exams;
    private final ExamTopicRepository examTopics;

    @PersistenceContext
    private EntityManager em;

    public DataSeeder(QuestionRepository repo, ExamRepository exams, ExamTopicRepository examTopics) {
        this.repo = repo;
        this.exams = exams;
        this.examTopics = examTopics;
    }

    /** Per-exam topic mixes from the salesforcetime.com score calculators.
     *  Each list sums to 100. Stored as a slug→list map so adding a new exam
     *  is a one-line change. */
    private static final java.util.Map<String, List<TopicSeed>> TOPIC_MIXES = java.util.Map.of(
            "salesforce-admin", List.of(
                    new TopicSeed("config-setup",        "Configuration and Setup",                  15, 10),
                    new TopicSeed("object-manager",      "Object Manager and Lightning App Builder", 15, 20),
                    new TopicSeed("sales-marketing",     "Sales and Marketing Applications",         10, 30),
                    new TopicSeed("service-support",     "Service and Support Applications",         10, 40),
                    new TopicSeed("productivity",        "Productivity and Collaboration",           10, 50),
                    new TopicSeed("data-analytics",      "Data and Analytics Management",            17, 60),
                    new TopicSeed("automation",          "Automation",                               15, 70),
                    new TopicSeed("agentforce-ai",       "Agentforce AI",                             8, 80)
            ),
            "mulesoft-platform-architect", List.of(
                    new TopicSeed("network-basics",      "Explaining application network basics",                          7, 10),
                    new TopicSeed("org-platform-foundations", "Establishing organizational and platform foundations",     10, 20),
                    new TopicSeed("designing-sharing-apis", "Designing and sharing APIs",                                 10, 30),
                    new TopicSeed("system-process-experience", "Designing APIs using System, Process, and Experience Layers", 12, 40),
                    new TopicSeed("governing-anypoint",  "Governing web APIs on Anypoint Platform",                       17, 50),
                    new TopicSeed("architecting-deploying", "Architecting and deploying API implementations",             11, 60),
                    new TopicSeed("deploying-cloudhub",  "Deploying API implementations to CloudHub",                     11, 70),
                    new TopicSeed("api-quality-goals",   "Meeting API quality goals",                                     10, 80),
                    new TopicSeed("monitoring-analyzing", "Monitoring and analyzing application networks",                12, 90)
            ),
            "agentforce-specialist", List.of(
                    new TopicSeed("ai-agents",                "AI Agents",                                  35, 10),
                    new TopicSeed("prompt-engineering",       "Prompt Engineering",                         20, 20),
                    new TopicSeed("data-cloud-for-agentforce", "Data Cloud for Agentforce",                 20, 30),
                    new TopicSeed("development-lifecycle",    "Development Lifecycle",                      20, 40),
                    new TopicSeed("multi-agent-interop",      "Multi-Agent Interoperability",                5, 50)
            ),
            "data-cloud-consultant", List.of(
                    new TopicSeed("dc-overview",          "Data Cloud Overview",                             18, 10),
                    new TopicSeed("dc-setup-admin",       "Data Cloud Setup and Administration",             12, 20),
                    new TopicSeed("dc-ingestion-modeling", "Data Ingestion and Modeling",                    20, 30),
                    new TopicSeed("dc-identity-resolution", "Identity Resolution",                           14, 40),
                    new TopicSeed("dc-segmentation-insights", "Segmentation and Insights",                   18, 50),
                    new TopicSeed("dc-act-on-data",       "Act on Data",                                     18, 60)
            )
    );

    private record TopicSeed(String key, String name, int weight, int sortOrder) {}

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        int backfilled = repo.backfillStatus(Question.Status.APPROVED);
        if (backfilled > 0) {
            log.info("DataSeeder: backfilled status=APPROVED on {} pre-existing question(s)", backfilled);
        }

        ObjectMapper om = new ObjectMapper();
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:seed/*.json");

        // Pass 1: upsert every exam so the default exam exists before migration.
        for (Resource f : files) {
            try (InputStream in = f.getInputStream()) {
                JsonNode root = om.readTree(in);
                upsertExam(root, f.getFilename());
            }
        }

        seedAllExamTopics();
        dropLegacyEnumCheckConstraints();

        // Migration: assign legacy exam-less questions to the default exam, then
        // drop the old single-column unique index on `number` if it survives.
        exams.findBySlug(DEFAULT_EXAM_SLUG).ifPresent(def -> {
            int moved = repo.backfillExam(def);
            if (moved > 0) {
                log.info("DataSeeder: assigned {} legacy question(s) to exam '{}'", moved, DEFAULT_EXAM_SLUG);
            }
        });
        dropLegacyNumberIndex();

        // Pass 2: upsert each exam's questions.
        for (Resource f : files) {
            try (InputStream in = f.getInputStream()) {
                JsonNode root = om.readTree(in);
                Exam exam = exams.findBySlug(root.get("slug").asText()).orElseThrow();
                int created = 0, updated = 0;
                for (JsonNode q : root.get("questions")) {
                    int number = q.get("n").asInt();
                    Question existing = repo.findByExamAndNumber(exam, number);
                    if (existing == null) {
                        repo.save(buildNew(q, exam));
                        created++;
                    } else if (refreshFields(existing, q)) {
                        repo.save(existing);
                        updated++;
                    }
                }
                log.info("DataSeeder: exam '{}' — created={}, updated={}", exam.getSlug(), created, updated);
            }
        }
        log.info("DataSeeder: {} exam(s), {} total question(s)", exams.count(), repo.count());
    }

    private void upsertExam(JsonNode root, String filename) {
        if (root == null || !root.hasNonNull("slug")) {
            log.warn("DataSeeder: seed file {} has no 'slug' — skipped", filename);
            return;
        }
        String slug = root.get("slug").asText();
        Exam e = exams.findBySlug(slug).orElseGet(Exam::new);
        e.setSlug(slug);
        e.setName(text(root, "name", slug));
        e.setDescription(text(root, "description", null));
        e.setQuestionsPerSession(root.path("questionsPerSession").asInt(60));
        e.setDurationMinutes(root.path("durationMinutes").asInt(90));
        e.setPassingScorePercent(root.path("passingScorePercent").asInt(65));
        e.setSortOrder(root.path("sortOrder").asInt(100));
        e.setActive(root.path("active").asBoolean(true));
        exams.save(e);
    }

    /** Upsert the per-topic weights for every exam in {@link #TOPIC_MIXES}.
     *  Idempotent: existing rows are updated, new rows inserted. */
    private void seedAllExamTopics() {
        for (java.util.Map.Entry<String, List<TopicSeed>> entry : TOPIC_MIXES.entrySet()) {
            String slug = entry.getKey();
            List<TopicSeed> mix = entry.getValue();
            exams.findBySlug(slug).ifPresent(exam -> {
                int created = 0, updated = 0;
                for (TopicSeed t : mix) {
                    ExamTopic existing = examTopics.findByExamAndTopicKey(exam, t.key()).orElse(null);
                    if (existing == null) {
                        ExamTopic et = new ExamTopic();
                        et.setExam(exam);
                        et.setTopicKey(t.key());
                        et.setName(t.name());
                        et.setWeightPercent(t.weight());
                        et.setSortOrder(t.sortOrder());
                        examTopics.save(et);
                        created++;
                    } else {
                        boolean dirty = false;
                        if (!t.name().equals(existing.getName())) { existing.setName(t.name()); dirty = true; }
                        if (t.weight() != existing.getWeightPercent()) { existing.setWeightPercent(t.weight()); dirty = true; }
                        if (t.sortOrder() != existing.getSortOrder()) { existing.setSortOrder(t.sortOrder()); dirty = true; }
                        if (dirty) { examTopics.save(existing); updated++; }
                    }
                }
                log.info("DataSeeder: topics for '{}' — created={}, updated={}", slug, created, updated);
            });
        }
    }

    /** Postgres-only: drop the legacy CHECK constraints Hibernate generated
     *  for our String-backed enums. When we add a new enum value (e.g. adding
     *  VERIFIER to UserRole, RETIRED to Question.Status), Hibernate's
     *  ddl-auto=update does NOT widen the existing constraint, so inserts/
     *  updates with the new value fail with: "new row for relation X violates
     *  check constraint X_role_check". Dropping the constraint here is the
     *  least-fuss fix — the app validates enum values in Java anyway. */
    private void dropLegacyEnumCheckConstraints() {
        try {
            String vendor = em.unwrap(org.hibernate.Session.class)
                    .doReturningWork(c -> c.getMetaData().getDatabaseProductName());
            if (vendor == null || !vendor.toLowerCase().contains("postgres")) {
                return;
            }
            String[] constraints = {
                    "ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check",
                    "ALTER TABLE users DROP CONSTRAINT IF EXISTS users_status_check",
                    "ALTER TABLE questions DROP CONSTRAINT IF EXISTS questions_status_check",
                    "ALTER TABLE questions DROP CONSTRAINT IF EXISTS questions_type_check",
                    "ALTER TABLE study_uploads DROP CONSTRAINT IF EXISTS study_uploads_status_check"
            };
            for (String sql : constraints) {
                em.createNativeQuery(sql).executeUpdate();
            }
            log.info("DataSeeder: dropped legacy enum CHECK constraints (Postgres) so new enum values can be persisted");
        } catch (Exception ex) {
            log.warn("DataSeeder: legacy enum check-constraint cleanup failed: {}", ex.getMessage());
        }
    }

    /** Drops any leftover single-column UNIQUE index on questions.number.
     *  This is a MySQL-only migration: the legacy index never existed on
     *  Postgres (different schema), and running the MySQL-specific
     *  information_schema query against Postgres would poison the outer
     *  @Transactional with `current transaction is aborted`. */
    private void dropLegacyNumberIndex() {
        try {
            String vendor = em.unwrap(org.hibernate.Session.class)
                    .doReturningWork(c -> c.getMetaData().getDatabaseProductName());
            if (vendor == null || !vendor.toLowerCase().contains("mysql")) {
                log.debug("DataSeeder: dropLegacyNumberIndex — skipping on {} (MySQL-only migration)", vendor);
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> names = em.createNativeQuery(
                    "SELECT index_name FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = 'questions' " +
                    "AND non_unique = 0 " +
                    "GROUP BY index_name " +
                    "HAVING COUNT(*) = 1 AND MAX(column_name) = 'number'").getResultList();
            for (String name : names) {
                em.createNativeQuery("ALTER TABLE questions DROP INDEX `" + name + "`").executeUpdate();
                log.info("DataSeeder: dropped legacy unique index '{}' on questions.number", name);
            }
        } catch (Exception ex) {
            log.warn("DataSeeder: could not check/drop legacy number index ({})", ex.getMessage());
        }
    }

    private Question buildNew(JsonNode q, Exam exam) {
        Question entity = new Question();
        entity.setExam(exam);
        entity.setNumber(q.get("n").asInt());
        entity.setStatus(Question.Status.APPROVED);  // seeded questions are vetted
        entity.setType(Question.Type.valueOf(q.get("type").asText()));
        entity.setText(q.get("text").asText());
        entity.setExplanation(q.has("explanation") ? q.get("explanation").asText() : null);
        entity.setHelpUrl(q.has("helpUrl") ? q.get("helpUrl").asText() : null);
        for (JsonNode c : q.get("choices")) {
            Choice ch = new Choice();
            ch.setLabel(c.get("label").asText());
            ch.setText(c.get("text").asText());
            ch.setCorrect(c.has("correct") && c.get("correct").asBoolean());
            entity.addChoice(ch);
        }
        return entity;
    }

    private boolean refreshFields(Question existing, JsonNode q) {
        boolean changed = false;
        String newText = q.get("text").asText();
        if (!Objects.equals(existing.getText(), newText)) { existing.setText(newText); changed = true; }

        Question.Type newType = Question.Type.valueOf(q.get("type").asText());
        if (existing.getType() != newType) { existing.setType(newType); changed = true; }

        String newExpl = q.has("explanation") ? q.get("explanation").asText() : null;
        if (!Objects.equals(existing.getExplanation(), newExpl)) { existing.setExplanation(newExpl); changed = true; }

        String newUrl = q.has("helpUrl") ? q.get("helpUrl").asText() : null;
        if (!Objects.equals(existing.getHelpUrl(), newUrl)) { existing.setHelpUrl(newUrl); changed = true; }

        return changed;
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.get(field).asText() : fallback;
    }
}
