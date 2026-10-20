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
- 산출물(`docs/deliverables/`)과 문서 갱신은 **백그라운드 서브에이전트**에게 맡기고, 결과를 검토한 뒤 커밋합니다. 판 번호는 README의 개정 이력을 따릅니다(지금 3.0).
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
- CI/CD 저장소 떼기는 행을 지우지 않습니다(2.9, `cicd_repositories.detached_at`). 뗀 저장소는 이벤트 처리·폴링·손 연결·표준 알림의 저장소 찾기에서 **붙지 않은 저장소와 같아야** 하므로, 저장소를 찾는 새 쿼리에는 `detached_at is null`을 꼭 넣습니다. 연동 삭제 409는 뗀 저장소까지 세고(대신 끄기), 허용 프로젝트 빼기·연동 목록의 저장소 수는 붙은 것만 셉니다. 다른 프로젝트로 옮기면 그 저장소의 `cicd_environment_heads`·`cicd_environment_commits`를 지우고, 반영·첫 배포·개발 탭은 언제나 저장소의 **현재** 프로젝트 환경만 봅니다.
- 배포 반영(`CicdDeployments.advance`)의 잠금은 `cicd_environment_heads` 행 `for update` 하나뿐이고, 비교 API(HTTP)는 **트랜잭션 밖**에서 부른 뒤 머리가 그새 바뀌었으면 쓰지 않고 다시 계획합니다. 첫 배포 확인(`CicdFirstDeploy.check`)은 반영 루프 뒤 실행마다 한 번(저장소당 50번). 바깥 호출 응답 상한은 2 MiB이고 **비교·조상 확인만 8 MiB**(`CicdHttp.MAX_COMPARE_BODY`). 로그에는 연동·저장소·배포 id와 상태 코드만.
- CI/CD 빌드·배포 전달 키는 상태 뒤에 소식의 시각을 붙입니다(다시 실행한 같은 상태를 받으려고). 그래서 웹훅과 폴링이 같은 상태를 두 번 저장할 수 있고, `CicdStates.replaces`(더 새로운 소식만)가 걸러 냅니다. 표준 알림의 빌드·배포 id는 `jenkins:`·`argocd:`를 앞에 붙여 저장합니다(GitLab id와 한 행을 고치지 않게). 늦은 소식에 커밋 번호가 없으면 아는 값을 지킵니다.
- Argo CD 연동은 서명(`X-OpenPMS-Signature`) 말고 `X-OpenPMS-Token: <웹훅 비밀>`도 받습니다(Notifications 웹훅은 본문을 서명하지 못함). Jenkins는 HMAC만. 틀(`resources/cicd/templates/`)에는 비밀을 넣지 않고(Argo CD는 `$openpms-webhook-secret` 참조), Argo CD 틀은 `sha` 대신 이미지를 보냅니다(동기화 리비전은 매니페스트 저장소의 커밋).
- V28이 V1010보다 먼저 돌아(새 개발 DB) 개발 프로젝트에는 V28이 기본 환경을 넣지 못하므로 V1012가 web-platform·mobile-app에 넣습니다. 모듈 `cicd`를 켜는 코드(프로젝트 수정·템플릿·복사)는 `CicdEnvironments.ensureDefaults`를 부릅니다(환경이 하나도 없을 때만).
- 환경을 만들거나 이름을 바꾸거나 별칭을 더하거나 숨김을 풀면 그 이름의 환경 미지정 배포를 옮겨 다시 `checking`으로 둡니다(`reassign`). 숨긴 환경은 새 배포와 맞추지 않지만, 이미 확인 중인 첫 배포는 끝까지 확인합니다. 개발 탭 환경 줄은 보는 사람이 `cicd.view`를 가진 상위 프로젝트만 씁니다.
- e2e F23(`f23-cicd-builds.spec.ts`)은 가짜 Git 서버의 **GitLab 모드**(`/api/v4/…`, `PRIVATE-TOKEN`, 비교·`merge_base`·파이프라인·배포 경로, 커밋 E1·E2·E3)가 필요하고, 예약 작업을 `POST /api/v1/dev/jobs/cicd-process`로 부르며 상태가 바뀔 때까지 다시 봅니다. F23-2 뒤에는 운영 환경이 15분 남짓 `checking`(다시 시도 간격)이라 뒤 케이스는 운영을 보지 않습니다. 브라우저에서 **확인 실패**는 다루지 않습니다(JUnit만).
- `cicd_environment_commits.added_at`(3.0, V29)은 그 커밋을 들여온 배포의 `deployed_at`이고, 비어 있으면 BEFORE INSERT 트리거가 채웁니다(2단계 코드·V1012는 이 열을 모름, 새 개발 DB에서는 V1012가 V29 **뒤에** 돎). `on conflict … do update set deployment_id` 같은 upsert는 `added_at`을 옮기지 않습니다(다시 확인된 커밋은 "새로 들어옴"이 아님). 검증 보드의 기준 시각·`basis_at`·`added_before`가 모두 이 시각끼리 비교합니다.
- 릴리스 검증 보드의 저장소를 찾는 쿼리(차이, 기준 시각, `latestIn`, 개발 탭 검증 줄)에도 `detached_at is null`과 저장소의 **현재** 프로젝트를 꼭 넣습니다. 보드·검증은 요청 때 DB만 읽고 HTTP를 부르지 않습니다. 작업의 커밋은 끊지 않은 커밋 연결 + 연결된 병합 MR의 병합 커밋(연결 SQL은 `CicdVerificationStates.workPackageCommits` 하나), 카드·수·기록은 작업마다 `wp.view`로 거릅니다(끊지 않은 연결이 있는 커밋은 보이지 않는 작업이라도 "연결 없는 커밋"이 아님).
- 릴리스 기록(`cicd_releases`)은 실제로 커밋을 새로 들인 운영(`production`) 배포만, `CicdDeployments.apply`의 트랜잭션 **안에서** 새 잠금·바깥 호출 없이 씁니다(롤백·첫 배포·같은 커밋 재시작은 쓰지 않음). `giveUp`과 `broken`(그 환경의 첫 배포 제외)은 작업 행 없는 "내용 확인 실패"(`content_failed`) 기록이고, 기본 원본(`source_environment_id`)도 남깁니다.
- 권한 키 `release.verify`는 `Ctx.MODULE_OF`(모듈 `cicd`)와 `AdminController.PERMISSIONS`에 **둘 다** 있어야 합니다. `PERMISSIONS`에 없으면 관리 › 역할에서 역할을 한 번 저장할 때 모르는 키로 조용히 지워집니다(`CicdMigrationIT#savingTheQaRoleInTheEditorKeepsReleaseVerify`). 새 권한 키도 같습니다.
- 검증의 실패 사유는 로그·예외 메시지·감사 줄에 남기지 않습니다(`CicdVerificationsIT#theFailureReasonIsNeverLogged`). 사유는 한 번 다듬은 값으로 길이를 재고 그대로 저장해 DB check가 터져 로그에 찍히지 않게 합니다. `Idempotency-Key` 응답 본문으로 24시간 `idempotency_keys`에 남는 것은 운영 안내(10 9.18.14).
- e2e F24(`f24-cicd-release.spec.ts`)는 F23과 다른 프로젝트·저장소(mobile-app, 가짜 Git 서버 GitLab 모드의 9301 `openpms-e2e/rel`, 커밋 R1–R4)를 씁니다(F23-2 뒤 web-platform 운영이 `checking`이라 대기열이 막힘). 개발 데이터 V1013이 web-platform에 검증 보드·기록과 QA 사용자 윤서아를 넣으므로 web-platform의 커밋·연결 수를 세는 테스트는 그 몫을 생각합니다(F22-1은 자기 커밋만 봄). 개발 데이터 마이그레이션이 여덟 개라 시스템 정보의 "최근 적용"(여덟 줄)에는 스키마의 가장 새 번호가 안 보일 수 있습니다(F11-H4는 `db/migration`·`db/dev`를 합친 가장 새 번호를 봄).
- 두 번째 작업 트리는 `dev.sh`에 `OPENPMS_DEV_NAME`·`OPENPMS_DEV_DB_PORT`·`OPENPMS_DEV_APP_PORT`를 주어 컨테이너를 따로 띄웁니다(예: `openpms-cicd`·55433·8091, 그 트리의 `backend/.env.local`에 적어 둠). 기본값(`openpms`·55432·8090)은 다른 세션이 쓰고 있을 수 있습니다. 화면은 `OPENPMS_API_URL`과 다른 vite 포트(`--port 5184 --strictPort`)로.

## 지금까지 (2026-10-20 기준)
- 범위: 작업 패키지 핵심 + 계획 화면(Gantt, 보드, 캘린더, 팀 플래너), 사내 도구. 기준 원본은 사용자의 자체 설치 OpenProject 17.9.1 Community(로컬 8080, 사용자 본인 계정).
- 원본 기능 패리티 100(필수 64/64). 스키마 V1–V19·V26–V29(V20–V25는 비워 둠), 개발 데이터 V1000–V1003·V1010–V1013(V1004–V1009는 비워 둠).
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
- CI/CD 연동 1단계(2.7, 2026-10-15–16, 브랜치 feat/cicd-phase1, 설계 `docs/superpowers/specs/2026-10-15-cicd-design.md`, 계획 `docs/superpowers/plans/2026-10-15-cicd-phase1.md`, REQ-CICD-001–009): 사내 GitLab·GitHub를 관리 › CI/CD 연동(`/admin/cicd`)에 등록하고 프로젝트 설정 › 저장소에서 붙이면, 커밋 메시지·MR/PR 제목·설명·소스 브랜치의 `#번호`로 작업의 **개발** 탭에 커밋·MR/PR이 이어짐. 마이그레이션 V26(`cicd_*` 표 6개, 모듈 `cicd`, 한때 ITSM V20·V21을 돌린 DB와 검사합이 부딪치지 않게 V20–V25를 건너뜀)과 개발 데이터 V1010. 권한 `cicd.view`·`cicd.link`·`cicd.manage`(모듈에 묶임)와 전역 `global.manage_cicd`, 새 역할 **개발 리더**(멤버 + 저장소 관리)·**CI/CD 연동 관리자**(전역, 연동 관리만). 받는 길은 서명한 웹훅(경로는 설계서의 `/hooks/cicd/…`가 아니라 `/api/v1/hooks/cicd/{hookKey}`: SPA 폴백과 운영 프록시가 `/api`만 백엔드로 보내서)과 5분 보완 폴링, 예약 작업 `cicd-process`(15초)·`cicd-poll`(5분)·`cicd-cleanup`(03:40). 토큰·웹훅 비밀은 `OPENPMS_SECRET_KEY`(32바이트 Base64)로 AES-256-GCM, 감사 로그는 로거 `openpms.audit`(감사 표 없음). 전달 키는 도구의 전달 id가 아니라 내용(`commit:저장소:sha`, `mr:저장소:번호:갱신 시각`)이라 웹훅과 폴링이 한 번만 쌓임. 붙지 않은 저장소의 이벤트는 처리 때 지움(붙인 뒤 폴링이 7일치를 채움). 매뉴얼은 07 16장(16.1–16.5), 10 6.12·9.18(도움말: `/admin/cicd` → 9.18, 개발 탭 → 16.2, 저장소 → 16.3). 설계서의 2단계(빌드·배포)는 2.9, 3단계(릴리스) 가운데 QA 검증 보드·릴리스 기록은 3.0에서 구현, 3단계의 PM 점검·릴리스 노트와 4단계(자동화)는 설계서만 있고, 5단계(변경 관리 연결)는 ITSM 제거로 **보류**.
- CI/CD 연동마다 허용 프로젝트(2.8, 2026-10-16, 브랜치 feat/cicd-allowed-projects, REQ-CICD-010): 2.7 최종 리뷰의 I3(어느 프로젝트의 개발 리더든 토큰이 볼 수 있는 저장소를 붙임)에 대한 사용자 결정. V27 `cicd_connection_projects`(이미 붙은 저장소의 프로젝트로 채움)와 V1011(새 개발 DB에서는 V27이 V1010보다 먼저 돌아 채울 것이 없으므로 개발 연동에 web-platform·mobile-app). `PUT /api/v1/admin/cicd/connections/{id}/projects`는 시스템 관리자만(위임받은 연동 관리자는 403, 목록에서는 볼 수 있는 프로젝트 이름과 `hiddenProjectCount`만). 프로젝트 쪽 연동 고르기·찾기·붙이기는 허용한 연동만이고 아니면 꺼진 연동과 같은 404(붙일 때 허용 행을 `FOR SHARE`로 잡고 다시 검사, 목록에서 뺄 때는 행을 먼저 지우고 저장소를 확인해 409). 새 연동은 빈 목록. 허용 프로젝트 이름은 한국어 Collator로 정렬(DB의 en_US 정렬은 한글 순서가 다름). 매뉴얼 10 6.13·9.18.10(새 절), 07 16.1·16.3. JUnit 229(3a8c7bd), e2e 147건 3a8c7bd에서 142 통과·5 건너뜀(SSO 4건은 IdP 없이·PERF-5)·실패 0.
- CI/CD 연동 2단계 — 빌드·배포 상태(2.9, 2026-10-16, 브랜치 feat/cicd-phase2, 018642b–3f3ffd4, 설계 `docs/superpowers/specs/2026-10-16-cicd-phase2-design.md`(보완 1–18), 계획 `docs/superpowers/plans/2026-10-16-cicd-phase2.md`, REQ-CICD-011–022): 개발 탭에 빌드 배지(커밋·MR 원본 브랜치마다 빌드 이름별 최신을 모아 실패 우선)와 환경 줄(✓·일부·확인 중·일부만 확인됨·확인 실패·첫 배포 이전·–), 커밋의 배포된 환경, 뗀 저장소 표시. 받는 곳: GitLab Pipeline·Deployment Hook, GitHub `workflow_run`·`deployment_status`(`inactive` 버림), 보완 폴링, 새 연동 종류 `jenkins`·`argocd`(표준 알림 JSON, 웹훅만, `tool_url`·`image_tag_pattern`, 허용 프로젝트에 붙은 저장소에만). V28 표 7개(`cicd_environments`·`cicd_pipelines`·`cicd_deployments`·`cicd_deployment_commits`·`cicd_environment_commits`·`cicd_environment_heads`·`cicd_first_deploy_checks`)와 `detached_at`·`merge_commit_sha`, V1012. 배포 반영 규칙: 성공만 바꿈, 늦은 옛 배포는 기록만, 이전 배포와 양방향 비교(한쪽 1,000개, 넘으면 `partial`), 실패는 1·2·4·8분 뒤 다시·5번째 `failed`(토큰 없음·꺼짐·GitHub 8 MiB 초과는 바로), 첫 배포는 연결된 커밋 200개 조상 확인. 프로젝트 설정 › **환경**(기본 개발·스테이징·운영, 별칭 소문자, 겹치면 422, 배포가 있으면 숨김, 미지정 배포를 별칭으로). 저장소 떼기가 기록을 남기고(다시 붙이기 `…/reattach`), 연동 삭제는 대신 끄기, 빌드 기록 정리(`…/pipelines/cleanup`, 그날 00:00 Asia/Seoul 앞). 환경·정리 경로는 API 토큰으로 닫힘. 매뉴얼 07 16.6·16.7, 10 6.14·9.18.11–13(도움말: 설정 › 환경 → 16.7). 경로 217개, 화면 54개(X17), 요구사항 172개. 새 이미지로 V27 → V28 스모크는 안 함(SQL로 V27 상태 DB에 V28·V1012를 적용해 확인).
- CI/CD 연동 3단계 — 릴리스 검증(3.0, 2026-10-20, 브랜치 feat/cicd-phase3, d936c42–a6e7ba8, 설계 `docs/superpowers/specs/2026-10-20-cicd-phase3-release-design.md`(보완 1–15), 계획 `docs/superpowers/plans/2026-10-20-cicd-phase3.md`, REQ-CICD-023–035): 프로젝트 메뉴 **릴리스**(모듈 `cicd`, 보기 `cicd.view`)의 **검증 보드**(X18) — 비교 원본(기본 스테이징)에 있고 대상(기본 운영)에 없는 커밋에 이어진 작업을 검증 대기·재검증 필요·실패·통과 칸으로, QA가 통과·실패(사유 1–2,000자, 실패는 볼 수 있는 담당자·공동 담당자에게 알림 `verification_failed`, 작업 활동에 저널 한 줄 `cause`, 작업 상태는 안 바꿈), 저장소마다 대상 첫 배포 앞의 커밋은 빼는 기준 시각과 손 제외 **이미 운영에 있음**(`cicd_board_exclusions`, 새 커밋이 오면 다시 보임), 버전 대조(이 프로젝트의 버전만, 빠짐 개발 중·코드 연결 없음, 끼어듦), 연결 없는 커밋, 알림 띠(`CicdEnvironmentSignals`, 개발 탭과 같은 판단). **릴리스 기록**(X19) — 운영 배포마다 읽기 전용 스냅숏(경고 `unverified`·`stale`·`failed`, `partial`, 내용 확인 실패, 볼 수 없는 작업은 수로만). 개발 탭 검증 줄. V29(`added_at` 백필·트리거, 표 4개 `cicd_verifications`·`cicd_board_exclusions`·`cicd_releases`·`cicd_release_items`, 알림 check, `cicd.manage` 역할에 `release.verify`, 새 역할 **QA** `55ecb595-…`(권한 6개, 같은 이름은 ` (기존)`)), V1013. 경로 6개(`/api/v1/projects/{p}/releases/…`, API 토큰에 읽기까지 닫힘, `CLOSED_PROJECT` 정규식), 경로 223개, 화면 56개(X18·X19), 요구사항 185개. 예산: 보드 p95 200 ms·SQL 18·27 KB(잰 값 9 ms·16·18 KB), 개발 탭 SQL 17 → 20(검증 1건, 잰 값 18). 매뉴얼 07 16.8·16.9, 10 6.15·9.18.14(도움말: `…/releases` → 16.8, `…/releases/records` → 16.9). 사용자 확인한 결정(2026-10-20): `broken`도 내용 확인 실패, 실패 사유가 활동에 보임, QA 권한 6개, 이 프로젝트 버전만 고름. 남은 결정은 01 6장 #53–#61(예: 비교가 이미 든 커밋만 돌려줘도 기록이 생길 수 있음 #58). 새 이미지로 V28 → V29 스모크는 안 함.
- 테스트(3.0): JUnit 484(a6e7ba8, 81개 클래스, 새 66건 UT-419–UT-484). e2e 160건(흐름 108·접근성 46·성능 6) 전체를 a6e7ba8(산출물 3.0 문서와 F11-H4 고침을 넣은 작업 트리)에서 IdP 없이 돌려 155 통과 + 5 건너뜀(SSO 4건·PERF-5), 실패 0(F24-1–3·A11Y X18·X19 4건·F22·F23·F17-H3 통과). 문서 단계의 첫 전체 실행은 154 통과·5 건너뜀·1 실패(F11-H4: V1013으로 개발 데이터 마이그레이션이 여덟 개가 되어 V29가 "최근 적용" 밖으로 밀림, 테스트 기대값을 고침).
- 테스트(2.9): JUnit 418(54c04ce #49 수정 뒤, 74개 클래스, 같은 판: 01 6장 #49. 239e9ce 후속 수정 뒤 417, 같은 판: #50–#52. 71e8bac 최종 리뷰 수정 뒤 410, 72개 클래스. 3f3ffd4에서 402, 71개 클래스, 새 173건). 71e8bac에서 e2e F22·F23·CI/CD 접근성 15건 다시 통과. e2e 153건(흐름 105·접근성 42·성능 6) 전체를 3f3ffd4에서 IdP 없이 돌려 148 통과 + 5 건너뜀(SSO 4건·PERF-5), 실패 0(F23-1–4·A11Y 환경 2건·F17-H3 통과). 개발 탭 서버 예산 150 ms·SQL 17·56 KB(잰 값 p95 11 ms·SQL 15·37 KB). 테스트(2.8): JUnit 229, e2e 147건 142 통과·5 건너뜀.
- 테스트(2.7): JUnit 220(4014802, 브랜치 전체 최종 리뷰 수정 뒤. 그 앞 c8896eb에서 212, CI/CD 12개 클래스 99건). e2e 147건(흐름 101·접근성 40·성능 6) 전체를 4014802에서 IdP 없이 돌려 142 통과 + 5 건너뜀(SSO 4건·PERF-5), 실패 0(F17-H3·F22-1–3 통과). 그 앞 병합 전 8e6b00b 전체(ITSM이 있던 157건)는 151 통과·5 건너뜀·1 실패(F17-H3: 매뉴얼 절이 없어서, 2.7 문서로 해소).
- 테스트(2.6까지): JUnit 121. e2e 137건 중 132 통과 + 5 건너뜀(PERF-5와 IdP 없이 건너뛴 SSO 4건, 2026-10-16, ITSM 제거 뒤 전체). 그 앞 2.3 보안 수정 뒤 전체(2026-10-13, b34418b)에서 132 통과 + 5 건너뜀(PERF-5와, IdP 포트 18081을 colima의 redpanda가 쓰고 있어 건너뛴 SSO 4건). colima를 끄고 IdP를 띄워 SSO 4건도 통과해, 모두 136 통과 + 1 건너뜀. e2e의 `axe()`는 검사 전에 마우스를 치우고 CSS 전환이 끝나기를 기다림(전환 중간 색이 대비 실패로 잡히던 F03-G7).
- 산출물 2.3(2026-10-13)에 반영한 것: BUG-015(API 토큰 필터의 퍼센트 인코딩 우회, 69c2760), BUG-016(사용자 관리자가 관리자 계정을 잠그거나 삭제, 0e229d6), BUG-017(로그인 응답 시간으로 계정 유무가 드러남, cdee894), 임시 비밀번호 보강(변경 토큰은 로그인이 검사한 해시일 때만·만료 처리의 잠금 순서·비밀번호가 든 레코드의 toString 가림·no-store 정확히 확인, 4889f8b). JUnit 116→121.
- 남은 일:
  - 손으로 할 것(사내 SMTP·SSO·웹훅·프록시, 임시 비밀번호를 실제 메신저로 전달, NVDA, 회사 서버 부하 측정, 원본 캡처로 레이아웃 비교, 실제 사내 GitLab·GitHub로 CI/CD 웹훅·폴링, `OPENPMS_SECRET_KEY` 교체 절차, V19 → V26 이미지 업그레이드 스모크, ITSM 제거 병합 뒤 e2e 전체, 실제 Jenkins·Argo CD로 빌드·배포 알림(10 9.18.11·9.18.12), 실제 GitLab·GitHub의 파이프라인·배포 웹훅과 비교 API, V27 → V28 이미지 업그레이드 스모크, 실제 도구의 배포로 릴리스 검증 보드·기록, V28 → V29 이미지 업그레이드 스모크("QA (기존)" 겹침 포함))
  - 정할 것(워크플로 '담당자일 때만' 조건을 화면에서 만들지, 템플릿 복사의 비공개 보기, 내보내기 시각 형식, CI/CD 다음 단계(3단계의 운영 배포 전 점검·릴리스 노트, 4단계 자동화·CI 관문 API)를 이어서 할지, 01 6장 #53–#61(3.0의 설계 판단과 #58 기록 조건). 01 6장 #42(웹훅 비밀의 멱등 보관, `/api/v1/admin/cicd/` 닫힌 경로)는 최종 리뷰 수정 4d79990에서 해결)
  - 테스트 빈틈(`replica/test-plan.md`의 "확인할 것")
- 이전 PC의 세션 스크래치 폴더에만 있던 브라우저 점검 스크립트 27묶음과 이미지 스모크 스크립트는 저장소에 없습니다. e2e로 옮기는 것이 다음 후보입니다.
