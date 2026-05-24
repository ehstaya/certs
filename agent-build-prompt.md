# Build Prompt: Salesforce Certification Question Crawler Agent

Paste the section below into Claude in VS Code. It's written as a single, self-contained brief so Claude has everything it needs to start without asking ten clarifying questions. Adjust the bracketed `[CHOOSE: ...]` lines to match your preferences before sending.

---

## Project brief

I want you to help me build a Python-based AI agent that crawls **public, free** Salesforce certification study material from the open web and populates a local questions database I can use for personal exam prep. The agent must **never** ingest paid content, leaked exam dumps, or anything that looks like it was copied from a real certification exam.

### My choices (edit these before sending)

- **Target certifications:** [CHOOSE: e.g., "Salesforce Administrator + Advanced Administrator", or "Platform App Builder only"]
- **Storage:** [CHOOSE: SQLite file at `./questions.db` | Postgres | JSON files per topic]
- **Runtime:** Local, run on demand via CLI (`python -m crawler run`) and optionally on a daily cron
- **LLM for classification/extraction:** Anthropic Claude via the `anthropic` Python SDK, using `claude-haiku-4-5` for cheap classification passes and `claude-sonnet-4-6` only when extraction quality matters
- **Python version:** 3.11+
- **License of the code you write:** MIT, in a fresh repo

### Goal

End state: I run `python -m crawler run --cert admin` and the agent fetches new public material from an allowlisted set of sources, identifies question-shaped content (multiple-choice, scenario, "what would you do" prompts), filters out anything that looks like paid/leaked content, deduplicates against the DB, and inserts clean, structured rows I can quiz myself with later.

---

## Hard rules (do not violate)

1. **Public, no-login only.** Never prompt for or store credentials for Reddit, Salesforce, Trailhead, or anywhere else. Use only endpoints that work anonymously or with app-only OAuth (Reddit's `client_credentials` flow with my own app ID — no user login).
2. **Respect robots.txt.** Use the `urllib.robotparser` module (or `reppy`) and skip any path the site disallows for our User-Agent.
3. **Rate limits.** Default to 1 request/second per domain, with exponential backoff on 429/503. Make this configurable per source.
4. **Identify ourselves.** Send a real User-Agent string: `SalesforceStudyBot/0.1 (personal-study; contact: ehstaya@gmail.com)`.
5. **No paid / no dumps.** See the filtering section below. When in doubt, drop the item and log the reason.
6. **Personal use only.** The DB stays local. Do not add any "publish" or "share" or "API server" features unless I explicitly ask later.
7. **Don't republish copyrighted text verbatim in any output the user sees outside the local DB.** Summaries and topic tags are fine; the raw question text stays inside the local DB row.

---

## Sources

### Allowlist (start here)

- **Reddit (public JSON or app-only OAuth):**
  - `r/salesforce`
  - `r/SalesforceDeveloper`
  - `r/sfdc`
  - Filter posts by flair containing "Certification", "Admin Exam", "Study", or by title regex matching `(exam|cert|study|prep|practice question)`.
- **Trailhead (public module pages, no login):**
  - Crawl module/unit pages under `https://trailhead.salesforce.com/content/learn/modules/...` for the trails associated with the target cert.
  - Extract the "Knowledge Check" / "Check your understanding" questions embedded in the HTML.
- **Salesforce Ben blog:** `https://www.salesforceben.com/` — crawl articles tagged with cert names; extract any practice questions they publish.
- **Apex Hours:** `https://www.apexhours.com/` — same approach.
- **Official Salesforce Help / release notes** for context, not questions: `https://help.salesforce.com/`

### Hard blocklist (never ingest)

Skip any URL whose host matches these patterns, and skip any post linking to them:

```
examtopics.com, certkillers.*, passleader.*, braindumps.*, dumpscollection.*,
exam-labs.com, *.itexams.com, certificationdumps.*, killexams.*, dumpsbase.*,
crackthepm.com (cert dump section), quizlet.com (decks tagged "dump"|"actual exam"|"real questions"),
udemy.com (paid content), pluralsight.com, focusonforce.com (paid)
```

### Content blocklist (heuristics)

Drop the item if any of these match the title or body:

- Phrases: `exam dump`, `real exam questions`, `actual exam`, `leaked questions`, `100% pass`, `verified answers`, `pdf dump`, `braindump`
- Structure: A single post containing more than 15 sequentially numbered questions (`Q1.`, `Q2.`, ...) — that's almost always a dump.
- Suspicious offers: any text matching `(buy|sell|paid|premium|pdf|download).{0,40}(dump|questions|answers)`
- Links to any blocklisted host above.

When the heuristic fires, log `{source, url, reason}` to `./logs/filtered.jsonl` and do not insert.

---

## Architecture

```
crawler/
  __init__.py
  __main__.py              # CLI entry: `python -m crawler run --cert admin`
  config.py                # Pydantic settings, .env loading
  sources/
    base.py                # Source ABC: discover() -> list[RawItem], fetch(item) -> str
    reddit.py              # Reddit JSON / app-only OAuth client
    trailhead.py           # Trailhead HTML scraper
    salesforceben.py
    apexhours.py
  pipeline/
    classify.py            # "Is this a question?" via Claude Haiku
    filter_paid.py         # Heuristic + LLM-backed paid/dump filter
    extract.py             # Pull structured Question objects from raw text via Claude
    dedup.py               # Hash-based + semantic dedup
    store.py               # SQLAlchemy models + insert
  models.py                # Pydantic + SQLAlchemy models for Question, Source, Run
  robots.py                # robots.txt cache
  rate_limit.py            # Per-domain token bucket
  logging_setup.py
tests/
  test_filter_paid.py      # Critical — see test cases below
  test_reddit.py
  test_trailhead.py
  fixtures/                # Saved HTML + JSON fixtures, no live network in tests
pyproject.toml
README.md
.env.example               # ANTHROPIC_API_KEY, REDDIT_CLIENT_ID, REDDIT_CLIENT_SECRET
```

### Data model

```python
class Question(BaseModel):
    id: str                      # sha256 of normalized text, first 16 chars
    cert: str                    # "admin", "platform_app_builder", etc.
    topic: str | None            # "Security", "Data Management", "Flow", ...
    question_text: str
    options: list[str] | None    # None for open-ended/scenario questions
    correct_answer: str | None   # Only if confidently extracted from official source
    explanation: str | None
    difficulty: Literal["easy", "medium", "hard"] | None
    source_url: str
    source_type: Literal["reddit", "trailhead", "blog", "other"]
    source_license_note: str     # e.g., "Salesforce Trailhead © Salesforce; personal study use"
    extracted_at: datetime
    confidence: float            # 0–1, how sure the LLM is this is a real question
```

### Pipeline flow

1. **Discover:** Each source's `discover()` returns a list of candidate URLs.
2. **Robots check:** Skip disallowed URLs.
3. **Fetch:** Polite GET with rate limit + retry. Cache raw HTML/JSON to `./cache/` keyed by URL hash so re-runs don't re-fetch.
4. **Paid/dump filter (pre-LLM):** Cheap regex/host checks on URL and raw text. Drop fast.
5. **Classify (LLM):** Ask Haiku: "Does this text contain Salesforce certification study questions? Yes/No + reason." Skip if no.
6. **Paid/dump filter (LLM):** Ask Haiku: "Does this look like leaked/paid exam content vs. user-generated study discussion? Reply with one of: `clean`, `suspicious`, `dump`. Treat anything claiming to contain 'real exam questions' as `dump`." Drop `dump`. Flag `suspicious` for manual review in `./logs/review.jsonl`.
7. **Extract (LLM):** Use Sonnet to extract structured `Question` objects. Prompt the model to return JSON conforming to the schema, with `null` for fields it can't fill confidently. Never invent answers — `correct_answer` stays `null` unless the source explicitly states it.
8. **Dedup:** Hash-based exact match first; then semantic dedup using embeddings (use `voyage-3-lite` or sentence-transformers locally — your call, pick the cheaper one and explain why in the README).
9. **Store:** Insert into SQLite. Track each run in a `runs` table (timestamp, source, fetched, inserted, filtered counts).

---

## Tech stack

- `httpx` for HTTP (async-capable, better than `requests` for this).
- `selectolax` or `beautifulsoup4` for HTML parsing.
- `pydantic` v2 for data models.
- `sqlalchemy` 2.x + `sqlite` for storage.
- `anthropic` Python SDK for LLM calls.
- `typer` for the CLI.
- `tenacity` for retries.
- `pytest` for tests, `pytest-vcr` or saved fixtures for HTTP replay.
- `ruff` + `mypy` configured in `pyproject.toml`.

---

## Critical test cases (write these first, TDD-style)

In `tests/test_filter_paid.py`, prove the paid/dump filter works on these fixtures **before** writing the crawler logic:

1. A Reddit post titled "Just passed my Admin exam, here are my study tips" with a discussion of topics → **keep**.
2. A Reddit post titled "Salesforce Admin Real Exam Questions PDF — 240 questions verified" → **drop as dump**.
3. A Reddit comment posting `Q1. ... Q2. ... Q3. ...` through `Q47.` → **drop as dump**.
4. A Trailhead module page with a "Check your understanding" multiple-choice question → **keep**.
5. A blog post linking to `examtopics.com` → **drop (linked-blocklist-host)**.
6. A genuine study discussion that happens to contain the phrase "I saw a question on the exam about sharing rules" → **keep, but flag for review** (mentions exam content but isn't republishing it).

Each test should produce a clear `FilterResult(decision, reason, matched_rules)` so I can audit decisions later.

---

## Deliverables for this first pass

Build it in phases and **stop after each phase to show me the output** before continuing:

**Phase 1 — Skeleton + filter (no network):**
- Project structure, `pyproject.toml`, `.env.example`, README stub.
- `filter_paid.py` fully implemented with the 6 test cases above passing.
- A `make test` / `pytest` command that runs green.

**Phase 2 — Reddit source:**
- `reddit.py` using app-only OAuth (read `REDDIT_CLIENT_ID` / `REDDIT_CLIENT_SECRET` from `.env`; if missing, fall back to public `.json` endpoints with stricter rate limit).
- Pull the latest 50 posts from `r/salesforce`, run them through the filter, show me what was kept vs. dropped and why.
- Save raw fetches to `./cache/` so re-runs are free.

**Phase 3 — Trailhead source:**
- Scrape one specific module I give you (I'll provide a URL), extract knowledge-check questions into `Question` objects, insert into SQLite. Show me the resulting rows.

**Phase 4 — Classify + extract pipeline with Claude:**
- Wire up the LLM steps for one source end-to-end.
- Cost guardrail: log estimated token spend per run and abort if a single run is projected to exceed $1 unless I pass `--allow-spend`.

**Phase 5 — Dedup, run tracking, daily cron-friendly mode.**

Do **not** build phases 2–5 until I approve phase 1.

---

## Things I want you to push back on

If any of the following are true, tell me before writing code:

- A source's ToS clearly forbids automated access for our use case → flag it, don't quietly skip.
- A library I picked is a poor fit → suggest the better one with a one-sentence reason.
- A part of the spec is ambiguous → ask before guessing.
- You think the LLM step in phase 4 can be replaced with deterministic parsing for some sources → say so, it'll save money.

Start with Phase 1. Ask me for the target cert (from my list above) and confirm storage choice before generating the skeleton.
