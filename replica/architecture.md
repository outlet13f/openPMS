# 아키텍처: openPMS (OpenProject 작업 패키지 + 계획 기능의 클린룸 재구현)

- 입력: `replica/recon.md` (화면 S01–S37, 흐름 F01–F11), `replica/features.csv` (must 64 · should 50 · could 26 · skip 20. 2026-10-11 지금 파일은 should 51, 3f9d5c0부터 could 27: "시간 추적·비용·예산"을 나눠 시간 기록이 could로 들어오고 비용·예산은 skip으로 남음)
- 산출물: 이 문서, `replica/schema.sql` (Flyway V1), `replica/seed.sql` (Flyway V3)
- 작성일: 2026-10-09
- 정해진 사항: **사내 서버 셀프 호스팅**, **Spring Boot + React**, **이메일·비밀번호 로그인 (SSO는 should 단계, OIDC로 구현 완료: a677079)**

원본의 구조가 아니라 원본의 기능을 재현합니다. 앱 하나와 데이터베이스 하나로 구성하고, 마이크로서비스는 두지 않습니다.

---

## 1. 스택

| 계층 | 선택 | 이유 |
| --- | --- | --- |
| 백엔드 | Spring Boot 4.x 최신 안정판 + Java 21 LTS, Spring MVC | 팀이 고른 스택입니다. 가상 스레드를 쓰면 I/O 대기가 많은 요청도 스레드 풀 튜닝 없이 처리할 수 있습니다. 프로젝트를 만들 때 아래 라이브러리 스타터가 그 버전과 호환되는지 확인합니다. |
| 데이터 접근 | jOOQ (OSS, PostgreSQL) + Flyway | 이 앱의 중심은 필터 30여 종을 동적으로 조합하는 쿼리 엔진과 재귀 계층 조회입니다. SQL 우선 방식이 맞습니다. 스키마에서 코드를 생성하므로 컬럼명 오타는 컴파일 단계에서 잡힙니다. 팀이 JPA에 익숙하다면 단순 CRUD만 Spring Data JPA로 쓰고 목록·계층·스케줄링은 jOOQ로 나눌 수 있습니다. |
| 데이터베이스 | PostgreSQL 17 (Docker 볼륨) | 지금 OpenProject와 같은 버전이라 운영 경험을 그대로 쓸 수 있습니다. pg_trgm, jsonb, `nulls not distinct`를 사용합니다. |
| 프론트엔드 | React 19 + TypeScript + Vite (SPA) | 빌드 결과를 Spring Boot가 정적 파일로 서빙하므로 배포 단위는 컨테이너 하나입니다. |
| UI 기반 | Tailwind CSS (토큰은 `/replica-design`에서) + Radix UI 프리미티브 | 접근성 있는 드롭다운·대화상자·탭을 직접 만들지 않아도 됩니다. |
| 서버 상태 | TanStack Query | 인라인 편집의 낙관적 갱신, 409 충돌 시 롤백, 캐시 무효화를 처리합니다. |
| 표·가상 스크롤 | TanStack Table + TanStack Virtual | 작업 패키지 목록, Gantt 왼쪽 표, 내 페이지 위젯이 같은 엔진을 씁니다. |
| Gantt·팀 플래너 | 자체 구현 타임라인 컴포넌트 (행 가상화 + 막대 DOM + SVG 의존선) | 무료 라이브러리는 의존선·자동 스케줄링 연동이 약하고, 강한 라이브러리는 상용입니다. 날짜 계산은 서버가 하므로 화면은 그리기와 드래그만 맡습니다. 팀 플래너(사람 × 날짜 격자)도 같은 컴포넌트를 씁니다. |
| 캘린더 | FullCalendar의 MIT 패키지만 (daygrid, timegrid, interaction) | 월·주 보기와 드래그를 지원합니다. 리소스 타임라인은 상용이라 쓰지 않고, 팀 플래너는 위의 자체 컴포넌트로 만듭니다. |
| 드래그 앤 드롭 | dnd-kit | 보드 카드·컬럼, 목록 행 순서 변경에 씁니다. |
| 리치 텍스트 | Tiptap (MIT 코어 + mention 확장) | @멘션과 코드 블록을 지원합니다. 저장은 서버에서 sanitize한 HTML과 검색용 평문으로 합니다. |
| 폼 | React Hook Form + Zod | 서버의 폼 스키마 응답으로 필수 여부·허용값을 그립니다. |
| 다국어 | react-i18next (ko 기본, en) + Spring MessageSource | 번역 문자열은 openPMS 용어집으로 새로 씁니다. |
| API 계약 | springdoc-openapi → openapi-typescript | 백엔드 DTO에서 프론트 타입을 생성하므로 수동 동기화가 필요 없습니다. |
| 인증 | Spring Security 폼 로그인 + 세션 (Spring Session JDBC, HttpOnly 쿠키) + CSRF 쿠키 토큰 | 세션을 Postgres에 두면 재시작해도 로그인이 유지되고, 나중에 인스턴스를 2개로 늘릴 수 있습니다. 비밀번호는 DelegatingPasswordEncoder(bcrypt)로 저장합니다. SSO는 `oauth2Login`(OIDC, PKCE)으로 붙였습니다(V10, 커밋 a677079). 공급자는 관리자가 화면에서 등록하고 DB에 두며, 로그인 뒤에는 폼 로그인과 같은 세션을 씁니다. SSO로 만든 계정은 `password_hash`가 null입니다. 사내 연동은 개인 API 토큰(`Authorization: Bearer`, V14, f835e1c)으로 세션 없이 들어옵니다(§4.1). |
| 결제 | 해당 없음 | 사내 도구입니다. |
| 이메일 | Spring Mail → 사내 SMTP 릴레이, 템플릿은 Thymeleaf | 외부 SaaS로 메일을 보내지 않습니다. 개발 환경에서는 Mailpit 컨테이너를 씁니다. |
| 백그라운드 작업 | db-scheduler (Postgres 테이블 하나, 클러스터 안전) | 별도 큐 인프라 없이 일회성 작업과 반복 작업을 모두 처리합니다. 업무 트랜잭션 안에서 작업을 예약하므로 저널을 저장한 뒤 알림 작업이 누락되는 일이 없습니다. |
| 파일 | 로컬 볼륨 (`/data/files`) + `FileStorage` 인터페이스 | 셀프 호스팅에서 가장 단순합니다. 사내에 S3 호환 저장소가 있으면 어댑터만 바꿉니다. |
| 파일 형식 판별 | Apache Tika core | 업로드된 확장자를 믿지 않고 내용으로 형식을 확인합니다. |
| HTML 정화 | OWASP Java HTML Sanitizer (허용 목록) | 코멘트와 설명의 XSS를 막습니다. |
| 내보내기 | Apache Commons CSV, Apache POI (SXSSF 스트리밍), OpenPDF + Noto Sans KR 임베드 | PDF에 한글 폰트를 임베드해야 글자가 깨지지 않습니다. 구현(6f7f18a): CSV는 직접 쓰고 XLSX는 POI, PDF는 **PDFBox 3.0.4**(Apache 2.0)에 **Pretendard** Regular·Bold(SIL OFL 1.1, 화면과 같은 글꼴)를 부분 내장. 글꼴 파일과 라이선스 원문은 `backend/src/main/resources/fonts/`. |
| 로그인 제한 | Bucket4j (메모리) | 인스턴스가 하나라 메모리 버킷으로 충분합니다. 구현(384a2f9): 키인 클라이언트 주소는 믿는 프록시가 보낸 `X-Forwarded-For`에서 꺼냅니다(Tomcat RemoteIpValve, §1 배포 구성). |
| 호스팅 | 사내 서버의 Docker Compose (Podman 호환): `app` + `db` | 지금 OpenProject 운영 방식과 같습니다. TLS는 사내 리버스 프록시(nginx 등)에서 종료합니다. |
| 관측 | Spring Boot Actuator (health, Prometheus 지표), JSON 로그를 stdout으로 | 사내 모니터링에 바로 연결합니다. |
| 백업 (구현에서 더함, 5966874) | 앱 안에서 `pg_dump`(커스텀 형식) + 런타임 이미지에 PostgreSQL 17 클라이언트(apt.postgresql.org) | 업그레이드 때 백업을 잊는 것이 원본 사용자 불만이라(`replica/fixes.md` #3) 마이그레이션 직전을 아는 앱이 직접 받습니다. 배포판 클라이언트는 17 서버를 덤프하지 못합니다. 매일 백업과 서버 밖 보관은 계속 호스트가 맡습니다. |
| 테스트 | JUnit 5 + Testcontainers(Postgres 17), jqwik(스케줄링 속성 기반 테스트), Playwright(E2E, `/replica-test`) | 실제 Postgres로 제약과 쿼리를 검증합니다. |

### 저장소 구조

```
openPMS/
  backend/                      Gradle(Kotlin DSL), Spring Boot
    src/main/java/.../openpms/
      auth  user  permission  project  member
      workpackage  journal  relation  scheduling  progress
      query  board  notification  mail  search  attachment
      admin  settings  jobs  common
      (구현: system — 업그레이드 전 백업·시스템 정보, 5966874)
    src/main/resources/db/migration/
      V1__init.sql        = replica/schema.sql
      V2__libraries.sql   = Spring Session JDBC + db-scheduler의 PostgreSQL 스키마(각 라이브러리 제공본)
      V3__seed.sql        = replica/seed.sql
      V4–V9               구현 중 추가(§2 머리말)
      V10__oidc.sql       사내 SSO: oidc_providers, user_identities
      V11–V13             내부 코멘트(projects.internal_comments), 고대비 테마(users.theme 검사), 개요 위젯(projects.overview_widgets)
      V14__api_tokens.sql 개인 API 토큰
      V15__webhooks.sql   웹훅: webhooks, webhook_deliveries
      V16__idempotency_keys.sql  생성 POST의 Idempotency-Key 응답 보관, webhooks 인덱스·트리거 보완
      V17__time_tracking.sql     시간 기록: time_activities, time_entries, 역할 권한 time.*, 모듈 time_tracking
  frontend/                     Vite + React + TS. 빌드 결과는 backend 정적 리소스로 복사
  Dockerfile                    저장소 최상위. 멀티 스테이지(프론트 빌드 → 백엔드 빌드 → JRE 런타임)
  compose.yaml                  저장소 최상위. app, db (mailpit 없음. 개발용 Mailpit은 backend/scripts/dev.sh가 띄움)
  .env.example                  compose가 읽는 .env의 견본(값 없음)
  replica/                      recon·설계 문서 (배포하지 않음)
```

`scheduling`과 `progress` 패키지는 DB나 Spring에 의존하지 않는 순수 Java로 만듭니다. 입력은 작업 그래프와 근무일 달력이고, 출력은 바뀐 날짜와 합계입니다. 이렇게 해 두면 가장 위험한 로직을 단위 테스트와 속성 기반 테스트로 집중 검증할 수 있습니다.

### 배포 구성

```
[사용자 브라우저] --HTTPS--> [사내 리버스 프록시: TLS, client_max_body_size 30m]
                                   |
                                   v
                     [app 컨테이너: Spring Boot :8090, 호스트 127.0.0.1:${OPENPMS_PORT:-8090}]
                       - /api/v1/**  REST
                       - /**         SPA(index.html 폴백)
                       - db-scheduler 작업 실행
                       - /data/files 볼륨(첨부)
                       - /data/backups 볼륨(DB 덤프: 업그레이드 전·지금 백업, 구현 5966874)
                                   |
                                   v
                     [db 컨테이너: PostgreSQL 17, 볼륨]
호스트 cron: pg_dump -Fc 매일 + /data/files 백업, 복원 연습 월 1회
앱(구현 5966874): 적용할 마이그레이션이 있는 업그레이드 직전에 pg_dump → /data/backups, 실패하면 시작 중단
```

환경 변수: `OPENPMS_BASE_URL`(메일 링크와 SSO 콜백 주소의 기준), `OPENPMS_DB_URL`·`_USER`·`_PASSWORD`, `OPENPMS_SMTP_HOST`·`_PORT`·`_USER`·`_PASSWORD`·`_STARTTLS`, `OPENPMS_MAIL_FROM`, `OPENPMS_FILES_DIR`, `OPENPMS_ADMIN_EMAIL`, `OPENPMS_SESSION_TIMEOUT`(기본 8h), `OPENPMS_SECURE_COOKIES`(기본 true), `OPENPMS_JOBS_ENABLED`(기본 true, dev 프로필은 false), `OPENPMS_PORT`(compose가 쓰는 호스트 포트, 기본 8090. 컨테이너 안 앱 포트는 8090이고 compose는 여기에 연결), `OPENPMS_FORWARD_HEADERS`(기본 native, 프록시 없이 쓸 때 none), `OPENPMS_TRUSTED_PROXIES`(`X-Forwarded-For`·`X-Forwarded-Proto`를 믿을 프록시 주소 정규식, 기본 127.x·::1·Docker 172.16–31.x·Podman 10.88–89.x, 384a2f9·90006c2. Docker 192.168.x 풀은 기본값 밖). 프록시는 두 헤더를 꼭 보내야 하고, 그래야 로그인 요청 제한이 사람마다 따로 셉니다. 값과 뜻은 `replica/deploy.md` "환경 변수". SSO 공급자 설정은 환경 변수가 아니라 DB(`oidc_providers`)에 둡니다. 구현(5966874): `OPENPMS_BACKUP_BEFORE_UPGRADE`(기본 true, dev false: 업그레이드 전 덤프, 실패하면 시작 중단), `OPENPMS_BACKUP_DIR`(기본 /data/backups, 이미지가 정하고 compose가 볼륨 `backups`를 붙임, dev /tmp/openpms-backups), `OPENPMS_BACKUP_KEEP`(종류마다 남길 덤프 수, 기본 5).

**첫 관리자**: 관리자 계정이 하나도 없고 `OPENPMS_ADMIN_EMAIL`이 설정돼 있으면, 시작할 때 그 이메일로 `invited` 상태의 관리자를 만들고 일회용 설정 링크(24시간)를 로그에 한 번 출력합니다. 기본 비밀번호는 없습니다.

---

## 2. 스키마

테이블 36개(자체) + 라이브러리 테이블 3개(spring_session, spring_session_attributes, scheduled_tasks). 전문은 `replica/schema.sql`(= Flyway V1). 구현 중 V4–V9로 바뀐 것(work_package_shares 테이블, user_preferences.last_digest_at·my_page_widgets, 알림 기본값, updated_at 트리거 예외, types.attribute_groups를 유형 양식으로 쓰며 기본값 '[]'로 초기화)은 `replica/review-2026-10-10.md` §2.3에 정리했습니다(V9는 그 뒤 추가). V10은 사내 SSO의 `oidc_providers`, `user_identities` 두 테이블을 더합니다(아래 목록과 설계 결정). 2026-10-11(4fb4ca5..86e58a9)에 V11 `projects.internal_comments`, V12 `users.theme`에 `high_contrast`, V13 `projects.overview_widgets`, V14 `api_tokens`, V15 `webhooks`·`webhook_deliveries`를 더했습니다. 같은 날 93d1a74의 V16이 `idempotency_keys`와 `webhooks_created_by_idx` 인덱스·`webhooks_set_updated_at` 트리거를 더했습니다. 789bb9d의 V17이 시간 기록 `time_activities`·`time_entries`를 더하고, `projects_known_modules` 검사에 `time_tracking`을 넣어 모든 기존 프로젝트에서 켰습니다. 구현 기준 테이블은 자체 45개 + 라이브러리 3개입니다(`docs/deliverables/05-데이터베이스설계서.md`).

**접근 규칙: 데이터 계층 검사.** RLS는 쓰지 않습니다. 앱이 DB 계정 하나로 접속하기 때문입니다. 대신 모든 서비스 메서드가 `PermissionService`로 권한을 확인하고, 모든 목록 쿼리는 `VisibleProjects.condition(user, permission)` 조건을 반드시 포함합니다(§4.1). 보이지 않는 리소스에는 403이 아니라 404를 돌려줘 존재 여부가 드러나지 않게 합니다.

**검증 완료 (2026-10-09)**: 임시 `postgres:17` 컨테이너에 `schema.sql`과 `seed.sql`을 적용했고 오류가 없었습니다. 테이블 36개, updated_at 트리거 27개, 기본 워크플로 300행이 생성됐고, 인덱스가 없는 외래 키는 0개였습니다. 아래 제약 9가지가 의도대로 동작하는 것도 확인했습니다.
낙관적 잠금(오래된 버전 갱신 0행) · 관계 쌍 유일성(역방향 거부) · lag는 precedes만 · 날짜 순서 · 기본 상태 하나 · 날짜 알림 중복 방지 · 사용자당 기본 알림 설정 하나 · 보드 컬럼 값 하나 · 사용 중인 상태 삭제 거부 · 부모-자식이 있는 프로젝트를 한 문장으로 cascade 삭제.

### 테이블 목록

| 영역 | 테이블 | 소유 기준 |
| --- | --- | --- |
| 사용자·인증 | users, user_preferences, user_tokens, user_groups, user_group_members, roles, global_role_assignments, oidc_providers(V10 추가), user_identities(V10 추가), api_tokens(V14 추가), idempotency_keys(V16 추가) | 사용자(user_id) 또는 인스턴스(관리자). SSO 공급자는 인스턴스(관리자), 연결은 user_id + provider_id. API 토큰은 user_id(본인, 관리자는 전체 폐기). Idempotency-Key 응답은 user_id + key(본인만, 앱 필터가 씀) |
| 프로젝트·멤버 | projects, project_favorites, members, member_roles | project_id, 즐겨찾기는 user_id |
| 작업 설정 | types, project_types, statuses, workflows, priorities, versions, categories, custom_fields, custom_field_options, custom_field_types, project_custom_fields | 인스턴스(관리자), 버전·카테고리는 project_id |
| 작업 패키지 | work_packages, relations, watchers, attachments, journals, work_package_shares(V6 추가) | project_id (작업 패키지를 통해), 공유는 work_package_id + user_id |
| 보기·보드 | queries, query_favorites, boards, board_columns, board_cards | owner_id / project_id |
| 알림 | notifications, notification_settings | recipient_id / user_id |
| 달력·설정 | non_working_days, settings | 인스턴스(관리자) |
| 연동 | webhooks(V15 추가), webhook_deliveries(V15 추가) | 인스턴스(관리자) |
| 시간 기록 | time_activities(V17 추가), time_entries(V17 추가) | 활동은 인스턴스(관리자). 기록은 user_id(본인, `time.manage`는 남의 것도) + project_id(작업의 프로젝트, 작업을 옮기면 따라감) |

### 설계 결정

- **사용자는 지우지 않고 익명화합니다.** `status='deleted'`로 바꾸고 이름은 "삭제된 사용자", 이메일은 `deleted+<id>@invalid`로 바꿉니다. 저널과 작성자 기록은 남고 개인정보만 사라집니다. 그래서 `author_id`와 `journals.user_id`는 `on delete restrict`입니다.
- **작업 번호(#123)는 인스턴스 전체에서 하나의 시퀀스**를 씁니다. PK는 uuid이고 URL과 코멘트의 `#123` 링크는 번호를 씁니다. 프로젝트를 옮겨도 번호는 바뀌지 않습니다.
- **일정 날짜는 `date`입니다.** 시작·완료·휴무일은 특정 순간이 아니라 달력 날짜이기 때문입니다. 순간을 나타내는 값(생성·수정·읽음·발송 시각)은 모두 `timestamptz`(UTC)입니다.
- **관계는 정방향 6종만 저장합니다.** "후행(follows)"은 `precedes`를 반대 방향에서 본 것입니다. 두 작업 사이에는 관계가 하나만 있을 수 있습니다(`least/greatest` 유니크 인덱스).
- **부모-자식은 `parent_id`와 재귀 CTE로 다룹니다.** 계층 깊이는 보통 10 미만이라 클로저 테이블이 필요 없습니다. 쿼리가 느려지면 그때 클로저 테이블을 추가합니다(§5).
- **사용자 정의 필드 값은 `custom_values jsonb`에 둡니다.** EAV 테이블보다 조회와 필터가 단순하고, `jsonb_path_ops` GIN 인덱스로 같음 조건을 빠르게 찾습니다. 대신 옵션·사용자 참조의 무결성은 앱이 검증합니다.
- **저장된 보기 설정은 `queries.spec jsonb` 한 칸**에 넣고 `"v":1`로 형식 버전을 둡니다. 기본 보기(모두 열림, 내게 할당됨 등)는 코드에 있습니다.
- **보드 컬럼은 종류별 FK를 nullable로 각각 둡니다**(`num_nonnulls = 1`). 다형 참조를 피했기 때문에 상태·버전 등이 삭제되면 컬럼도 FK에 따라 같이 삭제됩니다.
- **돈 컬럼은 없습니다.** 비용·단가·예산은 범위 밖입니다. 시간 기록은 3f9d5c0부터 있습니다(아래 V17).
- **SSO 공급자는 DB에 둡니다(V10).** `oidc_providers`: slug(콜백 주소에 들어감, `^[a-z0-9][a-z0-9-]{0,39}$`, 유일), display_name(1–60자), issuer, client_id, client_secret(평문, API로 내보내지 않음), scopes(기본 `openid email profile`), claim_email·claim_name(기본 `email`·`name`), auto_create(기본 false), enabled, position, updated_at 트리거. `user_identities`: user_id → users(cascade), provider_id → oidc_providers(cascade, 공급자를 지우면 연결만 사라짐), subject, last_login_at. 유일 (provider_id, subject)와 (provider_id, user_id): 공급자마다 계정 하나에 sub 하나. 공급자의 토큰은 저장하지 않습니다.
- **API 토큰은 해시만 둡니다(V14).** `api_tokens`: user_id(cascade), name(1–60자), token_hash(SHA-256, 유일), scope(`read`·`write`), last_used_at(1분에 한 번만 갱신), expires_at(null = 없음), revoked_at(폐기해도 행은 남김). 토큰 원문(`opms_` + 32바이트)은 만들 때 응답에 한 번만 있습니다.
- **웹훅은 보낼 일을 테이블에 쌓습니다(V15).** `webhooks`: name, url(`^https?://`), secret(HMAC 키, 평문, API로는 만들 때만), events(4종 부분집합, CHECK), project_ids(null = 전체), enabled, created_by. `webhook_deliveries`: 업무 변경과 같은 트랜잭션에서 넣는 아웃박스 행(event, payload jsonb, status pending·succeeded·failed, attempts, next_attempt_at, response_status, error). 보낼 차례인 행은 부분 인덱스(`status = 'pending'`)로 찾습니다. 웹훅을 지우면 전송 기록도 cascade로 지워지고, 그 밖에는 `token-cleanup`이 `pending`이 아니고 30일 지난 행을 지웁니다(dfde416). V16(93d1a74)이 `webhooks.created_by` 인덱스(`webhooks_created_by_idx`, 인덱스 없는 FK가 다시 0개)와 다른 테이블과 같은 `updated_at` 트리거(`webhooks_set_updated_at`)를 더했습니다.
- **생성 POST의 재시도는 DB에서 한 번으로 묶습니다(V16).** `idempotency_keys`: PK (user_id, key), user_id → users(cascade), key(1–255자, CHECK), method, path(쿼리 포함), request_hash(메서드·경로·본문의 SHA-256: 같은 키를 다른 요청에 쓰면 422), status(null = 처리 중, 같은 키가 또 오면 409), content_type, body(bytea, 처음 2xx 응답), created_at(인덱스 `idempotency_keys_created_idx`). 처음 요청이 행을 먼저 넣어(`on conflict do nothing`) 동시에 온 같은 요청 중 하나만 실행되게 하고, 2xx가 아니면 행을 지워 다시 보낼 수 있게 합니다. 24시간 뒤 `token-cleanup`이 지웁니다. 설계(§5)는 "10분 보관"이었지만 사람이 다음 날 다시 시도하는 경우까지 덮도록 하루로 늘렸습니다.
- **시간 기록은 사람·작업·날짜마다 한 줄씩 둡니다(V17).** `time_activities`: name(1–60자, 유일), position, is_default(부분 유일 인덱스로 하나만), active(끈 활동은 새 기록에 못 쓰고 지난 기록에는 남음), updated_at 트리거. 기본 7개: 개발(기본)·설계·회의·테스트·문서·관리·기타. `time_entries`: user_id → users(cascade), project_id → projects(cascade, 작업의 프로젝트를 복사해 둠 — 보고서·권한을 작업 조인 없이 프로젝트로 거르려고. 작업을 옮기면 `WorkPackageTransfers.move`가 같이 바꿈), work_package_id → work_packages(cascade), activity_id → time_activities(set null), spent_on(date), hours(numeric(5,2), 0 초과 24 이하), comment(1,000자), updated_at 트리거. 인덱스 (user_id, spent_on)·(project_id, spent_on)·(work_package_id)·(activity_id)라 인덱스 없는 FK는 여전히 0개. 한 사람의 하루 24시간 제한과 "칸 하나 = 그날 그 작업의 내 시간 합"은 DB 제약이 아니라 앱(`TimeController`)이 지킵니다. 같은 칸에 여러 줄을 허용해(시간 탭에서 활동·메모를 나눠 남김) 주간 입력표는 그런 칸을 바꾸지 않습니다. 시간 기록은 저널에 남기지 않습니다(근무 기록이지 작업의 변경이 아니어서). 처음 V17 초안은 `projects_known_modules`를 넓히지 않아 프로젝트가 있는 DB에서 실패했을 것이라 출시 전에 고쳤습니다.
- PostgreSQL 18로 올리면 시간순 정렬되는 `uuidv7()`을 기본값으로 바꾸는 것을 검토합니다.

### 권한 키

seed의 역할과 코드의 상수가 같아야 합니다. 이름은 openPMS가 정한 것입니다.

| 키 | 의미 |
| --- | --- |
| project.view / project.edit / project.archive | 프로젝트 보기 / 정보·상태 수정 / 보관 |
| project.manage_modules / project.manage_members / project.view_members | 모듈 켜기·끄기 / 멤버 관리 / 멤버 보기 |
| project.manage_versions / project.manage_categories / project.manage_types / project.manage_custom_fields | 프로젝트 설정 |
| wp.view / wp.create / wp.edit / wp.delete / wp.move | 작업 패키지 기본 |
| wp.attach | 첨부 추가·삭제 |
| wp.comment / wp.edit_own_comments / wp.edit_comments | 코멘트 |
| wp.view_internal_comments / wp.add_internal_comments | 내부 코멘트 |
| wp.manage_relations / wp.manage_subtasks / wp.assign_versions | 관계 / 부모·자식 / 버전 지정 |
| wp.view_watchers / wp.add_watchers / wp.delete_watchers | 주시자 |
| wp.export | 내보내기 |
| wp.share | 작업 패키지를 멤버 아닌 사용자와 공유(V6 추가, 기본: 프로젝트 관리자) |
| query.save / query.manage_public | 비공개 보기 저장 / 공개 보기 관리 |
| board.view / board.manage | 보드 보기 / 보드·컬럼 관리 |
| time.log / time.view / time.manage | 내 시간 기록(기록·고치기·지우기) / 모든 사람의 시간 기록 보기(공수 보고서) / 남의 시간 기록 고치기·대신 기록. V17 추가, 모듈 `time_tracking`이 꺼지면 빠짐. 기본: 프로젝트 관리자 셋 다, 멤버 time.log·time.view, 참관자·비멤버 없음 |
| global.create_projects / global.manage_users | 전역 역할 전용 |

기본 역할(seed): 프로젝트 관리자(전체), 멤버, 참관자, 비멤버(공개 프로젝트 열람), 프로젝트 생성자(전역).

### 전역 설정 키 (`settings`)

`calendar.working_days` `[1..5]`, `calendar.week_starts_on` `7`(일요일. 구현: `"default"`·`1` 월·`6` 토·`7` 일, ISO 요일), `calendar.date_format` `default`(한국어: 2026.10.21)·`YYYY.MM.DD`·`YYYY-MM-DD`·`YYYY/MM/DD`·`YYYY년 M월 D일`·`DD.MM.YYYY`·`MM/DD/YYYY`, `calendar.time_format` `default`(24시간)·`24h`·`12h`, `calendar.hours_per_day` `8`(구현: 3f9d5c0부터 주간 입력표의 하루 근무 시간 경고에도 씀), `calendar.duration_format`, `progress.mode` `work|status`, `progress.totals` `weighted|simple_average`, `progress.closed_sets_100`, `attachments.max_bytes` (25MB), `journals.aggregation_minutes` `5`, `notifications.poll_seconds` `60`, `i18n.default_locale`, `i18n.default_time_zone`, `auth.*` (비밀번호 길이, 잠금 횟수·시간, 토큰 수명), `mail.from`. 구현에서 더한 키: `api.tokens_enabled`(API 토큰 사용, 기본 true, f835e1c).

---

## 3. 도메인 규칙 (바로 구현할 수 있는 수준의 명세)

### 3.1 권한 계산 (`PermissionService`)

1. 관리자(`users.is_admin`)는 모든 권한을 가집니다.
2. 프로젝트 권한은 다음의 합집합입니다.
   - 사용자의 직접 멤버십 역할
   - 사용자가 속한 그룹의 멤버십 역할
   - 위 둘이 없고 프로젝트가 공개라면 `non_member` 역할
3. 프로젝트 모듈이 꺼져 있으면 그 모듈의 화면과 권한을 쓸 수 없습니다(보드 → `board.*`, 캘린더·팀 플래너·Gantt 화면. 구현 789bb9d: 시간 기록 `time_tracking` → `time.*`, `Ctx.MODULE_OF`). 원본에서는 이 경우 403이 떴는데, openPMS에서는 메뉴에서 숨기고 직접 접근하면 404를 돌려줍니다.
4. 보관된 프로젝트에서는 `*.view` 계열만 허용됩니다.
5. 전역 권한은 `global_role_assignments`에서 계산합니다. 구현(0af87f8): 관리자가 관리 · 사용자에서 `PUT /admin/users/{id}/global-roles`로 배정합니다(§4.2 관리).
6. 요청마다 `user → {project_id → Set<permission>}`을 한 번 계산해 캐시합니다. 목록 쿼리에는 `project_id = any(:visible)` 조건이 붙습니다.

### 3.2 워크플로 검사

- **허용되는 다음 상태**: 아래 조건을 모두 만족하는 workflows 행의 `to_status_id` 집합입니다.
  - `type_id` = 작업의 유형
  - `role_id` ∈ 사용자의 프로젝트 역할
  - `from_status_id` = 현재 상태
  - `applies_to = 'always'`, 또는 `'author'`이면서 사용자가 작성자, 또는 `'assignee'`이면서 사용자가 담당자
- 관리자는 모든 상태로 바꿀 수 있습니다.
- 유형과 상태를 함께 바꾸면 새 유형을 기준으로 검사합니다.
- 새 작업은 기본 상태로 시작합니다. 생성 폼에서는 기본 상태에서 갈 수 있는 상태만 고를 수 있습니다.
- 현재 상태가 **읽기 전용**이면 상태 외 필드를 바꾸는 요청은 422로 거부합니다. 상태 전환은 허용합니다.
- 보드에서 카드를 옮길 때도 같은 검사를 거칩니다. 실패하면 422를 돌려주고 카드는 원래 자리로 돌아갑니다.

### 3.3 스케줄링 엔진 (`scheduling`, 순수 Java)

용어: **근무일**은 `calendar.working_days`에 포함된 요일이면서 `non_working_days`에 없는 날입니다. 작업의 `ignore_non_working_days`가 true이면 모든 날을 근무일로 봅니다.

| # | 규칙 |
| --- | --- |
| S1 | `duration` = 시작일과 완료일 사이의 근무일 수(양 끝 포함). 근무일만 모드에서는 비근무일을 시작일·완료일로 고를 수 없습니다(화면에서 비활성, 서버는 422). |
| S2 | 시작·완료·기간 중 둘이 정해지면 나머지 하나를 계산합니다. 어느 둘을 기준으로 할지는 사용자가 마지막에 바꾼 두 필드로 정합니다(요청의 `touched` 배열). |
| S3 | 마일스톤은 시작일 = 완료일, 기간 1입니다. |
| S4 | 자동 모드(`schedule_manually=false`)에서 **자식이 있으면** 시작일 = 자식들의 최소 시작일, 완료일 = 최대 완료일입니다. 직접 수정할 수 없습니다(S10 배너 "날짜는 자식 작업 패키지에 의해 결정됩니다"). |
| S5 | 자동 모드에서 **자식이 없으면** 시작일 = max(선행 작업의 완료일 다음 `lag+1`번째 근무일)입니다. 선행 작업은 자신과 모든 조상의 선행 작업을 포함합니다. 선행이 없으면 현재 시작일을 유지합니다. 기간은 유지하고 완료일을 다시 계산합니다. |
| S6 | 수동 모드에서는 사용자가 정한 날짜를 그대로 둡니다. 선행 작업보다 먼저 시작하는 것도 허용하되, 화면에 경고를 표시합니다. |
| S7 | **전파**: 날짜, 모드, 근무일만 여부, 관계, 부모가 바뀌면 영향을 받는 그래프를 구합니다. 후행 작업(정방향), 부모(위로), 날짜 범위가 바뀐 작업의 자동 모드 자손(아래로)이 대상입니다. 위상 정렬 순서로 다시 계산하고, 바뀐 작업만 한 트랜잭션에 저장합니다. 각 작업에 저널을 남기고(`cause` 기록) `lock_version`을 올립니다. |
| S8 | **순환 방지**: precedes 관계를 추가하거나 부모를 바꿀 때, 부모가 자손 전체를 포함한다고 보고 경로가 생기는지 검사합니다. 순환이 생기거나 조상·자손 사이에 선후행을 걸면 422입니다. |
| S9 | **동시성**: 스케줄을 바꾸는 트랜잭션은 `pg_advisory_xact_lock(<스케줄 잠금 키>)`로 직렬화합니다. 사내 규모에서는 전역 잠금 하나로 충분하고 대기 시간은 밀리초 수준입니다. |
| S10 | **미리 보기**: 날짜 대화상자는 `schedule-preview`를 호출해 저장하지 않고 계산 결과와 영향받는 작업 수를 보여줍니다. |
| S11 | **근무일 설정 변경**: `reschedule-all` 작업이 모든 작업에 대해 시작일과 기간을 유지하고 완료일을 다시 계산한 뒤 S7로 전파합니다. 원본의 정확한 동작은 확인하지 못했으므로, 공개 문서(recon 출처 #9)를 읽은 뒤 확정합니다. |

### 3.4 진행률 (`progress`, 순수 Java)

| # | 규칙 |
| --- | --- |
| P1 | `work` 모드: 작업량·남은 작업·완료 % 중 둘을 입력하면 나머지를 계산합니다. 남은 작업 = 작업량 × (1 − %/100)이고 0.01시간 단위로 반올림합니다. % = (작업량 − 남은 작업) / 작업량이고 정수로 반올림합니다. 작업량이 없으면 %만 자유롭게 입력합니다. |
| P2 | `status` 모드: % = 상태의 `default_done_ratio`이고 직접 입력할 수 없습니다. 남은 작업 = 작업량 × (1 − %)입니다. |
| P3 | 닫힌 상태이고 `progress.closed_sets_100`이 켜져 있으면 % = 100, 남은 작업 = 0입니다. |
| P4 | 합계: `total_work`와 `total_remaining`은 자신과 자손의 합입니다. `excluded_from_totals` 상태의 작업은 빼고, 자손이 바뀌면 조상 체인을 갱신합니다. |
| P5 | `total_done_ratio`: `weighted`이면 (합계 작업량 − 합계 남은 작업) / 합계 작업량입니다. `simple_average`이면 자신과 자손의 % 평균입니다. 구현(31e39bd): `weighted`에서 작업량은 있고 남은 작업이 비어 있는 작업은 아직 시작하지 않은 것(남은 작업 = 작업량)으로 셉니다. 목록 합계의 완료 %도 같은 규칙입니다(§3.5). |

### 3.5 쿼리 엔진 (`query`)

**spec 형식** (`queries.spec`, URL의 `?q=` base64url과 같은 형식):

```json
{ "v": 1,
  "filters": [{"field": "status", "op": "open"}, {"field": "assignee", "op": "eq", "values": ["me"]}],
  "columns": ["number", "subject", "type", "status", "assignee", "priority"],
  "sort": [{"field": "number", "dir": "asc"}],
  "groupBy": null, "hierarchy": true, "sums": false, "includeSubprojects": true,
  "timeline": {"visible": false, "zoom": "auto", "labels": {"left": "start_date", "right": "due_date", "farRight": "subject"}} }
```

**연산자**:

| op | 의미 | 대상 |
| --- | --- | --- |
| eq / neq | 같음 / 다름 (값 여러 개면 OR) | 참조·목록·숫자·날짜 |
| any / none | 값 있음 / 없음 | 모든 필드 |
| open / closed | 열린 상태 / 닫힌 상태 | status |
| contains / not_contains | 부분 일치 (ILIKE, trigram) | 텍스트 |
| gte / lte / between | 이상 / 이하 / 사이 | 숫자·날짜 |
| today / this_week | 오늘 / 이번 주 (사용자 시간대 기준) | 날짜 |
| next_days(n) / past_days(n) | 앞으로 n일 이내 / 지난 n일 이내 | 날짜 |
| before_days_ago(n) / after_days_from_now(n) | n일 전보다 이전 / n일 후보다 이후 | 날짜 |
| overdue | 완료일 < 오늘이면서 열린 상태 | due_date |

특수 값: 사용자 필드에 쓰는 `me`, 사용자 또는 그룹 필드에 쓰는 `me_or_my_groups`.

**필드** (recon에서 관찰한 목록을 openPMS 키로 옮긴 것): number, subject, description, comment, status, type, priority, project, assignee, assignee_or_group, assignee_role, responsible, author, watcher, version, category, parent, descendants_of, is_milestone, start_date, due_date, dates(시작~완료 기간이 겹침), duration, created_at, updated_at, done_ratio, work, remaining_work, attachment_name, `cf:<custom_field_id>`.

**실행 순서**:
1. 볼 수 있는 프로젝트로 제한합니다(포함 옵션이면 하위 프로젝트 포함).
2. 필터를 WHERE 조건으로 바꿉니다.
3. 전체 건수를 셉니다.
4. 정렬하고 페이지를 자릅니다.
5. `hierarchy`가 켜져 있으면 결과의 조상을 재귀 CTE로 가져와 맥락 행으로 붙입니다(필터에 맞지 않음 표시). 형제끼리는 sort 기준으로 정렬해 트리 순서로 내보냅니다.
6. `groupBy`가 있으면 그룹 머리글과 그룹별 합계를 계산합니다.
7. `sums`가 켜져 있으면 전체 합계를 계산합니다.

구현(2bb76a8): 그룹화할 수 있는 열은 `QueryEngine.GROUPABLE`(type, status, priority, assignee, responsible, author, version, project)과 목록·사용자·예/아니요 사용자 정의 필드입니다. 그 밖의 열은 오류 없이 무시합니다. 그룹화하면 계층(5단계)은 쓰지 않고, 행은 그룹 순서(그 값의 순서, 값 없음은 맨 끝)로 정렬합니다. 그룹 건수와 합계는 페이지가 아니라 조건에 맞는 전체 기준입니다. 합계 대상은 보이는 열 중 작업량·남은 작업·완료 %·숫자 사용자 정의 필드입니다. 작업량·남은 작업은 목록의 모든 작업을 더합니다. 완료 %는 384a2f9부터 작업의 ∑ 합계(P4·P5)처럼 `progress.totals`를 따르고(가중 또는 단순 평균) `excluded_from_totals` 상태(시드: 취소)를 뺍니다. 가중일 때 남은 작업이 비어 있으면 작업량으로 셉니다(31e39bd). 응답은 `QueryResult.groups`(key, label, color, count, sums)와 `QueryResult.sums`, 행마다 `groupKey`입니다. 끈 상태면 셋 다 null입니다.

구현(2026-10-11): spec에 화면 전용 키가 더 있습니다. `timeline.labels`(c94f95f)의 값은 위 예와 달리 `none`·`subject`·`assignee`·`status`·`dates`·`type`·`priority`·`doneRatio`이고 기본은 `{left: none, right: subject, farRight: none}`입니다. `highlight`(e3eeb60)는 `none`·`inline`·`status`·`priority`·`type`입니다. 둘 다 서버는 저장만 합니다. `baseline`(1728c3c)은 `{kind: yesterday|lastWeek|lastMonth|date, date?}`이고, 서버(`query/Baselines`)가 사용자 시간대로 그날 0시를 정해 그 페이지 행의 저널에서 그 뒤 첫 변경의 `from`(그때 값)을 읽어 `QueryRow.baseline {added, changed}`와 `QueryResult.baselineAt`을 붙입니다. 그때는 조건에 맞았지만 지금은 빠진 작업은 돌려주지 않습니다(partial).

성능 목표(추정): 작업 1만 건인 프로젝트에서 p95 300ms 이하.

### 3.6 저널과 알림

- **저널 집계**: 같은 사용자가 같은 작업을 `journals.aggregation_minutes`(5분) 안에 연속으로 바꿨고, 그 사이에 다른 사람의 저널이나 코멘트가 없으면 직전 저널에 변경을 합칩니다(`changes` 병합). 이미 만든 알림이 아직 읽히지 않았다면 그 알림을 갱신하고, 읽혔다면 새로 만듭니다.
- **알림 생성(fan-out)**: 저널을 저장하는 트랜잭션 안에서 `notification-fanout` 작업을 예약합니다. 대상은 다음과 같습니다.
  - 새로 추가된 @멘션
  - 담당자와 책임자
  - 주시자
  - 사용자 설정에 따라 새 작업·코멘트·상태·우선순위·날짜 변경을 구독한 멤버
- **제외 대상**: 행위자 본인, 그 작업을 볼 권한이 없는 사용자(멤버가 아닌 사람이 멘션된 경우 포함). 내부 코멘트는 `wp.view_internal_comments` 권한이 있는 사용자에게만 알립니다.
- **수신자 1명당 1건**만 만듭니다. 이유가 여러 개면 우선순위가 높은 것 하나를 씁니다: mentioned > assigned > responsible > watched > 그 외.
- **날짜 알림**: 매시간 실행하는 작업이 사용자 시간대 기준 "오늘"로 시작·완료 N일 전 알림과 기한 지남 알림을 만듭니다. 유니크 인덱스가 중복을 막습니다.

---

## 4. API

### 4.1 규칙

- 경로 앞부분은 `/api/v1`, JSON 필드는 camelCase입니다. 인증은 세션 쿠키, CSRF는 `X-XSRF-TOKEN` 헤더로 합니다.
- 구현(f835e1c): 사내 연동은 개인 API 토큰을 `Authorization: Bearer opms_…`로 보냅니다. 세션·쿠키가 없으므로 CSRF를 보지 않고, 그 요청만 만든 사람의 권한으로 처리합니다. 읽기 전용 토큰은 GET(과 목록 조회·내보내기 POST)만, 로그인·비밀번호·토큰 관리 경로는 토큰으로 부를 수 없고, 토큰마다 분당 300회입니다. 버전 정책: v1 안에서는 응답 필드를 더하기만 하고, 빼거나 뜻을 바꾸는 변경은 `/api/v2`로 냅니다. 주요 경로의 OpenAPI 3.1 문서는 `GET /openapi.json`(공개).
- 구현(93d1a74): 무언가를 만드는 POST에 `Idempotency-Key: <요청마다 새 임의 값>` 헤더를 붙이면 같은 키의 재전송에 처음 2xx 응답을 그대로 돌려줍니다(응답 헤더 `Idempotent-Replayed: true`, 하루 보관). 같은 키에 다른 본문 422, 처음 요청이 처리 중이면 409, 키 255자 초과 422. 2xx가 아닌 응답은 보관하지 않고, multipart와 1MB 넘는 응답은 대상이 아닙니다. 화면은 프로젝트 만들기·복사, 작업 만들기·복사, 코멘트에 키를 붙입니다.
- **식별자**: 작업 패키지는 `number`, 프로젝트는 `identifier`(또는 uuid), 나머지는 uuid입니다.
- **오류**: RFC 9457 `application/problem+json` 형식입니다.

  | 코드 | 상황 |
  | --- | --- |
  | 401 | 로그인 필요 |
  | 403 | 볼 수는 있지만 이 동작은 할 수 없음 |
  | 404 | 없거나 볼 수 없음 |
  | 409 | lock_version 충돌 (본문에 현재 값 포함) |
  | 422 | 검증 실패 (필드별 오류), 워크플로 위반, 순환 |
  | 429 | 요청 제한 |

- **목록 응답**: `?page=1&pageSize=20`(20, 100, Gantt는 최대 1000) → `{items, total, page, pageSize}`.
- **작업 패키지 응답의 `_meta`**: `editableFields`, `allowedActions`(edit, delete, move, comment, manageRelations…), `derived`(파생 필드 목록)를 담습니다. 화면은 이 값으로 편집 가능 여부를 그리고, 권한 규칙을 프론트에 복제하지 않습니다.
- 원본 앱의 내부 API는 참고하지 않습니다. 모든 경로는 openPMS가 정의한 것입니다.

### 4.2 경로 (총 177개: 설계 134 + 사내 SSO 7 + 보드 카드 3 + 날짜 형식 1 + 비밀번호 만들기 1 + 전역 역할 배정 1 + 프로젝트 복사 1 + API 토큰·OpenAPI 8 + 웹훅 8 + 알림 건수 1 + 시간 기록 12 + 시스템 정보 2 = 179)

이 표는 설계에 구현 기록을 덧붙인 것입니다. 실제 컨트롤러 매핑 수(3f9d5c0 기준 186개, dev 전용 1개 포함)와 경로별 차이는 `docs/deliverables/04-인터페이스정의서.md` 부록에 있습니다.

#### 인증·계정 (18)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| POST /auth/login | 로그인, 세션 발급, 실패 횟수 누적·잠금. 요청 제한은 클라이언트 주소별(믿는 프록시의 `X-Forwarded-For`, 384a2f9) | 익명 | email, password | 200 me / 401 / 423 잠김 / 429 | S01 |
| POST /auth/logout | 세션 종료 | 로그인 | - | 204 | 공통 |
| GET /auth/me | 현재 사용자, 환경설정, 전역 권한. 384a2f9: `hasPassword`(SSO로만 들어와 비밀번호가 없으면 false), `signInWith`(연결된 SSO 공급자 표시 이름) | 로그인 | - | me | 공통 S37 |
| POST /auth/password-reset | 재설정 메일 발송 (가입 여부와 관계없이 항상 202) | 익명 | email | 202 | S01 |
| POST /auth/password-reset/confirm | 토큰으로 비밀번호 변경 | 토큰 | token, newPassword | 204 / 410 만료 | S01 |
| GET /auth/sso-providers | 켜진 SSO 공급자 목록(로그인 버튼) | 익명 | - | [{slug, displayName}] | S01 |
| GET /auth/oidc/start/{slug} | SSO 시작: state·nonce·PKCE(S256)를 세션에 두고 IdP로 302. `next`는 같은 사이트 경로만 세션에 둠. 꺼짐·없음·IdP에 닿지 못함이면 `/login?sso_error=unavailable` | 익명 | next? | 302 | S01 |
| GET /auth/oidc/callback/{slug} | IdP가 돌아오는 주소(= `OPENPMS_BASE_URL` + 이 경로). code 교환, ID 토큰 서명·issuer·nonce 확인 → 연결된 sub → 같은 이메일 → 자동 생성 → 거부. 성공하면 세션 발급 후 `next` 또는 `/`로 | IdP 리디렉션 | code, state (또는 error) | 302 / `/login?sso_error=no_account\|locked\|unverified\|no_email\|cancelled\|unavailable\|failed` | S01 |
| GET /auth/invitations/{token} | 초대 정보(이메일, 초대자) | 토큰 | - | invitation / 410 | F10 |
| POST /auth/invitations/{token}/accept | 이름·비밀번호 설정 후 활성화 (첫 관리자 설정도 같은 경로) | 토큰 | name, password | 200 me | F10 |
| PATCH /me | 이름, 언어, 시간대, 테마. 구현: 테마 `system`·`light`·`dark`·`high_contrast`(V12, a66d02e) | 본인 | 부분 | me | S37 |
| PUT /me/password | 비밀번호 변경 (다른 세션 만료) | 본인 | current, new | 204 / 422 | S37 |
| POST /me/password/setup | 비밀번호가 없는 사람(SSO 전용)에게 비밀번호 만들기 링크를 본인 주소로 메일(재설정과 같은 `password_reset` 토큰, 재설정 화면). 재설정 허용 설정과 무관. 사용자당 분당 3회. 384a2f9 | 본인 | - | 202 / 422 이미 비밀번호 있음 / 429 | S37 |
| PUT /me/preferences | 코멘트 정렬, 이탈 경고, 단축키, 요약 메일 시각. 구현: 단축키 `keyboardShortcuts`는 956e130부터 실제 동작(계정 설정 체크박스), 코멘트 정렬은 활동 탭의 정렬 버튼도 씀(4fb4ca5) | 본인 | preferences | preferences | S37, S20 |
| GET /me/notification-settings | 기본값과 프로젝트별 설정 | 본인 | - | settings[] | S20 |
| PUT /me/notification-settings/default | 기본 알림 설정 저장 | 본인 | 설정 | setting | S20 |
| PUT /me/notification-settings/projects/{project} | 프로젝트별 재정의 | 본인 + project.view | 설정 | setting | S20 |
| DELETE /me/notification-settings/projects/{project} | 재정의 삭제 | 본인 | - | 204 | S20 |

#### 프로젝트·멤버 — F10 (27)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /projects | 목록 (active, mine, favorite, archived, 상태, 이름 검색, 정렬). 구현(a3aee0b): `filter=templates`는 템플릿으로 표시한 활성 프로젝트(새 프로젝트의 템플릿 목록) | 로그인 (보이는 것만) | 쿼리 파라미터 | page<ProjectSummary> | S03 S04 |
| POST /projects | 생성 (빈 프로젝트 또는 templateId), 생성자를 관리자 역할로 추가, 기본 유형 활성. 구현(a3aee0b): `templateId`는 볼 수 있고 보관되지 않은 템플릿(없으면 422 `template`), `copy`는 members·versions·categories·workPackages·boards·queries 중 고른 것(그 밖은 422 `copy`). 모듈·유형·프로젝트 사용자 정의 필드·프로젝트 특성 값·설명(입력이 없을 때)은 항상 복사. 작업은 계층·작업끼리의 관계·"만듦" 저널 하나와 함께, 알림 없음. 복사한 버전·카테고리는 보기·보드 필터에서도 새 것으로, 비멤버는 담당자·책임자·보드 열에서 빠짐. 하위 프로젝트 보드와 첨부는 복사하지 않음. 복사는 요청 안에서 동기(`project/ProjectCopy`), 202·작업 예약은 없음. `parentId`는 상위에 project.edit. 새 시작일(2f7ffb9·b63eaa0): 템플릿과 함께 준 `startDate`(YYYY-MM-DD, 아니면 422 `startDate`)면 가장 이른 복사 날짜가 그날(근무일이 아니면 그 뒤 첫 근무일)이 되도록 작업은 근무일 기준(시작·완료 모두 근무일 위치 유지), 근무일 무시 작업과 버전은 같은 달력일 수만큼 옮김(`ProjectCopy.Shift`) | global.create_projects | name, identifier, description, parentId, isPublic, templateId, copy[], startDate? | 201 project (202 없음) | S04 F10 |
| GET /projects/{project} | 상세 + 내 권한 + 켜진 모듈 | project.view | - | project | S05 |
| PATCH /projects/{project} | 정보, 상태, 공개, 부모, 모듈. 구현: `isTemplate`(템플릿으로 쓰기, a3aee0b), `parentId`(5d641d1: 새 상위에 project.edit, 자기 자신이나 그 아래면 422 `parent`, null이면 최상위), `internalComments`(내부 코멘트 켜기, V11, 4fb4ca5), `overviewWidgets`(개요 위젯과 순서, V13, 3a5a544: description·workSummary·status·members·versions·dueSoon·recent·assignedToMe) | project.edit (모듈은 project.manage_modules) | 부분 | project | S05 S24 S25 |
| POST /projects/{project}/copy | 구현(cdce335): 이 프로젝트를 템플릿처럼 복사해 새 프로젝트를 만듦. 입력과 복사 규칙은 `POST /projects`의 템플릿과 같음(`ProjectCopy`, 동기, 개요 위젯·내부 코멘트 설정 포함). 선택 `startDate`도 같음(2f7ffb9·b63eaa0, 새 시작일로 일정 이동) | 원본에 project.edit + global.create_projects | name, identifier, parentId, copy[], startDate? | 201 project | S24 F10 |
| POST /projects/{project}/archive | 보관 | project.archive | - | 204 | S03 |
| POST /projects/{project}/unarchive | 복원 | 관리자 | - | 204 | S03 |
| DELETE /projects/{project} | 삭제 (식별자 재입력 확인, 하위 프로젝트가 있으면 409). 구현(1.6): 시간 기록도 cascade로 지워지고 확인 화면은 이를 말하지 않음 | 관리자 | confirmIdentifier | 204 | S03 |
| PUT /projects/{project}/favorite | 즐겨찾기 추가 | project.view | - | 204 | S02 S03 |
| DELETE /projects/{project}/favorite | 즐겨찾기 해제 | 본인 | - | 204 | S02 S03 |
| GET /projects/{project}/overview | 개요 위젯 데이터 (상태, 멤버 요약, 상태별 작업 수). 구현: 버전 진행·곧 완료일·최근 바뀐 작업·내게 할당된 작업 위젯은 버전 목록과 `POST /work-packages/query`를 씀 | project.view | - | overview | S05 |
| PUT /projects/{project}/types | 사용할 유형 지정 | project.manage_types | typeIds | types | S25 |
| PUT /projects/{project}/custom-fields | 사용할 사용자 정의 필드 지정 | project.manage_custom_fields | customFieldIds | fields | S25 |
| GET /projects/{project}/versions | 버전 목록 (공유 버전 포함) | wp.view | - | versions | S26 |
| POST /projects/{project}/versions | 버전 생성 | project.manage_versions | name, dates, status, sharing | 201 version | S26 |
| PATCH /versions/{id} | 버전 수정 | project.manage_versions | 부분 | version | S26 |
| DELETE /versions/{id} | 버전 삭제 (작업의 버전은 비움) | project.manage_versions | - | 204 | S26 |
| GET /projects/{project}/categories | 카테고리 목록 | wp.view | - | categories | S25 |
| POST /projects/{project}/categories | 생성 | project.manage_categories | name, defaultAssigneeId | 201 | S25 |
| PATCH /categories/{id} | 수정 | project.manage_categories | 부분 | category | S25 |
| DELETE /categories/{id} | 삭제 | project.manage_categories | - | 204 | S25 |
| GET /projects/{project}/members | 멤버 목록 (필터: 역할, 그룹, 상태) | project.view_members | 쿼리 파라미터 | page<Member> | S23 |
| POST /projects/{project}/members | 사용자·그룹 추가 + 역할 | project.manage_members | principalIds, roleIds | 201 members | S23 F10 |
| PATCH /members/{id} | 역할 변경 (마지막 관리자 보호) | project.manage_members | roleIds | member / 422 | S23 |
| DELETE /members/{id} | 제거 (마지막 관리자 보호) | project.manage_members | - | 204 / 422 | S23 |
| POST /projects/{project}/invitations | 없는 사용자를 이메일로 초대하고 멤버로 추가 | project.manage_members + global.manage_users(설정에 따라) | email, name, roleIds | 201 member | S23 F10 |
| GET /principals | 사용자·그룹 자동완성 (멘션, 담당자, 멤버 추가) | 로그인 (같은 프로젝트에 있는 사람 위주) | q, projectId, kind | principals[] | 공통 |

#### 작업 패키지 — F01 · F02 · F07 · F08 (19)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /projects/{project}/work-packages/form | 생성 폼 스키마 (필드, 필수, 허용값, 허용 상태, 설명 템플릿) | wp.create | typeId, parentNumber | form | S09 F01 |
| POST /projects/{project}/work-packages | 생성 → 저널 v1 → 알림 예약 → 스케줄 전파 | wp.create (부모 지정 시 wp.manage_subtasks) | typeId, subject, description, 속성, customValues, parentNumber, attachmentIds | 201 WP | S09 F01 F07 |
| GET /work-packages/{number} | 상세 + `_meta` | wp.view | - | WP | S07 S08 |
| PATCH /work-packages/{number} | 부분 수정 (워크플로, 읽기 전용, 스케줄·진행률 재계산, 저널) | wp.edit (상태: 워크플로, 부모: wp.manage_subtasks, 버전: wp.assign_versions) | lockVersion, 변경, touched[] | WP / 409 / 422 | S06–S08 S13 F02 F04 |
| DELETE /work-packages/{number} | 삭제 (자식 삭제 또는 조부모로 올리기 선택). 구현(1.6): 시간 기록도 cascade로 지워지고, 화면의 확인 대화상자가 기록된 시간을 경고(39c8974) | wp.delete | children=delete\|reparent | 204 | S08 |
| POST /work-packages/{number}/copy | 복사 (대상 프로젝트, 자식·관계·첨부 포함 여부) | 대상에서 wp.create | targetProject, options | 201 WP | S08 |
| POST /work-packages/{number}/move | 다른 프로젝트로 이동 (유형·버전·카테고리 매핑) | wp.move + 대상에서 wp.create | targetProject, mapping | WP / 422 | S08 |
| POST /work-packages/bulk | 일괄 수정·이동·삭제 (전부 성공하거나 전부 실패). 구현(d0023b1): `action: copy`(targetProject 없으면 같은 프로젝트), 고른 작업끼리의 상위-하위를 사본에도 유지. 화면은 일괄 바와 행 우클릭 메뉴 | 항목마다 해당 권한 (복사는 대상에서 wp.create) | numbers[], action(update·move·delete·copy), changes, targetProject | 200 / 422 + 실패 목록 | S06 |
| GET /work-packages/{number}/allowed-statuses | 지금 사용자 기준 전환 가능한 상태 | wp.view + wp.edit | - | statuses[] | S06–S08 F02 |
| GET /work-packages/autocomplete | `#번호`·제목 검색 (관계·부모 지정, 멘션의 #링크) | 로그인 (보이는 것만) | q, projectId, excludeRelatedTo | items[] | F05 F07 |
| GET /work-packages/{number}/activities | 저널 목록 (all, comments, changes, 정렬). 구현: 파라미터 없이 전체를 주고 모두·코멘트만·변경만과 정렬은 화면이 함(4fb4ca5) | wp.view (내부 코멘트는 wp.view_internal_comments) | filter, order, page | page<Journal> | S08 |
| POST /work-packages/{number}/comments | 코멘트 작성 (sanitize, 멘션 파싱, 알림 예약). 구현(4fb4ca5): `internal`은 프로젝트의 내부 코멘트가 켜져 있어야 함(아니면 422). 내부 코멘트는 웹훅으로 보내지 않음 | wp.comment (내부: wp.add_internal_comments) | bodyHtml, internal | 201 journal | S08 F08 |
| PATCH /comments/{journalId} | 코멘트 수정 | 본인 + wp.edit_own_comments, 또는 wp.edit_comments | bodyHtml | journal | S08 |
| GET /work-packages/{number}/watchers | 주시자 목록 | wp.view_watchers | - | users[] | S08 |
| POST /work-packages/{number}/watchers | 주시자 추가 (본인은 wp.view만으로 가능) | wp.add_watchers | userId | 201 | S07 S08 |
| DELETE /work-packages/{number}/watchers/{userId} | 주시 해제 (본인은 항상 가능) | wp.delete_watchers | - | 204 | S07 S08 |
| POST /attachments | 업로드 (multipart, 크기 제한, Tika 판별, SHA-256). 작업 없이 올리면 임시 첨부 | wp.attach (작업이 아직 없으면 wp.create) | file, workPackageNumber? | 201 attachment / 413 / 415 | S08 S09 |
| GET /attachments/{id}/content | 다운로드 (`Content-Disposition: attachment`, nosniff, 이미지 허용 목록만 inline) | 그 작업의 wp.view | - | 파일 스트림 | S08 |
| DELETE /attachments/{id} | 삭제 (파일은 커밋 후 지움) | 작성자 또는 wp.attach | - | 204 | S08 |

#### 관계 — F05 · F07 (4)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /work-packages/{number}/relations | 관계를 유형별로 묶은 것 + 부모·자식 (화면 이름은 역방향 포함) | wp.view | - | groups | S08 |
| POST /work-packages/{number}/relations | 관계 추가 (역방향 이름을 정방향으로 저장, 순환 검사, 스케줄 전파) | 양쪽 작업에 wp.manage_relations | type(11종), toNumber, lag, description | 201 / 422 순환 / 409 중복 | S08 F05 |
| PATCH /relations/{id} | lag, 설명 수정 (스케줄 전파) | wp.manage_relations | lag, description | relation | S08 S10 |
| DELETE /relations/{id} | 관계 삭제 (스케줄 전파) | wp.manage_relations | - | 204 | S08 |

#### 목록·쿼리·내보내기 — F03 (11)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| POST /work-packages/query | 쿼리 실행 (읽기 전용. spec이 복잡해서 POST). 구현: `spec.baseline`이면 행마다 `baseline`, 결과에 `baselineAt`(1728c3c)과 `baselineRemoved`(그때는 맞았지만 지금은 빠진 작업, 번호 낮은 순 최대 200건, 22ed5db) | 로그인 (결과는 wp.view 가능한 프로젝트로 제한) | spec, projectId, page | rows(행마다 groupKey·baseline), ancestors, groups, sums, total, baselineAt, baselineRemoved[{id, number, subject, project, then, now}] (§3.5) | S06 S13 S15 S17 S18 S21 F03 |
| GET /work-packages/filters | 쓸 수 있는 필터·연산자·값 (사용자 정의 필드 포함) | wp.view | projectId | filters[] | S06 S11 |
| GET /queries | 기본 보기 + 비공개 + 공개 + 즐겨찾기 | wp.view | projectId, view | queries[] | S06 S13 |
| POST /queries | 보기 저장 | query.save (공개: query.manage_public) | name, view, isPublic, spec | 201 query | F03 |
| GET /queries/{id} | 보기 불러오기 | 소유자, 또는 공개이면서 wp.view | - | query | S06 |
| PATCH /queries/{id} | 이름·spec 수정 | 소유자 (공개 보기는 query.manage_public) | 부분 | query | F03 |
| DELETE /queries/{id} | 삭제 | 위와 같음 | - | 204 | S06 |
| PUT /queries/{id}/favorite | 즐겨찾기 | 보기를 볼 수 있는 사용자 | - | 204 | S13 |
| DELETE /queries/{id}/favorite | 즐겨찾기 해제 | 본인 | - | 204 | S13 |
| POST /work-packages/export | 내보내기. 500행 이하는 바로 스트리밍, 그보다 많으면 작업 예약. 구현: 작업 예약 없이 동기, CSV·XLSX는 5,000건, PDF(6f7f18a, 입력 이름 `pdfType`: table·report·gantt)는 1,000건까지, 넘으면 422 | wp.export | spec, format(csv\|xlsx\|pdf), pdfKind, columns | 파일 / 202 jobId | S12 |
| GET /exports/{jobId} | 내보내기 상태와 다운로드 (24시간 보관) | 요청자 | - | status / 파일 | S12 |

#### 일정·Gantt·날짜 형식 — F04 (3, 나머지는 위의 query와 PATCH를 씀)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| POST /work-packages/{number}/schedule-preview | 날짜 변경 미리 보기 (저장 안 함): 계산된 날짜·기간, 영향받는 작업 | wp.edit | startDate, dueDate, duration, scheduleManually, ignoreNonWorkingDays, touched[] | preview | S10 S13 F04 |
| GET /calendar/non-working-days | 근무 요일과 기간 내 휴무일 (Gantt 음영, 날짜 선택기 비활성) | 로그인 | from, to | {workingDays, days[]} | S10 S13 S17 S18 |
| GET /settings/display | 날짜 형식·시간 형식·주 시작 요일 (화면이 로그인 직후 읽어 모든 날짜·시각, 캘린더·팀 플래너·날짜 선택 달력의 첫 열, Gantt 주 눈금에 씀). 84f6e6c | 로그인 | - | {dateFormat, timeFormat, weekStart} | 공통 S10 S13 S17 S18 S33 |

#### 보드 — F06 (12)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /projects/{project}/boards | 보드 목록 | board.view | - | boards[] | S14 |
| POST /projects/{project}/boards | 생성 (종류별 기본 컬럼: 상태 보드는 열린 상태들 등). 구현(388400b): 6종 모두. subproject는 볼 수 있는 바로 아래 하위 프로젝트마다 열, parent_child는 하위가 있는 작업 번호순 6개 | board.manage | name, kind | 201 board | S16 F06 |
| GET /boards/{id} | 보드 + 컬럼 + 컬럼별 카드 (쿼리 엔진 사용). `filters`를 주면 저장하지 않고 그 필터로 걸러 봄 | board.view + wp.view | filters? (JSON, 목록과 같은 형식) | board (+ 저장된 filters, canManage) / 422 형식 오류 | S15 |
| PATCH /boards/{id} | 이름, 공개, 보드 필터(모든 열에 적용, 모두에게) | board.manage | name, isPublic, filters | board / 422(모르는 필드·연산자) | S15 |
| DELETE /boards/{id} | 삭제 | board.manage | - | 204 | S14 |
| POST /boards/{id}/columns | 컬럼 추가 (최대 30) | board.manage | name 또는 valueId | 201 column (구현: 기본 보드는 name(1~60자), 나머지는 valueId. 200 board 전체. subproject는 바로 아래 하위 프로젝트만, parent_child는 같은 프로젝트의 작업만, 아니면 422) | S15 |
| PATCH /board-columns/{id} | 순서·이름 변경 | board.manage | position, name | column (구현: board 전체. 이름은 값 없는 열만) | S15 |
| DELETE /board-columns/{id} | 컬럼 삭제 (작업은 그대로) | board.manage | - | 204 (구현: 200 board 전체) | S15 |
| POST /boards/{id}/moves | 카드 이동: 보드 종류에 따라 작업 속성 변경(상태는 워크플로 검사) + 순서 저장 | wp.edit (basic 보드는 board.manage) | workPackageNumber, toColumnId, beforeNumber, lockVersion | card + WP / 409 / 422 (구현: 입력 이름은 `number`, 200 board 전체. 기본 보드는 작업을 바꾸지 않고 보드에 없는 카드면 404. subproject 보드는 작업 옮기기(`POST /work-packages/{n}/move`와 같은 규칙, 하위 작업 함께), parent_child 보드는 상위 변경) | S15 F06 |
| POST /boards/{id}/cards | 기본 보드: 그 프로젝트의 기존 작업을 열 맨 끝에 넣음(다른 열에 있으면 옮김). 작업은 바뀌지 않음. 88153b7 | board.manage | columnId, number | 200 board / 422(기본 보드 아님, 다른 프로젝트) | S15 |
| DELETE /boards/{id}/cards/{number} | 기본 보드: 카드를 보드에서 뺌. 작업은 그대로. 88153b7 | board.manage | - | 200 board | S15 |
| POST /boards/{id}/cards/new | 열에서 새 작업 만들기: 그 열의 상태·담당자·버전을 넣고 유형은 프로젝트 기본(typeId로 바꿀 수 있음). 기본 보드면 그 열에 넣음. 보통의 생성 규칙·권한을 그대로 거침. 88153b7. 388400b: subproject 열이면 그 하위 프로젝트에 만들고(쓰지 않는 유형이면 그 프로젝트 기본 유형), parent_child 열이면 그 작업의 하위로 | wp.create (basic 보드는 board.manage도) | columnId, subject, typeId? | 201 {board, workPackage} / 422 | S15 |

#### 알림 — F09 (5, 건수는 구현에서 더함)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /notifications | 받은 편지함 (읽지 않음·모두, 이유, 프로젝트, 같은 작업끼리 묶음). 구현: 배열(조건에 맞는 최근 500건). 이유·프로젝트 거르기는 57722a9에서 화면이 했고 0ef1888부터 서버가 500건 제한 전에 함 | 본인 | filter, reason, projectId, page. 구현: filter, reasons(쉼표로 여러 개), project(식별자 또는 id) | page<NotificationGroup>. 구현: NotificationGroup[] | S19 |
| GET /notifications/counts | 필터 선택지의 읽지 않은 수(전체 알림 기준, 500건 제한 없음). `wp.view`가 있는 프로젝트만. 구현에서 더함(0ef1888) | 본인 | reasons, project | {all, byReason, projects[{identifier, name, unread}], allProjects}: all·byReason은 고른 프로젝트 안에서, projects·allProjects는 고른 이유 안에서 | S19 |
| GET /notifications/unread-count | 헤더 배지 (60초 폴링, ETag) | 본인 | - | {count} / 304 | 공통 |
| POST /notifications/read | 읽음 처리 (ids, 작업 단위, 현재 필터 전체) | 본인 | ids[] \| workPackageNumber \| all + filter. 구현: ids \| workPackageNumber \| all \| {reasons?, project?}(필터에 맞는 읽지 않은 알림 전부, 0ef1888) | {updated}. 구현: 204 | S19 F09 |
| POST /notifications/unread | 읽지 않음으로 되돌리기 | 본인 | ids[] | {updated} | S19 |

#### 검색·홈 (3)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /search | 범주별 검색 (작업, 프로젝트, 코멘트). `#번호`는 정확히 일치 | 로그인 (보이는 것만) | q, scope, projectId, page | page<Hit> | S22 |
| GET /search/suggest | 헤더 자동완성 (작업 상위 5건 + 프로젝트 3건) | 로그인 | q | hits[] | 공통 |
| GET /home | 즐겨찾는 프로젝트 + 내게 할당된 작업 요약 | 로그인 | - | home | S02 S21 |

#### 관리 — F11 (47, 모두 관리자 전용. 사용자·그룹은 global.manage_users로도 가능. 전역 역할 배정은 관리자만)

| 리소스 | 경로 | 개수 | 비고 | 화면 |
| --- | --- | --- | --- | --- |
| 유형 | GET·POST /admin/types, PATCH·DELETE /admin/types/{id}, PUT /admin/types/{id}/projects | 5 | 쓰고 있는 유형은 삭제 시 409. PATCH로 attribute_groups(양식)까지 수정 | S27 S28 |
| 워크플로 | GET /admin/workflows?typeId&roleId, PUT /admin/workflows, POST /admin/workflows/copy | 3 | PUT은 (유형, 역할, applies_to) 단위로 전체를 교체 | S29 |
| 상태 | GET·POST /admin/statuses, PATCH·DELETE /admin/statuses/{id}, PUT /admin/statuses/order | 5 | 쓰고 있는 상태는 삭제 시 409, 기본 상태는 하나만 | S30 |
| 우선순위 | GET·POST /admin/priorities, PATCH·DELETE /admin/priorities/{id}, PUT /admin/priorities/order | 5 | 같은 규칙 | S36 |
| 역할 | GET·POST /admin/roles, PATCH·DELETE /admin/roles/{id}, GET /admin/permissions | 5 | 권한 키 목록과 설명 | S31 |
| 사용자 | GET·POST /admin/users, PATCH·DELETE /admin/users/{id}, POST /admin/users/{id}/resend-invitation, PUT /admin/users/{id}/global-roles | 6 | DELETE는 익명화 작업을 예약. PATCH로 잠금·해제, 관리자 지정. 384a2f9: 응답 `lockedUntil`(로그인 실패 잠금 중이고 상태가 active일 때만, 아니면 null, 화면은 "일시 잠김"), `status=locked` 필터가 일시 잠김도 찾음, `PATCH status=active`가 실패 횟수와 `locked_until`을 지움. 0af87f8: `PUT …/global-roles {roleIds}`가 그 사람의 전역 역할을 전체 교체(관리자만, global.manage_users만으로는 403, 전역 역할이 아니면 422 `roleIds`), 응답 `globalRoles` | S35 |
| 그룹 | GET·POST /admin/groups, PATCH·DELETE /admin/groups/{id}, PUT /admin/groups/{id}/members | 5 | | S35 |
| 사용자 정의 필드 | GET·POST /admin/custom-fields, PATCH·DELETE /admin/custom-fields/{id} | 4 | 옵션과 사용 유형은 PATCH 본문에 포함 | S34 |
| 설정 | GET·PUT /admin/settings/{section} | 2 | section: calendar, progress, attachments, notifications, auth, mail, display. calendar나 progress를 바꾸면 재계산 작업을 예약. display(84f6e6c)는 PUT만 있고 읽기는 로그인한 누구나 `GET /settings/display`. 저장하면 서버가 쓰는 날짜(`Dates.format`: 활동 기록, 사용자 정의 필드 값, 메일)도 바로 바뀜. 인스턴스가 여럿이면 다른 인스턴스는 날짜를 쓸 때 최대 30초마다 DB에서 다시 읽어 30초 안에 따라옴(`Dates.RELOAD_MS`, 384a2f9). 요약 메일 항목의 시각도 날짜 형식과 12시간제를 따름(`Dates.formatDateTime`), 필터 "이번 주"는 주 시작 요일을 따름(`Dates.startOfWeek`). 내보내기 파일의 시각만 기계가 읽기 좋은 `yyyy-MM-dd HH:mm` 고정 | S32 S33 |
| 휴무일 | GET /admin/non-working-days?year, POST, DELETE /admin/non-working-days/{id} | 3 | 바꾸면 reschedule-all 예약 | S33 |
| SSO 공급자 | GET·POST /admin/oidc-providers, PATCH·DELETE /admin/oidc-providers/{id} | 4 | V10. 관리자만(global.manage_users로는 안 됨). 발급자를 새로 넣거나 바꾸면 발견 문서를 읽어 확인(못 읽으면 422 issuer). 식별자는 만들 때만, 중복 422. 응답에 client_secret 없음(`secretSet`, `redirectUri`, `linkedUsers`만). PATCH에서 빈 시크릿은 기존 값 유지. DELETE는 연결만 지우고 계정은 남김 | 관리 로그인·비밀번호 |

구현에서 더한 관리 화면: 권한 보고서(`/admin/roles/report`, e92c999)는 위 역할 경로(`GET /admin/roles`, `PATCH /admin/roles/{id}`)만 씁니다.

#### 연동 — API 토큰·웹훅 (16, 구현 기록: f835e1c·86e58a9)

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /me/api-tokens | 내 토큰 목록(폐기하지 않은 것) | 본인(세션만) | - | tokens[] | S37 |
| POST /me/api-tokens | 토큰 만들기. 값은 이 응답에만. 사람마다 20개, 관리자가 끄면 403 | 본인(세션만) | name, scope(read\|write), expiresInDays(30\|90\|365\|null) | 201 {token, secret} | S37 |
| DELETE /me/api-tokens/{id} | 내 토큰 폐기 | 본인(세션만) | - | 204 | S37 |
| GET /admin/api-tokens | 모든 사람의 토큰(만든 사람, 최근 사용) | 관리자(세션만) | - | tokens[] | 관리 · API와 웹훅 |
| DELETE /admin/api-tokens/{id} | 누구 토큰이든 폐기 | 관리자(세션만) | - | 204 | 관리 · API와 웹훅 |
| GET /admin/settings/api | API 토큰 사용 여부 | 관리자(세션만) | - | {tokensEnabled} | 관리 · API와 웹훅 |
| PUT /admin/settings/api | 켜기·끄기(`api.tokens_enabled`). 끄면 있는 토큰도 바로 401 | 관리자(세션만) | tokensEnabled | {tokensEnabled} | 관리 · API와 웹훅 |
| GET /openapi.json | 주요 경로의 OpenAPI 3.1 문서(`resources/api/openapi.json`) | 익명 | - | JSON | 도움말 · API 문서 |
| GET /admin/webhooks | 웹훅 목록(서명 키는 끝 4자리만) | 관리자(세션만) | - | webhooks[] | 관리 · API와 웹훅 |
| POST /admin/webhooks | 웹훅 만들기. 서명 키 `whsec_…`는 이 응답에만 | 관리자(세션만) | name, url(http\|https, 169.254.x·[fe80:…]·metadata.google.internal은 422(dfde416, 06b48f5부터 이름이 그쪽으로 풀려도), `OPENPMS_WEBHOOK_BLOCK_PRIVATE=true`면 루프백·사설 주소도 422(06b48f5)), events[], projectIds[](비면 전체), enabled | 201 webhook + secret | 관리 · API와 웹훅 |
| PATCH /admin/webhooks/{id} | 수정(보낸 필드만) | 관리자(세션만) | 부분 | webhook | 관리 · API와 웹훅 |
| DELETE /admin/webhooks/{id} | 삭제(보내지 못한 이벤트·전송 기록도) | 관리자(세션만) | - | 204 | 관리 · API와 웹훅 |
| POST /admin/webhooks/{id}/secret | 서명 키 새로 만들기(옛 키는 바로 무효) | 관리자(세션만) | - | webhook + secret | 관리 · API와 웹훅 |
| GET /admin/webhooks/{id}/deliveries | 최근 전송 30건 | 관리자(세션만) | - | deliveries[] | 관리 · API와 웹훅 |
| POST /admin/webhooks/{id}/test | ping을 바로 한 번 보냄(실패하면 다시 보내지 않음) | 관리자(세션만) | - | delivery | 관리 · API와 웹훅 |
| POST /admin/webhook-deliveries/{id}/redeliver | 전송 하나를 바로 다시 보냄 | 관리자(세션만) | - | delivery | 관리 · API와 웹훅 |

"세션만"은 API 토큰으로 부를 수 없다는 뜻입니다(403 "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다."). 앞부분 목록으로 막으며, dfde416부터 연동 설정(`/admin/settings/api`, `/admin/webhooks*`, `/admin/webhook-deliveries*`)도 이 목록에 있어 관리자의 읽기·쓰기 토큰으로도 부를 수 없습니다(`ApiTokensIT`).

#### 시간 기록 — X10 · X11 · S08 (12, 구현 기록: 789bb9d·3f9d5c0)

처음 설계에는 없던 영역입니다(시간 추적은 범위 밖이었음). `replica/fixes.md` #1 주간 공수 입력표를 만들면서 더했습니다. 모든 경로는 프로젝트 모듈 `time_tracking`이 켜진 곳의 기록만 다룹니다(꺼지면 `time.*`가 없음).

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /time-activities | 쓸 수 있는(켜진) 활동, 순서대로 | 로그인 | - | activities[] | S08 대화상자 |
| GET /admin/time-activities | 모든 활동(꺼진 것 포함) | 관리자 | - | activities[] | 관리 · 달력과 날짜 |
| POST /admin/time-activities | 활동 추가(맨 뒤) | 관리자 | name(1–60자, 유일), isDefault? | 201 activity / 422 | 관리 · 달력과 날짜 |
| PATCH /admin/time-activities/{id} | 이름·순서·켜기·기본. 기본은 하나(새 기본을 정하면 앞의 것이 풀림), 기본 활동은 끌 수 없고 꺼진 활동은 기본이 될 수 없음 | 관리자 | name, position, active, isDefault | activity / 404 / 422 | 관리 · 달력과 날짜 |
| GET /time-entries | 기록 목록. `workPackage`면 그 작업의 전부, 아니면 기간(기본 이번 달, 최대 366일). 볼 수 있는 것만(내 것 time.log, 남의 것 time.view), 최신순 최대 2,000행 | 로그인 | workPackage(번호) 또는 from·to, project, user(me\|id) | entries[] (canEdit 포함) | S08 시간 탭 |
| POST /time-entries | 기록. 프로젝트는 작업의 프로젝트, 활동이 없으면 기본 활동. 한 사람의 하루 24시간까지 | 내 것: 작업 프로젝트의 time.log / 남의 몫(userId): time.manage | workPackageNumber, spentOn, hours(0 초과 24 이하, 소수 둘째 자리), activityId?, comment?(1,000자), userId? | 201 entry / 403 / 422 | S08 대화상자 |
| PATCH /time-entries/{id} | 고치기(보낸 필드만) | 내 것: time.log / 남의 것: time.manage | spentOn, hours, activityId, comment | entry / 403 / 404 / 422 | S08 대화상자 |
| DELETE /time-entries/{id} | 지우기 | 위와 같음 | - | 204 / 403 / 404 | S08 시간 탭 |
| GET /time-entries/week | 내 한 주. start가 속한 주(주 시작은 관리 · 날짜 형식) | 로그인(내 것만) | start? | {start, days[{date, working, holiday}], hoursPerDay, rows[{workPackage, canLog, cells[{date, hours, entries}]}]} | X10 |
| PUT /time-entries/week | 내 한 주 저장. 칸마다 그날 내 시간을 그 값으로: 없으면 기본 활동으로 새로, 하나면 고침(0이면 지움), 여러 건이면 거절. 전부 되거나 전부 안 됨 | 칸의 작업 프로젝트마다 time.log | start, cells[{workPackageNumber, date, hours}] | 저장 뒤의 week / 422(errors[].field = "#번호 날짜" 또는 날짜) | X10 |
| GET /time-entries/report | 합계. 볼 수 있는 기록만(time.view인 프로젝트는 모든 사람, time.log만이면 내 것). DB에서 GROUP BY로 합쳐 건수 한도 없음(6d816e4) | 로그인 | from, to, project, user, groupBy(user\|workPackage\|activity\|project\|day) | {from, to, groupBy, rows[{key, label, hours}], total} / 422 | X11 |
| GET /time-entries/export | 같은 조건의 기록을 xlsx로(날짜, 사람, 프로젝트, 번호, 작업, 활동, 시간, 메모, 합계 행). 50,000행까지(넘는 행은 말없이 빠짐) | 로그인 | from, to, project, user | xlsx "공수 {from}~{to}.xlsx" | X11 |

- 인스턴스의 하루 근무 시간(`calendar.hours_per_day`)을 넘는 것은 막지 않고 화면(X10)이 경고만 합니다. 막는 것은 한 사람의 하루 24시간입니다(422 "하루에 24시간을 넘게 기록할 수 없습니다.").
- 시간 기록은 저널에 남지 않고, 프로젝트 복사·템플릿이 가져가지 않으며, 웹훅 이벤트가 없습니다. 작업을 옮기면 기록의 프로젝트가 따라갑니다(`TimeController.follow`).
- 작업·프로젝트를 지우면 기록도 FK cascade로 지워집니다. 작업 삭제 확인은 `GET /time-entries?workPackage=`로 센 시간(내가 볼 수 있는 것)이 있으면 경고 배너를 보이고(39c8974), 프로젝트 삭제 확인은 시간 기록을 말하지 않습니다.
- X11은 기간이 거꾸로이거나 1년 이상이면 끝 칸 아래에 안내하고 보고서를 부르지 않습니다(6f46d4c·39c8974).
- API 토큰: 읽기 토큰은 GET(목록·주간·보고서·Excel), 기록·주간 저장은 읽기·쓰기 토큰. `openapi.json`에는 `/time-entries`·`/time-entries/week`·`/time-entries/report`가 있습니다.

#### 시스템 정보·백업 — X12 (2, 구현 기록: 5966874)

처음 설계에는 없던 영역입니다. `replica/fixes.md` #3 업그레이드 안전장치(업그레이드 불만 19건/5곳)를 만들면서 더했습니다. 덤프는 `system/Backups`(`pg_dump --format=custom --no-owner`, 접속 정보는 데이터소스 설정, 비밀번호는 `PGPASSWORD`, `.part`에 쓰고 원자적 이름 바꾸기, 종류마다 최근 `OPENPMS_BACKUP_KEEP`개, 한 번에 하나, 30분 제한). 업그레이드 전 덤프는 경로가 아니라 `system/MigrateWithBackup`(Flyway 마이그레이션 전략)이 시작할 때 받습니다: 스키마가 있고 적용할 마이그레이션이 있으면 `before-upgrade` 덤프(라벨 v{현재}-to-v{목표}) 다음에 migrate, 덤프가 실패하면 마이그레이션 없이 시작 중단, 새 설치·대기 0은 덤프 없음.

| method path | 하는 일 | 호출 가능 | 입력 | 출력 | 흐름 |
| --- | --- | --- | --- | --- | --- |
| GET /admin/system | 앱(버전·빌드 시각은 Gradle `buildInfo()`의 `BuildProperties`, 시작 시각, Java, 예약 작업 켜짐), DB(PostgreSQL `server_version`, `pg_database_size`), 마이그레이션(현재, 적용 수, 대기 수, 최근 8개와 시각·실패 표시), 백업(업그레이드 전 자동 켜짐, 폴더, 보관 수, `pg_dump --version` 줄 또는 null, 진행 중, 파일 목록 종류·크기·시각), 데이터 수(활성 사용자, 프로젝트, 작업, 첨부 바이트) | 관리자 | - | {app, database, migrations, backups, counts} | X12 F11 |
| POST /admin/system/backup | 지금 백업(`manual` 덤프 하나) | 관리자 | - | 201 {name, kind, bytes, createdAt}. 진행 중이면 409, pg_dump가 없거나 실패하면 503("백업하지 못했습니다.", detail에 원인) | X12 |

검증: `BackupsTest` 4개, `SystemIT` 1개(관리자 200·dev는 백업 꺼짐, pg_dump 없는 개발 환경의 지금 백업 503, 멤버 403), e2e F11-H4. 데모(가짜 계층)는 줄인 정보만 주고 백업은 503으로 거절.

운영용 `GET /actuator/health`는 API 개수에 넣지 않았습니다.

### 4.3 프론트엔드 라우트 (SPA)

| 경로 | 화면 |
| --- | --- |
| /login (?next=, ?sso_error=), /password-reset, /invitations/:token | S01 |
| / | S02 |
| /projects, /projects/new | S03, S04 |
| /projects/:p | S05 |
| /projects/:p/work-packages (?q=, ?view=) | S06 |
| /projects/:p/work-packages/details/:number/:tab | S07 (분할) |
| /projects/:p/work-packages/:number/:tab | S08 (전체) |
| /projects/:p/work-packages/new?type= | S09 |
| /projects/:p/gantt | S13 |
| /projects/:p/boards, /projects/:p/boards/new, /projects/:p/boards/:id | S14, S16, S15 |
| /projects/:p/calendar, /projects/:p/team-planner | S17, S18 |
| /projects/:p/members, /projects/:p/settings/:section | S23, S24–S26 |
| /work-packages, /gantt | 전역 목록, 전역 Gantt |
| /notifications (+ /notifications/details/:number) | S19 |
| /my/page, /my/account, /my/notifications | S21, S37, S20 |
| /my/timesheet (?week=) | X10 내 공수(구현에서 더함, 3f9d5c0) |
| /projects/:p/time | X11 공수 보고서(구현에서 더함, 3f9d5c0. 모듈 `time_tracking`과 time.view). 작업 상세의 시간 탭은 S07·S08의 `:tab` = `time` |
| /search | S22 |
| /admin/:section(/:id/:tab) | S27–S36. 구현에서 더함: /admin/roles/report(권한 보고서), /admin/integrations(API와 웹훅, 관리 첫 화면의 새 묶음 '연동'), /admin/system(시스템 정보 X12, 새 묶음 '시스템', 5966874) |
| /help/api | API 문서(도움말 메뉴, f835e1c) |
| /design | 컴포넌트 견본(도움말 메뉴). 384a2f9부터 로그인 필요, 별도 청크로 지연 로딩 |

S10–S12는 모달이라 라우트가 없습니다.

### 4.4 웹훅·작업

- **들어오는 웹훅**: 없음 (결제·외부 캘린더 연동이 없음).
- **나가는 웹훅**: 없음. 작업 변경 웹훅은 could 단계에서 추가합니다. 구현(86e58a9): 작업 생성·변경·삭제·코멘트(내부 코멘트 제외)를 관리자가 등록한 주소로 서명한 JSON으로 보냄. 업무 트랜잭션 안에서 `webhook_deliveries`에 쌓고 아래 `webhook-delivery` 작업이 보냄(아웃박스). 작업 옮기기(다른 프로젝트)는 dfde416부터 `work_package.updated`로 보냄. 06b48f5부터 자동 일정 전파로 밀린 작업과 첨부 추가·삭제도 `work_package.updated`, 프로젝트 복사·템플릿으로 생긴 작업은 `work_package.created`. `changes`는 그 요청이 바꾼 것만. 사설·루프백 받는 주소는 운영 스위치(`OPENPMS_WEBHOOK_BLOCK_PRIVATE`, 기본 꺼짐)로 막음(등록·전송 때 검사). 시간 기록(789bb9d)은 이벤트를 보내지 않음.
- **들어오는 연동**: 구현(f835e1c): 사내 시스템이 개인 API 토큰으로 `/api/v1`을 부름(§4.1).
- **나가는 메일**: 사내 SMTP.

| 작업 (db-scheduler) | 일정 | 하는 일 |
| --- | --- | --- |
| notification-fanout | 일회성, 저널 트랜잭션 안에서 즉시 예약 | §3.6 규칙으로 notifications 행 생성 |
| mail-mention | 일회성, +1분 | 즉시 알림이 켜진 사용자에게 멘션 메일 발송, emailed_at 기록 |
| mail-digest | 15분마다 | 요약 시각(사용자 시간대)이 지난 사용자에게 아직 메일로 보내지 않은 알림 요약 발송. 하루 1회 보장 |
| date-alerts | 매시간 | 사용자 시간대의 오늘 기준으로 시작·완료 N일 전 알림과 기한 지남 알림 생성 (유니크 인덱스로 멱등) |
| reschedule-all | 일회성, 근무일·휴무일 변경 시 | §3.3 S11 |
| recalc-progress | 일회성, 진행률 설정 변경 시 | 모든 작업의 P1–P5 재계산 |
| export | 일회성 | 대용량 내보내기 파일 생성 후 알림 |
| project-copy | 일회성 | 템플릿 기반 대형 프로젝트 복사 (구현하지 않음: 템플릿 복사는 생성 요청 안에서 동기, a3aee0b) |
| user-anonymize | 일회성 | 사용자 익명화, 멤버십·주시·알림 정리 |
| attachment-orphans | 매일 03:00 KST | 24시간 넘게 연결되지 않은 임시 첨부와 파일 삭제 |
| file-sweep | 매주 일요일 04:00 KST | DB에 없는 저장소 파일 삭제 (커밋 후 삭제가 실패한 경우를 정리) |
| token-cleanup | 매일 | 만료된 user_tokens 삭제 (세션은 Spring Session이 정리). 구현: 03:30 KST, 폐기·만료 30일 지난 api_tokens와 끝난 지 30일 지난 webhook_deliveries도 삭제(dfde416), 하루 지난 idempotency_keys도 삭제(93d1a74) |
| exports-cleanup | 매시간 | 24시간 지난 내보내기 파일 삭제 |
| webhook-delivery | 15초 간격(구현에서 더함, 86e58a9) | 보낼 차례인 웹훅 전송을 50건까지. 2xx 아니면 1·5·30·120분 뒤 다시, 그다음 실패. 리다이렉트 안 따라감, 연결 5초·응답 10초 |

백업은 앱 작업이 아니라 호스트 cron이 맡습니다(§1 배포 구성). 구현(5966874): 예약 작업은 여전히 없지만, 앱이 업그레이드 직전(시작할 때)과 관리 · 시스템 정보의 지금 백업에서 덤프를 받습니다(§4.2 "시스템 정보·백업"). 오래된 덤프 정리는 작업이 아니라 덤프 직후에 합니다.

---

## 5. 까다로운 부분

- **시간대**: JVM과 DB는 UTC로 둡니다. API는 ISO-8601(Z)로 주고받고 화면은 사용자 시간대(기본 Asia/Seoul)로 표시합니다. 일정 날짜는 `date`라 시간대 변환이 없습니다. 다만 "오늘", "이번 주", 날짜 알림, 기한 지남은 **사용자 시간대 기준**으로 계산합니다. 한국은 일광절약시간이 없지만 해외 사용자 시간대를 고려해 날짜 계산은 `java.time`의 `ZoneId`로만 합니다.
- **근무일·한국 공휴일**: 설날, 추석, 대체공휴일은 해마다 날짜가 바뀝니다. 휴무일을 관리자가 연초에 입력해야 하고, 빠뜨리면 일정 계산이 틀립니다. could 단계에서 공공데이터포털의 특일 정보 API(사내 키 사용)로 "올해 공휴일 가져오기"를 추가합니다. 근무일을 바꾸면 전체 재계산이 일어나므로 화면에서 경고를 보여줍니다.
- **멱등성**: 들어오는 웹훅은 없습니다. 나가는 웹훅(구현)은 "적어도 한 번"이라 받는 쪽이 `X-OpenPMS-Delivery`로 중복을 거릅니다.
  - 수정: `lockVersion`이 중복 저장을 막습니다.
  - 생성: 더블 클릭에 대비해 `Idempotency-Key` 헤더를 받습니다(10분 보관). 구현(93d1a74): 더블 클릭은 화면이 막고(BUG-001), 서버는 네트워크 재시도를 막습니다. 보관은 24시간(V16 `idempotency_keys`, §2 설계 결정), 2xx 응답만 보관.
  - 날짜 알림: 유니크 인덱스가 중복을 막습니다.
  - 메일: 적어도 한 번 발송합니다. 발송 후 `emailed_at`을 기록하기 전에 장애가 나면 중복 발송될 수 있고, 이는 감수합니다.
- **경쟁 조건**:
  - 같은 작업을 동시에 편집하면 409를 돌려주고, 화면에 "다른 사람이 먼저 수정했습니다"와 함께 최신 값을 보여줍니다.
  - 스케줄 전파는 advisory lock으로 직렬화합니다.
  - 워크플로 검사는 `SELECT … FOR UPDATE`로 행을 잠근 같은 트랜잭션 안에서 합니다.
  - 보드 순서는 실수 position을 씁니다. 간격이 1e-9보다 작아지면 그 컬럼만 다시 번호를 매깁니다.
  - 기본 상태·우선순위가 둘이 되는 것은 부분 유니크 인덱스로 막습니다.
- **요청 제한**: 로그인은 IP·이메일당 분당 5회이고, 5회 실패하면 15분 잠깁니다. 비밀번호 재설정 요청은 이메일당 시간당 3회입니다. 그 밖의 사내 API는 제한하지 않습니다.
  - 구현: 로그인은 클라이언트 주소당 분당 20회(`openpms.logins-per-minute`), 재설정 메일은 주소당 분당 5회, 비밀번호 변경은 사용자당 분당 10회, 비밀번호 만들기 링크는 사용자당 분당 3회. 실패 잠금은 설정(`auth.lockout_attempts`·`auth.lockout_minutes`, 기본 5회·15분)이고 관리 · 사용자에 "일시 잠김"으로 보입니다.
  - 프록시 뒤(384a2f9): 앱이 보는 주소는 프록시라서, 믿는 프록시(`OPENPMS_TRUSTED_PROXIES`)가 보낸 `X-Forwarded-For`에서 클라이언트 주소를 꺼냅니다. 전에는 프록시 뒤의 모든 사람이 버킷 하나를 같이 썼습니다. 버킷은 인스턴스 메모리에 있어 인스턴스를 늘리면 인스턴스마다 따로 셉니다.
- **파일 크기**: 기본 25MB입니다(원본 인스턴스는 5MB). Spring multipart 한도, 리버스 프록시의 `client_max_body_size`, `attachments.max_bytes`를 함께 맞춥니다.
  - 저장소 키는 uuid로 만들고 원본 파일명을 경로에 쓰지 않습니다.
  - SVG와 HTML은 inline으로 보여주지 않고 다운로드로만 제공합니다(XSS 방지).
- **검색(한국어)**: 기본 PostgreSQL 전문 검색에는 한국어 형태소 분석이 없습니다. 그래서 `pg_trgm` + `ILIKE`로 부분 일치 검색을 합니다.
  - 한계: 1~2글자 검색어(예: "버그")는 트라이그램 인덱스를 쓰지 못해 순차 탐색이 됩니다. 작업 10만 건 정도까지는 감수할 수 있습니다.
  - 그 이상이 되면 pg_bigm이나 OpenSearch(nori 분석기)를 검토합니다. `#번호`는 정확히 일치로 바로 찾습니다.
- **리치 텍스트 XSS**: 저장할 때 서버가 허용 목록으로 sanitize합니다. 화면은 sanitize된 HTML만 렌더링합니다. 멘션은 `data-user-id` 속성에서 파싱하고, 본문에 적힌 이름은 믿지 않습니다.
- **실시간**: 없습니다. 알림 배지는 60초마다 폴링하고(ETag로 변경 없으면 304), 동시 편집은 lockVersion으로 처리합니다. 사용자가 늘면 SSE를 검토합니다.
- **오프라인**: 해당 없음.
- **메일 전달성**: 사내 SMTP 릴레이를 씁니다. SPF·DKIM은 사내 메일 담당에게 확인합니다. From은 `no-reply@<사내 도메인>`으로 둡니다. 개발 환경에서는 Mailpit으로 실제 발송을 막습니다.
- **멀티테넌시**: 회사 하나 = 인스턴스 하나라서 `org_id`가 없습니다. 나중에 계열사별로 분리해야 하면 인스턴스를 따로 띄우는 것이 스키마를 바꾸는 것보다 쌉니다.
- **개인정보 삭제**: 사용자는 익명화합니다(§2). 첨부와 저널 본문에 남은 개인정보는 자동으로 지우지 않습니다. 퇴사자 처리 절차를 사내 정책과 맞춰야 합니다.
- **PDF 한글**: OpenPDF에 Noto Sans KR(SIL OFL)을 임베드하지 않으면 한글이 빈칸으로 나옵니다. PDF가 커지지 않도록 폰트 서브셋을 사용합니다. 구현(6f7f18a): PDFBox + Pretendard(SIL OFL 1.1) 서브셋. 글꼴에 없는 문자는 '?'로 바꿔 파일이 늘 만들어지게 하고, 한 번에 1,000건까지만.
- **권한 누수**: 목록 쿼리에서 `VisibleProjects` 조건이 하나라도 빠지면 다른 프로젝트 작업이 보입니다. 모든 목록 API에 대해 "비멤버는 0건" 통합 테스트를 둡니다. jOOQ 쿼리 헬퍼를 거치지 않는 `select from work_packages`는 코드 리뷰 체크리스트에 넣습니다.
- **계층 쿼리 성능**: 재귀 CTE는 깊이 10, 작업 10만 건까지 충분할 것으로 봅니다(추정). 느려지면 `work_package_hierarchies` 클로저 테이블을 추가하고, 부모를 바꿀 때 같은 트랜잭션에서 갱신합니다.
- **백업·업그레이드**: Flyway는 시작할 때 마이그레이션을 적용합니다. 업그레이드 전에 pg_dump를 받고, 파괴적인 마이그레이션(컬럼 삭제)은 두 릴리스에 걸쳐 나눕니다. 구현(5966874): "업그레이드 전에 pg_dump"를 운영자 절차가 아니라 앱이 합니다. `MigrateWithBackup`이 적용할 마이그레이션이 있는 기존 DB를 먼저 덤프하고(실패하면 마이그레이션 없이 시작 중단, 끄려면 `OPENPMS_BACKUP_BEFORE_UPGRADE=false`), 덤프는 `backups` 볼륨에 종류마다 5개. 이미지 스모크: V17을 되돌린 DB로 시작 → 덤프(170,430바이트) 뒤 V17 적용, `pg_restore --list`로 읽힘, 쓸 수 없는 폴더면 시작 실패·스키마 v16 유지. 남는 위험: 덤프가 같은 서버에 있고 첨부는 없으며, 복원은 손으로 합니다(`replica/deploy.md`).

---

## 6. 빌드 순서

추정 기간은 개발자 2~3명 기준이며 약속이 아닙니다.

### M0. 기반 (약 1주)

- 저장소, Gradle, Vite, Flyway V1–V3, `compose.yaml`, `Dockerfile`(둘 다 저장소 최상위)
- CI: 테스트(Testcontainers) → 이미지 빌드
- 첫 관리자 부트스트랩
- 완료 기준: 개발 서버에서 `compose up` 한 번으로 빈 화면과 로그인 화면이 뜹니다.

### M1. 수직 슬라이스 (약 2주)

핵심 루프를 처음부터 끝까지, 화면 품질은 신경 쓰지 않고 연결합니다.

- 화면: S01 로그인, S03 프로젝트 목록(간단), S06 작업 목록(기본 컬럼, 상태·담당자 필터 2종), S07 분할 상세, S09 생성 폼
- 흐름: F01 작업 만들기, F02 상태 진행
- 테이블: users, user_preferences, user_tokens, roles, projects, members, member_roles, types, project_types, statuses, workflows, priorities, work_packages, journals, settings (+ spring_session)
- 경로:
  - `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`
  - `POST /auth/invitations/{token}/accept` (첫 관리자)
  - `GET /projects`, `GET /projects/{project}`
  - `GET …/work-packages/form`, `POST …/work-packages`
  - `GET /work-packages/{number}`, `PATCH /work-packages/{number}`, `GET …/allowed-statuses`, `GET …/activities`
  - `POST /work-packages/query` (필터 2종만)
- 완료 기준:
  - 로그인 → 작업 생성 → 목록에 나타남 → 상태 변경(참관자는 거부됨) → 활동에 기록됨
  - 두 탭에서 동시에 수정하면 한쪽이 409를 받음
  - 사내 서버에 배포됨

### M2. must 항목 (영역별, 약 8~10주)

| # | 영역 | 화면 | 테이블 | 경로 |
| --- | --- | --- | --- | --- |
| M2-1 | 작업 상세 완성 | S07 S08 | relations, watchers | DELETE /work-packages/{number}, GET /work-packages/autocomplete, comments·watchers 5개, relations 4개 |
| M2-2 | 쿼리 엔진·저장된 보기 | S06 S11 | queries, query_favorites | POST /work-packages/query (전체 필터·연산자·계층·정렬·페이지), GET /work-packages/filters, /queries 7개 |
| M2-3 | 일정 엔진 | S10 S33 | non_working_days, settings | POST …/schedule-preview, GET /calendar/non-working-days, /admin/settings/calendar, /admin/non-working-days 3개, 작업 reschedule-all |
| M2-4 | Gantt | S13 | (기존) | query + PATCH + schedule-preview 재사용, 타임라인 컴포넌트 |
| M2-5 | 상태 보드 | S14 S15 S16 | boards, board_columns, board_cards | /boards 9개 (상태 보드 먼저) |
| M2-6 | 알림 | S19 (헤더 배지) | notifications, notification_settings(기본값) | /notifications 4개, 작업 notification-fanout |
| M2-7 | 프로젝트·멤버·관리 | S03 S04 S05 S23 S25 S27 S29 S30 S31 S35 S36 | project_favorites, user_groups, user_group_members, global_role_assignments | /projects·/members 핵심, /admin types·workflows·statuses·priorities·roles·users |
| M2-8 | 마감 | 전체 | - | 한국어 문자열, 빈 상태·오류 상태, 409 충돌 UI, 페이지네이션, 사용자 잠금 |

순서는 M2-2(쿼리 엔진)와 M2-3(일정 엔진)을 먼저 합니다. 다른 영역이 모두 이 둘에 기대고, 가장 위험한 부분이라 일찍 드러나야 합니다. M2-3은 `scheduling` 패키지를 jqwik 속성 테스트와 함께 먼저 만들고 API는 그다음에 붙입니다.

### M3. should 항목 (약 6~8주)

- 인증: 사내 SSO(OIDC, 완료: a677079), 이메일 초대(`/projects/{project}/invitations`, `/auth/invitations/*`), 계정·알림 설정(`/me/*`)
- 프로젝트: 템플릿 생성(완료: a3aee0b), 하위 프로젝트(상위 지정 완료: 5d641d1), 모듈 켜기·끄기, 상태, 보관, 버전·카테고리(경로 8개), 그룹(`/admin/groups` 5개)
- 작업 패키지:
  - 사용자 정의 필드(`/admin/custom-fields` 4개, `PUT …/custom-fields`)
  - 첨부(`/attachments` 3개 + 정리 작업)
  - 일괄 편집·복사·이동(경로 3개, 일괄 복사와 우클릭 메뉴 완료: d0023b1)
  - 기타 관계 유형
- 목록·보기: 그룹화와 그룹 합계, 하위 프로젝트 포함, URL로 보기 공유, CSV·XLS 내보내기(`/work-packages/export`, `/exports/{id}`)
- 일정·진행률: 진행률 계산 모드와 recalc-progress 작업(S32)
- 계획 뷰: 담당자·버전·하위 프로젝트·부모-자식·기본 보드(모두 완료, 마지막 두 종류 388400b), 캘린더(S17), 팀 플래너(S18, 원본에서는 Enterprise 전용 기능)
- 알림·검색: 날짜 알림, 즉시·요약 메일(작업 4개), 검색(`/search`, `/search/suggest`)
- 모바일 반응형(M1·M2 화면)

### M4. could 항목

- 목록: 기준선 비교(완료: 1728c3c, 빠진 작업 22ed5db), 강조 표시(완료), PDF 내보내기(한글 폰트, 완료), 합계 행(완료)
- 사용성: 키보드 단축키(완료), 다크 모드·고대비(완료)
- 화면: 홈과 내 페이지 위젯(`/home` 경로 없이 완료), 프로젝트 개요 위젯(완료), 활동 필터(완료)
- 협업: 내부 코멘트(완료), 작업 공유(완료), 비참여 이벤트 구독, 프로젝트별 알림 재정의(완료)
- 관리: 계층 합계 단순 평균(완료), 양식 구성 드래그, 권한 보고서(완료)
- 연동: REST API 토큰(완료), 나가는 웹훅(완료), 한국 공휴일 가져오기
- 프로젝트: 프로젝트 복사(완료, cdce335)

### M5. replica-entrepreneur 개선 사항

조사는 끝났습니다(2026-10-11, 2c84a0d, `replica/fixes.md`: 실제 사용자 리뷰 137건·7곳, 고칠 계획 5개). 만든 것과 남은 것:

- 1. 주간 공수 입력표(완료: 789bb9d·3f9d5c0): 원본의 시간 기록을 함께 들여옴(V17 `time_activities`·`time_entries`, 모듈 `time_tracking`, 권한 `time.*`, 경로 12개 — §2 설계 결정, §4.2 "시간 기록"). 화면 X10 내 공수, S08 시간 탭, X11 공수 보고서, 관리 · 달력과 날짜의 시간 기록 활동. 비용·단가·예산은 계속 범위 밖
- 2. 템플릿 복사 때 새 시작일로 전체 일정 이동(완료: 2f7ffb9·b63eaa0): `POST /projects`(templateId)·`POST /projects/{id}/copy`의 선택 `startDate`
- 3. 업그레이드 안전장치(완료: 5966874): 업그레이드 전 자동 pg_dump(실패하면 시작 중단), 관리 · 시스템 정보 X12와 지금 백업, 경로 2개(§4.2 "시스템 정보·백업"), 이미지에 PostgreSQL 17 클라이언트, 볼륨 `backups`
- 4. 성능 예산, 5. 화면별 도움말 연결: 남음

---

## 7. 요약

- **스택**: Spring Boot 4 + Java 21 · jOOQ + Flyway · PostgreSQL 17 · React 19 + Vite + TanStack · db-scheduler · 사내 SMTP · Docker Compose 셀프 호스팅
- **규모**: 테이블 36개(+ 라이브러리 3개. 구현은 V17까지 자체 45개), API 경로 179개(설계 134 + 사내 SSO 7 + 보드 카드 3 + 날짜 형식 1 + 비밀번호 만들기 1 + 전역 역할 배정 1 + 프로젝트 복사 1 + API 토큰·OpenAPI 8 + 웹훅 8 + 알림 건수 1 + 시간 기록 12 + 시스템 정보 2 = 179. 실제 컨트롤러 매핑은 188개), 작업 13종(구현은 6종 + 요청 안 동기 처리)
- **가장 위험한 세 가지**:
  1. 스케줄링 엔진: 근무일, lag, 자동 모드, 부모 파생, 전파, 순환 검출
  2. 쿼리 엔진: 필터 30여 종과 계층·그룹 합계를 권한 조건 아래에서 빠르게 처리
  3. Gantt·팀 플래너 타임라인 UI 자체 구현: 드래그, 의존선, 가상 스크롤
- **다음 단계**: `/replica-design`
