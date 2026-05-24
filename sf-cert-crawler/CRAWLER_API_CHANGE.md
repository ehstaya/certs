# Crawler update required — quiz app import API is now exam-scoped

The quiz app's question-import API has been changed to support multiple
certification tracks (Salesforce, AWS, TOGAF, …). The crawler currently posts
the OLD payload format and will now fail. Update the crawler's push path to the
new contract below.

> Paste-ready: the body of this file is written so it can be handed directly to
> a Claude Code session working on the crawler. It is self-contained.

## Context

- The crawler module lives in this repo (`python -m crawler …`,
  `python -m crawler.external push`). The push command authenticates as the
  app admin and POSTs scraped/generated questions to the quiz app.
- Endpoint is **UNCHANGED**: `POST /admin/questions/import`
  (ADMIN-only; CSRF disabled; same session/cookie auth as today).
- The **per-question JSON shape is UNCHANGED**. Only the OUTER payload changed:
  the array of questions must now be wrapped in an "exam envelope", and the
  crawler must specify which exam the questions belong to.

## Old request body (sent today — now rejected)

```json
[ { "n":1, "type":"SINGLE", "text":"…", "helpUrl":"…", "explanation":"…",
    "sourceUrl":"…",
    "choices":[ {"label":"A","text":"…","correct":true} ] } ]
```

## New request body (`ImportExamRequest` envelope)

```json
{
  "slug": "salesforce-admin",
  "name": "Salesforce Administrator (CRT-101)",
  "description": "Core admin exam: configuration, security, automation, data, reporting.",
  "questionsPerSession": 60,
  "durationMinutes": 105,
  "sortOrder": 10,
  "active": true,
  "questions": [
    { "n":1, "type":"SINGLE", "text":"…", "helpUrl":"…",
      "explanation":"…", "sourceUrl":"…",
      "choices":[ {"label":"A","text":"…","correct":true} ] }
  ]
}
```

- `slug` is **REQUIRED**.
- `name` is used only when the exam is first created.
- `questions[]` items are the **same shape as before**.

## Server behavior

- The exam is **upserted by `slug`**. First push with a new slug creates the
  exam from `name`/`description`/`questionsPerSession`/`durationMinutes`/
  `sortOrder`/`active`. Subsequent pushes only override the metadata fields you
  actually send (null/omitted fields leave the existing value untouched).
- Missing `slug` →
  `{"imported":0,"skipped":0,"skippedTexts":["missing exam slug"]}`.
- `n` is ignored — the server assigns the next free **per-exam** question
  number.
- Imported questions are created as **PENDING** (an admin approves them).
  Unchanged.
- Duplicate detection is now **per-exam** by question text (was global).
  Re-pushing the same text into the same exam is skipped as
  `"already present"`.
- Response body now includes an `exam` key:
  ```json
  { "exam":"salesforce-admin", "imported":42, "skipped":3,
    "skippedTexts":[ "<snippet> — already present" ] }
  ```
- Per-question validation is **unchanged** and enforced server-side: text ≥ 10
  chars; ≥ 2 choices; ≥ 1 correct choice; type must be `SINGLE` or `MULTI`;
  a `SINGLE` question with >1 correct choice is rejected.

## Cert → exam mapping

The crawler already has a cert selector (e.g. `--cert admin`). Add a mapping
from cert key to exam envelope metadata. Start with:

| cert key | slug              | name                                  | questionsPerSession | durationMinutes | sortOrder |
|----------|-------------------|---------------------------------------|---------------------|-----------------|-----------|
| `admin`  | `salesforce-admin`| Salesforce Administrator (CRT-101)    | 60                  | 105             | 10        |

Make it an easily extended dict (future keys like `platform-app-builder`,
`aws-saa`, `togaf`). The chosen cert key selects which envelope metadata +
slug to wrap the questions in.

## Important nuance

The server also seeds `salesforce-admin` from a seed file on every app start
and resets that exam's metadata to the seed values on restart. For
`salesforce-admin` specifically, prefer to send `slug` + `name` + the
`questions` and **do not** fight the seed over
`questionsPerSession`/`durationMinutes` — unless the crawler is intended to be
authoritative for a brand-new (non-seeded) exam.

## Parity bonus

The server's seed files (`seed/*.json`) now use this **exact same envelope**
shape. If the crawler can also emit a local seed file, that artifact is
byte-compatible with the import body — keep them identical so one builder
serves both paths.

## Tasks

1. Locate the push code (the `crawler.external push` command and wherever the
   request body + POST to `/admin/questions/import` is built).
2. Refactor the payload builder to emit the new envelope; add the cert→exam
   metadata mapping and thread the selected cert through to the builder.
3. Update response handling/logging for the new
   `{exam,imported,skipped,skippedTexts}` shape.
4. Keep `--dry-run` working and have it print the full envelope (pretty JSON)
   without POSTing.
5. Verify with `python -m crawler.external push --dry-run` — confirm the
   printed body is the envelope with the correct slug + metadata and a
   `questions[]` array. Then do a real push against a running app and confirm a
   non-zero `imported` count and that the new exam/questions appear in the
   app's exam picker (PENDING questions need admin approval first).

Do **not** change the per-question fields or the endpoint path — only the
wrapper, the cert mapping, and response parsing.
