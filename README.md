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
bash backend/scripts/dev.sh run          # http://127.0.0.1:8090 — 첫 실행에 마이그레이션(V1–V19, V26)과 개발 데이터(V1000–V1003, V1010)가 들어감

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

e2e의 CI/CD 테스트(`f22-cicd`, 2.9부터 `f23-cicd-builds`)에는 가짜 Git 서버 컨테이너가 필요합니다(`frontend/` 폴더에서). 없으면 3건(F23은 4건)을 건너뜁니다. F23은 가짜 서버의 GitLab 모드(비교·파이프라인·배포 경로)를 씁니다. 호스트의 18090이 다른 프로그램에 잡혀 있으면(예: colima) `-p 127.0.0.1:18095:18090`으로 띄우고 e2e에 `FAKE_GIT_URL=http://127.0.0.1:18095`를 줍니다.

```bash
podman run -d --rm --name openpms-cicd-git --network openpms-dev -p 127.0.0.1:18090:18090 \
  -v "$PWD/e2e:/e2e:ro" docker.io/library/node:24-alpine node /e2e/fake-git.mjs
```

같은 PC에서 두 번째 작업 트리를 따로 띄울 때는 `dev.sh`에 `OPENPMS_DEV_NAME`·`OPENPMS_DEV_DB_PORT`·`OPENPMS_DEV_APP_PORT`를 줍니다(예: `openpms-cicd`·55433·8091, 기본은 `openpms`·55432·8090). 그 작업 트리의 git에서 빠진 `backend/.env.local`에 적어 두면 잊지 않습니다. 화면은 `OPENPMS_API_URL=http://127.0.0.1:8091 npm run dev:api -- --port 5184 --strictPort`처럼 다른 포트로 띄웁니다.

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
npx playwright test                       # e2e 전체 (5174 + 8090 + openpms-idp + openpms-cicd-git 필요, SSO 케이스는 5173도, 파일마다 DB를 되돌림)
npx playwright test e2e/f18-co-assignees.spec.ts
npm run build                             # 운영용 화면 → backend/src/main/resources/static (dev.sh run이 jar에 넣음)
```

마이그레이션을 새로 만들었으면 복원 지점을 다시 만듭니다. 순서: 앱 정지 → `dev.sh reset-db` → `dev.sh run` → `dev.sh gradle jooqCodegen` → 앱 정지 → `dev.sh snapshot-db`. 개발 DB를 그대로 스냅샷하면 e2e가 남긴 데이터까지 들어갑니다.

### 4. 지금 상태 (2026-10-16)

- CI/CD 연동 2단계 2.9(2026-10-16, 브랜치 feat/cicd-phase2): 작업의 **개발** 탭에 빌드 결과(빌드 배지)와 어느 환경까지 배포됐는지(환경 줄). GitLab·GitHub의 파이프라인·배포 이벤트, 새 연동 종류 Jenkins·Argo CD(표준 알림, 웹훅만), 프로젝트 설정 › **환경**(별칭, 기본 개발·스테이징·운영), 배포마다 비교 API로 환경에 든 커밋을 계산. 저장소 떼기가 기록을 남기고(다시 붙이기), 연동 삭제는 대신 끄기, 빌드 기록 정리. 스키마 V1–V19·V26–V28, 개발 데이터 V1000–V1003·V1010–V1012. JUnit 402/402(3f3ffd4), e2e 153건(3f3ffd4에서 148 통과·5 건너뜀(SSO 4건은 IdP 없이·PERF-5)·실패 0). 최종 리뷰 수정(같은 판, 71e8bac): 응답하지 않는 도구 서버는 `cicd-process` 한 실행에 한 번만 부름, 뗀 저장소의 배포는 도구를 부르지 않음, 붙이기의 연동 고르기는 GitLab·GitHub만, 링크 없는 빌드 배지, 없어진 커밋은 첫 배포 확인에서 "들어 있지 않음", Jenkinsfile `ref`. JUnit 410/410(71e8bac), e2e F22·F23·CI/CD 접근성 15건 다시 통과. 후속 수정(같은 판, f29483d·86d86f1·239e9ce): 응답 없는 연동의 배포가 많아도 다른 연동은 같은 실행에서 반영, 첫 배포 조상 확인의 404는 저장소를 한 번 조회해 보일 때만 "들어 있지 않음"(토큰이 접근을 잃으면 확인 실패), 병합 커밋은 전체 번호로 저장하고 같음 비교(색인 사용). JUnit 417/417(239e9ce)
- CI/CD 연동마다 허용 프로젝트 2.8(2026-10-16, 브랜치 feat/cicd-allowed-projects): 연동마다 저장소를 찾고 붙일 수 있는 프로젝트를 시스템 관리자가 정함(REQ-CICD-010, 관리 › CI/CD 연동의 수정 대화상자, `PUT /api/v1/admin/cicd/connections/{id}/projects`). 목록에 없는 프로젝트에는 연동이 보이지 않음. 스키마 V1–V19·V26·V27, 개발 데이터 V1000–V1003·V1010·V1011. JUnit 229/229(3a8c7bd), e2e 147건(3a8c7bd에서 142 통과·5 건너뜀(SSO 4건은 IdP 없이·PERF-5)·실패 0)
- CI/CD 연동 1단계 2.7(2026-10-16, 브랜치 feat/cicd-phase1): 사내 GitLab·GitHub의 커밋·MR/PR을 `#번호`로 작업의 **개발** 탭에 잇기(관리 › CI/CD 연동, 프로젝트 설정 › 저장소, 서명한 웹훅 `POST /api/v1/hooks/cicd/{hookKey}`, 5분 보완 폴링). 스키마 V1–V19·V26(V20–V25는 비워 둠), 개발 데이터 V1000–V1003·V1010, 새 환경 변수 `OPENPMS_SECRET_KEY`(CI/CD 연동을 쓸 때만, `docs/deliverables/10-운영자매뉴얼.md` 9.18). JUnit 220/220(4014802), e2e 147건(4014802 전체에서 142 통과·5 건너뜀, SSO 4건은 IdP 없이·PERF-5). 설계서의 2–4단계는 설계서만 있고 5단계는 보류
- 2.6까지: 원본 기능 패리티 100 (기능 142개, 필수 64/64), 스키마 V1–V19, JUnit 121건, e2e 137건 중 132 통과 + 5건 건너뜀(PERF-5와 테스트 IdP 없이 건너뜀 SSO 4건, 2026-10-16 ITSM 제거 뒤 전체). 이전: e2e 136건 통과 + 1건 의도적 건너뜀(느린 망 측정은 빌드된 화면에서만. 2.3 보안 수정 뒤 b34418b에서 전체 132 통과·5 건너뜀(SSO 4건은 IdP 포트 18081 충돌로 건너뜀)이었고 IdP를 띄워 SSO 4/4를 통과해 합계 136 통과·1 건너뜀)
- 개선 계획 `replica/fixes.md` 1–6번 완료, 7번(반복 작업) 보류
- ITSM 제거 2.6(2026-10-16): 사용자 결정으로 ITSM 전체 제거(코드는 3cb9acd로 되돌림, V20·V21·V1004–V1006과 설계 문서 삭제). 매뉴얼 16–18장 등 ITSM 절은 이때 지움. CI/CD는 한때 ITSM V20·V21을 돌린 DB와 부딪치지 않게 V26 / V1010을 씀
- 산출물 2.9: CI/CD 연동 2단계 반영(요구사항 REQ-CICD-011–022, 04 `IF-CICD-017`–`026`·4.8 표준 알림, 05 V28, 화면 X17, 07 16.6·16.7, 10 6.14·9.18.11–13. `docs/deliverables/README.md`의 개정 이력)
- 산출물 2.8: 연동마다 허용 프로젝트 반영(요구사항 REQ-CICD-010, 04 `IF-CICD-016`, 05 V27, 07 16.1·16.3, 10 6.13·9.18.10. `docs/deliverables/README.md`의 개정 이력)
- 산출물 2.7: CI/CD 연동 1단계 반영(요구사항 3.24 REQ-CICD, 화면 X14–X16, 07 16장, 10 6.12·9.18. `docs/deliverables/README.md`의 개정 이력)
- 산출물 2.6: ITSM 제거 반영. 2.4·2.5의 ITSM 내용은 이 판에서 모두 지움(`docs/deliverables/README.md`의 개정 이력)
- 산출물 2.3: 2.2 뒤 보안 점검에서 찾아 고친 BUG-015(API 토큰의 퍼센트 인코딩 우회)·BUG-016(사용자 관리자가 관리자 계정을 잠그거나 삭제)·BUG-017(로그인 응답 시간으로 계정 유무가 드러남)과 임시 비밀번호 후속 보강을 반영(`docs/deliverables/README.md`의 개정 이력, `replica/bugs.md`)
- 사내 개선 '임시 비밀번호'(관리자가 메일 없이 사용자를 만들거나 다시 발급하고 첫 로그인에서 새 비밀번호를 정하게 함, REQ-AUTH-005) 완료. 평문이 사용자가 바꾸거나 기한이 지난 뒤 다음 로그인 시도나 매일 03:30 정리 작업이 지울 때까지 DB에 남으므로 운영에서 `org.jooq` 로그를 DEBUG로 올리지 않습니다(`docs/deliverables/10-운영자매뉴얼.md` 9.2.7)
- 남은 일과 손으로 확인할 것: `replica/test-plan.md`의 "손으로 확인할 것"·"확인할 것", `replica/parity.md`
