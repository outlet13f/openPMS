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
- 산출물(`docs/deliverables/`)과 문서 갱신은 **백그라운드 서브에이전트**에게 맡기고, 결과를 검토한 뒤 커밋합니다. 판 번호는 README의 개정 이력을 따릅니다(지금 2.7).
- 큰 수정은 스크래치 폴더에 Python 스크립트를 써서 적용합니다. **bash heredoc은 따옴표·백슬래시를 망가뜨리므로** 코드 패치에 쓰지 않습니다.
- **prettier를 돌리지 않습니다**(저장소의 포매터가 아니어서 파일 전체가 바뀜).
- 문서 줄바꿈을 그대로 둡니다: `docs/deliverables`의 03·04·05·10과 `replica/` 문서는 CRLF, 01·02·06·07·08·09와 README는 LF. (2.7 확인) 이 저장소의 `.gitattributes`는 `* text=auto eol=lf`라 Mac 체크아웃에서는 모든 문서가 LF로 나옵니다. 있는 줄바꿈을 그대로 두면 됩니다.
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
- 가짜 데이터 계층(`frontend/src/api/fake`)도 실제 API와 같은 규칙으로 맞춥니다. 시드 모양을 바꾸면 `db.ts`의 `SCHEMA`를 올립니다. `seed.ts`가 db를 import하는 모듈을 import하면 TDZ 순환이 생깁니다(시간 기록 시드는 `fake/time-seed.ts`).
- 측정 예산: 서버는 `PerfBudgetIT`(p95·SQL 수·응답 크기), 화면은 `e2e/perf.spec.ts`(`E2E_BASE=http://localhost:8090`이면 빌드된 화면), 부하는 `backend/scripts/perf_load.py`(시험용 설치에서만, 비밀번호는 `OPENPMS_PERF_PASSWORD`). 부하 측정은 CPU를 제한한 컨테이너로 합니다(8코어 PC에서는 BUG-012가 안 보였음). `podman stats`의 CPU %는 시작 이후 평균이므로 `CPUNano` 차이를 씁니다.
- 운영 이미지 확인은 스크래치 폴더에 compose를 복사해 별도 프로젝트 이름·포트로 띄웁니다(개발 컨테이너와 겹치지 않게).
- CI/CD 웹훅 `POST /api/v1/hooks/cicd/{hookKey}`는 인증 경로와 헬스 체크를 빼면 **로그인 없이 받는 유일한 경로**입니다(`SecurityConfig`에서 이 경로의 POST만 `permitAll`·CSRF 제외). 없는 키·꺼진 연동·서명 실패는 모두 같은 404로 답하고, 요청 안에서는 저장만 합니다(도구 호출·연결은 예약 작업). 이 성질을 깨는 수정은 `CicdHookIT`가 막습니다.
- CI/CD 참조(`#123`)는 저장소가 붙은 프로젝트와 그 **하위** 프로젝트의 작업에만 잇습니다(`CicdLinker.projectTree`). 손 연결은 작업 쪽에서 본 같은 관계라 작업의 프로젝트와 그 **상위** 프로젝트에 붙은 저장소만 받습니다(`projectAncestors`). 다른 프로젝트 작업이 있는지 드러나면 안 됩니다.
- `OPENPMS_SECRET_KEY`: dev 프로필의 기본값은 저장소에 적힌 고정 개발용 키입니다. **운영에 쓰지 않습니다**(운영 기본값은 빈 값 → 연동 저장 422). 토큰·웹훅 비밀은 응답·로그·`toString`에 끝 4자리 말고는 나오면 안 됩니다. CMDB의 구성 항목(CI)과 섞이지 않게 식별자는 `cicd_`·`Cicd*`.
- e2e F22(`f22-cicd.spec.ts`)는 가짜 Git 서버 컨테이너 `openpms-cicd-git`(`frontend/e2e/fake-git.mjs`, 개발 네트워크 `openpms-dev`)가 떠 있어야 돕니다. 없으면 3건을 건너뜁니다. 호스트 18090이 잡혀 있으면(colima) 다른 포트로 게시하고 `FAKE_GIT_URL`을 줍니다.
- 두 번째 작업 트리는 `dev.sh`에 `OPENPMS_DEV_NAME`·`OPENPMS_DEV_DB_PORT`·`OPENPMS_DEV_APP_PORT`를 주어 컨테이너를 따로 띄웁니다(예: `openpms-cicd`·55433·8091, 그 트리의 `backend/.env.local`에 적어 둠). 기본값(`openpms`·55432·8090)은 다른 세션이 쓰고 있을 수 있습니다. 화면은 `OPENPMS_API_URL`과 다른 vite 포트(`--port 5184 --strictPort`)로.

## 지금까지 (2026-10-16 기준)
- 범위: 작업 패키지 핵심 + 계획 화면(Gantt, 보드, 캘린더, 팀 플래너), 사내 도구. 기준 원본은 사용자의 자체 설치 OpenProject 17.9.1 Community(로컬 8080, 사용자 본인 계정).
- 원본 기능 패리티 100(필수 64/64). 스키마 V1–V19·V26·V27(V20–V25는 비워 둠), 개발 데이터 V1000–V1003·V1010·V1011(V1004–V1009는 비워 둠).
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
- ITSM 제거(2.6, 2026-10-16): 사용자 결정으로 ITSM 전체 제거(코드는 3cb9acd로 되돌림, V20·V21·V1004–V1006과 설계 문서 삭제). 매뉴얼 16–18장 등 ITSM 절은 이때 지움.
- CI/CD 연동 1단계(2.7, 2026-10-15–16, 브랜치 feat/cicd-phase1, 설계 `docs/superpowers/specs/2026-10-15-cicd-design.md`, 계획 `docs/superpowers/plans/2026-10-15-cicd-phase1.md`, REQ-CICD-001–009): 사내 GitLab·GitHub를 관리 › CI/CD 연동(`/admin/cicd`)에 등록하고 프로젝트 설정 › 저장소에서 붙이면, 커밋 메시지·MR/PR 제목·설명·소스 브랜치의 `#번호`로 작업의 **개발** 탭에 커밋·MR/PR이 이어짐. 마이그레이션 V26(`cicd_*` 표 6개, 모듈 `cicd`, 한때 ITSM V20·V21을 돌린 DB와 검사합이 부딪치지 않게 V20–V25를 건너뜀)과 개발 데이터 V1010. 권한 `cicd.view`·`cicd.link`·`cicd.manage`(모듈에 묶임)와 전역 `global.manage_cicd`, 새 역할 **개발 리더**(멤버 + 저장소 관리)·**CI/CD 연동 관리자**(전역, 연동 관리만). 받는 길은 서명한 웹훅(경로는 설계서의 `/hooks/cicd/…`가 아니라 `/api/v1/hooks/cicd/{hookKey}`: SPA 폴백과 운영 프록시가 `/api`만 백엔드로 보내서)과 5분 보완 폴링, 예약 작업 `cicd-process`(15초)·`cicd-poll`(5분)·`cicd-cleanup`(03:40). 토큰·웹훅 비밀은 `OPENPMS_SECRET_KEY`(32바이트 Base64)로 AES-256-GCM, 감사 로그는 로거 `openpms.audit`(감사 표 없음). 전달 키는 도구의 전달 id가 아니라 내용(`commit:저장소:sha`, `mr:저장소:번호:갱신 시각`)이라 웹훅과 폴링이 한 번만 쌓임. 붙지 않은 저장소의 이벤트는 처리 때 지움(붙인 뒤 폴링이 7일치를 채움). 매뉴얼은 07 16장(16.1–16.5), 10 6.12·9.18(도움말: `/admin/cicd` → 9.18, 개발 탭 → 16.2, 저장소 → 16.3). 설계서의 2단계(빌드·배포)·3단계(릴리스)·4단계(자동화)는 설계서만 있고, 5단계(변경 관리 연결)는 ITSM 제거로 **보류**.
- CI/CD 연동마다 허용 프로젝트(2.8, 2026-10-16, 브랜치 feat/cicd-allowed-projects, REQ-CICD-010): 2.7 최종 리뷰의 I3(어느 프로젝트의 개발 리더든 토큰이 볼 수 있는 저장소를 붙임)에 대한 사용자 결정. V27 `cicd_connection_projects`(이미 붙은 저장소의 프로젝트로 채움)와 V1011(새 개발 DB에서는 V27이 V1010보다 먼저 돌아 채울 것이 없으므로 개발 연동에 web-platform·mobile-app). `PUT /api/v1/admin/cicd/connections/{id}/projects`는 시스템 관리자만(위임받은 연동 관리자는 403, 목록에서는 볼 수 있는 프로젝트 이름과 `hiddenProjectCount`만). 프로젝트 쪽 연동 고르기·찾기·붙이기는 허용한 연동만이고 아니면 꺼진 연동과 같은 404(붙일 때 허용 행을 `FOR SHARE`로 잡고 다시 검사, 목록에서 뺄 때는 행을 먼저 지우고 저장소를 확인해 409). 새 연동은 빈 목록. 허용 프로젝트 이름은 한국어 Collator로 정렬(DB의 en_US 정렬은 한글 순서가 다름). 매뉴얼 10 6.13·9.18.10(새 절), 07 16.1·16.3. JUnit 229(3a8c7bd), e2e 147건 3a8c7bd에서 142 통과·5 건너뜀(SSO 4건은 IdP 없이·PERF-5)·실패 0.
- 테스트(2.7): JUnit 220(4014802, 브랜치 전체 최종 리뷰 수정 뒤. 그 앞 c8896eb에서 212, CI/CD 12개 클래스 99건). e2e 147건(흐름 101·접근성 40·성능 6) 전체를 4014802에서 IdP 없이 돌려 142 통과 + 5 건너뜀(SSO 4건·PERF-5), 실패 0(F17-H3·F22-1–3 통과). 그 앞 병합 전 8e6b00b 전체(ITSM이 있던 157건)는 151 통과·5 건너뜀·1 실패(F17-H3: 매뉴얼 절이 없어서, 2.7 문서로 해소).
- 테스트(2.6까지): JUnit 121. e2e 137건 중 132 통과 + 5 건너뜀(PERF-5와 IdP 없이 건너뛴 SSO 4건, 2026-10-16, ITSM 제거 뒤 전체). 그 앞 2.3 보안 수정 뒤 전체(2026-10-13, b34418b)에서 132 통과 + 5 건너뜀(PERF-5와, IdP 포트 18081을 colima의 redpanda가 쓰고 있어 건너뛴 SSO 4건). colima를 끄고 IdP를 띄워 SSO 4건도 통과해, 모두 136 통과 + 1 건너뜀. e2e의 `axe()`는 검사 전에 마우스를 치우고 CSS 전환이 끝나기를 기다림(전환 중간 색이 대비 실패로 잡히던 F03-G7).
- 산출물 2.3(2026-10-13)에 반영한 것: BUG-015(API 토큰 필터의 퍼센트 인코딩 우회, 69c2760), BUG-016(사용자 관리자가 관리자 계정을 잠그거나 삭제, 0e229d6), BUG-017(로그인 응답 시간으로 계정 유무가 드러남, cdee894), 임시 비밀번호 보강(변경 토큰은 로그인이 검사한 해시일 때만·만료 처리의 잠금 순서·비밀번호가 든 레코드의 toString 가림·no-store 정확히 확인, 4889f8b). JUnit 116→121.
- 남은 일:
  - 손으로 할 것(사내 SMTP·SSO·웹훅·프록시, 임시 비밀번호를 실제 메신저로 전달, NVDA, 회사 서버 부하 측정, 원본 캡처로 레이아웃 비교, 실제 사내 GitLab·GitHub로 CI/CD 웹훅·폴링, `OPENPMS_SECRET_KEY` 교체 절차, V19 → V26 이미지 업그레이드 스모크, ITSM 제거 병합 뒤 e2e 전체)
  - 정할 것(워크플로 '담당자일 때만' 조건을 화면에서 만들지, 템플릿 복사의 비공개 보기, 내보내기 시각 형식, CI/CD 2단계를 이어서 할지. 01 6장 #42(웹훅 비밀의 멱등 보관, `/api/v1/admin/cicd/` 닫힌 경로)는 최종 리뷰 수정 4d79990에서 해결)
  - 테스트 빈틈(`replica/test-plan.md`의 "확인할 것")
- 이전 PC의 세션 스크래치 폴더에만 있던 브라우저 점검 스크립트 27묶음과 이미지 스모크 스크립트는 저장소에 없습니다. e2e로 옮기는 것이 다음 후보입니다.
