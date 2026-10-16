# ITSM 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ITSM(서비스 데스크·SLA·포털·카탈로그·승인)을 코드·DB·테스트·문서에서 모두 걷어내고 ITSM 이전(커밋 3cb9acd)의 동작으로 돌아간다.

**Architecture:** ITSM 이전 커밋 `3cb9acd`와 지금 `main` 사이의 코드 변경은 ITSM뿐이다(예외: `frontend/e2e/f11-admin.spec.ts`의 F11-H4를 마이그레이션 번호에 무관하게 만든 일반 개선). 그래서 코드 경로를 `3cb9acd` 상태로 되돌리고, 예외 파일은 main 것을 유지한다. 실제 설치에 V20·V21이 적용된 곳은 없으므로(사용자 확인) 마이그레이션 파일을 지우고 개발 DB를 새로 만든다.

**Tech Stack:** Spring Boot 4 / Java 21, jOOQ, Flyway, PostgreSQL 17, React 19 + TS, Playwright.

**Spec:** 사용자 결정(2026-10-16): "ITSM 기능 전체 제거", 실제 설치 없음(개발용만), 서비스 데스크 템플릿 브랜치 삭제(완료), ITSM 설계서·계획서·산출물 내용 모두 삭제.

## Global Constraints

- 기준 커밋: ITSM 이전 `3cb9acd`. 되돌린 뒤 스키마는 V1–V19, 개발 데이터는 V1000–V1003.
- 남기는 것: `frontend/e2e/f11-admin.spec.ts`(main 버전, F11-H4가 저장소의 가장 큰 스키마 번호를 읽음). 동료 세션의 CI/CD 문서(`docs/superpowers/specs/2026-10-15-cicd-design.*`, `docs/superpowers/plans/2026-10-15-cicd-phase1.md`)와 HTML 생성 스크립트 `docs/superpowers/scripts/`(CI/CD 문서도 같은 생성기를 씀 — Ruling R1).
- 가짜 데이터 계층 `SCHEMA`는 지금(17)보다 큰 값(19)으로 올려 ITSM 시드를 가진 브라우저가 새로 시드를 받게 한다.
- 저장소 규칙(CLAUDE.md): JUnit은 `bash backend/scripts/dev.sh test`로만, prettier 금지, 코드 패치에 bash heredoc 금지, 커밋은 경로 지정, 브랜드 검사 깨끗, 커밋 끝 줄 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 작업 폴더는 `/Users/duksang/claudeWorkspace/openPMS-rmitsm`(브랜치 `chore/remove-itsm`)만. 메인 체크아웃은 고치지 않는다.

## Review Focus

1. 되돌린 코드에 ITSM 흔적이 남지 않음: `grep -ri "itsm\|service_desk\|serviceDesk\|portal\|sla_\|catalog\|approval"`이 ITSM 이전에도 있던 것만 남김.
2. ITSM 이후 main에만 있던 일반 개선을 잃지 않음: `git log 3cb9acd..main`의 비-ITSM 커밋 확인(지금 아는 것은 F11-H4뿐).
3. jOOQ 생성 코드가 V19 스키마와 일치(새로 만든 DB에서 `jooqCodegen` 결과가 3cb9acd의 생성 코드와 같음).
4. 가짜 데이터 계층: 이미 ITSM 시드를 가진 브라우저가 깨지지 않음(SCHEMA 19).
5. 테스트 수가 ITSM 이전 기준과 맞음(JUnit 121, e2e 137건 안팎 — 실제 실행 결과를 기록).

---

### Task 1: 코드·DB·테스트 되돌리기

**Files:** `backend/src/**`(main, test, generated, resources), `frontend/src/**`, `frontend/e2e/**`(f11-admin.spec.ts 제외), `.env.example`, `replica/perf.md`, `frontend/src/api/fake/db.ts`(SCHEMA)

- [ ] **Step 1:** `git checkout 3cb9acd -- backend/src frontend/src frontend/e2e .env.example replica/perf.md` 후 `git checkout main -- frontend/e2e/f11-admin.spec.ts`. 3cb9acd 이후에 새로 생긴 파일(ITSM 파일)은 checkout으로 지워지지 않으므로 `git diff --name-status 3cb9acd HEAD -- backend/src frontend/src frontend/e2e`에서 `A`(추가) 파일을 `git rm`으로 지운다(f11 제외 확인).
- [ ] **Step 2:** `frontend/src/api/fake/db.ts`의 `SCHEMA`를 19로(주석: ITSM 시드를 가진 브라우저를 새로 시드).
- [ ] **Step 3:** 확인: `git diff --stat 3cb9acd -- backend/src frontend/src frontend/e2e .env.example replica/perf.md`가 f11-admin.spec.ts와 db.ts만 보여야 한다.
- [ ] **Step 4:** DB: 앱 정지 → `bash backend/scripts/dev.sh reset-db` → `OPENPMS_JOBS_ENABLED=true bash backend/scripts/dev.sh run` → `flyway_schema_history`의 최대 버전 19와 1003 확인 → `bash backend/scripts/dev.sh gradle jooqCodegen`(CLAUDE.md의 방법) → 생성 코드가 3cb9acd와 같은지 `git status backend/src/main/generated`(차이가 있으면 원인 기록) → 앱 정지 → `bash backend/scripts/dev.sh snapshot-db` → `OPENPMS_JOBS_ENABLED=true bash backend/scripts/dev.sh run`.
- [ ] **Step 5:** `bash backend/scripts/dev.sh test` 전체 통과(수 기록). `cd frontend && npm run tokens && npm run typecheck`, 브랜드 검사 `sweep.py frontend`·`sweep.py backend`(`--config replica/brand.json`).
- [ ] **Step 6:** e2e 전체: `npm run dev:api`(5174) → `npx playwright test`(수와 건너뛴 이름 기록) → 개발 서버 정지.
- [ ] **Step 7: Commit** — `chore: remove ITSM (service desk, SLA, portal, catalog, approvals)` / "ITSM 1·2단계를 걷어내고 ITSM 이전(3cb9acd)의 코드로 되돌립니다. 마이그레이션 V20·V21과 개발 데이터 V1004·V1005 삭제, 가짜 데이터 SCHEMA 19. F11-H4의 일반 개선은 유지."

### Task 2: 문서

**Files:** `docs/superpowers/specs/*itsm*`, `docs/superpowers/plans/*itsm*`, `docs/superpowers/plans/2026-10-15-service-desk-template.md`, `docs/superpowers/specs/2026-10-15-service-desk-template-design.md`, `docs/deliverables/*`, `README.md`, `CLAUDE.md`

산출물은 **백그라운드 서브에이전트**에게 맡기고 결과를 검토한 뒤 커밋한다.

- [ ] **Step 1:** ITSM 설계서·계획서·HTML과 템플릿 설계·계획을 `git rm`(스크립트는 남김, Ruling R1).
- [ ] **Step 2:** 산출물: `git checkout 3cb9acd -- docs/deliverables` 뒤 개정 이력에 2.4(ITSM 1단계 반영)·2.5(ITSM 2단계 반영)·2.6(ITSM 제거, 2026-10-16) 행을 덧붙인다(본문에는 ITSM 내용 없음). 테스트 수는 Task 1의 실제 결과.
- [ ] **Step 3:** README·CLAUDE.md: ITSM 관련 줄과 2.4·2.5의 ITSM 기록을 걷어내고(판은 2.6, 스키마 V1–V19, 개발 데이터 V1000–V1003, 테스트 수), ITSM 이후에 더한 일반 규칙(예: `.playwright-mcp` 정리, 동료 세션 공유)은 유지. 도움말 번호 규칙 문장은 그대로 두되 "ITSM을 걷어낼 때 16–18장 등을 지움(2.6)"을 한 줄 남긴다. 동료 세션의 CI/CD 계획 번호(V20·V1004부터)도 적는다.
- [ ] **Step 4:** 확인: `grep -ril "itsm\|서비스 데스크\|REQ-ITSM" docs/deliverables README.md CLAUDE.md`가 개정 이력의 줄만 남김. `npx playwright test e2e/f17-help.spec.ts` 통과.
- [ ] **Step 5: Commit** — `docs: remove ITSM from specs, plans and deliverables (v2.6)`.
