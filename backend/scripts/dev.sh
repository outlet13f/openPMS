#!/usr/bin/env bash
# openPMS development environment on Podman. Nothing is installed on the host: the JDK, Gradle,
# Postgres and Mailpit all run in containers on one network.
#
#   scripts/dev.sh up            start Postgres 17 (127.0.0.1:55432) and Mailpit (UI http://127.0.0.1:8025)
#   scripts/dev.sh gradle ARGS   run ./gradlew ARGS in a JDK 21 container (e.g. "build", "test", "jooqCodegen")
#   scripts/dev.sh run           build and start the app (dev profile) on http://127.0.0.1:8090; scripts/dev.sh logs follows it
#   scripts/dev.sh psql          open psql in the database container
#   scripts/dev.sh reset-db      drop and recreate the dev database (migrations and dev data run on next start)
#   scripts/dev.sh down          stop the containers (data stays in volumes)
#
# The dev database password is generated once into backend/.env.local (git-ignored).
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=.env.local
if [ ! -f "$ENV_FILE" ]; then
  echo "OPENPMS_DB_PASSWORD=$(openssl rand -hex 16)" > "$ENV_FILE"
  echo "created $ENV_FILE with a new dev database password"
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

NET=openpms-dev
JDK_IMAGE=docker.io/library/eclipse-temurin:21-jdk
PG_IMAGE=docker.io/library/postgres:17
MAIL_IMAGE=docker.io/axllent/mailpit:v1.27
export MSYS_NO_PATHCONV=1   # Git Bash: keep container paths as they are
HOST_DIR="$(cygpath -w "$PWD" 2>/dev/null || pwd)"

ensure_infra() {
  podman network exists "$NET" || podman network create "$NET" >/dev/null
  podman volume exists openpms-db-data || podman volume create openpms-db-data >/dev/null
  podman volume exists openpms-gradle || podman volume create openpms-gradle >/dev/null
  podman volume exists openpms-build || podman volume create openpms-build >/dev/null
  if ! podman container exists openpms-db; then
    podman run -d --name openpms-db --network "$NET" -p 127.0.0.1:55432:5432 \
      -e POSTGRES_DB=openpms -e POSTGRES_USER=openpms -e POSTGRES_PASSWORD="$OPENPMS_DB_PASSWORD" \
      -v openpms-db-data:/var/lib/postgresql/data "$PG_IMAGE" >/dev/null
  fi
  podman start openpms-db >/dev/null
  if ! podman container exists openpms-mail; then
    podman run -d --name openpms-mail --network "$NET" -p 127.0.0.1:8025:8025 "$MAIL_IMAGE" >/dev/null
  fi
  podman start openpms-mail >/dev/null
  for _ in $(seq 1 30); do
    podman exec openpms-db pg_isready -U openpms -d openpms >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "database did not become ready" >&2
  return 1
}

jdk() {
  # $1: extra podman args (ports, name); rest: command
  local extra="$1"
  shift
  # shellcheck disable=SC2086
  podman run --rm $extra --network "$NET" \
    -v "$HOST_DIR:/work" -w /work \
    -v openpms-gradle:/root/.gradle \
    -e GRADLE_USER_HOME=/root/.gradle \
    -e OPENPMS_DB_URL=jdbc:postgresql://openpms-db:5432/openpms \
    -e OPENPMS_DB_USER=openpms \
    -e OPENPMS_DB_PASSWORD="$OPENPMS_DB_PASSWORD" \
    -e OPENPMS_SMTP_HOST=openpms-mail -e OPENPMS_SMTP_PORT=1025 \
    -e OPENPMS_BASE_URL=http://localhost:5173 \
    "$JDK_IMAGE" "$@"
}

case "${1:-}" in
  up) ensure_infra && echo "db: 127.0.0.1:55432  mail UI: http://127.0.0.1:8025" ;;
  gradle) shift; ensure_infra; jdk "" ./gradlew --no-daemon "$@" ;;
  run) ensure_infra; podman rm -f openpms-app >/dev/null 2>&1 || true
       jdk "" ./gradlew --no-daemon -q bootJar
       # run a copy of the jar, so the next build can replace it (and use the Gradle cache) while the app is up
       jdk "-d --name openpms-app -p 127.0.0.1:8090:8090" sh -c 'cp build/libs/openpms-0.1.0.jar /tmp/app.jar && exec java -jar /tmp/app.jar --spring.profiles.active=dev' >/dev/null
       for _ in $(seq 1 90); do
         podman logs openpms-app 2>&1 | grep -q "Started OpenPmsApplication" && { echo "app: http://127.0.0.1:8090"; exit 0; }
         podman logs openpms-app 2>&1 | grep -q "APPLICATION FAILED" && break
         sleep 2
       done
       podman logs openpms-app 2>&1 | tail -40; exit 1 ;;
  logs) podman logs -f openpms-app ;;
  psql) podman exec -it openpms-db psql -U openpms -d openpms ;;
  reset-db) ensure_infra
            podman exec openpms-db psql -U openpms -d postgres -c "drop database if exists openpms with (force)" -c "create database openpms" ;;
  down) podman stop openpms-app openpms-mail openpms-db >/dev/null 2>&1 || true ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
