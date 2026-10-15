# openPMS — Claude Code 작업 규칙

이 저장소에서 일하는 Claude Code가 지킬 규칙과 지금까지의 맥락입니다. 개발 환경 꾸리기는 `README.md`를 보세요.

## 언어
- 사용자와의 대화, 산출물 문서, 화면 문구, 커밋 본문의 설명은 **한국어**로 씁니다(코드 주석·식별자는 기존 코드처럼 영어 위주).

## 클린룸과 보안 (반드시 지킴)
- **OpenProject의 소스 코드, GitHub 저장소, JS 번들, 네트워크 호출은 절대 읽지 않습니다**(GPLv3, 클린룸). 공개 문서·리뷰·포럼은 읽어도 됩니다. 리뷰어 인용은 조사 자료일 뿐 홍보 문구로 쓰지 않고, 리뷰·인용을 지어내지 않습니다.
- `replica/screens/`(원본 화면 캡처)는 참고용이며 **절대 커밋하지 않습니다**(.gitignore). `replica/diffs/`도 커밋하지 않습니다.
- 계정에 대신 로그인하거나, 비밀번호를 묻거나, 기본 자격 증명을 쓰지 않습니다. 개발 데이터의 비밀번호 `openpms!`는 dev 프로필에만 있습니다.
- 비밀값은 환경 변수로만. `.env`·`backend/.env.local`은 커밋하지 않습니다(새 PC에서는 `dev.sh up`이 새로 만듦). 토큰을 출력·붙여 넣지 않습니다.
- 커밋은 경로를 지정해서 합니다(`git add -A` 금지).

## 일하는 방식
- 산출물(`docs/deliverables/`)과 문서 갱신은 **백그라운드 서브에이전트**에게 맡기고, 결과를 검토한 뒤 커밋합니다. 판 번호는 README의 개정 이력을 따릅니다(지금 2.4).
- 큰 수정은 스크래치 폴더에 Python 스크립트를 써서 적용합니다. **bash heredoc은 따옴표·백슬래시를 망가뜨리므로** 코드 패치에 쓰지 않습니다.
- **prettier를 돌리지 않습니다**(저장소의 포매터가 아니어서 파일 전체가 바뀜).
- 문서 줄바꿈을 그대로 둡니다: `docs/deliverables`의 03·04·05·10과 `replica/` 문서는 CRLF, 01·02·06·07·08·09와 README는 LF.
- Windows Git Bash에서 `podman exec`에 `/data/...` 같은 경로를 넘길 때는 `MSYS_NO_PATHCONV=1`.
- Windows의 Python `urllib`로 `localhost`를 부르면 요청마다 약 2초 늦어집니다(IPv6 먼저 시도). `127.0.0.1`을 쓰세요.

## 개발 순서에서 잊기 쉬운 것
- 백엔드를 고쳤으면 e2e 전에 `bash backend/scripts/dev.sh run`으로 다시 띄웁니다.
- 마이그레이션 뒤 복원 지점: 앱 정지 → `reset-db` → `run` → `gradle jooqCodegen` → 앱 정지 → `snapshot-db`.
- 개발 데이터 마이그레이션(V1000번대)은 스키마 마이그레이션 **뒤에** 돕니다. 새 스키마에 개발 데이터가 필요하면 V100x를 새로 만듭니다(예: V1002 시간 기록, V1003 공동 담당자). 새 모듈을 넣으면 `projects_known_modules` check 제약도 넓힙니다.
- jOOQ 생성 코드(`backend/src/main/generated`)는 커밋합니다(빌드에 DB가 필요 없게).
- `work_package_assignees`에 `ON CONFLICT`를 쓸 때는 `(work_package_id, user_id)`를 꼭 적습니다(지연 unique 제약 때문에).
- 요청은 가상 스레드에서 돕니다(Java 21). **`synchronized`나 `ConcurrentHashMap.computeIfAbsent` 안에서 DB를 기다리지 않습니다**. 캐리어 스레드를 붙잡아 서버 전체가 멈춥니다(BUG-012). `NoPinningTest`와 `PerfBudgetIT`의 JFR 검사가 막습니다.
- 앱 도움말(`frontend/src/features/help/topics.ts`)은 화면을 매뉴얼 **절 번호**로 잇습니다. `07-사용자매뉴얼.md`·`10-운영자매뉴얼.md`의 기존 번호 붙은 제목은 **번호를 바꾸거나 지우지 말고**, 새 내용은 새 번호로 덧붙입니다(e2e f17-H3가 확인).
- 서비스 데스크 프로젝트는 **비공개만**입니다(공개면 비멤버가 모든 티켓을 봄). 앱이 422로 막고, 모듈 `service_desk`와 `isPublic = true`를 함께 두지 않습니다.
- 요청자는 멤버가 아닙니다: 포털 코드(`itsm/PortalService`)는 항상 `service_tickets.requester_id`로 좁히고, 작업 패키지 API 응답(409의 `current` 포함)을 포털에 돌려주지 않습니다. 남의 티켓은 403이 아니라 404입니다(`PortalSecurityIT`).
- 요청자의 상태 전환은 내장 역할 "요청자"(`roles.builtin = 'requester'`)의 워크플로로 정합니다(V20은 `applies_to = 'always'`로 넣고 포털이 작성자 = 요청자로 검사하므로, 관리 › 워크플로에서 고칠 수 있음). 서비스 데스크의 `service.*` 권한은 `Ctx.MODULE_OF`가 모듈에 묶습니다.
- 가짜 데이터 계층(`frontend/src/api/fake`)도 실제 API와 같은 규칙으로 맞춥니다. 시드 모양을 바꾸면 `db.ts`의 `SCHEMA`를 올립니다. `seed.ts`가 db를 import하는 모듈을 import하면 TDZ 순환이 생깁니다(시간 기록 시드는 `fake/time-seed.ts`).
- 측정 예산: 서버는 `PerfBudgetIT`(p95·SQL 수·응답 크기), 화면은 `e2e/perf.spec.ts`(`E2E_BASE=http://localhost:8090`이면 빌드된 화면), 부하는 `backend/scripts/perf_load.py`(시험용 설치에서만, 비밀번호는 `OPENPMS_PERF_PASSWORD`). 부하 측정은 CPU를 제한한 컨테이너로 합니다(8코어 PC에서는 BUG-012가 안 보였음). `podman stats`의 CPU %는 시작 이후 평균이므로 `CPUNano` 차이를 씁니다.
- 운영 이미지 확인은 스크래치 폴더에 compose를 복사해 별도 프로젝트 이름·포트로 띄웁니다(개발 컨테이너와 겹치지 않게).

## 지금까지 (2026-10-14 기준)
- 범위: 작업 패키지 핵심 + 계획 화면(Gantt, 보드, 캘린더, 팀 플래너), 사내 도구, ITSM 1단계(서비스 데스크·SLA·요청자 포털). 기준 원본은 사용자의 자체 설치 OpenProject 17.9.1 Community(로컬 8080, 사용자 본인 계정).
- 원본 기능 패리티 100(필수 64/64). 스키마 V1–V20, 개발 데이터 V1000–V1004.
- `/replica-entrepreneur`의 개선 계획 `replica/fixes.md`:
  - 1 주간 공수 입력표(시간 기록)
  - 2 템플릿 새 시작일
  - 3 업그레이드 전 자동 백업 + 관리 · 시스템 정보
  - 4 성능 예산(`replica/perf.md`)
  - 5 화면별 도움말(`/help`, `/help/admin`)
  - 6 공동 담당자(`work_package_assignees`, 첫 사람 = 주 담당자 = `assignee_id`)
  - — 모두 완료. **7 반복 작업은 사용자 결정으로 보류.**
- 브랜드(`/replica-brand`, 2026-10-12, `replica/brand.md`): 이름은 openPMS 그대로(사용자 결정, 사내 전용. 밖에 내놓으면 다시 정함). 강조색 자주 #8b2f8f, 헤더 먹색 #1e1c22. 원본의 파랑·초록 계열을 다시 쓰지 않고, 앱 쪽은 `sweep.py frontend`·`sweep.py backend`(`--config replica/brand.json`)가 깨끗해야 합니다(내부 문서의 출처 표기는 일부러 남김).
- 사내 개선(`fixes.md` 밖): 임시 비밀번호(REQ-AUTH-005, `temporary_passwords`·V19) — 관리자가 메일 없이 사용자를 만들거나 다시 발급하고, 첫 로그인에서 새 비밀번호를 정하게 함. 평문이 바뀔 때까지 DB에 남으므로 `org.jooq` 로그를 DEBUG로 올리지 않음. SSO e2e는 5173 개발 서버도 필요.
- ITSM 1단계(2026-10-14, 설계 `docs/superpowers/specs/2026-10-14-itsm-design.md`, 계획 `docs/superpowers/plans/2026-10-14-itsm-phase1.md`, REQ-ITSM-001–013): 장애·서비스 요청 티켓(작업 패키지 + 1:1 `service_tickets`), SLA 정책·서비스 달력(`itsm/SlaClock` 순수 함수), 요청자 포털 `/portal`(별도 컨트롤러, 요청자 본인 티켓만), 예약 작업 `sla-check`·`portal-mail`(1분), 알림 `sla_warning`·`sla_breached`와 웹훅 `service_ticket.sla_breached`. 설계서의 2~6단계(카탈로그·문제·CMDB·변경·지식 베이스)는 설계서만 있고 구현하지 않음. 마이그레이션은 V20(2단계 계획은 V21 이후).
- 테스트: JUnit 199(ITSM 1단계 최종 리뷰 수정 뒤, 그 앞 191). e2e는 최종 리뷰 수정 뒤(777e7bf)에도 147건 중 142 통과·5 건너뜀(IdP 없이), ITSM 1단계 뒤 전체(2026-10-14, 76723c6)에서 IdP 없이 147건 중 142 통과 + 5 건너뜀(PERF-5와 SSO 4건), IdP를 띄우면 146 통과 + 1 건너뜀이 기대 결과(측정하지 않음). 그 앞 2.3 보안 수정 뒤 전체(2026-10-13, b34418b)에서 132 통과 + 5 건너뜀(PERF-5와, IdP 포트 18081을 colima의 redpanda가 쓰고 있어 건너뛴 SSO 4건). colima를 끄고 IdP를 띄워 SSO 4건도 통과해, 모두 136 통과 + 1 건너뜀. e2e의 `axe()`는 검사 전에 마우스를 치우고 CSS 전환이 끝나기를 기다림(전환 중간 색이 대비 실패로 잡히던 F03-G7).
- 산출물 2.4(2026-10-14)에 반영한 것: ITSM 1단계(f3e8999–76723c6)의 새 영역 REQ-ITSM(요구사항 163개), 범위 밖 REQ-OOS-021–025, NFR-SEC-020·NFR-PERF-010, 새 화면 X14–X18, 경로 210개, V20, 07 사용자 매뉴얼 16·17장·10 운영자 매뉴얼 6.12·9.18–9.20(도움말은 `/portal` → 07 16장, 서비스 탭 → 17.3, 프로젝트 설정 서비스 데스크 → 17.6, `/admin/service-calendars` → 10 9.19에 연결). JUnit 121→191, e2e 137→147건. 이미지 배포 스모크는 하지 않음(V19 → V20).
- 산출물 2.3(2026-10-13)에 반영한 것: BUG-015(API 토큰 필터의 퍼센트 인코딩 우회, 69c2760), BUG-016(사용자 관리자가 관리자 계정을 잠그거나 삭제, 0e229d6), BUG-017(로그인 응답 시간으로 계정 유무가 드러남, cdee894), 임시 비밀번호 보강(변경 토큰은 로그인이 검사한 해시일 때만·만료 처리의 잠금 순서·비밀번호가 든 레코드의 toString 가림·no-store 정확히 확인, 4889f8b). JUnit 116→121.
- 남은 일:
  - 손으로 할 것(사내 SMTP·SSO·웹훅·프록시, 임시 비밀번호를 실제 메신저로 전달, NVDA, 회사 서버 부하 측정, 원본 캡처로 레이아웃 비교, 포털 요청자 메일을 실제 SMTP로·SSO 계정 요청자의 접수, IdP를 띄운 전체 e2e와 V19 → V20 이미지 업그레이드 스모크)
  - 정할 것(워크플로 '담당자일 때만' 조건을 화면에서 만들지, 템플릿 복사의 비공개 보기, 내보내기 시각 형식, ITSM 2단계(서비스 카탈로그·승인)를 이어서 할지)
  - 테스트 빈틈(`replica/test-plan.md`의 "확인할 것")
- 이전 PC의 세션 스크래치 폴더에만 있던 브라우저 점검 스크립트 27묶음과 이미지 스모크 스크립트는 저장소에 없습니다. e2e로 옮기는 것이 다음 후보입니다.
