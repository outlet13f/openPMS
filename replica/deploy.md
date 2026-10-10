# 배포 안내 (openPMS 사내 서버 운영)

- 작성: 2026-10-10 · 빌드: 11fb59b
- 기준 파일: `Dockerfile`, `compose.yaml`, `.env.example`, `.dockerignore`, `backend/src/main/resources/application.yml`, `backend/src/main/java/com/openpms/auth/FirstAdmin.java`, `frontend/vite.config.ts`
- 이 문서는 위 파일에 실제로 있는 것만 적습니다. 파일이 바뀌면 이 문서도 같이 고칩니다.

## 구성

| 구성 요소 | 내용 |
| --- | --- |
| `app` 컨테이너 | 이미지 하나에 웹 화면(SPA)과 API가 같이 들어 있습니다. Spring Boot가 빌드된 화면을 같은 출처에서 제공합니다. 컨테이너 안 포트는 8090입니다. |
| `db` 컨테이너 | PostgreSQL 17 (`docker.io/library/postgres:17`). 호스트에 포트를 열지 않고 `app`만 접속합니다. |
| 볼륨 `db` | 데이터베이스 파일 (`/var/lib/postgresql/data`) |
| 볼륨 `files` | 첨부 파일 (`/data/files`) |

```
[사용자 브라우저] --HTTPS--> [사내 리버스 프록시: TLS]
                                   |
                                   v  127.0.0.1:8090
                     [app: SPA + /api/v1, 예약 작업, /data/files]
                                   |
                                   v
                     [db: PostgreSQL 17]
```

- 이미지는 3단계로 만듭니다. 웹 화면 빌드(Node 24) → jar 빌드(JDK 21) → 실행용 이미지(JRE 21만).
- 두 컨테이너 모두 `restart: unless-stopped`입니다. 서버를 재부팅하면 다시 뜹니다(Docker 데몬 또는 Podman 서비스가 켜져 있을 때).
- `app`은 `db`의 헬스 체크(`pg_isready`)가 통과한 뒤에 시작합니다.

## 요구 사항

| 항목 | 내용 |
| --- | --- |
| 컨테이너 런타임 | Docker + Compose, 또는 Podman (`podman compose`) |
| 빌드 메모리 | 2GB면 충분합니다. Gradle 힙을 768MB로 제한해 두었습니다. |
| 실행 메모리 | JVM은 컨테이너 메모리의 75%까지 씁니다(`-XX:MaxRAMPercentage=75`). |
| 디스크 | 이미지 약 405MB, 그 밖에 DB와 첨부 파일, 백업 공간 |
| 빌드 시 네트워크 | 베이스 이미지(node, eclipse-temurin, postgres)와 npm·Gradle 의존성을 내려받습니다. 사내 미러를 쓴다면 미리 맞춰 둡니다. |
| 리버스 프록시 | TLS를 끝내는 사내 프록시(nginx 등). 업로드 한도는 30MB 이상으로 둡니다(첨부 한도 25MB, 앱의 요청 한도 30MB). |
| SMTP | 사내 SMTP 릴레이. 초대·비밀번호 재설정·알림 메일에 씁니다. |

## 처음 설치

1. 저장소를 서버에 받고 저장소 최상위 폴더로 갑니다.
2. 설정 파일을 만듭니다.

   ```bash
   cp .env.example .env
   chmod 600 .env
   ```

3. `.env`를 채웁니다(아래 "환경 변수" 표). 꼭 채울 값: `OPENPMS_BASE_URL`, `OPENPMS_DB_PASSWORD`, SMTP 값, `OPENPMS_MAIL_FROM`, `OPENPMS_ADMIN_EMAIL`.
   DB 비밀번호는 길고 무작위로 만듭니다. 예: `openssl rand -base64 32`
4. 이미지를 빌드하고 띄웁니다.

   ```bash
   docker compose up -d --build
   ```

   Podman이면 `podman compose up -d --build`처럼 `docker` 자리에 `podman`을 씁니다.
5. 로그에서 일회용 설정 링크를 찾습니다.

   ```bash
   docker compose logs app | grep "No administrator yet"
   ```

   이런 줄이 한 번 나옵니다(토큰은 예시).

   ```
   No administrator yet. Set up admin@example.co.kr within 24 hours: https://pms.example.co.kr/invitations/<토큰>
   ```

6. 링크를 브라우저에서 열고 이름과 비밀번호를 정합니다. 이 계정이 첫 관리자입니다.
7. 이후 사용자는 관리 화면에서 초대합니다.

첫 관리자 링크의 규칙(`FirstAdmin.java`):

- 활성 관리자가 없고 `OPENPMS_ADMIN_EMAIL`이 있을 때만 만듭니다. 기본 비밀번호는 없습니다.
- 링크는 24시간 유효하고 한 번만 쓸 수 있습니다.
- 관리자가 생기기 전에는 앱을 다시 시작할 때마다 새 링크가 나오고, 이전 링크는 더 이상 쓸 수 없습니다.
- 관리자가 생긴 뒤에는 다시 시작해도 링크가 나오지 않습니다.
- 그 주소가 이미 초대 상태가 아닌 계정(활성 등)이면 바꾸지 않고 경고만 남깁니다.

## 환경 변수

`.env`는 `compose.yaml` 옆에 둡니다. `app`은 `.env` 전체를 받고, `db`는 `OPENPMS_DB_PASSWORD`만 받습니다.

| 변수 | 뜻 | `.env.example` 값 | 비고 |
| --- | --- | --- | --- |
| `OPENPMS_BASE_URL` | 사용자가 여는 공개 주소. 메일 속 링크와 설정 링크에 씁니다. 예: `https://pms.example.co.kr` | 비어 있음 | 필수 |
| `OPENPMS_DB_PASSWORD` | 함께 뜨는 PostgreSQL의 `openpms` 사용자 비밀번호 | 비어 있음 | 필수. 비어 있으면 compose가 시작을 거부합니다. |
| `OPENPMS_SMTP_HOST` | 사내 SMTP 릴레이 주소 | 비어 있음 | 필수 |
| `OPENPMS_SMTP_PORT` | SMTP 포트 | `25` | |
| `OPENPMS_SMTP_USER` | SMTP 사용자 | 비어 있음 | 인증이 없으면 비워 둡니다. |
| `OPENPMS_SMTP_PASSWORD` | SMTP 비밀번호 | 비어 있음 | 인증이 없으면 비워 둡니다. |
| `OPENPMS_SMTP_STARTTLS` | STARTTLS 사용 | `false` | |
| `OPENPMS_MAIL_FROM` | 보내는 사람 주소. 예: `openPMS <no-reply@example.co.kr>` | 비어 있음 | 필수 |
| `OPENPMS_ADMIN_EMAIL` | 첫 관리자 이메일. 관리자가 없을 때 이 주소로 설정 링크를 만듭니다. | 비어 있음 | 첫 설치에 필수 |
| `OPENPMS_PORT` | 호스트에서 앱이 듣는 포트(127.0.0.1에만) | `8090` | 선택 |
| `OPENPMS_SESSION_TIMEOUT` | 로그인 세션 유지 시간 | `8h` | 선택 |
| `OPENPMS_SECURE_COOKIES` | 세션 쿠키에 `Secure`를 붙임 | `true` | 선택. 프록시가 평문 http일 때(시험용)만 `false` |
| `OPENPMS_JOBS_ENABLED` | 날짜 알림, 알림 메일, 정리 작업을 이 인스턴스에서 돌림 | `true` | 선택 |

`.env`에 넣지 않는 값(compose와 이미지가 정합니다):

| 변수 | 값 | 정하는 곳 |
| --- | --- | --- |
| `OPENPMS_DB_URL` | `jdbc:postgresql://db:5432/openpms` | `compose.yaml` |
| `OPENPMS_DB_USER` | `openpms` | `compose.yaml` |
| `OPENPMS_FILES_DIR` | `/data/files` | `Dockerfile` |

주의: `.env`에 변수 줄을 두고 값을 비우면 빈 문자열이 들어갑니다. `application.yml`의 기본값(예: 보내는 사람 `openPMS <no-reply@example.com>`)은 변수가 아예 없을 때만 쓰입니다. 필수 값은 반드시 채웁니다.

## 리버스 프록시·TLS

- 앱은 호스트의 `127.0.0.1:${OPENPMS_PORT}`(기본 8090)에서만 듣습니다. 다른 서버에서 바로 들어올 수 없습니다.
- TLS는 사내 리버스 프록시가 끝내고, 프록시가 `http://127.0.0.1:8090`으로 넘깁니다.
- `OPENPMS_BASE_URL`은 반드시 사용자가 브라우저에 치는 공개 주소(https)로 둡니다. 틀리면 메일과 설정 링크가 엉뚱한 곳(예: localhost)을 가리킵니다.
- `OPENPMS_SECURE_COOKIES=true`(기본)이면 세션 쿠키가 https에서만 갑니다. 프록시가 https라면 그대로 둡니다. 평문 http로 시험할 때만 `false`로 바꾸고, 운영에서는 다시 `true`로 둡니다.
- 프록시의 업로드 한도(nginx의 `client_max_body_size`)는 30MB 이상으로 둡니다.
- `/actuator/**`(헬스 체크)는 로그인 없이 응답합니다. 외부에 열 필요가 없으면 프록시에서 막는 것을 권합니다.

## 백업과 복원

백업 대상은 두 가지입니다. **DB 덤프**와 **`files` 볼륨(첨부 파일)**. 둘을 같은 시각에 받습니다.

### DB 백업

`compose.yaml` 머리말의 명령입니다. 저장소 최상위 폴더에서 실행합니다.

```bash
docker compose exec -T db pg_dump -U openpms -Fc openpms > openpms-$(date +%F).dump
```

- `-Fc`는 PostgreSQL 사용자 지정 형식(압축)입니다. 복원은 `pg_restore`로 합니다.
- 파일로 받을 때는 `-T`(TTY 끄기)를 붙입니다. `compose.yaml` 주석에는 `-T`가 없습니다. TTY가 붙으면 바이너리 덤프가 깨질 수 있습니다.
- 매일 받도록 호스트 cron에 등록합니다(설계: `architecture.md` §1 배포 구성).

### 첨부 파일 백업

```bash
docker compose exec -T app tar czf - -C /data/files . > files-$(date +%F).tar.gz
```

### DB 복원

앱을 멈추고, DB를 새로 만든 뒤 덤프를 넣습니다.

```bash
docker compose stop app
docker compose exec -T db dropdb -U openpms openpms
docker compose exec -T db createdb -U openpms -O openpms openpms
docker compose exec -T db pg_restore -U openpms -d openpms --no-owner < openpms-2026-10-10.dump
docker compose start app
```

복원 연습(운영 DB를 건드리지 않음): 새 DB를 만들어 넣고 확인한 뒤 지웁니다.

```bash
docker compose exec -T db createdb -U openpms restore_check
docker compose exec -T db pg_restore -U openpms -d restore_check --no-owner < openpms-2026-10-10.dump
docker compose exec -T db psql -U openpms -d restore_check -c "select count(*) from projects; select count(*) from work_packages;"
docker compose exec -T db dropdb -U openpms restore_check
```

### 첨부 파일 복원

```bash
docker compose exec -T app tar xzf - -C /data/files < files-2026-10-10.tar.gz
```

## 업그레이드

1. **먼저 백업합니다**(DB와 첨부 파일 모두).
2. 새 소스를 받습니다(`git pull` 등).
3. 이미지를 다시 빌드하고 띄웁니다.

   ```bash
   docker compose up -d --build
   ```

4. 앱이 시작할 때 Flyway가 새 DB 마이그레이션을 자동으로 적용합니다. 로그와 헬스 체크로 확인합니다.

   ```bash
   docker compose logs -f app
   curl -s http://127.0.0.1:8090/actuator/health
   ```

- 마이그레이션이 실패하면 앱이 뜨지 않습니다. 원인을 고치거나, 이전 소스로 되돌려 빌드하고 백업에서 DB를 복원합니다.
- 세션은 DB(Spring Session JDBC)에 있으므로 재시작해도 사용자는 다시 로그인하지 않아도 됩니다.

## 헬스 체크

호스트에서 확인합니다(앱이 127.0.0.1에만 열려 있음).

| 경로 | 용도 |
| --- | --- |
| `/actuator/health` | 전체 상태 (`UP`/`DOWN`) |
| `/actuator/health/liveness` | 살아 있는지 (프로세스 재시작 판단용) |
| `/actuator/health/readiness` | 요청을 받을 준비가 됐는지 |

```bash
curl -s http://127.0.0.1:8090/actuator/health
# {"status":"UP"}
```

- **메일 헬스는 일부러 껐습니다**(`management.health.mail.enabled: false`). SMTP 릴레이가 잠깐 죽어도 앱은 계속 동작해야 하기 때문입니다. 실패한 메일은 로그에 남고, 알림 메일은 예약 작업이 다시 보냅니다.
- SMTP 연결·읽기·쓰기 시간 제한은 각각 10초입니다. 기본값은 제한 없음이라, 응답 없는 릴레이가 스레드를 붙잡지 않게 넣었습니다.
- 그래서 `DOWN`이면 메일이 아니라 DB 연결 같은 다른 원인을 먼저 봅니다.

## 문제 해결

| 증상 | 확인할 것 |
| --- | --- |
| `set OPENPMS_DB_PASSWORD in .env` 오류로 시작이 안 됨 | `.env`가 `compose.yaml` 옆에 있는지, `OPENPMS_DB_PASSWORD`를 채웠는지 |
| 로그에 설정 링크가 없음 | `OPENPMS_ADMIN_EMAIL`이 비어 있지 않은지. 이미 활성 관리자가 있으면 나오지 않습니다. 그 주소가 이미 활성 계정이면 `OPENPMS_ADMIN_EMAIL ... not changing it` 경고만 나옵니다. |
| 설정 링크가 410(만료) | 24시간이 지났거나, 이미 썼거나, 그 뒤 앱을 다시 시작해 새 링크로 바뀐 경우입니다. 관리자가 아직 없으면 `docker compose restart app` 후 로그의 새 링크를 씁니다. |
| 메일·설정 링크가 localhost 등을 가리킴 | `OPENPMS_BASE_URL`을 공개 주소로 고치고 `docker compose up -d`로 다시 띄웁니다. |
| 로그인해도 곧바로 로그인 화면으로 돌아옴 | 평문 http로 접속 중인데 `OPENPMS_SECURE_COOKIES=true`인지. 운영은 https로 접속합니다. |
| 로그인 상태인데 화면이 로그인 페이지에 머묾, 저장이 안 됨 | 화면이 실제 API가 아닌 브라우저 안 가짜 데이터로 빌드된 경우입니다. 지금은 `.env.production`이 빠지면 빌드가 실패하므로, 최신 소스로 이미지를 다시 빌드합니다. |
| 빌드가 `production build without VITE_API=http`로 실패 | `frontend/.env.production`이 빌드에 들어가지 않았습니다. 파일이 있는지, `.dockerignore`의 `!frontend/.env.production` 줄이 있는지 봅니다. |
| 헬스가 `DOWN` | `docker compose ps`로 `db`가 healthy인지, `docker compose logs app`에서 DB 연결 오류를 봅니다. 메일 헬스는 꺼져 있어 SMTP는 원인이 아닙니다. |
| 메일이 오지 않음 | SMTP 값(`HOST`·`PORT`·`USER`·`PASSWORD`·`STARTTLS`)과 `OPENPMS_MAIL_FROM`. 릴레이가 응답하지 않으면 10초 뒤 실패가 로그에 남습니다. 알림 메일은 `OPENPMS_JOBS_ENABLED=true`일 때만 나갑니다. |
| 큰 파일 업로드가 실패 | 프록시 업로드 한도(30MB 이상). 앱의 첨부 한도는 25MB입니다. |
| `.env`의 DB 비밀번호를 바꿨더니 앱이 DB에 못 붙음 | PostgreSQL 이미지는 `db` 볼륨을 처음 만들 때만 비밀번호를 정합니다. 바꾸려면 DB 안에서 `ALTER USER openpms PASSWORD '...'`를 먼저 하고 `.env`를 같은 값으로 고칩니다. |

## 보안 메모

- 앱은 권한 없는 사용자 `openpms`(uid 10001)로 돕니다. jar(`/app`)는 root 소유이고, 이 사용자에게 넘긴 폴더는 첨부 파일용 `/data/files`뿐입니다.
- 비밀값(DB·SMTP 비밀번호)은 `.env`에만 둡니다. `.env`는 git에서 제외되고(`.gitignore`), `.dockerignore`로 이미지에도 들어가지 않습니다. 저장소에는 값이 빈 `.env.example`만 있습니다.
- `frontend/.env.production`은 비밀값이 아닙니다. 화면을 실제 API에 붙이는 설정(`VITE_API=http`) 하나뿐입니다.
- 운영 웹 빌드는 `VITE_API=http`가 없으면 빌드를 거부합니다(`vite.config.ts`). 가짜 데이터 계층이 실린 이미지가 나가는 일을 막습니다.
- 앱은 127.0.0.1에만 열리고, DB는 호스트에 포트를 열지 않습니다.
- 세션 쿠키 `OPENPMS_SESSION`은 HttpOnly, SameSite=Lax, 기본 Secure입니다. 오류 응답에 메시지와 스택 추적을 넣지 않습니다.
- 기본 비밀번호는 없습니다. 첫 관리자 설정 링크는 로그에 한 번 나옵니다. 링크가 유효한 24시간 동안은 로그를 다룰 때 주의합니다.

## 검증 기록

2026-10-10, 빌드한 이미지를 Podman으로 띄워 확인했습니다. 포트 18090, 버리는 DB를 썼습니다.

| 항목 | 결과 |
| --- | --- |
| 이미지 | 405MB. 사용자 `openpms`(uid 10001)로 실행 |
| 새 DB | 마이그레이션 9개 적용, 약 5초 만에 시작 |
| 헬스 | 처음엔 `DOWN`: 메일 헬스 지표가 SMTP에 닿지 못함. 메일 헬스를 끄고 SMTP 시간 제한을 넣음(기본값은 제한 없음). 고친 뒤 `UP` |
| 첫 관리자 링크 | 관리자가 없는 동안 재시작마다 링크가 바뀌고, 이전 링크는 410. 쓴 링크도 410. 관리자가 생긴 뒤 재시작하면 링크가 나오지 않음 |
| **실제 버그** | `.dockerignore`가 `frontend/.env.production`을 빼서, 이미지에 브라우저 안 가짜 데이터 계층이 실림(서버 세션이 있어도 로그인 화면에 머묾). 이 파일을 넣도록 고치고, `vite.config.ts`가 `VITE_API=http` 없는 운영 빌드를 실패시키게 함 |
| 브라우저 점검 | 이미지에서 Chrome으로, 서버 CSP 아래에서: 초대 링크로 가입, 프로젝트·작업 만들기, 홈·관리·보드·Gantt·계정 화면 열기, 로그아웃 후 폼 로그인. 로그아웃 상태의 예상된 401 말고는 콘솔 오류 없음, 5xx 없음 |
| 세션 | 앱을 다시 시작해도 로그인 유지(Spring Session JDBC) |
| 첨부 저장소 | `/data/files`에 쓰기 가능 |
| 백업·복원 | `pg_dump`로 152KB 덤프, 새 DB에 `pg_restore` 성공(프로젝트 1개, 작업 1개) |
| 데모 데이터 | 운영에는 데모 데이터가 없음. V3 시드는 기준 데이터만 |

고친 내용은 커밋 11fb59b에 있습니다.
