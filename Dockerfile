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
# 도움말 shows the user and operator manuals inside the app (read at build time)
COPY docs/deliverables/ /src/docs/deliverables/
RUN npm run build

# 2. the jar, with the web app inside (jOOQ classes are checked in: no database needed to build)
FROM docker.io/library/eclipse-temurin:21-jdk AS jar
WORKDIR /src/backend
COPY backend/ ./
COPY --from=web /src/backend/src/main/resources/static/ src/main/resources/static/
# sh: the wrapper may lose its exec bit on Windows checkouts; a small heap fits a 2 GB build VM
RUN sh ./gradlew --no-daemon -q -Dorg.gradle.jvmargs=-Xmx768m bootJar -x test

# 3. runtime: JRE only, an unprivileged user, attachments and backups on volumes
FROM docker.io/library/eclipse-temurin:21-jre
# pg_dump for the backup before an upgrade (and 관리 · 시스템 정보 · 지금 백업): the PostgreSQL 17 client from the PostgreSQL
# project's apt repository (the distribution's own client is older and cannot dump a version 17 server)
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    && . /etc/os-release \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-17 \
    && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --home-dir /app openpms \
    && mkdir -p /app /data/files /data/backups && chown openpms /data/files /data/backups
COPY --from=jar /src/backend/build/libs/openpms-0.1.0.jar /app/openpms.jar
USER openpms
ENV OPENPMS_FILES_DIR=/data/files OPENPMS_BACKUP_DIR=/data/backups
VOLUME ["/data/files", "/data/backups"]
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/openpms.jar"]
