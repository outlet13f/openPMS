#!/usr/bin/env bash
# openPMS development environment on Podman. Nothing is installed on the host: the JDK, Gradle,
# Postgres and Mailpit all run in containers on one network.
#
#   scripts/dev.sh up            start Postgres 17 (127.0.0.1:55432) and Mailpit (UI http://127.0.0.1:8025)
#   scripts/dev.sh gradle ARGS   run ./gradlew ARGS in a JDK 21 container (e.g. "build", "test", "jooqCodegen")
#   scripts/dev.sh run           build and start the app (dev profile) on http://127.0.0.1:8090; scripts/dev.sh logs follows it
#   scripts/dev.sh psql          open psql in the database container
#   scripts/dev.sh reset-db      drop and recreate the dev database (migrations and dev data run on next start)
#   scripts/dev.sh snapshot-db   save the database as a restore point (app stopped); restore-db goes back to it
#   scripts/dev.sh test          run the JUnit tests on a copy of the restore point
#   scripts/dev.sh perf-seed     add the 1,000-item project of the performance budget (perf-1000) to the dev database
#   scripts/dev.sh down          stop the containers (data stays in volumes)
#
# The dev database password is generated once into backend/.env.local (git-ignored).
#
# A second worktree can run beside the first with its own database and app (nothing shared but the network, Mailpit and
# the Gradle cache), e.g.: OPENPMS_DEV_NAME=openpms-cicd OPENPMS_DEV_DB_PORT=55433 OPENPMS_DEV_APP_PORT=8091 scripts/dev.sh up
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
DEV=${OPENPMS_DEV_NAME:-openpms}          # container and volume prefix: $DEV-db, $DEV-app, $DEV-db-data, $DEV-files
DB_PORT=${OPENPMS_DEV_DB_PORT:-55432}
APP_PORT=${OPENPMS_DEV_APP_PORT:-8090}
JDK_IMAGE=docker.io/library/eclipse-temurin:21-jdk
PG_IMAGE=docker.io/library/postgres:17
MAIL_IMAGE=docker.io/axllent/mailpit:v1.27
export MSYS_NO_PATHCONV=1   # Git Bash: keep container paths as they are
HOST_DIR="$(cygpath -w "$PWD" 2>/dev/null || pwd)"

ensure_infra() {
  podman network exists "$NET" || podman network create "$NET" >/dev/null
  podman volume exists "$DEV-db-data" || podman volume create "$DEV-db-data" >/dev/null
  podman volume exists openpms-gradle || podman volume create openpms-gradle >/dev/null
  podman volume exists openpms-build || podman volume create openpms-build >/dev/null
  podman volume exists "$DEV-files" || podman volume create "$DEV-files" >/dev/null
  if ! podman container exists "$DEV-db"; then
    podman run -d --name "$DEV-db" --network "$NET" -p "127.0.0.1:$DB_PORT:5432" \
      -e POSTGRES_DB=openpms -e POSTGRES_USER=openpms -e POSTGRES_PASSWORD="$OPENPMS_DB_PASSWORD" \
      -v "$DEV-db-data":/var/lib/postgresql/data "$PG_IMAGE" >/dev/null
  fi
  podman start "$DEV-db" >/dev/null
  if ! podman container exists openpms-mail; then
    podman run -d --name openpms-mail --network "$NET" -p 127.0.0.1:8025:8025 "$MAIL_IMAGE" >/dev/null
  fi
  podman start openpms-mail >/dev/null
  for _ in $(seq 1 30); do
    podman exec "$DEV-db" pg_isready -U openpms -d openpms >/dev/null 2>&1 && return 0
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
    -e OPENPMS_DB_URL="jdbc:postgresql://$DEV-db:5432/${DB_NAME:-openpms}" \
    -e OPENPMS_DB_USER=openpms \
    -e OPENPMS_DB_PASSWORD="$OPENPMS_DB_PASSWORD" \
    -e OPENPMS_SMTP_HOST=openpms-mail -e OPENPMS_SMTP_PORT=1025 \
    -e OPENPMS_BASE_URL=http://localhost:5173     ${OPENPMS_JOBS_ENABLED:+-e OPENPMS_JOBS_ENABLED=$OPENPMS_JOBS_ENABLED} \
    "$JDK_IMAGE" "$@"
}

case "${1:-}" in
  up) ensure_infra && echo "db: 127.0.0.1:$DB_PORT  mail UI: http://127.0.0.1:8025" ;;
  gradle) shift; ensure_infra; jdk "" ./gradlew --no-daemon "$@" ;;
  run) ensure_infra; podman rm -f "$DEV-app" >/dev/null 2>&1 || true
       jdk "" ./gradlew --no-daemon -q bootJar
       # run a copy of the jar, so the next build can replace it (and use the Gradle cache) while the app is up
       jdk "-d --name $DEV-app -p 127.0.0.1:$APP_PORT:8090 -v $DEV-files:/data/files" sh -c 'cp build/libs/openpms-0.1.0.jar /tmp/app.jar && exec java -jar /tmp/app.jar --spring.profiles.active=dev' >/dev/null
       for _ in $(seq 1 90); do
         # read the log first: "podman logs | grep -q" fails under pipefail when grep quits early (SIGPIPE, remote podman on macOS)
         log="$(podman logs "$DEV-app" 2>&1)"
         grep -q "Started OpenPmsApplication" <<<"$log" && { echo "app: http://127.0.0.1:$APP_PORT"; exit 0; }
         grep -q "APPLICATION FAILED" <<<"$log" && break
         sleep 2
       done
       podman logs "$DEV-app" 2>&1 | tail -40; exit 1 ;;
  logs) podman logs -f "$DEV-app" ;;
  psql) podman exec -it "$DEV-db" psql -U openpms -d openpms ;;
  reset-db) ensure_infra
            podman exec "$DEV-db" psql -U openpms -d postgres -c "drop database if exists openpms with (force)" -c "create database openpms" ;;
  snapshot-db) # keep the current database as the restore point (the app must be stopped: templates need no connections)
            podman exec "$DEV-db" psql -U openpms -d postgres -c "drop database if exists openpms_seed" -c "create database openpms_seed template openpms" ;;
  restore-db) # back to the restore point in a second; the running app reconnects by itself
            podman exec "$DEV-db" psql -U openpms -d postgres -q -c "drop database if exists openpms with (force)" -c "create database openpms template openpms_seed" ;;
  perf-seed) # the performance budget's project (replica/fixes.md 4); prints the board id and the detail item's number for perf.spec.ts
            podman exec -i "$DEV-db" psql -U openpms -d openpms -q -v ON_ERROR_STOP=1 -c "set client_min_messages = warning" -f - < src/test/resources/perf/perf-1000.sql
            podman exec "$DEV-db" psql -U openpms -d openpms -At -F ' ' -c "select b.id, w.number from projects p join boards b on b.project_id = p.id join work_packages w on w.project_id = p.id and w.subject = '상세 화면 측정용 작업' where p.identifier = 'perf-1000'" ;;
  test) # JUnit on a throwaway copy of the restore point (run snapshot-db once first)
        ensure_infra
        podman exec "$DEV-db" psql -U openpms -d postgres -q -c "drop database if exists openpms_test with (force)" -c "create database openpms_test template openpms_seed"
        shift; DB_NAME=openpms_test jdk "" ./gradlew --no-daemon test "$@" ;;
  down) podman stop "$DEV-app" "$DEV-db" >/dev/null 2>&1 || true
        [ "$DEV" = openpms ] && { podman stop openpms-mail >/dev/null 2>&1 || true; } ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
