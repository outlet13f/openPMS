# openPMS: one image with the web app and the API (Spring Boot serves the built frontend from the same origin).
# Build:  docker build -t openpms .      (or: podman build -t openpms .)
# Run:    docker compose up -d           (compose.yaml: the app and PostgreSQL 17; settings in .env)

# 1. the web app → backend/src/main/resources/static
FROM docker.io/library/node:24-alpine AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# the design tokens are the source of the generated CSS (npm run tokens, run by prebuild)
COPY replica/design/ /src/replica/design/
RUN npm run build

# 2. the jar, with the web app inside (jOOQ classes are checked in: no database needed to build)
FROM docker.io/library/eclipse-temurin:21-jdk AS jar
WORKDIR /src/backend
COPY backend/ ./
COPY --from=web /src/backend/src/main/resources/static/ src/main/resources/static/
# sh: the wrapper may lose its exec bit on Windows checkouts; a small heap fits a 2 GB build VM
RUN sh ./gradlew --no-daemon -q -Dorg.gradle.jvmargs=-Xmx768m bootJar -x test

# 3. runtime: JRE only, an unprivileged user, attachments on a volume
FROM docker.io/library/eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app openpms \
    && mkdir -p /app /data/files && chown openpms /data/files
COPY --from=jar /src/backend/build/libs/openpms-0.1.0.jar /app/openpms.jar
USER openpms
ENV OPENPMS_FILES_DIR=/data/files
VOLUME /data/files
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/openpms.jar"]
