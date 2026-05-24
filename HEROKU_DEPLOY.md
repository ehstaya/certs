# Deploy the cert GUI to Heroku

Self-contained Heroku deploy. The Spring app extracts questions from uploaded
study material **in-process** via the Anthropic Java SDK — no laptop-side
agent, no S3, no worker dyno. Uploads land directly in Postgres so the
ephemeral dyno filesystem doesn't lose anything.

```
   testers ──── HTTPS ─── web dyno: Spring quiz GUI + extraction
                                        │
                                        ├── Heroku Postgres essential-0
                                        └── Anthropic API (Claude Sonnet 4.6 / Haiku 4.5)
```

## Prereqs

1. Heroku account: <https://signup.heroku.com> (verify a credit card — add-ons
   below are paid but cheap).
2. Heroku CLI: `brew tap heroku/brew && brew install heroku`, then `heroku login`.
3. Docker Desktop running locally.
4. Anthropic API key (Console → API Keys → Create). Budget guard defaults to
   $2 / UTC day; override with `EXTRACTION_DAILY_BUDGET_USD`.

## 1. Create the app + add-ons

```bash
cd ~/SalesforceAdmin

APP="sfquiz-$(whoami)"
heroku create "$APP" --region us
heroku stack:set container -a "$APP"

# Heroku Postgres essential-0 (~$5/mo, 10M rows / 1 GB, no query-per-hour limit)
heroku addons:create heroku-postgresql:essential-0 -a "$APP"
```

If you previously had JawsDB MySQL attached, the `heroku-entrypoint.sh` prefers
`DATABASE_URL` (Postgres) so the next deploy switches automatically. Detach
JawsDB once Postgres works: `heroku addons:destroy jawsdb -a "$APP" --confirm "$APP"`.

## 2. Set env config

```bash
ADMIN_PW='set-a-real-password-here'
ANTHROPIC_KEY='sk-ant-...'    # from console.anthropic.com

heroku config:set -a "$APP" \
  ADMIN_EMAIL='admin@local' \
  ADMIN_PASSWORD="$ADMIN_PW" \
  ADMIN_NAME='Default Admin' \
  APP_BASE_URL="https://$APP.herokuapp.com" \
  MAIL_FROM='noreply@yourdomain.com' \
  MAIL_CONSOLE_FALLBACK=true \
  THYMELEAF_CACHE=true \
  ANTHROPIC_API_KEY="$ANTHROPIC_KEY" \
  EXTRACTION_DAILY_BUDGET_USD=2.0
```

`MAIL_CONSOLE_FALLBACK=true` logs generated emails to `heroku logs` instead of
sending them — fine while you're showing 2–3 people. Wire up SendGrid /
Mailgun later if you outgrow that.

## 3. Deploy (container push, no git remote needed)

```bash
heroku container:login
heroku container:push web -a "$APP"
heroku container:release web -a "$APP"
heroku logs -a "$APP" --tail   # watch for: "Started SfQuizApplication"
```

First push uploads the built image (~280 MB). Subsequent pushes are layer-only.

Visit `https://$APP.herokuapp.com`. Sign in as `admin@local` with the password
from step 2. The default Admin exam is seeded automatically.

## 4. Upload study material

Go to `https://$APP.herokuapp.com/admin/uploads`. Drop `.txt` / `.md` / `.html`
/ `.pdf` / screenshot images. Each row's *Status* column starts at **Pending**,
flips to **Extracting…** within ~1 s, and lands on **Done** (with the number of
questions imported) usually within 15–30 s. Refresh the page to watch progress.

If extraction fails — bad PDF, network blip, malformed JSON from Claude — the
row goes to **Failed** with the error in red and a **Retry** button. Re-uploads
of the same file are de-duped on question text by the existing import path, so
hitting Retry is safe.

The daily-budget meter at the top shows today's UTC spend. When the cap is hit,
new uploads are stored but the extractor short-circuits with "budget exhausted
— resumes at UTC midnight."

## 5. Add testers

Send them: `https://<APP>.herokuapp.com/register`. Approve them at
`/admin`. Until you wire up real SMTP (next section), `MAIL_CONSOLE_FALLBACK=true`
keeps the temp password in `heroku logs` — fish it out with
`heroku logs -a "$APP" --tail` and paste it to them directly.

## 6. Wire up real email (SendGrid direct, no Heroku add-on)

The Heroku SendGrid add-on is retired, so sign up at SendGrid directly. The
Spring app reads `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD`
— no code changes needed.

### One-time SendGrid setup

1. **Sign up** at <https://signup.sendgrid.com/> — free tier is 100 emails/day,
   plenty for a test group. Use `ehstaya@gmail.com` (or whichever address).
2. **Verify a Single Sender** at
   <https://app.sendgrid.com/settings/sender_auth/senders/new> — paste the
   email address you want messages to come *from*. SendGrid sends a one-time
   verification link; click it. (If you have a domain, you can do full Domain
   Authentication instead — better deliverability but requires DNS records.)
3. **Create an API key** at <https://app.sendgrid.com/settings/api_keys/new>.
   Pick **Restricted Access** and grant only **Mail Send → Full Access**.
   Copy the key (starts with `SG.…`) — it's shown once.

### Set the env vars on Heroku

```bash
heroku config:set -a "$APP" \
  MAIL_HOST=smtp.sendgrid.net \
  MAIL_PORT=587 \
  MAIL_USERNAME=apikey \
  MAIL_PASSWORD='SG.your-key-here' \
  MAIL_AUTH=true \
  MAIL_STARTTLS=true \
  MAIL_FROM='your-verified-sender@example.com' \
  MAIL_CONSOLE_FALLBACK=false
```

`MAIL_USERNAME` is literally the string `apikey` for SendGrid — that's not a
placeholder. `MAIL_FROM` **must** be the address you verified in step 2
(otherwise SendGrid rejects the send with `from address does not match a
verified Sender Identity`).

### Verify it works

```bash
heroku restart -a "$APP"
```

Then on the live app:
- Hit `/forgot-password` with your own email → you should get a reset link
  within ~10 s.
- Register a fresh tester account → admin emails go out to every active admin
  ("New registration pending: …").
- Upload a study doc → once extraction finishes, every active admin gets
  "N new question(s) from <file>".
- Promote the tester to admin (or approve them) → they get the temp-password
  email.

If anything bounces, check `heroku logs --tail -a "$APP"` for
`Mail send failed`. Common causes:
- `MAIL_FROM` doesn't match the verified sender → SendGrid 550.
- API key was created with the wrong scope → SendGrid 401.
- `MAIL_PASSWORD` was pasted with quotes/trailing spaces → SendGrid 535.

### When you grow past 100/day

Bump to the SendGrid Essentials plan (~$20/mo for 50k emails) or switch to
Mailgun / Amazon SES — code stays unchanged, only the four `MAIL_*` env vars
move.

## Day-2

```bash
# Logs
heroku logs -a "$APP" --tail

# Restart
heroku ps:restart -a "$APP"

# DB shell
heroku pg:psql -a "$APP"

# Update the app after code changes
heroku container:push web -a "$APP" && heroku container:release web -a "$APP"

# Bump dyno to no-sleep
heroku ps:scale web=1:basic -a "$APP"   # ~$7/mo
```

## Cost expectations (personal account)

| Item | Plan | Monthly |
|---|---|---|
| `eco` dyno (default) | Eco | $5 / 1000 dyno-hours, sleeps when idle |
| `basic` dyno (no sleep) | Basic | $7 |
| Heroku Postgres essential-0 | `essential-0` | $5 |
| Anthropic API | usage-based | ~$0.01–0.10 per upload @ Sonnet 4.6 |

Total: **~$10–12 / month** plus per-upload Anthropic spend (capped by the
daily-budget guard).

## What happened to the laptop agent?

The Python crawler in `sf-cert-crawler/` is still in the repo but no longer
required for the Heroku flow — extraction now runs in the Spring app. Keep it
around if you ever want to do bulk external-web crawling (which is a worse fit
for the dyno because of rate limits).
