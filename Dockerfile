# Stage 1: build the Spring Boot jar
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
# Cache deps
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
# Build
COPY src ./src
RUN mvn -B -q -DskipTests package

# Stage 2: minimal runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/target/sf-quiz-app.jar /app/app.jar
# Entrypoint script: translates Heroku's injected env vars (JAWSDB_URL, PORT,
# SENDGRID_*) into the ones Spring already reads. No-op locally.
COPY heroku-entrypoint.sh /app/heroku-entrypoint.sh
RUN chmod +x /app/heroku-entrypoint.sh

EXPOSE 8095

# JVM tuning for a 512 MB Heroku dyno. Set as ENV defaults so they apply on
# every deploy; can be overridden by `heroku config:set JAVA_OPTS=...` if a
# bigger dyno needs different flags.
#   -Xmx320m / -Xms128m       : heap ceiling, headroom for metaspace + native.
#   -XX:MaxMetaspaceSize=160m : caps class metadata (Spring Boot 3 sits ~120m).
#   -XX:+UseSerialGC          : single-thread GC, smallest footprint on 1 vCPU.
#   -XX:+ExitOnOutOfMemoryError: crash + auto-restart, never run in OOM zombie.
#
# MALLOC_ARENA_MAX=2 caps glibc's per-thread malloc arenas (default ~8×vCPU).
# This single env var is the biggest RSS win for a JVM on Heroku — typically
# ~100 MB off resident memory.
ENV JAVA_OPTS="-Xms128m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENV MALLOC_ARENA_MAX=2

ENTRYPOINT ["/app/heroku-entrypoint.sh"]
