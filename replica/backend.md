# 백엔드 (openPMS)

- 기준: `replica/architecture.md`, 화면 계약은 `frontend/src/api/api.ts`·`types.ts`, 동작 규칙은 가짜 계층(`frontend/src/api/fake`)
- 작성: 2026-10-09
- 스택: Spring Boot 4.1.1 · Java 21(가상 스레드) · jOOQ 3.21 · Flyway 12 · PostgreSQL 17 · Spring Session JDBC · Spring Security 7 · Gradle 9.7
- 개발 환경: PC에 아무것도 설치하지 않고 Podman 컨테이너로만 빌드·실행합니다(`backend/scripts/dev.sh`).

## 실행 방법

```bash
cd backend
scripts/dev.sh up            # Postgres 17(127.0.0.1:55432), Mailpit(메일 확인 http://127.0.0.1:8025)
scripts/dev.sh run           # 빌드 후 앱 실행 → http://127.0.0.1:8090 (dev 프로필: 개발 데이터 포함)
scripts/dev.sh test          # JUnit (시드 복원 지점의 복사본 DB에서)
scripts/dev.sh snapshot-db   # 현재 DB를 복원 지점으로 저장 (앱을 멈춘 상태에서)
scripts/dev.sh restore-db    # 복원 지점으로 1초 만에 되돌리기
cd ../frontend
npm run dev:api              # 화면을 실제 API에 붙여 실행 → http://localhost:5174
npm run build                # 운영 빌드(실제 API)를 backend 정적 리소스로 출력 → jar 하나로 배포
```

- 개발 DB 비밀번호는 처음 실행할 때 `backend/.env.local`에 무작위로 만들어집니다(git 제외).
- 개발 데이터의 계정 비밀번호는 모두 `openpms!`입니다. **dev 프로필에서만** 들어가며 운영 DB에는 없습니다.
- 운영 첫 실행: 관리자가 없고 `OPENPMS_ADMIN_EMAIL`이 있으면, 그 주소로 초대된 관리자를 만들고 24시간짜리 설정 링크를 로그에 한 번 출력합니다. 기본 비밀번호는 없습니다.
- 변수 목록은 `backend/.env.example`에 있습니다(값 없음).

## 구현 범위

화면이 쓰는 경로 **약 95개 전부**와 인증 흐름을 구현했습니다. 경로 이름과 응답 모양은 `frontend/src/api/http.ts`와 같습니다.

| 영역 | 내용 |
| --- | --- |
| 인증 | 세션 로그인(HttpOnly 쿠키, Postgres 저장), SPA용 CSRF 쿠키, 실패 누적 잠금(설정: 5회·15분), IP별 요청 제한, 로그아웃, 비밀번호 재설정(메일 링크, 사용 후 모든 세션 종료), 초대 수락, 첫 관리자 초대. 사내 SSO(OIDC, V10, 커밋 a677079): `auth/OidcProviders`·`OidcLogin`·`OidcController`, Spring Security `oauth2Login`(PKCE·state·nonce·ID 토큰 서명·issuer), 공급자 토큰 저장 안 함, 연결된 sub → 같은 이메일 → 자동 생성 → 거부, 로그인 뒤에는 폼 로그인과 같은 세션 |
| 권한 | 요청마다 역할 × 프로젝트 계산(직접 멤버 + 그룹 멤버 + 공개 프로젝트 비멤버 역할), 보관 프로젝트는 보기만, 꺼진 모듈의 권한 제외. 보이지 않는 리소스는 404 |
| 작업 | 생성·수정(lock_version 409 + 현재 값), 읽기 전용 상태, 워크플로, 저널 5분 집계, 알림 대상 계산(멘션 > 담당 > 책임 > 주시), 코멘트(HTML 정화·@멘션), 주시, 관계, 삭제(하위 삭제·재배치) |
| 일정 | S1–S8, S11을 순수 Java(`wp/Schedule.java`)로. advisory lock으로 직렬화, 바뀐 작업마다 원인이 담긴 저널 |
| 목록 | 필터 29종을 SQL 조건으로, 한글 정렬, 계층(맥락 행·페이지별 조상), 타임라인 관계, 저장된 보기·기본 보기·즐겨찾기 |
| 그 밖 | 프로젝트·멤버·카테고리·버전·보드(카드 이동)·알림함·검색 제안·근무일 달력, 관리 전체(유형·상태·우선순위·워크플로·역할·사용자·그룹·근무일) |

## 검증

- **브라우저 점검 8묶음(가짜 계층용으로 쓴 것)을 실제 API에 그대로 돌려 모두 통과**했습니다. 수직 슬라이스, 키보드, 보드·멤버, 관리, 정리분, 캘린더·팀 플래너, 필터, 날짜 대화상자입니다. 묶음마다 시드 복원 지점에서 시작했습니다.
- 운영 구성(jar 하나가 SPA와 API를 같은 출처에서 제공, CSP 적용)에서도 5묶음이 그대로 통과했습니다. 나머지 3묶음은 테스트 도중 개발 서버 전용 소스 모듈을 불러오는 단계가 있어서 개발 서버(같은 백엔드)로만 돌렸습니다.
- JUnit 27개: 일정 규칙 7개, 접근 권한 6개(두 번째 사용자는 다른 프로젝트의 작업·멤버·검색 결과를 하나도 받지 못함, 로그인 필요, CSRF 없으면 거부, 관리자 전용, 뷰어 수정 거부, 잠긴 계정). 예약 작업 5개(`JobsIT`: 시간대별 날짜 알림과 중복 방지, 기한 지남, 마일스톤·닫힌 작업·프로젝트별 설정·일시 중지·볼 권한, 요약 시각 계산, 멘션 1분 지연·요약 1회·읽은 알림 제외), 파일 정리 2개, 사용자 정의 필드 4개(`CustomFieldsIT`: 관리자 전용, 형식·선택지·비활성 필드 검사, 목록 필터·정렬, 삭제 때 작업 시각 유지), 작업 공유 1개(`SharingIT`: 프로젝트 밖 사람이 공유받은 작업만 보고 역할만큼만 함, 해제하면 다시 404), 알림 설정 2개(`NotificationSettingsIT`: 값 검사, fan-out이 담당 끄기·프로젝트 코멘트 구독을 따름).
- 그 뒤 추가: 사내 SSO `OidcIT` 5개(테스트 안의 IdP로 왕복: 이메일 연결 뒤 sub로 찾기, 계정 없음·자동 생성, 잠김·미확인 이메일·이메일 없음, 다른 키 서명·남의 state, 관리 API와 시크릿 비공개). 2026-10-10 a677079 기준 JUnit 51개 모두 통과.
- 점검 중에 찾아 고친 문제: 세션 없는 로그인 요청의 500, 저널 필드 순서(jsonb가 키 순서를 바꿈), 멘션 직후 빠른 입력 시 글자 순서가 섞이던 프론트 버그.

## 결제

해당 없음. 사내 도구입니다.

## 이메일과 작업

- 메일은 Spring Mail로 사내 SMTP 릴레이를 씁니다(개발: Mailpit). 보내는 메일: 초대, 비밀번호 재설정, 멘션 알림, 하루 요약. 문구는 모두 새로 썼고 일반 텍스트입니다.
- 예약 작업은 db-scheduler(`scheduled_tasks` 테이블)로 돌립니다(`jobs/`). 인스턴스가 여럿이어도 한 번만 실행되고, 실패하면 다음 일정에 다시 돕니다.

| 작업 | 일정 | 하는 일 |
| --- | --- | --- |
| date-alerts | 매시 5분(KST) | 담당·책임·주시 중인 열린 작업의 시작·완료 N일 전, 기한 지남 알림. 사람별 시간대의 "오늘" 기준. 유니크 인덱스로 날짜당 1회. 프로젝트별 설정 > 개인 기본 > 기본값(1일 전·1일 전·기한 지남 켬). 일시 중지(`reminders_paused_until`)와 볼 권한 확인 |
| mail-mention | 1분마다 | 1분 지난 멘션 알림을 메일로(코멘트 500자까지). `immediate_on_mention`을 끈 사람은 요약으로 |
| mail-digest | 15분마다(KST) | 요약 시각(기본 08:00, 사람별 시간대, 최대 4개)이 지났고 그 시각에 아직 안 보냈으면 읽지 않은·안 보낸 알림(7일 이내)을 한 통으로. `last_digest_at`(V4)으로 시각당 1회 |
| token-cleanup | 매일 03:30(KST) | 만료·사용 후 7일 지난 초대·재설정 토큰 삭제 |
| file-sweep | 매주 일요일 04:00(KST) | DB 행이 없는 저장소 파일과 남은 임시 파일 삭제(하루 지난 것만). 작업 패키지 삭제로 사라진 첨부의 파일도 여기서 정리 |

- 메일은 "적어도 한 번": 릴레이가 받은 뒤 `emailed_at`을 기록합니다. 읽은 알림은 보내지 않습니다. 메일 실패 로그에는 제목·주소·본문을 남기지 않습니다.
- 끄고 켜기: `OPENPMS_JOBS_ENABLED`(운영 기본 true). dev 프로필은 브라우저 테스트가 흔들리지 않게 기본 false이고, 관리자가 `POST /api/v1/dev/jobs/{name}?at=ISO시각`으로 원하는 시각을 넣어 바로 돌립니다(dev 프로필에만 있는 경로).
- 아키텍처의 `mail-mention`은 "+1분 일회성 작업"이었지만, 1분 주기 작업이 같은 일을 하고 재시도까지 단순해서 주기 작업으로 바꿨습니다. `notification-fanout`도 별도 작업 없이 저널과 같은 트랜잭션에서 바로 만듭니다.
- 세션 만료 정리는 Spring Session JDBC가 자체 주기로 합니다.

## 연동

결제·캘린더 연동은 없습니다. 사내 SSO(OpenID Connect)는 있습니다(커밋 a677079).

- 사내 IdP에 클라이언트를 등록하는 일은 사내 담당자가 합니다. 리디렉션 URI는 `OPENPMS_BASE_URL` + `/api/v1/auth/oidc/callback/{식별자}`입니다.
- 클라이언트 ID·시크릿은 `.env`가 아니라 관리 → 로그인·비밀번호 → 사내 SSO에서 넣고 DB(`oidc_providers`)에 저장합니다. 시크릿은 API로 내보내지 않습니다.
- 발견 문서(`{issuer}/.well-known/openid-configuration`)는 저장할 때 확인하고, 첫 로그인 때 읽어 메모리에 둡니다.
- 연결 절차와 오류 코드별 문제 해결은 `replica/deploy.md` "사내 SSO 연결"에 있습니다.
- 없는 것: 클레임으로 그룹 동기화, IdP 로그아웃(RP-initiated logout), 비밀번호 로그인 숨기기, 시크릿 암호화 저장.

## 보안 체크리스트

- [x] 비밀값은 환경 변수로만. `.env*`는 git 제외(`backend/.env.example`만 커밋), 클라이언트 번들에 비밀값 없음. 예외: SSO 클라이언트 시크릿은 관리 화면에서 넣어 DB에 평문으로 저장(API로는 내보내지 않음). DB 덤프를 비밀값처럼 다뤄야 함(`replica/deploy.md` 보안 메모)
- [x] SSO: PKCE(S256)·state·nonce·ID 토큰 서명·issuer 확인, 공급자 토큰 저장 안 함, `email_verified=false`면 이메일 연결 거부, 잠긴 계정 거부, 돌아갈 주소는 같은 사이트 경로만, 실패 주소에는 이유 코드만(`OidcIT`)
- [ ] SSO 클라이언트 시크릿 암호화 저장: 아직 없음
- [x] 모든 경로에서 서버 검증(필드별 422, 형식 오류 400, 알 수 없는 ID 422/404). 입력 HTML은 OWASP 허용 목록으로 정화
- [x] 모든 읽기·쓰기에서 권한 확인. 두 번째 사용자로 테스트(`AccessIT`). 작업 공유(V6)는 작업 하나의 권한을 프로젝트 역할 ∪ 공유 역할로 계산(`Ctx.can(project, wpId, perm)`), 공유받은 사람이 프로젝트에서 얻는 것은 `project.view`뿐(`SharingIT`)
- [x] 로그인·비밀번호 재설정에 요청 제한(IP별, 메모리), 로그인 실패 누적 잠금
- [ ] 웹훅 서명: 해당 없음(웹훅 없음)
- [x] 업로드 크기·형식 제한: 25MB(설정 `attachments.max_bytes`) 초과 413, 내용으로 형식 판별(Tika, 확장자·브라우저 값 무시). 저장은 `/data/files`(DB 밖, 임의 UUID 이름). 내려받기는 `nosniff` + `CSP sandbox`, png·jpeg·gif·webp만 inline, 나머지는 `attachment`. 별도 도메인은 쓰지 않음(사내 단일 호스트) — 위 헤더로 같은 출처 실행을 막음. 권한 없는 사용자는 404
- [x] URL·로그에 사용자 데이터 없음. 메일 본문(일회용 링크)은 로그에 남기지 않음. 예외: 첫 관리자 설정 링크는 설계대로 로그에 한 번 출력
- [x] 의존성 점검: `npm audit` 0건. Java 쪽은 아직 도구 없음(OWASP dependency-check를 CI에 넣는 것을 권장)
- [x] 개인정보 처리 위탁: 외부 처리자 없음(셀프 호스팅, 사내 SMTP). 사용자 삭제는 익명화(이름·이메일·비밀번호 제거, 멤버십·주시·알림 삭제, 저널은 "삭제된 사용자"로 유지)
- [x] 보안 헤더: CSP(`default-src 'self'`), `X-Frame-Options: DENY`, `nosniff`. 쿠키 `Secure`는 운영 기본값(개발만 해제)
- [x] 백업: `pg_dump -Fc` → 새 DB에 `pg_restore` 복원 확인(2026-10-10, `replica/deploy.md` 검증 기록). 호스트 cron 등록과 첨부 파일(`files` 볼륨) 백업은 서버 담당자가 `replica/deploy.md`대로

## 아직 없는 것 (다음 단계)

| 항목 | 메모 |
| --- | --- |
| ~~계정 설정 API~~ | 완료(S37: `PATCH /me`, `/me/password`, `/me/preferences`, `POST /me/sessions/end-others`) |
| PDF 내보내기 | S12의 CSV·XLSX는 완료(`POST /work-packages/export`, Apache POI). PDF는 한글 글꼴 내장과 함께. 설계의 500행 초과 백그라운드 작업·`GET /exports/{id}`는 빼고 5000행 초과 422로 바꿈. 수식 주입 방어(`=` `+` `-` `@` 탭·CR로 시작하는 셀에 `'`) 완료. 공유 역할에는 내보내기 권한이 없어(원본 문서도 보기·코멘트·편집만) 공유로만 보는 작업은 빠지며, `POST /work-packages/export/count`로 대화상자가 파일 행 수와 빠지는 건수를 미리 보여 줌(전부 빠지면 422). `ExportIT` |
| ~~로그인·비밀번호 정책~~ | 완료(`auth/PasswordPolicy`: 길이·문자 종류·섞을 종류 수, 잠금 0=끔, 재설정 허용, 링크 유효 시간. `GET/PUT /admin/settings/auth`, 공개 `GET /auth/password-policy`) |
| ~~초대 수락·비밀번호 재설정 화면 연결~~ | 완료(S01: `Api.auth` 4개 메서드, `/invitations/:token`, `/password-reset`). 새 링크를 보내거나 하나를 쓰면 같은 종류의 다른 링크는 무효(`Tokens.revokeAll`) |
| 설계에 있으나 없는 경로 17개 | `replica/review-2026-10-10.md` §2 (프로젝트 복원·삭제, 작업 복사·이동·일괄, 보드 열 관리, `GET /queries/{id}`, `/home`, `/admin/settings/{section}` 5종 등) |
| 사용자 정의 필드: 프로젝트·사용자 엔터티 | 작업 패키지 필드는 완료(`customfield/`). 프로젝트 특성·사용자 특성, 버전 형식은 없음 |
| ~~진행률 합계(P2–P5)~~ | 완료(S32: `wp/Progress.java`·`fake/progress.ts`, `/admin/progress`, 저장 시 전체 재계산은 동기) |
| ~~배포 파일~~ | 완료(커밋 11fb59b: 저장소 최상위 `Dockerfile`·`compose.yaml`·`.env.example`, 이미지로 점검까지. 운영 안내는 `replica/deploy.md`) |
| ~~사내 SSO(OIDC)~~ | 완료(커밋 a677079: V10 `oidc_providers`·`user_identities`, `GET /auth/sso-providers`, `/auth/oidc/start·callback/{slug}`, `/admin/oidc-providers` 4개, `OidcIT`) |
| SSO 남은 것 | 클레임으로 그룹 동기화, IdP 로그아웃(RP-initiated logout), 비밀번호 로그인 숨기기 옵션, 클라이언트 시크릿 암호화 저장 |
