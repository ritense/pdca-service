# Self-contained build: no local JDK or Node needed, `docker build .` from a
# clean checkout produces a runnable image.
#
#   docker build -t pdca-app .
#   docker run --rm -p 7500:7500 pdca-app          # needs the compose deps (see README)
#   docker compose --profile app up -d --build     # entire stack in Docker
#
# All external endpoints are env-configurable (see README "Configuration"):
# PDCA_DB_HOST/PORT/NAME/USER/PASS, GZAC_URL, SERVER_PORT. The registers are
# reached through GZAC and need no settings here.

# ---- Stage 1: frontend bundles (Vite) --------------------------------------
# vite.config.ts writes to ../src/main/resources/static/bundles/react, so the
# output lands in /workspace/src/... and is copied into the backend stage.
# The frontend checks (typecheck, lint, tests) run in ./gradlew build, not here.
FROM node:22-alpine AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- Stage 2: backend jar (Gradle) -----------------------------------------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
# Warm the Gradle distribution + dependency cache in its own layer so source
# changes don't re-download everything.
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src/ src/
COPY --from=frontend /workspace/src/main/resources/static/bundles/react/ src/main/resources/static/bundles/react/
# -PskipFrontend: the bundles come from stage 1, this stage has no Node.
RUN ./gradlew --no-daemon bootJar -PskipFrontend

# ---- Stage 3: runtime ------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S pdca && adduser -S pdca -G pdca
USER pdca
WORKDIR /app
COPY --from=backend /workspace/build/libs/pdca-app-*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 7500
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -qO- "http://127.0.0.1:${SERVER_PORT:-7500}/health" > /dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
