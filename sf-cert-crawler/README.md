# sf-cert-crawler

Personal Salesforce certification study tool. Two agents pull study material — from
public web sources (external) or internal Google Drive / local files (internal) —
extract well-formed quiz questions, run a quality gate, store them locally, and on
demand `push` them to the **quiz app** (the Spring "Salesforce Admin Practice playground"
in the parent directory) where an admin **approves** them before they go live.

```
                 external agent                          internal agent
  Salesforce Ben / Apex Hours (web)          Google Drive folder / local folder
              │                                            │
              ▼                                            ▼
   robots.txt + rate-limit + cache              Drive API / local file read
              │                                            │
              └──────────────┬─────────────────────────────┘
                             ▼
        heuristic paid/dump filter  →  (deterministic | Claude classify→dump-check→extract)
                             ▼
                   exact + semantic dedup  →  ./questions.db (SQLite)
                             ▼
                  `push`  →  quality gate  →  POST /admin/questions/import
                             ▼
              quiz app: questions land as PENDING  →  admin approves in /admin/questions
                             ▼
                         live quiz at http://localhost:8095
```

**Status:** the two-agent split, the quiz-app push, and the human-approval flow are
implemented on top of the original five-phase crawler. 48 Python tests pass; the Spring
app compiles (`mvn package`); the internal-agent Docker image builds.

## The two agents

| | **external** (`python -m crawler.external`) | **internal** (`python -m crawler.internal`) |
|---|---|---|
| Where it runs | anywhere | inside the Salesforce network |
| Sources | Salesforce Ben blog, Apex Hours blog (web) | Google Drive folder, local folder — **no web crawling** |
| Outbound network | the allowlisted blogs + (with `--use-llm`) `api.anthropic.com` + the quiz app | Google APIs + `api.anthropic.com` (with `--use-llm`) + the quiz app — that's it |
| Credentials | none for sources | a Google service-account JSON (or OAuth token) you provide, mounted into the container |
| Commands | `run`, `ingest <url>`, `push`, `list`, `runs` | `run`, `push`, `list`, `runs` |

The agents are hard-separated by their **source registry** — the internal agent has no web
sources registered and can't crawl; the external agent has no Google source. They share the
core (fetch/filter/extract/dedup/store) via `crawler/cli_common.py`. A dispatcher also exists:
`python -m crawler external …` / `python -m crawler internal …`.

> Note on Google NotebookLM: NotebookLM has no public API, so it can't be read programmatically.
> The internal agent reads **Google Drive** (a folder you point it at — including any NotebookLM
> source docs or exports you save there) and a **local folder** (e.g. NotebookLM exports you
> download). Configure `GOOGLE_*` and `LOCAL_SOURCE_DIR` in `.env`.

## Scope

| | |
|---|---|
| Target cert | Salesforce Administrator (only) |
| Crawler storage | SQLite at `./questions.db` |
| Python | 3.11+ |
| LLM classify/filter | Claude Haiku 4.5 (`claude-haiku-4-5`) |
| LLM extract | Claude Sonnet 4.6 (`claude-sonnet-4-6`) |
| Embeddings (dedup) | local `sentence-transformers/all-MiniLM-L6-v2` — free, no key, sufficient for "same question?" |
| Per-run LLM cost cap | $1 USD; exceeding requires `--allow-spend` |
| Quiz app | the Spring app in `../` (MySQL, admin UI at `/admin`, questions seeded from `seed/questions.json`) |

**Excluded sources:** Reddit (`robots.txt` is `Disallow: /` for the site *and* the OAuth API
host; Public Content Policy restricts automated access) and Trailhead (Salesforce ToU restricts
automated access; "Check your understanding" content isn't exam-question-shaped). Both decided
during the build, not arbitrarily.

## Hard rules (enforced in code)

- External agent: public, no-login sources only; `robots.txt` respected; 1 req/sec/domain default
  with exponential backoff on 429/503; `User-Agent: SalesforceStudyBot/0.1 (personal-study; contact: ehstaya@gmail.com)`.
- Internal agent: reads **only** Google Drive / the configured local folder — no web crawling.
- Paid content and exam dumps filtered out (heuristic in `crawler/pipeline/filter_paid.py`, plus an
  LLM dump-check when `--use-llm`).
- Questions are stored locally and only enter the quiz app via `push` → admin approval. Nothing is
  auto-published. Raw question text outside the local DB / quiz app is summarized in CLI output.

## Install

```bash
cd sf-cert-crawler
python3.11 -m venv .venv               # or python3.14 if that's what you have
source .venv/bin/activate
pip install -e ".[dev]"                # core + test/lint tooling
pip install -e ".[dev,internal]"       # + google-api-python-client, google-auth, pypdf (internal agent)
pip install -e ".[dev,dedup]"          # + sentence-transformers (+ torch) for --semantic-dedup
```

Configure `.env` (see `.env.example`): `ANTHROPIC_API_KEY` (for `--use-llm`),
`QUIZ_APP_URL` / `QUIZ_APP_ADMIN_EMAIL` / `QUIZ_APP_ADMIN_PASSWORD` (for `push`), and for the
internal agent `GOOGLE_SERVICE_ACCOUNT_FILE` (or `GOOGLE_OAUTH_TOKEN_FILE`) + `GOOGLE_DRIVE_FOLDER_ID`
and/or `LOCAL_SOURCE_DIR`.

## Test

```bash
pytest                          # 48 tests, no network, no API key needed
```

## Run — external agent (web)

```bash
python -m crawler.external run --cert admin --limit 20
python -m crawler.external run --cert admin --source apexhours --limit 20
python -m crawler.external run --cert admin --limit 20 --use-llm                 # needs ANTHROPIC_API_KEY
python -m crawler.external run --cert admin --use-llm --budget 0.50              # tighter cost cap
python -m crawler.external run --cert admin --semantic-dedup                     # skip near-duplicates
python -m crawler.external run --cert admin --quiet                              # cron-friendly one-liner
python -m crawler.external ingest https://www.salesforceben.com/salesforce-admin-practice-exam/ --cert admin
python -m crawler.external list --cert admin --limit 10 --full 2
python -m crawler.external runs --limit 15
python -m crawler.external push --dry-run                                        # see what would go to the quiz app
python -m crawler.external push                                                  # actually push (needs QUIZ_APP_ADMIN_*)
```

## Run — internal agent (Google Drive / local folder)

```bash
python -m crawler.internal run --source drive --use-llm                          # read the Drive folder, extract with Claude
python -m crawler.internal run --source localfolder --use-llm                    # read LOCAL_SOURCE_DIR
python -m crawler.internal list --cert admin
python -m crawler.internal push                                                  # quality-gate + send to the quiz app
```

(`--use-llm` is essentially required for the internal agent — Drive/local content is prose/notes,
not structured quizzes, so without the Claude pipeline it extracts almost nothing.)

## Feeding the internal agent — three ways

The internal agent reads from a folder; how files get into that folder is up to you.

1. **Admin uploads via the quiz-app UI** (recommended for the SF-network deployment — zero external API
   exposure): admins sign in at the quiz app and drop `.txt` / `.md` / `.html` / `.pdf` files at
   **`/admin/uploads`**. Files land in the `sfquiz-uploads` Docker volume that the agent mounts read-only
   at `/uploads`. The agent picks them up on its next `run --source localfolder --use-llm`.
2. **Local folder on disk** (for host-venv dev or non-Docker setups): point `LOCAL_SOURCE_DIR` at any
   directory. Same agent code path.
3. **Google Drive folder** (skip if you don't want Google API calls): `--source drive` reads from a
   Drive folder. Needs a GCP project + service-account JSON + sharing the folder with the SA's email.

For testing on a network where Google APIs / Anthropic APIs may be blocked, options 1 and 2 keep
egress at exactly one URL: `api.anthropic.com` (only when `--use-llm`).

## How questions reach the quiz app — and the approval flow

1. Either agent's `run`/`ingest` extracts questions and stores them in `./questions.db` (deduplicated).
2. `push` takes the not-yet-pushed questions, runs the **automated quality gate**
   (`crawler/pipeline/quality.py`): non-empty text, ≥2 distinct options, a stated correct answer that
   resolves to an option, SINGLE-vs-MULTI inferred, extractor confidence ≥ `QUALITY_MIN_CONFIDENCE`.
   Failures are reported and skipped (not pushed).
3. The passing questions are grouped by cert and wrapped in an **exam envelope**
   (`crawler/exams.py` maps cert → `{slug, name, …}`; e.g. `admin` → `salesforce-admin`). Each
   envelope is POSTed to `POST /admin/questions/import` on the quiz app (form-login as the admin
   first; CSRF is disabled there). The quiz app upserts the exam by `slug` and creates the
   questions with `status = PENDING` and the next free per-exam `number`, skipping any whose text
   already exists in that exam. For server-seeded exams (`admin`) only `slug`+`name`+`questions`
   are sent so the crawler doesn't fight the seed over session/duration metadata.
   `push --dry-run` prints the full envelope(s) as JSON without sending.
4. A human reviews them in the quiz app's admin UI at **`/admin/questions`** — **Approve** (adds to the
   live quiz), **Reject** (hides it), or **Edit** (text / type / explanation / help URL / choices &
   correct answers) first. Only `APPROVED` questions appear in the quiz; seeded questions are `APPROVED`.

**Quiz-app credentials caveat:** `push` logs in as the admin. The default `ChangeMe123!` password
triggers the app's force-change-password filter, which would bounce the import — so change the admin
password in the quiz app first, then set `QUIZ_APP_ADMIN_PASSWORD` to the new value.

## Docker — internal agent

The internal agent is dockerized (`Dockerfile.internal`) to run inside the SF network alongside the
quiz app. End-to-end with the **uploads UI** (option 1 above — recommended):

```bash
cd /Users/ehstaya/SalesforceAdmin
# repo-root .env: ANTHROPIC_API_KEY=...  QUIZ_APP_ADMIN_PASSWORD=<post-change value>
docker compose -f docker-compose.yml -f docker-compose.internal.yml up -d app mysql mailpit

# 1. sign in at http://localhost:8095 (change admin password on first login)
# 2. open http://localhost:8095/admin/uploads, upload .txt / .md / .html / .pdf files
# 3. extract + push (one-shot tasks):
docker compose -f docker-compose.yml -f docker-compose.internal.yml run --rm internal-agent run --source localfolder --use-llm
docker compose -f docker-compose.yml -f docker-compose.internal.yml run --rm internal-agent push
# 4. approve at http://localhost:8095/admin/questions
```

The `sfquiz-uploads` named volume is declared in both compose files and shared between the quiz app
(read/write) and the internal-agent (read-only) — admins drop files in via the browser, the agent
picks them up from the same volume. The agent reaches the quiz app at `http://app:8095` (compose
service name) and persists its DB / cache / logs in the `sf-internal-data` volume. For a daily run,
drive `run` + `push` from the host's crontab. The `--semantic-dedup` torch dependency is intentionally
not in the image — add `[internal,dedup]` to the Dockerfile's `pip install` if you want it.

For **option 3 (Drive)**, uncomment the service-account bind mount in `docker-compose.internal.yml`,
put the JSON at `./google-sa.json`, set `GOOGLE_DRIVE_FOLDER_ID` in `.env`, then
`… run --rm internal-agent run --source drive --use-llm`. The Drive code path is unchanged.

## Module map

| Path | What it is |
|---|---|
| `crawler/external/__main__.py`, `crawler/internal/__main__.py` | the two agents (typer CLIs); `crawler/__main__.py` is a dispatcher |
| `crawler/cli_common.py` | shared `run` / `ingest` / `push` / `list` / `runs` logic |
| `crawler/sources/{salesforceben,apexhours}.py` | web sources (HTTP, robots.txt, polite fetch) |
| `crawler/sources/{drive,localfolder}.py` | internal sources (Drive API / local files; `fetch_via_http = False`) |
| `crawler/fetch.py`, `crawler/robots.py`, `crawler/rate_limit.py` | polite HTTP for web sources |
| `crawler/pipeline/filter_paid.py` | heuristic paid/dump filter (regex/host) |
| `crawler/llm.py`, `crawler/pipeline/{classify,extract}.py` | Claude pipeline + `CostMeter` + budget guard + prompt caching |
| `crawler/pipeline/dedup.py` | exact + semantic (local MiniLM) dedup |
| `crawler/pipeline/quality.py` | the quality gate run before `push` |
| `crawler/pipeline/store.py` | SQLAlchemy `QuestionRow` / `RunRow`, lightweight SQLite migration, push lifecycle |
| `crawler/exams.py` | cert → exam-envelope metadata mapping (slug/name/…); easily extended for new certs |
| `crawler/quizapp.py` | client for the quiz app's form login + `import_exam(envelope)` → `/admin/questions/import` |
| `crawler/audit.py` | `logs/filtered.jsonl` / `logs/review.jsonl` |
| `Dockerfile.internal`, `../docker-compose.internal.yml` | the internal agent container |
| `../src/main/java/com/sfquiz/**` | the Spring quiz app — `Question.Status`, `QuestionAdminController` (import + approve/reject/edit), `questions.html` / `question-edit.html` admin UI, `QuizService` filters to `APPROVED` |

## Limitations / not verified here

- The end-to-end internal flow (`internal run --source drive --use-llm` → `push` → admin approves)
  needs your Google credentials + an Anthropic key + a quiz app whose admin password isn't the default
  — none available in the dev environment. The pieces are unit-tested individually; the Java app
  compiles; the Docker image builds. `push --dry-run` and the quality gate are verified against the
  60 stored questions.
- `--use-llm` needs `ANTHROPIC_API_KEY`; without it everything except the Claude steps works keyless.
