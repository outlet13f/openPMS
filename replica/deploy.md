# 배포 안내 (openPMS 사내 서버 운영)

- 작성: 2026-10-10 · 빌드: 11fb59b · 갱신: 2026-10-11 (384a2f9: 프록시 뒤 클라이언트 주소, SSO 사용자의 비밀번호 만들기 / 86e58a9: 마이그레이션 V11–V15, API 토큰, 웹훅, PDF 글꼴 / 99e0287: 마이그레이션 V16(Idempotency-Key), 웹훅 사내망 주소 막기 `OPENPMS_WEBHOOK_BLOCK_PRIVATE` / 3f9d5c0: 마이그레이션 V17(시간 기록), 새 모듈 `time_tracking` / 5966874: 업그레이드 전 자동 DB 백업과 관리 · 시스템 정보, 볼륨 `backups`, 이미지에 PostgreSQL 17 클라이언트, `OPENPMS_BACKUP_*`)
- 기준 파일: `Dockerfile`, `compose.yaml`, `.env.example`, `.dockerignore`, `backend/src/main/resources/application.yml`, `backend/src/main/java/com/openpms/auth/FirstAdmin.java`, `frontend/vite.config.ts`, `backend/src/main/java/com/openpms/auth/ApiTokenFilter.java`, `backend/src/main/java/com/openpms/webhook/Webhooks.java`, `backend/src/main/java/com/openpms/common/IdempotencyFilter.java`, `backend/src/main/resources/fonts/`, `backend/src/main/java/com/openpms/system/Backups.java`·`MigrateWithBackup.java`·`SystemController.java`(5966874)
- 이 문서는 위 파일에 실제로 있는 것만 적습니다. 파일이 바뀌면 이 문서도 같이 고칩니다.

## 구성

| 구성 요소 | 내용 |
| --- | --- |
| `app` 컨테이너 | 이미지 하나에 웹 화면(SPA)과 API가 같이 들어 있습니다. Spring Boot가 빌드된 화면을 같은 출처에서 제공합니다. 컨테이너 안 포트는 8090입니다. |
| `db` 컨테이너 | PostgreSQL 17 (`docker.io/library/postgres:17`). 호스트에 포트를 열지 않고 `app`만 접속합니다. |
| 볼륨 `db` | 데이터베이스 파일 (`/var/lib/postgresql/data`) |
| 볼륨 `files` | 첨부 파일 (`/data/files`) |
| 볼륨 `backups` | 앱이 받은 DB 덤프 (`/data/backups`, 5966874). 업그레이드 전 자동 백업과 관리 · 시스템 정보의 **지금 백업** |

```
[사용자 브라우저] --HTTPS--> [사내 리버스 프록시: TLS]
                                   |
                                   v  127.0.0.1:8090
                     [app: SPA + /api/v1, 예약 작업, /data/files, /data/backups]
                                   |   (pg_dump: 업그레이드 전·지금 백업)
                                   v
                     [db: PostgreSQL 17]
```

- 이미지는 3단계로 만듭니다. 웹 화면 빌드(Node 24) → jar 빌드(JDK 21) → 실행용 이미지(JRE 21, 5966874부터 PostgreSQL 17 클라이언트 `pg_dump`·`pg_restore`도. Ubuntu 배포판의 클라이언트는 버전이 낮아 17 서버를 덤프하지 못하므로 PostgreSQL 프로젝트의 apt 저장소 `apt.postgresql.org`(서명 키 ACCC4CF8)에서 `postgresql-client-17`을 설치).
- 두 컨테이너 모두 `restart: unless-stopped`입니다. 서버를 재부팅하면 다시 뜹니다(Docker 데몬 또는 Podman 서비스가 켜져 있을 때).
- `app`은 `db`의 헬스 체크(`pg_isready`)가 통과한 뒤에 시작합니다.

## 요구 사항

| 항목 | 내용 |
| --- | --- |
| 컨테이너 런타임 | Docker + Compose, 또는 Podman (`podman compose`) |
| 빌드 메모리 | 2GB면 충분합니다. Gradle 힙을 768MB로 제한해 두었습니다. |
| 실행 메모리 | JVM은 컨테이너 메모리의 75%까지 씁니다(`-XX:MaxRAMPercentage=75`). |
| 디스크 | 이미지 약 405MB(2026-10-10 측정. 그 뒤 PDF용 글꼴 파일 2개 약 5.4MB와 PDFBox가 jar에 더해짐, 다시 재지 않음), 그 밖에 DB와 첨부 파일, 백업 공간(5966874부터 `backups` 볼륨에 덤프가 종류마다 5개까지, 덤프 하나는 대략 DB 크기. 아래 "백업과 복원"). 웹훅 전송 기록(`webhook_deliveries`)은 끝난 지 30일 지나면 `token-cleanup`이 지웁니다(dfde416, 아래 "웹훅과 API 토큰") |
| 빌드 시 네트워크 | 베이스 이미지(node, eclipse-temurin, postgres)와 npm·Gradle 의존성을 내려받습니다. 5966874부터 실행용 이미지 단계가 Ubuntu 패키지 저장소, `apt.postgresql.org`, `www.postgresql.org`(서명 키)에도 접속합니다. 사내 미러를 쓴다면 미리 맞춰 둡니다. |
| 리버스 프록시 | TLS를 끝내는 사내 프록시(nginx 등). 업로드 한도는 30MB 이상으로 둡니다(첨부 한도 25MB, 앱의 요청 한도 30MB). |
| SMTP | 사내 SMTP 릴레이. 초대·비밀번호 재설정·알림 메일에 씁니다. |
| 나가는 네트워크(웹훅) | 웹훅을 쓰면 `app` 컨테이너가 받는 쪽 주소(http/https)로 나갈 수 있어야 합니다. 사내 방화벽의 egress 허용, DNS, https면 JRE가 그 인증서를 믿어야 합니다(사내 CA를 넣는 단계는 이미지에 없음). 웹훅을 안 쓰면 필요 없습니다. |

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
| `OPENPMS_TRUSTED_PROXIES` | `X-Forwarded-For`·`X-Forwarded-Proto`를 믿을 프록시 주소(정규식). 넣으면 기본값을 통째로 바꿉니다 | 주석(기본값 사용) | 선택. 기본: 이 호스트(`127.x`, `::1`), Docker 브리지(`172.16`–`172.31.x`), Podman 네트워크(`10.88.x`, `10.89.x`). 아래 "리버스 프록시·TLS" |
| `OPENPMS_FORWARD_HEADERS` | 프록시 헤더 처리 방식. `native`(기본)는 위 주소에서 온 헤더를 씀, `none`은 헤더를 무시 | 주석(`native`) | 선택. 프록시 없이 앱에 바로 붙을 때만 `none` |
| `OPENPMS_WEBHOOK_BLOCK_PRIVATE` | `true`면 웹훅을 루프백·사설 주소(127.x, 10.x, 172.16–31.x, 192.168.x, 100.64.0.0/10, fc00::/7, 0.0.0.0)로 보내지 않음. 등록할 때 422, 보낼 때도 DNS를 다시 풀어 막고 기록에 "보내지 않음: …". 링크 로컬(169.254.x, fe80:, metadata.google.internal)은 이 값과 상관없이 늘 막음 | 주석(`false`) | 선택(06b48f5). 받는 사내 시스템이 사설 주소에 있으면 켤 수 없음. 아래 "웹훅" |
| `OPENPMS_BACKUP_BEFORE_UPGRADE` | `true`면 시작할 때 스키마가 이미 있고 적용할 마이그레이션이 있으면 먼저 `pg_dump`로 덤프하고, 실패하면 마이그레이션하지 않고 시작을 멈춤. `false`면 덤프 없이 마이그레이션 | 주석(`true`) | 선택(5966874). `false`는 따로 덤프를 받아 둔 뒤 백업 실패를 우회할 때만. `dev` 프로필 기본은 `false` |
| `OPENPMS_BACKUP_KEEP` | 종류(업그레이드 전, 직접 백업)마다 남길 최신 덤프 수. 새 덤프를 쓴 뒤 오래된 것을 지움 | 주석(`5`) | 선택(5966874). 1보다 작으면 1 |

`.env`에 넣지 않는 값(compose와 이미지가 정합니다):

| 변수 | 값 | 정하는 곳 |
| --- | --- | --- |
| `OPENPMS_DB_URL` | `jdbc:postgresql://db:5432/openpms` | `compose.yaml` |
| `OPENPMS_DB_USER` | `openpms` | `compose.yaml` |
| `OPENPMS_FILES_DIR` | `/data/files` | `Dockerfile` |
| `OPENPMS_BACKUP_DIR` | `/data/backups` | `Dockerfile`(볼륨 선언, `compose.yaml`이 이름 있는 볼륨 `backups`를 붙임, 5966874). `dev` 프로필 기본은 `/tmp/openpms-backups` |

주의: `.env`에 변수 줄을 두고 값을 비우면 빈 문자열이 들어갑니다. `application.yml`의 기본값(예: 보내는 사람 `openPMS <no-reply@example.com>`)은 변수가 아예 없을 때만 쓰입니다. 필수 값은 반드시 채웁니다.

## 리버스 프록시·TLS

- 앱은 호스트의 `127.0.0.1:${OPENPMS_PORT}`(기본 8090)에서만 듣습니다. 다른 서버에서 바로 들어올 수 없습니다.
- TLS는 사내 리버스 프록시가 끝내고, 프록시가 `http://127.0.0.1:8090`으로 넘깁니다.
- `OPENPMS_BASE_URL`은 반드시 사용자가 브라우저에 치는 공개 주소(https)로 둡니다. 틀리면 메일과 설정 링크가 엉뚱한 곳(예: localhost)을 가리킵니다.
- `OPENPMS_SECURE_COOKIES=true`(기본)이면 세션 쿠키가 https에서만 갑니다. 프록시가 https라면 그대로 둡니다. 평문 http로 시험할 때만 `false`로 바꾸고, 운영에서는 다시 `true`로 둡니다.
- 프록시의 업로드 한도(nginx의 `client_max_body_size`)는 30MB 이상으로 둡니다.
- `/actuator/**`(헬스 체크)는 로그인 없이 응답합니다. 외부에 열 필요가 없으면 프록시에서 막는 것을 권합니다.

### 프록시 헤더와 클라이언트 주소 (384a2f9, 90006c2)

프록시 뒤에서는 모든 요청이 프록시 주소에서 옵니다. 앱은 로그인·재설정 메일 요청 제한을 클라이언트 주소마다 세므로, 프록시가 보낸 헤더에서 진짜 주소를 꺼내야 사람마다 따로 셉니다. 고치기 전에는 프록시 뒤의 모든 사람이 분당 20회 한도 하나를 같이 썼습니다(`replica/bugs.md` BUG-008).

- 프록시는 두 헤더를 꼭 보냅니다. nginx 예(`.env.example` 주석과 같음):

  ```nginx
  location / {
      proxy_pass http://127.0.0.1:8090;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      client_max_body_size 30m;
  }
  ```

- 앱은 `application.yml`의 `server.forward-headers-strategy: ${OPENPMS_FORWARD_HEADERS:native}`(Tomcat RemoteIpValve)로 헤더를 읽습니다. 헤더는 `server.tomcat.remoteip.internal-proxies: ${OPENPMS_TRUSTED_PROXIES:...}`에 맞는 주소에서 온 요청일 때만 믿습니다. 기본 정규식은 `127\.\d+\.\d+\.\d+|0:0:0:0:0:0:0:1|::1|172\.(1[6-9]|2[0-9]|3[01])\.\d+\.\d+|10\.8[89]\.\d+\.\d+`입니다(90006c2에서 Podman의 10.88·10.89 추가). 이 호스트의 프록시가 compose 포트(127.0.0.1:8090)로 들어오면 앱에는 보통 Docker 브리지 주소로 보이므로 기본값으로 맞습니다. rootless Podman은 게시한 포트로 들어온 연결을 컨테이너 자신의 주소(예: 10.89.1.3, `/proc/net/tcp`에서 확인)에서 온 것으로 앱에 넘깁니다. 이 대역도 기본값에 있으므로 Podman에서도 따로 정할 것이 없습니다.
- 다른 곳에서 보낸 헤더는 무시합니다. 사람이 `X-Forwarded-For`에 가짜 주소를 앞에 붙여 보내도, 앱은 오른쪽에서부터 믿는 프록시를 건너뛴 첫 주소(프록시가 붙인 실제 주소)를 씁니다. 그래서 헤더를 바꿔 요청 제한을 피할 수 없습니다(`ClientAddressIT`).
- compose 네트워크 대역이 172.16–31(Docker)이나 10.88·10.89(Podman)가 아니면 기본값이 맞지 않습니다. 이때는 모든 사람이 다시 프록시 주소 하나로 보입니다. Docker가 192.168.x 풀을 쓰는 경우가 그렇습니다. 앱이 보는 주소는 보통 그 네트워크의 게이트웨이입니다(`docker network inspect <프로젝트>_default`의 Gateway). 그 주소가 맞도록 `.env`에 정규식을 넣습니다. 예: `OPENPMS_TRUSTED_PROXIES=127\.\d+\.\d+\.\d+|0:0:0:0:0:0:0:1|::1|192\.168\.\d+\.\d+`. 넣은 값이 기본값을 대신하므로 127.x 등도 같이 적습니다.
- 프록시 앞에 부하 분산기가 하나 더 있으면 그 주소도 정규식에 넣습니다. 넣지 않으면 모든 사람이 부하 분산기 주소로 보입니다.
- 프록시 없이 앱에 바로 붙는 구성(시험 등)에서는 `OPENPMS_FORWARD_HEADERS=none`으로 헤더를 무시합니다.
- 검증: JUnit `ClientAddressIT`(무작위 포트의 실제 HTTP, 이 호스트를 프록시로 보고 `X-Forwarded-For`로 두 사람을 흉내 냄: 한 사람이 한도를 넘어도 다른 사람은 401로 계속 확인받음, 앞에 가짜 주소를 붙여도 429 유지). 운영 이미지(90006c2, Podman)로는 2026-10-11에 호스트에서 게시 포트로 확인했습니다(아래 "검증 기록"의 두 번째 1.2 재검증). 실제 사내 프록시(nginx)로는 아직 확인하지 않았습니다. 설치 때 아래 문제 해결의 "모든 사람이 로그인할 때 429" 줄로 확인합니다.

## 사내 SSO 연결

- 기준: 커밋 a677079 (`OidcProviders.java`, `OidcLogin.java`, `OidcController.java`, `SecurityConfig.java`, `V10__oidc.sql`, `frontend/src/pages/admin/authentication.tsx`)
- 회사 계정 시스템(IdP)으로 OpenID Connect 로그인을 합니다. 공급자마다 로그인 화면에 "<버튼 이름>(으)로 로그인" 버튼이 하나씩 생깁니다.
- 이메일·비밀번호 로그인은 그대로 남습니다. 끄는 설정은 없습니다.
- `.env`에 넣을 값은 없습니다. 공급자는 관리 화면에서 등록하고 DB에 저장합니다.

### 순서

1. 공급자 식별자를 정합니다. 예: `company`. 영문 소문자·숫자·하이픈으로 40자까지입니다. 콜백 주소에 들어가고, 만든 뒤에는 바꿀 수 없습니다.
2. IdP에 클라이언트(앱)를 등록합니다(아래 "IdP에 등록할 것").
3. openPMS에서 **관리 → 로그인·비밀번호 → 사내 SSO (OpenID Connect) → SSO 공급자 추가**에 값을 넣고 저장합니다.
4. 로그아웃한 뒤 로그인 화면의 버튼으로 시험합니다. 먼저 기존 계정이 있는 사람으로 해 봅니다.

### `OPENPMS_BASE_URL`은 공개 주소여야 합니다

- 콜백 주소 = `OPENPMS_BASE_URL` + `/api/v1/auth/oidc/callback/{식별자}`. 예: `https://pms.example.co.kr/api/v1/auth/oidc/callback/company`
- openPMS는 이 주소를 IdP에 `redirect_uri`로 보냅니다. IdP에 등록한 값과 글자 하나까지 같아야 합니다.
- 로그인 시작 때 state·nonce·PKCE 값을 세션에 둡니다. 콜백 주소의 호스트가 사용자가 연 주소와 다르면 세션 쿠키가 따라가지 않아 `failed`가 됩니다.
- 끝의 `/`는 openPMS가 떼어 냅니다. `https://`로 둡니다(`OPENPMS_SECURE_COOKIES=true`와 같이).
- 주소를 바꾸면 IdP의 리디렉션 URI도 같이 바꿉니다.

### IdP에 등록할 것

| 항목 | 값 |
| --- | --- |
| 클라이언트 종류 | 비밀값이 있는(confidential) 웹 앱, 권한 코드 흐름(authorization code) |
| 리디렉션 URI | 위의 콜백 주소. 저장한 뒤에는 openPMS 목록과 수정 대화상자에 "콜백 주소"로 보입니다. |
| 범위(scope) | `openid email profile` (openPMS 기본값). `openid`는 꼭 있어야 합니다. |
| PKCE | openPMS는 항상 S256으로 보냅니다. IdP에서 PKCE를 필수로 켜도 됩니다. |
| 토큰에 담길 값 | `sub`, 이메일(`email`), 이름(`name`, 없으면 `family_name`·`given_name`) |
| 로그아웃 주소 | 등록하지 않습니다. openPMS는 IdP 로그아웃을 부르지 않습니다. |

- `app` 컨테이너가 IdP의 발견 문서·토큰·키(JWKS)·userinfo 주소에 직접 접속할 수 있어야 합니다. 브라우저만 닿는 주소면 안 됩니다. 사내 방화벽·DNS를 확인합니다.
- IdP가 사내 CA 인증서로 https를 쓰면 JRE가 그 CA를 믿어야 합니다. 지금 이미지에는 사내 CA를 넣는 단계가 없습니다.

### openPMS에 넣을 것

| 칸 | 내용 |
| --- | --- |
| 버튼 이름 | 로그인 화면에 "…(으)로 로그인"으로 보입니다. 60자까지. 예: `사내 계정` |
| 식별자 | 위 1번에서 정한 값. 처음 만들 때만 입력합니다. |
| 발급자(Issuer) 주소 | IdP의 issuer. 저장할 때 `{issuer}/.well-known/openid-configuration`을 읽고, 문서 안의 issuer가 입력값과 같은지 봅니다. 끝의 `/`까지 같아야 합니다. 못 읽으면 칸 옆에 오류가 나고 저장되지 않습니다. |
| 클라이언트 ID | IdP에서 받은 값 |
| 클라이언트 시크릿 | IdP에서 받은 값. 저장한 뒤에는 화면·API에 다시 나오지 않습니다. 수정할 때 비워 두면 저장된 값을 그대로 씁니다. |
| 범위(scope) | 공백으로 구분합니다. 기본 `openid email profile` |
| 이메일 클레임 | 기본 `email`. 주소가 다른 클레임에 오면 그 이름을 씁니다. |
| 이름 클레임 | 기본 `name`. 새 계정을 만들 때만 씁니다. 없으면 `family_name`·`given_name`(한글이면 성+이름을 붙여서), 그다음 이메일 앞부분 |
| 계정이 없으면 처음 로그인할 때 만들기 | 기본 꺼짐. 켜면 새 계정은 활성, 관리자 아님, 비밀번호 없음입니다. 프로젝트 멤버로 추가해야 프로젝트가 보입니다. |
| 사용 | 끄면 로그인 버튼이 사라집니다. 연결 정보는 남습니다. |

- 공급자를 삭제하면 그 공급자와의 연결만 지워지고 계정은 남습니다. 비밀번호가 없는 사람은 비밀번호 재설정이나 다른 공급자로 로그인해야 합니다. 미리 계정 설정에서 비밀번호를 만들어 두면 이메일·비밀번호로도 들어옵니다(아래 "비밀번호가 없는 SSO 사용자").
- 발견 문서는 첫 로그인 때 읽어 메모리에 둡니다. 공급자를 수정·삭제하면 다시 읽습니다. IdP에 닿지 못하면 버튼이 `unavailable`로 돌아오고, 다음 시도 때 다시 읽습니다.

### 누가 로그인되나

1. 이 공급자의 `sub`에 이미 연결된 계정
2. 없으면 이메일이 같은 계정(대소문자 무시). 이때 연결을 만듭니다. 공급자가 `email_verified=false`를 보내면 거부합니다(`unverified`). 값이 아예 없으면 확인된 것으로 봅니다.
3. 없고 "계정 만들기"가 켜져 있으면 새 계정
4. 그 밖에는 거부합니다(`no_account`). 관리자가 먼저 초대해야 합니다.

- 한 번 연결되면 IdP에서 이메일이 바뀌어도 같은 계정으로 들어옵니다. openPMS 계정의 이메일은 바꾸지 않습니다.
- 계정 하나에는 공급자마다 `sub` 하나만 연결됩니다. 같은 이메일로 다른 `sub`가 들어오면 새 것으로 바뀝니다.
- 잠긴 계정(관리자 잠금, 로그인 실패 잠금 시간 중)은 거부합니다(`locked`). 삭제된 계정은 `no_account`입니다.
- 초대 상태인 계정은 SSO로 로그인하면 활성이 됩니다(초대 링크 없이).
- 로그인하면 폼 로그인과 같은 openPMS 세션을 씁니다. 유지 시간은 `OPENPMS_SESSION_TIMEOUT`입니다. 로그인 실패 횟수는 0으로 돌아갑니다.

### 비밀번호가 없는 SSO 사용자 (384a2f9)

- SSO 자동 생성으로 생긴 계정은 비밀번호가 없습니다(`password_hash` null). 계정 설정(S37)의 "비밀번호" 절이 "{공급자}(으)로 로그인하고 있어 비밀번호가 없습니다…"라고 알리고 **비밀번호 만들기 링크 받기** 버튼을 보여 줍니다. 현재 비밀번호를 묻는 바꾸기 폼은 나오지 않습니다.
- 버튼은 `POST /api/v1/me/password/setup`을 부릅니다. 202이면 본인 주소로 "[openPMS] 비밀번호 만들기" 메일이 갑니다. 링크는 비밀번호 재설정 화면(`/password-reset?token=…`)이고, 재설정과 같은 일회용 토큰(`password_reset`, 유효 시간 `auth.password_reset_ttl_minutes`)입니다. 새 링크를 받으면 이전 링크는 무효가 됩니다.
- 관리자가 "비밀번호 재설정 허용"을 꺼도 이 링크는 보낼 수 있습니다. 링크가 본인 메일로만 가므로, 남의 열린 세션을 잠깐 빌린 사람이 비밀번호를 더해 들어올 길을 만들 수 없습니다.
- 이미 비밀번호가 있으면 422 "이미 비밀번호가 있습니다."입니다. 요청은 사용자당 분당 3회로 제한합니다.
- 비밀번호가 있고 SSO에도 연결된 사람은 비밀번호 절 안내에 "사내 계정({공급자})으로도 로그인할 수 있습니다."가 붙습니다(`Me.hasPassword`, `Me.signInWith`).
- 메일이 오려면 SMTP가 동작해야 합니다. 검증: JUnit `OidcIT`(비밀번호 없음 → 202, 토큰 1개 / 비밀번호 있음 → 422), e2e `f00-sso` F00-S4.

### IdP별 메모

일반적인 OIDC 설정만 적습니다. 메뉴 이름은 IdP 버전에 따라 다를 수 있습니다.

**Keycloak**

- 발급자: `https://<호스트>/realms/<realm>` (예전 배포판은 `https://<호스트>/auth/realms/<realm>`)
- 클라이언트: OpenID Connect, Client authentication 켬, Standard flow 켬, Valid redirect URIs에 콜백 주소
- 시크릿은 클라이언트의 Credentials 탭에서 받습니다.
- Keycloak은 사용자마다 `email_verified`를 보냅니다. 이메일 확인이 안 된 사용자는 기존 계정에 연결되지 않습니다(`unverified`). 사용자 설정의 Email verified를 확인합니다.

**Microsoft Entra ID**

- 발급자: `https://login.microsoftonline.com/<테넌트 ID>/v2.0`
- 테넌트 ID(디렉터리 ID)를 그대로 씁니다. `common`·`organizations`는 발견 문서 안의 issuer가 입력값과 달라 저장이 거부됩니다.
- 앱 등록: 플랫폼 "웹", 리디렉션 URI에 콜백 주소. 클라이언트 암호를 만들어 시크릿 칸에 넣습니다.
- 클라이언트 암호에는 만료일이 있습니다. 만료되면 로그인이 `failed`가 됩니다. 만료 전에 새 암호를 만들어 openPMS에 넣습니다.
- 사용자에게 메일 주소가 없으면 `email`이 오지 않아 `no_email`이 됩니다. 토큰 구성에서 선택적 클레임 `email`을 확인합니다.
- Entra ID는 `email_verified`를 보내지 않는 경우가 많습니다. 그러면 openPMS는 이메일로 바로 연결합니다(아래 보안 메모).
- 이 앱을 쓸 사람만 들어오게 하려면 엔터프라이즈 앱에서 사용자 할당을 필수로 하고 사람·그룹을 할당합니다. 할당되지 않은 사람은 IdP 단계에서 막힙니다.

### 문제 해결 (오류 코드별)

실패하면 브라우저가 `/login?sso_error=<코드>`로 돌아오고, 로그인 화면이 이유를 보여 줍니다. 서버 로그에는 거부가 `SSO sign-in through <식별자> refused: <코드>`, IdP와의 실패가 `SSO sign-in failed: ...`, 발견 문서 실패가 `OpenID provider <식별자> unavailable: ...`로 남습니다.

| 코드 | 뜻 | 확인할 것 |
| --- | --- | --- |
| `no_account` | 연결된 계정도, 같은 이메일 계정도 없고 자동 생성이 꺼져 있음. 또는 삭제된 계정 | 그 이메일로 초대하거나 자동 생성을 켭니다. IdP가 보내는 이메일이 openPMS 계정 이메일과 같은지 봅니다. |
| `locked` | 잠긴 계정이거나 로그인 실패 잠금 시간 중 | 관리 → 사용자에서 잠금을 풀거나 잠금 시간이 지나길 기다립니다. |
| `unverified` | 같은 이메일 계정은 있지만 IdP가 `email_verified=false`를 보냄 | IdP에서 그 사용자의 이메일을 확인 처리합니다. |
| `no_email` | 이메일 클레임이 없거나 주소 형식이 아님(`@` 없음, 254자 초과) | 범위에 `email`이 있는지, 이메일 클레임 이름, IdP 사용자에 메일 주소가 있는지 봅니다. |
| `cancelled` | IdP가 `access_denied`로 돌려보냄. 사람이 취소했거나 IdP가 이 앱을 허용하지 않음 | IdP의 사용자·그룹 할당, 동의 설정 |
| `unavailable` | 공급자가 꺼져 있거나 없음, 또는 서버가 발견 문서를 읽지 못함 | 관리 화면의 "사용", `app` 컨테이너에서 IdP로 나가는 네트워크·DNS·TLS 인증서 |
| `failed` | 그 밖의 실패. 토큰 교환 실패(시크릿 틀림·만료), ID 토큰 서명이나 issuer 불일치, state 불일치 | 시크릿, `OPENPMS_BASE_URL`이 사용자가 여는 주소와 같은지, 서버 시계, 서버 로그의 `SSO sign-in failed` 줄 |

- IdP 화면에 `redirect_uri` 오류가 나오고 openPMS로 돌아오지 않으면: IdP에 등록한 리디렉션 URI와 openPMS 목록의 "콜백 주소"를 글자 단위로 비교합니다.
- 저장할 때 "발견 문서를 읽을 수 없습니다": 발급자 주소 오타, 끝의 `/`, 서버에서 IdP로 나가는 네트워크를 봅니다.

### 보안 메모 (SSO)

- **클라이언트 시크릿은 DB(`oidc_providers.client_secret`)에 평문으로 저장됩니다.** 암호화하지 않습니다. API와 화면에는 내보내지 않고, 저장돼 있는지만(`secretSet`) 보여 줍니다.
- 그래서 DB 덤프에도 시크릿이 들어갑니다. 백업 파일은 `.env`처럼 다룹니다. 접근할 수 있는 사람을 줄이고, 암호화된 곳에 보관합니다. DB 접속 계정도 같이 관리합니다.
- 시크릿이 샜다고 의심되면 IdP에서 새 시크릿을 만들어 openPMS 수정 대화상자에 넣고, 옛 시크릿을 IdP에서 지웁니다.
- **이메일로 자동 연결합니다.** openPMS는 IdP가 보내는 이메일을 믿습니다. 사람이 자기 이메일을 직접 바꿀 수 있는 IdP라면 다른 사람의 계정(관리자 포함)에 연결될 수 있습니다. 이메일을 IdP 관리자만 바꿀 수 있는 공급자만 등록합니다. `email_verified`가 없으면 확인된 것으로 봅니다.
- 자동 생성을 켜면 그 IdP에 로그인할 수 있는 모든 사람이 openPMS 계정을 얻습니다(관리자 아님, 프로젝트 없음). IdP에서 이 앱을 쓸 사람·그룹만 할당합니다.
- IdP의 토큰(access·ID·refresh)은 저장하지 않습니다. 로그인에만 쓰고 버립니다.
- 확인하는 것: state, nonce, PKCE(S256), ID 토큰 서명(IdP의 JWKS), issuer.
- 로그인 뒤 돌아갈 주소(`next`)는 같은 사이트 경로(`/`로 시작, `//` 아님)만 받습니다. 실패할 때 주소에는 이유 코드만 붙고 계정 정보는 넣지 않습니다.
- IdP에서 사람을 막아도 이미 열린 openPMS 세션은 끝날 때까지 남습니다. 바로 내보내려면 openPMS에서도 계정을 잠급니다. 잠그면 그 사람의 세션이 모두 끝납니다.

### 검증과 남은 일

- 개발 환경에서 확인했습니다: JUnit `OidcIT` 5개(테스트 안의 작은 IdP), e2e `f00-sso` 4개(테스트 IdP 컨테이너 `frontend/e2e/fake-idp.mjs`). 바뀐 뒤 전체 JUnit 51/51, e2e 77/77 통과.
- 운영 이미지와 실제 사내 IdP(Keycloak·Entra ID)로는 아직 확인하지 않았습니다. 처음 연결할 때 위 순서대로 시험합니다.
- 없는 것: 클레임으로 그룹 동기화, IdP 로그아웃(RP-initiated logout), 비밀번호 로그인 숨기기, 시크릿 암호화 저장.

## 웹훅과 API 토큰

- 기준: 커밋 f835e1c(API 토큰), 86e58a9(웹훅), dfde416(연동 설정 토큰 차단·옮기기 이벤트·정리·주소 제한), 93d1a74(Idempotency-Key, V16), 06b48f5(자동 일정·첨부·복사 이벤트, 사설 주소 막기 스위치). 관리 화면은 **관리 → 연동 → API와 웹훅**(`/admin/integrations`)입니다.
- 설정은 DB(`api_tokens`, `settings`의 `api.tokens_enabled`, `webhooks`, `webhook_deliveries`)에 둡니다. 환경 변수는 06b48f5의 `OPENPMS_WEBHOOK_BLOCK_PRIVATE`(웹훅 사설 주소 막기, 기본 false) 하나뿐입니다.

### API 토큰 (들어오는 연동)

- 사람마다 **계정 설정 → API 토큰**에서 만듭니다: 이름, 권한 범위(읽기 전용 / 읽기·쓰기), 만료(30·90·365일·없음). 토큰 값(`opms_…`)은 만들 때 한 번만 보이고, DB에는 SHA-256만 남습니다. 한 사람이 쓰는 토큰은 20개까지입니다.
- 사내 시스템은 `Authorization: Bearer <토큰>`으로 `/api/v1`을 부릅니다. 세션 쿠키와 CSRF 토큰은 필요 없고, 토큰을 만든 사람의 권한 그대로 동작합니다. 연동 전용 계정(사람이 아닌 계정)을 초대해 필요한 프로젝트에만 넣고, 그 계정으로 토큰을 만드는 것을 권합니다.
- 읽기 전용 토큰은 조회(GET)와 목록 조회·내보내기(`POST /work-packages/query`, `/export`, `/export/count`)만 합니다. 시간 기록(3f9d5c0)의 목록·주간·공수 보고서·Excel(`GET /time-entries`, `/week`, `/report`, `/export`)도 GET이라 읽기 토큰으로 됩니다. 사내 근태·정산 시스템이 공수를 가져갈 때는 읽기 토큰으로 충분하고, 기록하거나 주간 입력표를 저장(`POST /time-entries`, `PUT /time-entries/week`)하려면 읽기·쓰기 토큰이 필요합니다. 로그인·비밀번호·토큰 관리 경로(`/auth/**`의 `/auth/me` 밖, `/me/password*`, `/me/api-tokens`, `/admin/api-tokens`)와 연동 설정(`/admin/settings/api`, `/admin/webhooks*`, `/admin/webhook-deliveries*`, dfde416)은 관리자의 토큰을 포함해 어떤 토큰으로도 부를 수 없습니다(403 "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다."). 토큰 스위치와 웹훅은 관리자가 로그인해서 화면으로 바꿉니다.
- 토큰마다 분당 300회까지입니다(넘으면 429). 버킷은 인스턴스 메모리라 인스턴스를 늘리면 인스턴스마다 따로 셉니다.
- **관리자 스위치**: **API 토큰 쓰기**를 끄면(`PUT /api/v1/admin/settings/api`, 설정 키 `api.tokens_enabled`) 새 토큰을 만들 수 없고 있는 토큰도 모두 바로 401입니다. 기본은 켜짐입니다. 연동을 쓰지 않는 동안 꺼 두는 것도 방법입니다. 같은 화면에서 모든 사람의 토큰(만든 사람, 최근 사용)을 보고 폐기합니다.
- 만든 사람을 잠그거나 삭제하면 그 사람의 토큰은 바로 401입니다. 퇴사자 처리 때 따로 할 일은 없습니다.
- 폐기했거나 만료된 토큰 행은 30일 뒤 예약 작업 `token-cleanup`(매일 03:30)이 지웁니다(dfde416).
- 계약: `/api/v1/openapi.json`(로그인 없이, OpenAPI 3.1, 주요 경로)과 화면 **도움말 → API 문서**(`/help/api`). 버전 정책: v1 안에서는 응답 필드를 더하기만 하고, 빼거나 뜻을 바꾸는 변경은 `/api/v2`로 냅니다. 연동하는 쪽은 모르는 필드를 무시하도록 만듭니다.
- 리버스 프록시는 `Authorization` 헤더를 그대로 넘겨야 합니다(nginx 기본값은 넘김). `Idempotency-Key` 요청 헤더와 `Idempotent-Replayed` 응답 헤더도 그대로 둡니다(nginx 기본값은 넘김).
- **재시도와 `Idempotency-Key`**(93d1a74): 연동이 무언가를 만드는 POST(작업·코멘트·복사 등)에 `Idempotency-Key: <요청마다 새 임의 값>`을 붙이면, 응답을 못 받아 같은 키로 다시 보내도 한 번만 만들어지고 처음 응답이 `Idempotent-Replayed: true`와 함께 돌아옵니다. 같은 키에 다른 본문은 422, 처음 요청이 아직 처리 중이면 409, 실패(2xx 아님)한 요청은 기억하지 않아 고쳐서 같은 키로 보내도 됩니다. 화면(브라우저)도 만들기 요청에 키를 붙입니다. 보관한 응답(`idempotency_keys`)은 24시간 뒤 `token-cleanup`이 지웁니다. 키는 사람(토큰을 만든 사람)마다 따로입니다.

### 웹훅 (나가는 연동)

1. **관리 → API와 웹훅 → 웹훅 추가**: 이름, 받는 주소(http:// 또는 https://. 169.254.x·[fe80:…]·metadata.google.internal은 "이 주소로는 보낼 수 없습니다.", `OPENPMS_WEBHOOK_BLOCK_PRIVATE=true`면 루프백·사설 주소도 422), 보낼 이벤트(작업 생성·작업 변경·작업 삭제·코멘트), 프로젝트(고르지 않으면 모든 프로젝트), 켜기.
2. 저장하면 **서명 키**(`whsec_…`)가 한 번만 보입니다. 받는 쪽에 저장합니다. 잃어버리면 **서명 키 새로 만들기**(옛 키는 바로 무효).
3. **테스트 전송**으로 `ping`을 한 번 보내 봅니다. 결과와 **최근 전송 기록**(30건)이 화면에 나옵니다. 실패한 전송은 **다시 보내기**.

- 요청: `POST`, `Content-Type: application/json`, `User-Agent: openPMS-Webhook/1`, `X-OpenPMS-Event`, `X-OpenPMS-Delivery`(전송 id, 중복 거르기용), `X-OpenPMS-Signature: sha256=<본문을 서명 키로 HMAC-SHA256 한 16진수>`. 받는 쪽은 받은 본문 그대로 HMAC을 계산해 비교합니다.
- 보내는 방식: 작업이 바뀌는 트랜잭션 안에서 보낼 일을 `webhook_deliveries`에 쌓고, 예약 작업 `webhook-delivery`가 15초마다 보냅니다(`OPENPMS_JOBS_ENABLED=true`인 인스턴스에서만). 2xx면 성공, 아니면 1·5·30·120분 뒤 다시 보내고 다섯 번째에도 실패하면 실패로 끝냅니다. "적어도 한 번"이라 같은 전송이 두 번 갈 수 있습니다.
- 리다이렉트(3xx)는 따라가지 않습니다(실패로 셈). 연결 5초, 응답 10초 안에 2xx를 돌려줘야 합니다. 받는 쪽은 저장만 하고 바로 응답하는 것이 좋습니다.
- 내부 코멘트는 보내지 않습니다. 작업을 다른 프로젝트로 옮기면(하위 프로젝트 보드의 카드 이동 포함) `work_package.updated`로 갑니다(dfde416). 06b48f5부터 자동 일정으로 밀린 다른 작업과 첨부 추가·삭제도 `work_package.updated`, 프로젝트 복사·템플릿으로 생긴 작업은 작업마다 `work_package.created`로 갑니다. 본문 `changes`는 그 요청이 바꾼 항목만 담습니다(전에는 5분 안에 합쳐진 변경 전체). 자동 일정이 많은 작업을 한꺼번에 밀면 그만큼 전송이 생깁니다.
- **전송 기록은 30일 뒤 자동으로 지웁니다**(dfde416). 예약 작업 `token-cleanup`(매일 03:30, `OPENPMS_JOBS_ENABLED=true`인 인스턴스)이 `pending`이 아니고 만든 지 30일 지난 행을 지웁니다. 웹훅을 지우면 그 웹훅의 기록도 같이 지워집니다. 30일보다 오래 남겨야 하면 받는 쪽에 보관합니다.
- 받는 주소: 링크 로컬(169.254.x, [fe80:…])과 metadata.google.internal(클라우드 메타데이터)은 늘 막습니다(dfde416, 06b48f5부터 이름이 그 주소로 풀려도). 사내 주소나 이 서버 자신(127.0.0.1)은 **기본으로는 허용**합니다(SSRF 성격). 받는 사내 시스템이 모두 공인·DMZ 주소라면 `.env`에 `OPENPMS_WEBHOOK_BLOCK_PRIVATE=true`를 넣어 루프백·사설 주소를 막습니다(등록 때 422 "사내망·루프백 주소로는 보낼 수 없습니다(운영 설정에서 막혀 있음).", 이미 등록한 웹훅도 보낼 때 다시 검사해 기록 오류 "보내지 않음: …"). 받는 쪽이 사설 주소라 켤 수 없으면 방화벽 egress를 받는 쪽 주소만 허용합니다.
- 실패 로그: `webhook delivery <전송 id> to <웹훅 id> not accepted: <오류>`(INFO). 작업 처리 건수는 `webhook-delivery: N`.

## PDF 내보내기와 글꼴

- PDF(표·보고서·Gantt, 6f7f18a)는 서버가 만듭니다. 이미지에 글꼴이 들어 있어 서버에 따로 설치할 것이 없습니다.
- 글꼴: Pretendard Regular·Bold(`backend/src/main/resources/fonts/`, SIL Open Font License 1.1). 라이선스 원문 `Pretendard-OFL.txt`가 글꼴 옆에 있고 jar·이미지에 같이 들어갑니다. 이미지를 다른 곳에 배포하거나 글꼴만 따로 꺼내 쓸 때도 이 파일을 함께 둡니다. 글꼴 이름을 바꾸거나 글꼴 자체를 팔 수는 없습니다(OFL 조건). PDF 안에는 쓴 글자만 부분 내장됩니다.
- 라이브러리: Apache PDFBox 3.0.4(Apache License 2.0).
- 한 번에 1,000건까지입니다(넘으면 "PDF로 내보내기에는 작업이 너무 많습니다." 422, CSV·Excel은 5,000건). 글꼴에 없는 문자(일부 이모지 등)는 `?`로 나옵니다.

## 백업과 복원

백업 대상은 두 가지입니다. **DB 덤프**와 **`files` 볼륨(첨부 파일)**. 5966874부터 DB 덤프는 앱이 스스로도 받습니다(업그레이드 전 자동, 관리 · 시스템 정보의 **지금 백업**). 매일 정해진 시각에 받는 기능은 없으므로 매일 백업은 호스트 cron으로 받고, 첨부 파일도 따로 받습니다.

### 앱이 받는 DB 덤프 (5966874)

| 종류 | 언제 | 파일 이름 |
| --- | --- | --- |
| 업그레이드 전 | 시작할 때 Flyway의 현재 버전이 있고(스키마가 있음) 적용할 마이그레이션이 있으며 `OPENPMS_BACKUP_BEFORE_UPGRADE`가 true이면, 마이그레이션 직전에(`system/MigrateWithBackup`, Flyway 마이그레이션 전략) | `openpms-before-upgrade-v{현재}-to-v{목표}-{yyyyMMdd-HHmmss-SSS}.dump` |
| 직접 백업 | 관리 · 시스템 정보(`/admin/system`, X12)의 **지금 백업**(`POST /api/v1/admin/system/backup`) | `openpms-manual-manual-{yyyyMMdd-HHmmss-SSS}.dump` |

- `system/Backups`: `pg_dump --format=custom --no-owner`. 호스트·포트·DB 이름은 JDBC 주소(`OPENPMS_DB_URL`)에서, 사용자는 `OPENPMS_DB_USER`, 비밀번호는 환경 변수 `PGPASSWORD`로 넘깁니다(명령줄에 나오지 않음). 시각은 UTC.
- `<이름>.part`로 쓰고 끝나면 원자적으로 이름을 바꿉니다. 실패하면 `.part`를 지웁니다.
- 폴더는 `OPENPMS_BACKUP_DIR`(이미지 기본 `/data/backups`, 볼륨 `backups`). 종류마다 최신 `OPENPMS_BACKUP_KEEP`개(기본 5)를 남기고 오래된 것을 지웁니다. 이름 규칙에 맞지 않는 파일(손으로 넣은 덤프)은 건드리지 않습니다.
- 30분 제한, 한 번에 하나(진행 중에 또 누르면 409). 실패하면 pg_dump의 마지막 출력을 메시지에 담습니다(503).
- 업그레이드 전 덤프가 실패하면 시작이 "업그레이드 전 백업에 실패해 마이그레이션을 하지 않았습니다. … 원인을 고치거나, 따로 백업을 받은 뒤 OPENPMS_BACKUP_BEFORE_UPGRADE=false로 다시 시작하세요."로 멈추고 아무것도 마이그레이션하지 않습니다. 새 설치(스키마 없음)와 적용할 것이 없는 시작은 덤프하지 않습니다.
- **`backups` 볼륨은 같은 서버에 있습니다.** 정기적으로(최소 매주, 업그레이드 직후 바로) 서버 밖으로 복사합니다.

```bash
docker compose exec -T app tar czf - -C /data/backups . > backups-$(date +%F).tar.gz
```

- 디스크: 덤프 하나는 대략 DB 크기입니다(관리 · 시스템 정보의 데이터베이스 · 크기). 볼륨에는 최대 2 × KEEP개가 쌓입니다. 공간이 모자라면 업그레이드 전 백업이 실패하고 앱이 시작하지 않습니다.
- 한계: 덤프는 같은 서버에 있고(서버 밖 보관은 운영자 몫), 첨부 파일은 들어가지 않고, 매일 일정은 없고(업그레이드 전과 지금 백업뿐), 복원은 손으로 합니다.

### DB 백업 (손으로, 매일 cron)

`compose.yaml` 머리말의 명령입니다. 저장소 최상위 폴더에서 실행합니다.

```bash
docker compose exec -T db pg_dump -U openpms -Fc openpms > openpms-$(date +%F).dump
```

- `-Fc`는 PostgreSQL 사용자 지정 형식(압축)입니다. 복원은 `pg_restore`로 합니다.
- 파일로 받을 때는 `-T`(TTY 끄기)를 붙입니다. `compose.yaml` 머리말 주석에도 `-T`가 있습니다. TTY가 붙으면 바이너리 덤프가 깨질 수 있습니다.
- 매일 받도록 호스트 cron에 등록합니다(설계: `architecture.md` §1 배포 구성). 앱의 자동 백업은 업그레이드 때와 **지금 백업**을 누를 때만 생깁니다.

### 첨부 파일 백업

```bash
docker compose exec -T app tar czf - -C /data/files . > files-$(date +%F).tar.gz
```

DB 덤프(앱이 받는 것 포함)에는 첨부 파일이 들어가지 않습니다.

### DB 복원

앱을 멈추고 덤프를 넣은 뒤, **덤프의 스키마 버전에 맞는 이미지**로 다시 띄웁니다. 업그레이드 전 덤프는 이름의 `v{현재}`가 그 버전입니다(`…-v16-to-v17-…`이면 V16 스키마).

```bash
# 1. 볼륨에서 덤프 꺼내기 (앱이 뜨지 않으면: docker compose run --rm --no-deps -T --entrypoint cat app /data/backups/<파일> > restore.dump)
docker compose exec -T app cat /data/backups/<파일> > restore.dump
# 2. 앱을 멈추고 넣기
docker compose stop app
docker compose exec -T db pg_restore -U openpms -d openpms --clean --if-exists --no-owner < restore.dump
# 3. 덤프의 스키마 버전에 맞는 이미지로 시작(업그레이드를 되돌리면 이전 이미지)
docker compose up -d
```

DB를 아예 비우고 넣으려면 2단계 대신:

```bash
docker compose exec -T db dropdb -U openpms openpms
docker compose exec -T db createdb -U openpms -O openpms openpms
docker compose exec -T db pg_restore -U openpms -d openpms --no-owner < openpms-2026-10-10.dump
```

새 이미지로 띄우면 앱이 다시 업그레이드 전 백업을 받고 마이그레이션합니다.

복원 연습(운영 DB를 건드리지 않음): 새 DB를 만들어 넣고 확인한 뒤 지웁니다.

```bash
docker compose exec -T db createdb -U openpms restore_check
docker compose exec -T db pg_restore -U openpms -d restore_check --no-owner < openpms-2026-10-10.dump
docker compose exec -T db psql -U openpms -d restore_check -c "select count(*) from projects; select count(*) from work_packages;"
docker compose exec -T db dropdb -U openpms restore_check
```

덤프를 읽을 수 있는지만 보려면 `docker compose exec app pg_restore --list /data/backups/<파일>`(앱 이미지에 `pg_restore`가 있음).

### 첨부 파일 복원

```bash
docker compose exec -T app tar xzf - -C /data/files < files-2026-10-10.tar.gz
```

## 업그레이드

업그레이드는 새 이미지로 바꾸고 다시 시작하는 한 가지 절차입니다(5966874). 스키마가 바뀌면 앱이 먼저 DB를 덤프합니다(위 "앱이 받는 DB 덤프").

1. 첨부 파일을 백업하고, 되돌릴 때 쓸 지금 이미지를 남겨 둡니다(`docker tag` 또는 지금 커밋을 적어 둠). `OPENPMS_BACKUP_BEFORE_UPGRADE=false`로 운영 중이면 DB도 손으로 백업합니다.
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

   적용할 마이그레이션이 있으면 그 전에 `N migration(s) to apply (vX -> vY): backing up the database first`와 `Backup written: /data/backups/openpms-before-upgrade-….dump (N bytes)`가 남습니다.
5. 관리 · 시스템 정보에서 버전·빌드 시각, 현재 버전, 적용 대기 0, 최근 적용에 실패 없음, 업그레이드 전 백업 파일을 확인하고, 그 파일을 서버 밖으로 복사합니다.

- 업그레이드 전 백업이 실패하면 앱이 마이그레이션 없이 멈춥니다. 공간·권한(`backups` 볼륨, uid 10001)·DB 접속을 고쳐 다시 띄우거나, 손으로 덤프를 받은 뒤 `OPENPMS_BACKUP_BEFORE_UPGRADE=false`로 띄우고 시작한 뒤 되돌립니다. DB는 그대로이므로 이전 이미지로 띄울 수도 있습니다.
- 마이그레이션이 실패하면 앱이 뜨지 않습니다. 원인을 고치거나, 업그레이드 전 덤프로 DB를 복원하고 이전 이미지로 띄웁니다(위 "DB 복원").
- 5966874 기준으로 올리면 새 마이그레이션은 없습니다(V17 그대로). 그래서 이번 시작에는 자동 백업이 생기지 않고 다음 마이그레이션부터 동작합니다. 바뀌는 것: 실행용 이미지에 PostgreSQL 17 클라이언트(빌드 때 `apt.postgresql.org` 접속), 새 볼륨 `backups`(새 `compose.yaml`로 `docker compose up -d`하면 생김. compose 파일을 직접 고쳐 쓰면 `app`에 `- backups:/data/backups`와 `volumes:`의 `backups:`를 더함), 선택 환경 변수 `OPENPMS_BACKUP_BEFORE_UPGRADE`·`OPENPMS_BACKUP_KEEP`, 관리 메뉴 묶음 **시스템** · **시스템 정보**. 올린 뒤 시스템 정보에서 pg_dump 버전이 보이는지 보고 **지금 백업**으로 첫 덤프를 받아 둡니다(아래 재검증 8).
- 3f9d5c0 기준으로 올리면 V17 하나가 적용됩니다(시간 기록: `time_activities`·`time_entries` 테이블과 인덱스·트리거, 기본 활동 7개(개발(기본)·설계·회의·테스트·문서·관리·기타), 기본 역할 "프로젝트 관리자"에 `time.log`·`time.view`·`time.manage`, "멤버"에 `time.log`·`time.view`, `projects_known_modules` 검사에 `time_tracking`을 넣고 **모든 기존 프로젝트에서 시간 기록 모듈을 켬**). 테이블을 더하고, 역할 권한 배열과 프로젝트의 모듈 배열에 값을 더할 뿐 기존 데이터를 지우거나 바꾸지 않습니다. 역할 권한은 그 역할에 `time.log`가 아직 없을 때만 더하고, 이름이 아니라 V3 시드의 고정 id로 찾으므로 이름을 바꾼 기본 역할에도 들어갑니다(새로 만든 역할에는 들어가지 않음). 99e0287 이미지로 만든 V16 DB에서 올려 데이터 유지, 기존 프로젝트에 `time_tracking`, 활동 7개를 확인했습니다(아래 재검증 7). 새 환경 변수는 없습니다.
  - 사용자에게 보이는 변화: 모든 프로젝트 메뉴에 **공수 보고서**(시간 기록 보기 권한이 있는 사람), 작업 상세에 **시간** 탭, 전역 메뉴에 **내 공수**. 시간 기록을 쓰지 않을 프로젝트는 프로젝트 설정 → 모듈에서 **시간 기록**을 끄고, 인스턴스 전체에서 쓰지 않으려면 역할 권한에서 시간 기록 권한을 뺍니다. 올리기 전에 프로젝트 관리자에게 알립니다.
  - 주의(이번 작업에서 찾은 것): V17 첫 초안(커밋하지 않음)은 `projects_known_modules` 검사 제약을 넓히지 않고 `time_tracking`을 켜려 해서, 프로젝트가 있는 기존 DB에서는 실패했을 것입니다. 커밋된 V17(789bb9d)은 제약을 지우고 다시 만들므로 운영자가 따로 할 일은 없습니다. 앞으로 모듈을 더하는 마이그레이션도 이 검사 제약을 함께 바꿔야 하고, 기존 DB를 올려 보는 점검(아래 재검증 7처럼)이 이런 문제를 잡습니다.
- 99e0287 기준으로 올리면 V16 하나가 적용됩니다(`idempotency_keys` 테이블, `webhooks_created_by_idx` 인덱스, `webhooks_set_updated_at` 트리거). 테이블·인덱스·트리거를 더할 뿐 기존 데이터를 바꾸지 않습니다. 9252851 이미지로 만든 V15 DB에서 올려 프로젝트·작업이 그대로인 것을 확인했습니다(아래 재검증 6). 새 환경 변수 `OPENPMS_WEBHOOK_BLOCK_PRIVATE`는 선택이고 넣지 않으면 지금처럼 동작합니다. 사용자에게 보이는 변화: 목록 기준선 아래 "기준선 이후 목록에서 빠진 작업", 알림 필터·건수가 최근 500건에 묶이지 않음. 웹훅 받는 쪽에는 자동 일정·첨부·프로젝트 복사 이벤트가 새로 가므로 미리 알립니다.
- 86e58a9 기준으로 올리면 V11–V15 다섯 개가 적용됩니다(내부 코멘트 켜기, 고대비 테마 값, 개요 위젯, API 토큰, 웹훅). 모두 컬럼·테이블을 더하거나 검사 조건을 넓히는 것이라 기존 데이터를 바꾸지 않습니다. V10 DB에서 올려 데이터가 그대로인 것을 확인했습니다(아래 재검증 4). 새 환경 변수는 없습니다. API 토큰은 기본으로 켜져 있으므로, 연동을 쓰지 않을 거면 올린 뒤 관리 → API와 웹훅에서 끕니다. 키보드 단축키도 이번에 처음 동작하는데, 환경설정 기본값(`user_preferences.keyboard_shortcuts`)이 켬이라 기존 사용자 모두에게 켜진 채로 시작합니다(끄기는 계정 설정 → 화면 → 키보드 단축키). 사용자에게 미리 알립니다.
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
| 여러 사람이 동시에 로그인할 때 모두 429 "요청이 너무 많습니다." | 프록시 뒤의 모든 사람이 프록시 주소 하나로 보이는 경우입니다. 프록시가 `X-Forwarded-For`·`X-Forwarded-Proto`를 보내는지, 프록시가 들어오는 주소가 `OPENPMS_TRUSTED_PROXIES`(기본: 127.x, ::1, 172.16–31.x, 10.88–89.x)에 맞는지 봅니다("프록시 헤더와 클라이언트 주소"). 한도는 1분 뒤 저절로 풀립니다. |
| 관리 → 사용자에 "일시 잠김", "로그인 실패가 많아 {시각}까지" | 비밀번호를 연속으로 틀려 잠금 시간 중입니다(관리 → 로그인·비밀번호의 횟수·시간). 상태는 활성 그대로이고 "잠김" 필터로도 찾습니다. 기다리지 않으려면 행 메뉴의 **잠금 해제**(실패 횟수와 잠금 시각을 지움). |
| 웹훅이 계속 "실패" | 관리 → API와 웹훅의 전송 기록 오류. `ConnectException`·시간 초과면 방화벽 egress·DNS, `SSLHandshakeException`이면 JRE가 받는 쪽 인증서(사내 CA)를 믿지 않음, `HTTP 3xx`면 리다이렉트(주소를 최종 주소로), `HTTP 4xx·5xx`면 받는 쪽(서명 검증 실패 포함). 받는 쪽은 10초 안에 2xx를 돌려줘야 합니다 |
| 웹훅 기록에 "보내지 않음: 사내망·루프백 주소로는 보낼 수 없습니다…" | `OPENPMS_WEBHOOK_BLOCK_PRIVATE=true`인데 받는 주소(또는 그 이름이 풀린 주소)가 사설·루프백입니다. 받는 쪽을 공인 주소로 옮기거나 스위치를 끕니다. "이 주소로는 보낼 수 없습니다."는 링크 로컬이라 스위치와 상관없이 막힙니다 |
| 같은 키로 보낸 연동 요청이 409 "같은 요청을 아직 처리하고 있습니다." 또는 422 "같은 Idempotency-Key를 다른 요청에 썼습니다." | 409는 처음 요청이 끝나지 않은 것(잠시 뒤 같은 키로 다시), 422는 연동 쪽이 키를 재사용하는 것(요청마다 새 키) |
| 웹훅이 아예 가지 않음(기록도 없음) | 웹훅이 켜져 있는지, 이벤트·프로젝트를 골랐는지, 그 변경이 이벤트를 만드는 종류인지(06b48f5부터 작업 옮기기·자동 일정·첨부·프로젝트 복사도 이벤트가 있음, 내부 코멘트는 없음), 기록은 있는데 "다시 보낼 예정"에 머물면 `OPENPMS_JOBS_ENABLED=true`인지 |
| 연동이 401 "API 토큰이 올바르지 않거나 만료되었습니다." | 토큰을 폐기했거나 만료됐거나, 만든 사람이 잠김·삭제됨. 관리 → API와 웹훅에서 끈 경우는 "API 토큰을 쓰지 않도록 설정되어 있습니다." |
| 연동이 403 "읽기 전용 토큰입니다." 또는 "…로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다." | 읽기·쓰기 토큰을 새로 만들거나, 토큰으로 부를 수 없는 경로입니다(dfde416부터 토큰 스위치·웹훅 설정도 포함, 관리자 토큰도 403). 그 밖의 403은 만든 사람에게 그 권한이 없는 것 |
| 연동이 429 | 토큰 하나로 분당 300회를 넘음. 1분 뒤 풀림 |
| 업그레이드 뒤 앱이 시작하지 않고 로그에 "업그레이드 전 백업에 실패해 마이그레이션을 하지 않았습니다." | DB는 바뀌지 않음. 뒤의 원인: "백업 파일을 쓸 수 없습니다(…)"면 디스크 공간·`backups` 볼륨 권한(uid 10001), "pg_dump가 실패했습니다: …"면 pg_dump 출력(DB 접속 등), "pg_dump를 실행할 수 없습니다"면 클라이언트 없는 이미지, "30분 안에 끝나지 않았습니다"면 큰 DB. 고치고 `docker compose up -d`. 바로 못 고치면 손으로 덤프한 뒤 `OPENPMS_BACKUP_BEFORE_UPGRADE=false`로 띄우고, 시작한 뒤 그 줄을 지우고 다시 `docker compose up -d` |
| 관리 · 시스템 정보의 **지금 백업**이 회색, pg_dump "실행할 수 없음 — 이 환경에서는 백업할 수 없습니다(운영 이미지에는 들어 있음)" | `pg_dump`가 없는 환경(개발, 클라이언트를 뺀 이미지). 저장소 `Dockerfile`로 만든 이미지에는 있음. 이대로면 업그레이드 전 백업도 실패함 |
| **지금 백업**이 "백업하지 못했습니다."(503) / "다른 백업이 진행 중입니다. 끝난 뒤 다시 시도하세요."(409) | 503은 위 줄과 같은 원인(메시지에 pg_dump 출력). 409는 다른 백업이 진행 중이므로 기다림 |
| PDF 내보내기가 422 | 1,000건 넘음. 필터로 줄이거나 CSV·Excel로 |
| `.env`의 DB 비밀번호를 바꿨더니 앱이 DB에 못 붙음 | PostgreSQL 이미지는 `db` 볼륨을 처음 만들 때만 비밀번호를 정합니다. 바꾸려면 DB 안에서 `ALTER USER openpms PASSWORD '...'`를 먼저 하고 `.env`를 같은 값으로 고칩니다. |

## 보안 메모

- 앱은 권한 없는 사용자 `openpms`(uid 10001)로 돕니다. jar(`/app`)는 root 소유이고, 이 사용자에게 넘긴 폴더는 첨부 파일용 `/data/files`와 백업용 `/data/backups`(5966874)뿐입니다.
- 비밀값(DB·SMTP 비밀번호)은 `.env`에만 둡니다. `.env`는 git에서 제외되고(`.gitignore`), `.dockerignore`로 이미지에도 들어가지 않습니다. 저장소에는 값이 빈 `.env.example`만 있습니다.
- `frontend/.env.production`은 비밀값이 아닙니다. 화면을 실제 API에 붙이는 설정(`VITE_API=http`) 하나뿐입니다.
- 운영 웹 빌드는 `VITE_API=http`가 없으면 빌드를 거부합니다(`vite.config.ts`). 가짜 데이터 계층이 실린 이미지가 나가는 일을 막습니다.
- 앱은 127.0.0.1에만 열리고, DB는 호스트에 포트를 열지 않습니다.
- `X-Forwarded-For`·`X-Forwarded-Proto`는 `OPENPMS_TRUSTED_PROXIES`에 맞는 주소(기본: 이 호스트, Docker 브리지, Podman 네트워크)에서 온 것만 믿습니다. 정규식을 넓게(예: 모든 주소) 잡으면 누구나 헤더로 요청 제한을 피할 수 있으니 실제 프록시 주소만 넣습니다.
- 세션 쿠키 `OPENPMS_SESSION`은 HttpOnly, SameSite=Lax, 기본 Secure입니다. 오류 응답에 메시지와 스택 추적을 넣지 않습니다.
- 기본 비밀번호는 없습니다. 첫 관리자 설정 링크는 로그에 한 번 나옵니다. 링크가 유효한 24시간 동안은 로그를 다룰 때 주의합니다.
- API 토큰은 원문을 저장하지 않습니다(SHA-256). 토큰은 비밀번호처럼 다루라고 사용자에게 안내합니다. 토큰으로 한 변경은 활동 기록에 만든 사람으로 남고, 토큰으로 했다는 표시는 없습니다.
- **웹훅 서명 키는 DB(`webhooks.secret`)에 평문으로 저장됩니다**(HMAC 계산에 원문이 필요). SSO 클라이언트 시크릿과 같이 DB 덤프를 비밀값처럼 다룹니다. 샜다고 의심되면 **서명 키 새로 만들기**.
- 웹훅 받는 주소는 관리자가 정하며, 기본으로는 링크 로컬·메타데이터 주소 말고는 사내 주소 제한이 없습니다. `OPENPMS_WEBHOOK_BLOCK_PRIVATE=true`면 루프백·사설 주소도 막습니다(06b48f5, 위 "웹훅"). 그 밖에는 관리자 계정 관리와 egress 방화벽이 방어선입니다.
- `backups` 볼륨의 덤프에는 DB 전체(SSO 시크릿·웹훅 서명 키 평문 포함)가 들어 있습니다. 볼륨과 서버 밖 사본의 접근을 줄이고 사본은 암호화된 곳에 둡니다. 앱은 DB 비밀번호를 `PGPASSWORD` 환경 변수로 `pg_dump`에 넘깁니다(명령줄에 보이지 않음).
- `idempotency_keys.body`에는 만들기 요청의 응답(만든 작업·코멘트 내용)이 하루 동안 남습니다. DB 덤프를 비밀값처럼 다루는 기존 원칙에 포함됩니다.
- 번들 글꼴(Pretendard, SIL OFL 1.1)의 라이선스 원문을 지우지 않습니다(위 "PDF 내보내기와 글꼴").

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

### 2026-10-11 재검증 (SSO·그룹화·보드·날짜 형식이 들어간 빌드, 84f6e6c 기준 이미지)

새 DB에 같은 compose 구성으로 띄워 확인했습니다.

| 항목 | 결과 |
| --- | --- |
| 마이그레이션 | v10까지 적용(V10 사내 SSO 테이블) |
| 헬스 | UP |
| `GET /api/v1/auth/sso-providers` (로그인 전) | 200, 빈 목록 |
| 없는 SSO 공급자로 시작 | `/login?sso_error=unavailable`로 돌려보냄 |
| `GET /api/v1/settings/display` (로그인 전) | 401 |
| 브라우저 흐름 | 초대 링크로 가입 → 프로젝트·작업 만들기 → 홈·관리·보드·Gantt·계정 화면 → 로그아웃 뒤 폼 로그인, 모두 통과 |
| 콘솔·5xx | 로그아웃 상태의 401 외에 없음 |

### 2026-10-11 재검증 2 (90006c2 기준 이미지, 프록시 헤더 확인)

`podman build -t localhost/openpms:test .`로 만든 이미지를 스크래치 compose(포트 18090, 새 볼륨)로 띄워 확인했습니다.

| 항목 | 결과 |
| --- | --- |
| 마이그레이션 | 빈 스키마에 10개 적용 |
| 헬스 | UP |
| 브라우저 흐름 | 초대 링크로 가입 → 프로젝트·작업 만들기 → 홈·관리·보드·Gantt·계정 화면 → 로그아웃 뒤 폼 로그인, 모두 통과 |
| 콘솔·5xx | 로그아웃 상태의 예상된 401 외에 없음 |
| 프록시 헤더(호스트 → 게시 포트, 운영 한도 클라이언트 주소당 분당 로그인 20회) | `X-Forwarded-For` 주소 29개를 바꿔 가며 보냄 → 429 없음. 주소 하나로 → 21번째에 첫 429. 앞에 가짜 주소를 붙여도(`198.51.100.1, 198.51.100.7`) 429 유지 |
| 고치기 전(81053e7 이미지) | 같은 확인에서 헤더가 무시되어 모든 요청이 한도 하나를 같이 씀. rootless Podman이 게시 포트 연결을 컨테이너 자신의 주소(10.89.1.3, `/proc/net/tcp`)에서 온 것으로 넘기는데 그 대역이 기본 신뢰 범위에 없었음 → 90006c2에서 10.88·10.89 추가(BUG-008 후속) |
| 컨테이너 안에서 localhost로 직접 | 24번 요청 → 21번째부터 429(한도 자체는 동작) |

실제 사내 프록시(nginx)를 거친 확인은 배포 때 합니다.

### 2026-10-11 재검증 3 (21a23fb 기준 이미지: 템플릿·전역 역할·보드 종류·우클릭 메뉴가 들어간 빌드)

같은 compose 구성, 새 볼륨으로 띄워 확인했습니다. 마이그레이션은 1.2와 같습니다(새 마이그레이션 없음).

| 항목 | 결과 |
| --- | --- |
| 마이그레이션 | 빈 스키마에 10개 적용 |
| 헬스 | UP |
| 브라우저 흐름 | 초대 링크로 가입 → 프로젝트·작업 만들기 → 홈·관리·보드·Gantt·계정 화면 → 로그아웃 뒤 폼 로그인, 모두 통과 |
| 콘솔·5xx | 로그아웃 상태의 401 외에 없음 |
| 프록시 뒤 요청 제한(호스트에서 게시 포트로) | 서로 다른 `X-Forwarded-For` 29개 → 429 없음, 한 주소 → 21번째에 429 |

### 2026-10-11 재검증 4 (86e58a9 기준 이미지: 마이그레이션 V11–V15, API 토큰, 웹훅, PDF가 들어간 빌드)

86e58a9 코드로 만든 이미지로 두 가지를 확인했습니다. 같은 compose 구성(포트 18090)입니다.

| 항목 | 결과 |
| --- | --- |
| 업그레이드(V10 DB) | 마이그레이션 10개가 적용된 DB에 새 이미지를 띄움 → V11–V15 다섯 개 적용, 기존 데이터 유지 |
| 새 설치 | 빈 스키마에 15개 적용 |
| 헬스 | UP |
| 브라우저 흐름 | 초대 링크로 가입 → 프로젝트·작업 만들기 → 홈·관리·보드·Gantt·계정 화면, 그리고 관리 · API와 웹훅(`/admin/integrations`), 권한 보고서(`/admin/roles/report`), 도움말 · API 문서(`/help/api`), 프로젝트 개요(위젯), 모두 통과 |
| PDF | 이미지 안에서 Gantt PDF 내보내기 → 200 `application/pdf`(글꼴이 이미지에 들어 있음) |

실제 사내 시스템으로 받는 웹훅(방화벽·서명 검증)과 API 토큰 연동은 배포 때 위 "웹훅과 API 토큰" 순서대로 확인합니다.

### 2026-10-11 재검증 5 (9252851 기준 이미지: 리뷰 후속 dfde416과 OpenAPI 보강)

같은 compose 구성(포트 18090), 새 볼륨입니다. 새 마이그레이션은 없습니다.

| 항목 | 결과 |
| --- | --- |
| 새 설치 | 빈 스키마에 15개 적용, 헬스 UP |
| 브라우저 흐름 | 초대 링크로 가입 → 프로젝트·작업 만들기 → 홈·관리·보드·Gantt·계정 → 로그아웃 뒤 폼 로그인 → API와 웹훅·권한 보고서·API 문서·프로젝트 개요, 모두 통과 |
| PDF | 200 `application/pdf`, `%PDF-` |
| 토큰 | 관리자가 만든 읽기·쓰기 토큰으로 `GET /auth/me` 200, `GET /admin/webhooks` 403 |
| 웹훅 받는 주소 | `http://169.254.169.254/` → 422 |
| OpenAPI | `/work-packages/export/count` 있음 |
| 콘솔·5xx | 5xx 없음. 로그아웃 상태의 401과 위 확인에서 일부러 낸 403·422, 확인 스크립트가 부른 개발용 모듈 경로의 404뿐 |

### 2026-10-11 재검증 6 (99e0287 기준 이미지: 마이그레이션 V16, Idempotency-Key, 웹훅 보강, 알림 필터)

99e0287 코드로 만든 이미지(`localhost/openpms:test`)를 스크래치 compose(포트 18090)로 띄워 두 가지를 확인했습니다.

| 항목 | 결과 |
| --- | --- |
| 업그레이드(V15 DB) | 9252851 이미지로 만든 V15 DB에 새 이미지를 띄움 → V16 적용, 프로젝트와 작업이 그대로 |
| 새 설치 | 빈 스키마에 16개 적용, 헬스 UP |
| 브라우저 흐름 | 초대 링크로 가입 → 프로젝트·작업 만들기 → 홈·관리·보드·Gantt·계정 → 로그아웃 뒤 폼 로그인 → API와 웹훅·권한 보고서·API 문서·프로젝트 개요, 모두 통과 |
| PDF | 200, `%PDF-` |
| 토큰 | 읽기·쓰기 토큰으로 `GET /admin/webhooks` 403 |
| 웹훅 받는 주소 | `http://169.254.169.254/` → 422 |
| Idempotency-Key | 화면이 만든 두 생성 요청(`POST /api/v1/projects` 201, `POST /api/v1/projects/{id}/work-packages` 201)이 `idempotency_keys`에 상태·응답과 함께 남음 |

`OPENPMS_WEBHOOK_BLOCK_PRIVATE=true`로 띄운 확인과 실제 사내 시스템으로 받는 웹훅은 배포 때 위 "웹훅과 API 토큰" 순서대로 합니다.

### 2026-10-11 재검증 7 (3f9d5c0 기준 이미지: 마이그레이션 V17, 시간 기록)

3f9d5c0 코드로 만든 이미지를 같은 스크래치 compose(포트 18090)로 띄워 두 가지를 확인했습니다.

| 항목 | 결과 |
| --- | --- |
| 업그레이드(V16 DB) | 99e0287 이미지로 만든 V16 DB에 새 이미지를 띄움 → V17 적용, 프로젝트와 작업이 그대로, 기존 프로젝트의 모듈에 `time_tracking`이 들어감, `time_activities` 7행 |
| 새 설치 | 빈 스키마에 17개 적용 |
| 브라우저 흐름 | 초대 링크로 가입 → 프로젝트·작업 만들기 → 홈·관리·보드·Gantt·계정 화면, 통과 |
| PDF | 200 |
| 주간 입력표 | 내 공수에 `2:30` 입력 → 저장 → 다시 읽으면 `2.5` |
| 공수 보고서 | 합계 2.5 |
| Excel | 내려받은 파일 이름 "공수 2026-10-01~2026-10-11.xlsx" |

V17 첫 초안의 검사 제약 문제(위 "업그레이드")는 이 점검 전에 고친 것이고, 이 점검의 V16 → V17 업그레이드가 그 수정을 확인합니다. 사내 근태 시스템이 API 토큰으로 공수를 가져가는 연동은 배포 때 위 "웹훅과 API 토큰" 순서대로 확인합니다.

### 2026-10-11 재검증 8 (5966874 기준 이미지: 업그레이드 전 자동 백업, 관리 · 시스템 정보)

5966874 코드로 만든 이미지를 같은 스크래치 compose(포트 18090)로 띄워 확인했습니다. 이미지 안 `pg_dump` 17.11, `pg_restore` 있음.

| 항목 | 결과 |
| --- | --- |
| 새 설치 | 빈 스키마에 마이그레이션 17개 적용, 백업 파일 없음(새 설치는 덤프하지 않음) |
| 지금 백업 | 관리 · 시스템 정보의 **지금 백업** → `openpms-manual-…dump`(약 178KB), 화면 목록에 나옴 |
| 업그레이드 전 백업 | DB에서 V17을 되돌림(V17의 테이블 두 개 삭제, 이력 행 삭제) → 앱 재시작 → 로그 `1 migration(s) to apply (v16 -> v17): backing up the database first`, `Backup written: …before-upgrade-v16-to-v17-….dump (170430 bytes)` → V17 적용 |
| 덤프 읽기 | `pg_restore --list`로 위 덤프를 읽음, 테이블 데이터 47개(`projects`, `work_packages` 포함) |
| 백업 실패 | 다시 V17을 되돌리고 `OPENPMS_BACKUP_DIR=/proc/no-backups`로 시작 → "업그레이드 전 백업에 실패해 마이그레이션을 하지 않았습니다. …"로 시작 실패, 스키마는 V16 그대로 |
| 다시 보통으로 | 환경 변수를 빼고 다시 시작 → 백업한 뒤 마이그레이션 |

`backups` 볼륨을 서버 밖으로 복사하는 일은 이 점검에서 하지 않았습니다. 복원은 문서 점검 뒤 같은 이미지·스크래치 compose에서 위 "DB 복원" 절차 그대로(덤프 꺼내기 → 앱 정지 → `pg_restore --clean --if-exists --no-owner` → 앱 시작) 실행해, 지운 작업과 바꾼 프로젝트 이름이 돌아오는 것을 확인했습니다(종료 코드 0, 현재 버전 17). 복원 연습(새 DB에 넣고 수 확인 후 삭제)도 같은 날 확인했습니다.
