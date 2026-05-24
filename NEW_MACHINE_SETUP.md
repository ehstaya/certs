# Setup on a new machine

Both the cert GUI (Spring + MySQL + Mailpit) and the internal crawler agent
(Python) run as Docker containers. Nothing else needs to be installed on the
host beyond Docker Desktop — the GUI's JAR is built inside its Dockerfile, and
the agent's Python deps are installed inside its Dockerfile.

## Prerequisites

- **Docker Desktop** running (you said it's already installed).
- An **Anthropic API key** for the crawler's Claude pipeline (text + vision
  extraction). Get one at <https://console.anthropic.com/>.

## First-time setup

```bash
# 1. Unzip wherever you want it to live
unzip salesforceadmin-package.zip -d ~/SalesforceAdmin
cd ~/SalesforceAdmin

# 2. Create your .env from the template (fill in ANTHROPIC_API_KEY)
cp .env.example .env
# edit .env -> set ANTHROPIC_API_KEY=sk-ant-...
# leave QUIZ_APP_ADMIN_PASSWORD as ChangeMe123! (the bootstrap admin doesn't
# require a password change; you can change it later in the UI and update .env)

# 3. Build + start everything (first build downloads Maven base + python:3.12
#    + builds the JAR + installs Python deps — ~3-5 minutes the first time)
docker compose -f docker-compose.yml -f docker-compose.internal.yml up -d --build

# 4. Wait for the GUI to be ready
docker compose logs -f app
# look for: "Started SfQuizApplication"  (Ctrl-C once you see it)
```

That's it. Reachable now:

| Service | URL |
|---|---|
| Quiz GUI | <http://localhost:8095> (sign in `admin@local` / `ChangeMe123!`) |
| Mailpit (captured email) | <http://localhost:8025> |
| Admin uploads | <http://localhost:8095/admin/uploads> |
| Admin question review | <http://localhost:8095/admin/questions> |

## Daily use

Upload a study doc or a question screenshot at **/admin/uploads** → the
`internal-agent-watcher` container picks it up within ~20 s, extracts questions
with Claude (text or vision depending on the file type), runs the quality gate,
and posts them as PENDING. Approve at **/admin/questions**. They go live in the
quiz at the home page.

Tail the watcher live:

```bash
docker compose -f docker-compose.yml -f docker-compose.internal.yml logs -f internal-agent-watcher
```

Stop everything (data persists in Docker volumes):

```bash
docker compose -f docker-compose.yml -f docker-compose.internal.yml down
```

Fresh start (wipes the DB + uploads + agent state):

```bash
docker compose -f docker-compose.yml -f docker-compose.internal.yml down -v
```

For full details (architecture, manual one-shot commands, troubleshooting), see
[`sf-cert-crawler/README.md`](sf-cert-crawler/README.md) and [`RUN.txt`](RUN.txt).
