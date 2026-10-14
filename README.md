# openPMS

사내용 프로젝트 관리 시스템입니다. OpenProject 17.9.1의 작업 관리와 계획 기능(작업, 목록, Gantt, 보드, 캘린더, 팀 플래너, 알림, 관리)을 클린룸 방식으로 새로 만들었습니다. 원본 소스는 읽거나 옮기지 않았습니다.

- **프런트엔드**: `frontend/` — React 19, Vite, TypeScript, Tailwind 4
- **백엔드**: `backend/` — Spring Boot 4, Java 21, jOOQ, Flyway, PostgreSQL 17
- **배포**: 루트의 `Dockerfile`과 `compose.yaml` (앱 이미지 하나 + PostgreSQL)
- **산출물 문서**: `docs/deliverables/` (요구사항, 설계, 매뉴얼, 테스트 문서). 앱의 도움말 화면도 이 문서를 그대로 보여 줍니다.
- **분석·계획 기록**: `replica/` (기능 목록 `features.csv`, 개선 계획 `fixes.md`, 버그 `bugs.md`, 성능 측정 `perf.md`, 테스트 계획 `test-plan.md` 등)

운영 설치·업그레이드·백업은 `docs/deliverables/10-운영자매뉴얼.md`를 보세요. 이 문서는 개발 환경을 새 PC에 꾸리는 방법입니다.

## 새 PC에서 개발 환경 꾸리기

호스트에는 JDK·Gradle·PostgreSQL을 설치하지 않습니다. 모두 Podman 컨테이너에서 돕니다.

### 1. 필요한 것

| 도구 | 확인한 버전 | 용도 |
|---|---|---|
| Git (Windows는 Git Bash 포함) | — | `backend/scripts/dev.sh`는 bash 스크립트입니다. Windows에서는 Git Bash에서 실행합니다 |
| Podman | 6.0 | 개발 DB(PostgreSQL 17), 메일(Mailpit), JDK 21 빌드·실행, 테스트용 IdP. Windows·macOS는 `podman machine init && podman machine start`를 먼저 |
| Node.js | 24 | 프런트엔드 |
| Google Chrome | — | e2e 테스트가 설치된 Chrome(`channel: "chrome"`)을 씁니다 |
| Python 3 | 3.12 이상 | 산출물·측정 보조 스크립트(`backend/scripts/*.py`, `replica/*.py`). 필수는 아님 |

### 2. 처음 한 번

```bash
git clone <이 저장소 주소> openPMS && cd openPMS

# 백엔드: DB와 메일을 띄우고(처음이면 backend/.env.local에 개발 DB 비밀번호를 새로 만듦), 앱을 빌드해 실행
bash backend/scripts/dev.sh up
bash backend/scripts/dev.sh run          # http://127.0.0.1:8090 — 첫 실행에 마이그레이션(V1–V20)과 개발 데이터(V1000–V1004)가 들어감

# 복원 지점 만들기: JUnit과 e2e가 매번 이 상태로 되돌린 DB에서 돕니다(앱을 멈춘 채로 만들어야 함)
podman stop openpms-app
bash backend/scripts/dev.sh snapshot-db
bash backend/scripts/dev.sh run

# 프런트엔드
cd frontend
npm ci
npm run dev        # http://localhost:5173 — 브라우저 안의 가짜 데이터 계층(데모, 백엔드 없이 동작)
npm run dev:api    # http://localhost:5174 — 실제 백엔드(8090)에 붙는 화면. e2e는 이쪽을 씁니다
```

개발 계정은 개발 데이터에만 있고 비밀번호는 모두 `openpms!`입니다(예: 관리자 `seoyeon.kim@openpms.local`). 운영 이미지에는 이 계정이 없습니다.

e2e의 SSO 테스트에는 테스트용 IdP 컨테이너가 필요합니다(`frontend/` 폴더에서):

```bash
podman run -d --name openpms-idp --network openpms-dev -p 127.0.0.1:18081:18080 \
  -v "$PWD/e2e:/idp:ro" docker.io/library/node:24-alpine node /idp/fake-idp.mjs
```

SSO e2e(`f00-sso`)는 OIDC 콜백이 `OPENPMS_BASE_URL`(개발 환경은 `http://localhost:5173`)을 따라가므로, 5174와 8090에 더해 **5173 개발 서버(`npm run dev`)도 떠 있어야** 합니다. 없으면 SSO 케이스가 로그인 뒤 돌아올 곳을 찾지 못합니다.

### 3. 자주 쓰는 명령

```bash
bash backend/scripts/dev.sh run           # 백엔드를 다시 빌드해 실행 (백엔드를 고쳤으면 e2e 전에 꼭)
bash backend/scripts/dev.sh test          # JUnit (복원 지점의 복사본 DB에서)
bash backend/scripts/dev.sh test --tests com.openpms.wp.CoAssigneesIT
bash backend/scripts/dev.sh restore-db    # 개발 DB를 복원 지점으로 되돌림
bash backend/scripts/dev.sh perf-seed     # 작업 1,000건 측정용 프로젝트(perf-1000) 넣기
bash backend/scripts/dev.sh gradle jooqCodegen   # 마이그레이션을 바꾼 뒤 jOOQ 코드 다시 만들기
bash backend/scripts/dev.sh logs | down

cd frontend
npx tsc -b --noEmit                       # 타입 검사
npx playwright test                       # e2e 전체 (5174 + 8090 + openpms-idp 필요, SSO 케이스는 5173도, 파일마다 DB를 되돌림)
npx playwright test e2e/f18-co-assignees.spec.ts
npm run build                             # 운영용 화면 → backend/src/main/resources/static (dev.sh run이 jar에 넣음)
```

마이그레이션을 새로 만들었으면 복원 지점을 다시 만듭니다. 순서: 앱 정지 → `dev.sh reset-db` → `dev.sh run` → `dev.sh gradle jooqCodegen` → 앱 정지 → `dev.sh snapshot-db`. 개발 DB를 그대로 스냅샷하면 e2e가 남긴 데이터까지 들어갑니다.

### 4. 지금 상태 (2026-10-14)

- 원본 기능 패리티 100 (기능 142개, 필수 64/64), 스키마 V1–V20, JUnit 199건, e2e 147건 중 142 통과 + 5건 건너뜀(PERF-5와 테스트 IdP 없이 건너뜀 SSO 4건. IdP를 띄우면 146 통과 + 1건 건너뜀이 기대 결과이나 측정하지 않음). 이전 판: e2e 136건 통과 + 1건 의도적 건너뜀(느린 망 측정은 빌드된 화면에서만. 2.3 보안 수정 뒤 b34418b에서 전체 132 통과·5 건너뜀(SSO 4건은 IdP 포트 18081 충돌로 건너뜀)이었고 IdP를 띄워 SSO 4/4를 통과해 합계 136 통과·1 건너뜀)
- 개선 계획 `replica/fixes.md` 1–6번 완료, 7번(반복 작업) 보류
- 사내 개선 ITSM 1단계(서비스 데스크: 장애·서비스 요청 티켓, SLA 정책·서비스 달력, 요청자 포털 `/portal`, 처리 그룹, SLA 임박·위반 알림, 요청자 메일, REQ-ITSM-001–013) 완료. 서비스 데스크는 비공개 프로젝트에서만 켜고, 요청자는 멤버가 아니라 포털이 늘 요청자 본인의 티켓으로만 좁혀 보여 줍니다. 요청자 메일·SLA 알림은 `OPENPMS_JOBS_ENABLED=true`인 인스턴스가 보냅니다(`docs/deliverables/10-운영자매뉴얼.md` 9.18–9.20). 설계 `docs/superpowers/specs/2026-10-14-itsm-design.md`, 2~6단계(카탈로그·문제·CMDB·변경·지식 베이스)는 단계별 설계서만 있고 구현하지 않음
- 산출물 2.4: ITSM 1단계(서비스 데스크·SLA·요청자 포털)를 반영(`docs/deliverables/README.md`의 개정 이력, 새 영역 REQ-ITSM, 마이그레이션 V20, 07 사용자 매뉴얼 16·17장, 10 운영자 매뉴얼 6.12·9.18–9.20). 이전 판 산출물 2.3: 2.2 뒤 보안 점검에서 찾아 고친 BUG-015(API 토큰의 퍼센트 인코딩 우회)·BUG-016(사용자 관리자가 관리자 계정을 잠그거나 삭제)·BUG-017(로그인 응답 시간으로 계정 유무가 드러남)과 임시 비밀번호 후속 보강을 반영(`docs/deliverables/README.md`의 개정 이력, `replica/bugs.md`)
- 사내 개선 '임시 비밀번호'(관리자가 메일 없이 사용자를 만들거나 다시 발급하고 첫 로그인에서 새 비밀번호를 정하게 함, REQ-AUTH-005) 완료. 평문이 사용자가 바꾸거나 기한이 지난 뒤 다음 로그인 시도나 매일 03:30 정리 작업이 지울 때까지 DB에 남으므로 운영에서 `org.jooq` 로그를 DEBUG로 올리지 않습니다(`docs/deliverables/10-운영자매뉴얼.md` 9.2.7)
- 남은 일과 손으로 확인할 것: `replica/test-plan.md`의 "손으로 확인할 것"·"확인할 것", `replica/parity.md`
