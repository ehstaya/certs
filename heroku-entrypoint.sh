#!/bin/sh
# Container entrypoint. Idempotent: when running on Heroku, translates Heroku's
# injected env vars into the ones Spring/the app already reads. When running
# locally (no Heroku vars present), it falls through unchanged.
set -e

# --- Heroku Postgres add-on -> Spring datasource ---------------------------
# Heroku injects DATABASE_URL = postgres://user:pass@host:port/dbname
# Postgres takes priority if both are present (we're migrating off JawsDB).
if [ -n "$DATABASE_URL" ] && [ -z "$SPRING_DATASOURCE_URL" ]; then
  SPRING_DATASOURCE_USERNAME=$(printf '%s' "$DATABASE_URL" | sed -E 's|^postgres(ql)?://([^:]+):.*|\2|')
  SPRING_DATASOURCE_PASSWORD=$(printf '%s' "$DATABASE_URL" | sed -E 's|^postgres(ql)?://[^:]+:([^@]+)@.*|\2|')
  HOSTDB=$(printf '%s' "$DATABASE_URL" | sed -E 's|^postgres(ql)?://[^@]+@(.+)$|\2|')
  SPRING_DATASOURCE_URL="jdbc:postgresql://${HOSTDB}?sslmode=require"
  export SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
fi

# --- JawsDB MySQL add-on -> Spring datasource ------------------------------
# Heroku injects JAWSDB_URL = mysql://user:pass@host:port/dbname.
# Kept for backwards-compat; only used if DATABASE_URL is unset.
if [ -n "$JAWSDB_URL" ] && [ -z "$SPRING_DATASOURCE_URL" ]; then
  SPRING_DATASOURCE_USERNAME=$(printf '%s' "$JAWSDB_URL" | sed -E 's|^mysql://([^:]+):.*|\1|')
  SPRING_DATASOURCE_PASSWORD=$(printf '%s' "$JAWSDB_URL" | sed -E 's|^mysql://[^:]+:([^@]+)@.*|\1|')
  HOSTDB=$(printf '%s' "$JAWSDB_URL" | sed -E 's|^mysql://[^@]+@(.+)$|\1|')
  SPRING_DATASOURCE_URL="jdbc:mysql://${HOSTDB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
  export SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
fi

# --- Heroku-assigned port ---------------------------------------------------
# Heroku requires the dyno to bind $PORT (random per dyno). Map it to Spring's
# SERVER_PORT so we don't have to touch application.properties.
if [ -n "$PORT" ]; then
  export SERVER_PORT="$PORT"
fi

# --- SendGrid add-on -> Spring mail -----------------------------------------
# Heroku injects SENDGRID_USERNAME (usually "apikey") + SENDGRID_PASSWORD (the
# API key). Translate to the MAIL_* vars Spring/application.properties already
# reads. Leave overrides intact if you set them explicitly.
if [ -n "$SENDGRID_PASSWORD" ]; then
  : "${MAIL_HOST:=smtp.sendgrid.net}"
  : "${MAIL_PORT:=587}"
  : "${MAIL_USERNAME:=${SENDGRID_USERNAME:-apikey}}"
  : "${MAIL_PASSWORD:=$SENDGRID_PASSWORD}"
  : "${MAIL_AUTH:=true}"
  : "${MAIL_STARTTLS:=true}"
  export MAIL_HOST MAIL_PORT MAIL_USERNAME MAIL_PASSWORD MAIL_AUTH MAIL_STARTTLS
fi

exec java $JAVA_OPTS -jar /app/app.jar
