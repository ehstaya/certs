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
ENV JAVA_OPTS=""
ENTRYPOINT ["/app/heroku-entrypoint.sh"]
