# 서비스 데스크 템플릿 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 서비스 데스크를 원본으로 새 프로젝트를 만들면 SLA 정책·우선순위 행렬·카탈로그가 함께 복사되고 티켓은 복사되지 않으며, 개발 데이터에 "서비스 데스크 템플릿" 프로젝트를 둔다.

**Architecture:** `ProjectCopy.copy`가 원본에 모듈 `service_desk`가 있으면 새 메서드 `copyServiceDesk(template, target)`를 부른다(표마다 `insert … select` 한 번, 카탈로그 분류·항목은 새 id 매핑). `ProjectController.make`가 서비스 데스크 원본의 `workPackages` 복사를 422로 막는다. 화면(새 프로젝트·프로젝트 복사)은 원본이 서비스 데스크이면 **작업** 선택지를 숨기고 안내를 보인다. 가짜 데이터 계층도 같은 규칙.

**Tech Stack:** Spring Boot 4 / Java 21, jOOQ, Flyway(개발 데이터 V1006), PostgreSQL 17, React 19 + TS + TanStack Query, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-15-service-desk-template-design.md`

## Global Constraints

- 모듈 `service_desk`는 그대로 둔다. 서비스 데스크 여부는 모듈로만 판단한다.
- 원본에 `service_desk`가 있으면 SLA 정책·우선순위 행렬·카탈로그(분류·항목·양식 필드·승인 단계)를 `copy` 선택과 상관없이 **항상** 복사한다.
- 원본이 서비스 데스크이고 `copy`에 `workPackages`가 있으면 422, 필드 `copy`, 문구 "서비스 데스크의 티켓은 복사하지 않습니다."
- 처리 그룹·사용자 정의 필드·서비스 달력은 전체 공통 — 복사하지 않고 같은 id를 참조한다.
- 지금의 IT 서비스 데스크(`service-desk`, id `a02eb82b-c707-5a81-b0ce-71ea6d523f21`)는 템플릿으로 표시하지 않는다(포털이 `isTemplate` 프로젝트를 뺀다).
- 템플릿 프로젝트: 식별자 `service-desk-template`, 이름 "서비스 데스크 템플릿", 비공개, `is_template = true`, id `ee03a071-653f-5b50-a36d-ec87aa12d19b`(= `ids.py p-service-desk-template`).
- 화면 안내 문구: "서비스 데스크는 SLA·우선순위 행렬·카탈로그를 함께 복사하고 티켓은 복사하지 않습니다."
- 저장소 규칙(CLAUDE.md): JUnit은 `bash backend/scripts/dev.sh test`로만(절대 `dev.sh gradle test` 아님), prettier 금지, 코드 패치에 bash heredoc 금지, 커밋은 경로 지정, 화면 문구 한국어, 브랜드 검사 깨끗, 가짜 계층은 실제 API와 같은 규칙 + `SCHEMA` 올림, `seed.ts` TDZ 순환 주의, 07·10의 기존 번호 제목을 바꾸지 않음, 커밋 끝 줄 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. 원본 카탈로그 항목의 분류가 null(분류 없음)이면 복사본도 null — 다른 분류로 잘못 매핑되면 안 됨 → Task 1 테스트에 분류 없는 항목 포함.
2. 같은 트랜잭션에서 복사한 직후 새 데스크를 포털이 바로 보여야 함(Snapshot 갱신) — `ctx.refresh()` 뒤 `GET /portal/desks`에 새 데스크와 카탈로그 → Task 1 테스트.
3. 원본 항목이 원본 프로젝트에서 꺼진 유형이나 연결이 끊긴 사용자 정의 필드를 가리켜도 복사가 실패하지 않아야 함(복사본도 같은 상태로, 포털은 기존 규칙대로 거름) → Task 1 테스트에서 비활성 항목(`is_active = false`)도 그대로 복사되는지 확인.
4. 서비스 데스크가 아닌 원본의 복사 동작은 전혀 바뀌지 않아야 함(작업 복사 포함) → Task 1 회귀 테스트.
5. 템플릿에서 만든 데스크의 첫 요청에 SLA 정책이 붙어야 함(복사한 정책의 새 id를 가리킴) → Task 1 테스트.

---

### Task 1: 복사 규칙 (백엔드)

**Files:**
- Modify: `backend/src/main/java/com/openpms/project/ProjectCopy.java` (`copy(...)` 끝에서 서비스 데스크면 `copyServiceDesk` 호출, 새 private 메서드)
- Modify: `backend/src/main/java/com/openpms/project/ProjectController.java` (`make`: 원본이 서비스 데스크이고 `parts`에 `workPackages`면 422)
- Create: `backend/src/test/java/com/openpms/itsm/ServiceDeskTemplateIT.java`

**Interfaces:**
- Produces: `ProjectCopy.copyServiceDesk(UUID template, UUID target)` (private 또는 package-private), 422 `copy` "서비스 데스크의 티켓은 복사하지 않습니다."
- Consumes: `ItsmIds.MODULE`(= `"service_desk"`), jOOQ 테이블 `SLA_POLICIES`, `PRIORITY_MATRIX`, `CATALOG_CATEGORIES`, `CATALOG_ITEMS`, `CATALOG_ITEM_FIELDS`, `CATALOG_ITEM_APPROVAL_STEPS`; 테스트는 `ItsmFixture`(`login`, `newDesk`, `send`, `body`).

- [ ] **Step 1: 실패하는 테스트 작성** — `ServiceDeskTemplateIT`(`@SpringBootTest(properties = "openpms.jobs.enabled=false")`, `@ActiveProfiles("dev")`, `CatalogFormIT`의 setUp 방식). 준비: `f.newDesk(admin)`로 원본 데스크(SLA·행렬은 `newDesk`가 넣음), 카탈로그 API로 분류 1개 + 항목 3개(분류 있음·승인 단계 2개·양식 필드 1개 필수 / 분류 없음 / `isActive:false`). 테스트:
  - `copiesSlaMatrixAndCatalog`: `POST /api/v1/projects/{원본}/copy` `{"name":…,"identifier":…,"copy":["boards","queries"]}` → 201. 새 프로젝트의 `sla_policies`(type, priority, calendar, 분)·`priority_matrix` 행이 원본과 같은 값(id는 다름), 카탈로그 `GET /api/v1/projects/{새}/service-desk/catalog`의 분류 이름·항목(이름·유형·기본 우선순위·처리 그룹·`isActive`·position·필드·단계)이 같고, 분류 없는 항목은 `categoryId` null, 분류 있는 항목은 새 분류 id.
  - `theCopyIsIndependent`: 새 데스크의 항목 이름을 바꾸고 SLA 정책 하나를 지워도 원본 카탈로그·정책은 그대로.
  - `ticketsAreNotCopied`: `copy`에 `workPackages` → 422, `$.errors.copy` 또는 저장소의 필드 오류 형식으로 "서비스 데스크의 티켓은 복사하지 않습니다."(형식은 `ProjectController`의 기존 `ApiException.field` 응답을 따름). `isPublic:true` → 422(기존 문구).
  - `fromATemplate`: 원본을 `PATCH /api/v1/projects/{원본}` `{"isTemplate":true}`로 템플릿 표시 → `POST /api/v1/projects` `{"name":…,"identifier":…,"templateId":"{원본}","copy":[]}` → 201, 카탈로그·SLA 복사됨. 템플릿 표시를 끄고 정리.
  - `theNewDeskServesThePortal`: 새 데스크에 요청자(`ItsmFixture.REQUESTER`)가 볼 수 있게 1단계 규칙대로 둔 뒤(새 데스크는 비공개·요청자는 멤버 아님 — 포털은 모듈만 보면 됨) `GET /api/v1/portal/desks`에 새 데스크와 복사된 활성 항목이 있음. 승인 단계 없는 항목을 포털로 요청 → 201, `service_tickets.sla_policy_id`가 **새 데스크의** 정책 id.
  - `otherProjectsCopyAsBefore`: 서비스 데스크가 아닌 프로젝트(개발 데이터의 일반 프로젝트 또는 새로 만든 것)를 `copy:["workPackages"]`로 복사 → 201, 작업이 복사되고 `sla_policies`·카탈로그 행은 생기지 않음.
  - `copySqlDoesNotGrowWithItems`: (선택, 저장소에 SQL 세기 도구가 있으면 `PerfBudgetIT`의 방식으로) 항목 3개와 13개일 때 복사 요청의 SQL 수가 같음. 도구가 없으면 생략하고 보고서에 적는다.
  - 모든 테스트는 만든 프로젝트를 `@AfterEach`에서 지운다(관리자 `DELETE /api/v1/projects/{id}` 또는 저장소의 기존 정리 방식).
- [ ] **Step 2: 실패 확인** — `bash backend/scripts/dev.sh test --tests com.openpms.itsm.ServiceDeskTemplateIT`(dev.sh test가 인자를 넘기는지 `backend/scripts/dev.sh`에서 확인. 넘기지 않으면 전체 실행). 기대: 복사·422 테스트 FAIL.
- [ ] **Step 3: 구현**
  - `ProjectController.make`: `serviceDeskStaysPrivate(...)` 다음 줄에
    ```java
    if (template != null && template.enabledModules().contains(ItsmIds.MODULE) && parts.contains("workPackages"))
        throw ApiException.field("copy", "서비스 데스크의 티켓은 복사하지 않습니다.");
    ```
  - `ProjectCopy.copy(...)` 끝(질의 복사 뒤)에
    ```java
    if (java.util.Arrays.asList(src.getEnabledModules()).contains(com.openpms.itsm.ItsmIds.MODULE)) copyServiceDesk(template.id(), target);
    ```
  - `copyServiceDesk`: 
    ```java
    /** A service desk's own settings go with it: SLA policies, the priority matrix and the catalog (서비스 데스크 템플릿). Groups, custom fields and calendars are shared. */
    private void copyServiceDesk(UUID template, UUID target) {
        db.insertInto(SLA_POLICIES, SLA_POLICIES.PROJECT_ID, SLA_POLICIES.TYPE_ID, SLA_POLICIES.PRIORITY_ID, SLA_POLICIES.CALENDAR_ID,
                        SLA_POLICIES.FIRST_RESPONSE_MINUTES, SLA_POLICIES.RESOLVE_MINUTES)
                .select(db.select(DSL.val(target), SLA_POLICIES.TYPE_ID, SLA_POLICIES.PRIORITY_ID, SLA_POLICIES.CALENDAR_ID,
                        SLA_POLICIES.FIRST_RESPONSE_MINUTES, SLA_POLICIES.RESOLVE_MINUTES).from(SLA_POLICIES).where(SLA_POLICIES.PROJECT_ID.eq(template)))
                .execute();
        db.insertInto(PRIORITY_MATRIX, PRIORITY_MATRIX.PROJECT_ID, PRIORITY_MATRIX.IMPACT, PRIORITY_MATRIX.URGENCY, PRIORITY_MATRIX.PRIORITY_ID)
                .select(db.select(DSL.val(target), PRIORITY_MATRIX.IMPACT, PRIORITY_MATRIX.URGENCY, PRIORITY_MATRIX.PRIORITY_ID)
                        .from(PRIORITY_MATRIX).where(PRIORITY_MATRIX.PROJECT_ID.eq(template)))
                .execute();
        var categories = new HashMap<UUID, UUID>();
        for (var c : db.selectFrom(CATALOG_CATEGORIES).where(CATALOG_CATEGORIES.PROJECT_ID.eq(template)).fetch()) categories.put(c.getId(), UUID.randomUUID());
        var items = new HashMap<UUID, UUID>();
        var sourceItems = db.selectFrom(CATALOG_ITEMS).where(CATALOG_ITEMS.PROJECT_ID.eq(template)).fetch();
        for (var i : sourceItems) items.put(i.getId(), UUID.randomUUID());
        // one batch per table: the SQL count does not grow with the catalog
        var batch = new java.util.ArrayList<org.jooq.Query>();
        for (var c : db.selectFrom(CATALOG_CATEGORIES).where(CATALOG_CATEGORIES.PROJECT_ID.eq(template)).fetch())
            batch.add(db.insertInto(CATALOG_CATEGORIES).set(CATALOG_CATEGORIES.ID, categories.get(c.getId())).set(CATALOG_CATEGORIES.PROJECT_ID, target)
                    .set(CATALOG_CATEGORIES.NAME, c.getName()).set(CATALOG_CATEGORIES.POSITION, c.getPosition()));
        for (var i : sourceItems)
            batch.add(db.insertInto(CATALOG_ITEMS).set(CATALOG_ITEMS.ID, items.get(i.getId())).set(CATALOG_ITEMS.PROJECT_ID, target)
                    .set(CATALOG_ITEMS.CATEGORY_ID, i.getCategoryId() == null ? null : categories.get(i.getCategoryId()))
                    .set(CATALOG_ITEMS.NAME, i.getName()).set(CATALOG_ITEMS.DESCRIPTION_HTML, i.getDescriptionHtml()).set(CATALOG_ITEMS.DESCRIPTION_TEXT, i.getDescriptionText())
                    .set(CATALOG_ITEMS.TYPE_ID, i.getTypeId()).set(CATALOG_ITEMS.DEFAULT_PRIORITY_ID, i.getDefaultPriorityId())
                    .set(CATALOG_ITEMS.ASSIGNMENT_GROUP_ID, i.getAssignmentGroupId()).set(CATALOG_ITEMS.IS_ACTIVE, i.getIsActive()).set(CATALOG_ITEMS.POSITION, i.getPosition()));
        if (!batch.isEmpty()) db.batch(batch).execute();
        if (items.isEmpty()) return;
        // fields and steps: copied with insert … select joined to a values() map of old → new item ids
        var map = DSL.values(items.entrySet().stream().map(e -> DSL.row(e.getKey(), e.getValue())).toArray(org.jooq.Row2[]::new)).as("m", "old_id", "new_id");
        var oldId = map.field("old_id", UUID.class);
        var newId = map.field("new_id", UUID.class);
        db.insertInto(CATALOG_ITEM_FIELDS, CATALOG_ITEM_FIELDS.CATALOG_ITEM_ID, CATALOG_ITEM_FIELDS.CUSTOM_FIELD_ID, CATALOG_ITEM_FIELDS.POSITION, CATALOG_ITEM_FIELDS.IS_REQUIRED)
                .select(db.select(newId, CATALOG_ITEM_FIELDS.CUSTOM_FIELD_ID, CATALOG_ITEM_FIELDS.POSITION, CATALOG_ITEM_FIELDS.IS_REQUIRED)
                        .from(CATALOG_ITEM_FIELDS).join(map).on(oldId.eq(CATALOG_ITEM_FIELDS.CATALOG_ITEM_ID)))
                .execute();
        db.insertInto(CATALOG_ITEM_APPROVAL_STEPS, CATALOG_ITEM_APPROVAL_STEPS.CATALOG_ITEM_ID, CATALOG_ITEM_APPROVAL_STEPS.STEP_NO, CATALOG_ITEM_APPROVAL_STEPS.APPROVER_KIND)
                .select(db.select(newId, CATALOG_ITEM_APPROVAL_STEPS.STEP_NO, CATALOG_ITEM_APPROVAL_STEPS.APPROVER_KIND)
                        .from(CATALOG_ITEM_APPROVAL_STEPS).join(map).on(oldId.eq(CATALOG_ITEM_APPROVAL_STEPS.CATALOG_ITEM_ID)))
                .execute();
    }
    ```
    분류를 두 번 읽지 말고 첫 조회 결과를 변수에 담아 쓴다(위 코드의 두 번째 `selectFrom(CATALOG_CATEGORIES)`는 첫 결과로 바꿈). 컬럼 이름·타입은 생성 코드(`backend/src/main/generated`)로 확인한다(예: `POSITION`이 `Integer`인지 `Short`인지). 감사 컬럼(`created_at`/`updated_at`)은 기본값.
  - 가상 스레드 규칙: `synchronized`·`computeIfAbsent` 안 DB 호출 없음.
- [ ] **Step 4: 통과 확인** — `bash backend/scripts/dev.sh test` 전체. 기대: 기존 253 + 새 테스트 모두 통과.
- [ ] **Step 5: Commit** — `git add backend/src/main/java/com/openpms/project/ProjectCopy.java backend/src/main/java/com/openpms/project/ProjectController.java backend/src/test/java/com/openpms/itsm/ServiceDeskTemplateIT.java` → `feat(itsm): copying a service desk takes its SLA, priority matrix and catalog` / 본문 "서비스 데스크를 원본으로 새 프로젝트를 만들면 SLA 정책·우선순위 행렬·카탈로그(분류·항목·양식 필드·승인 단계)를 함께 복사하고, 티켓 복사는 422로 막습니다."

### Task 2: 개발 데이터 V1006 "서비스 데스크 템플릿"

**Files:**
- Create: `backend/src/main/resources/db/dev/V1006__dev_service_desk_template.sql`

**Interfaces:**
- Consumes: V1004·V1005의 IT 서비스 데스크(`a02eb82b-c707-5a81-b0ce-71ea6d523f21`) 행.
- Produces: 프로젝트 `ee03a071-653f-5b50-a36d-ec87aa12d19b`(`service-desk-template`), 분류 `799a9855-17ac-56cd-aacf-ad1d570638a7`(하드웨어)·`63e3b732-f361-5902-99e5-fb9cdef692cb`(계정·권한), 항목 `ebbbbc67-3a0a-5f54-8534-98692a917365`(노트북 신청)·`26ab166a-4e90-57e1-a8b5-3c877e2acfa4`(VPN 계정)·`239a3f5f-6b1d-5d48-b465-0d631c05c4e9`(공유 폴더 권한). Task 3의 가짜 시드와 Task 4의 e2e가 이 이름·id를 쓴다.

- [ ] **Step 1: SQL 작성** — 첫 줄 주석 "개발 데이터: 서비스 데스크 템플릿(서비스 데스크 템플릿 설계). IT 서비스 데스크의 설정을 담은 비공개 템플릿, 티켓 없음." 내용:
  - `insert into projects (id, identifier, name, description_html, description_text, is_public, is_template, enabled_modules)` — IT 서비스 데스크의 `enabled_modules`를 `select`로 가져옴, 설명 "<p>새 서비스 데스크를 만들 때 고르는 템플릿입니다. SLA 정책·우선순위 행렬·카탈로그가 함께 복사되고 티켓은 복사되지 않습니다.</p>".
  - `project_types`, `project_custom_fields`: IT 서비스 데스크 것을 `insert … select`.
  - 멤버: 김서연(관리자)만 관리자 역할로(V1004에서 멤버 넣는 방식을 그대로 따름). 템플릿은 관리자만 고칠 것이므로.
  - `sla_policies`, `priority_matrix`: IT 서비스 데스크 것을 `insert … select`(새 id는 기본값).
  - 카탈로그: 위 고정 id로 분류 2개·항목 3개를 V1005와 같은 값으로, `catalog_item_fields`·`catalog_item_approval_steps`도 V1005와 같게(노트북 신청: 노트북 기종 필수, 단계 1 관리자·2 그룹 책임자; VPN: 단계 1 관리자; 공유 폴더: 단계 없음).
- [ ] **Step 2: 적용 확인** — CLAUDE.md의 복원 지점 순서: 앱 정지 → `bash backend/scripts/dev.sh reset-db` → `bash backend/scripts/dev.sh run` → `podman exec openpms-db psql -U openpms -d openpms -c "select identifier, is_template, is_public from projects where identifier like 'service-desk%'"`(템플릿 1행 true/false) + 카탈로그 3항목·SLA 정책 수가 원본과 같은지 확인 → 앱 정지 → `bash backend/scripts/dev.sh snapshot-db` → `bash backend/scripts/dev.sh run`. 스키마 변경이 없으므로 jOOQ 코드 생성은 하지 않는다.
- [ ] **Step 3: 전체 JUnit** — `bash backend/scripts/dev.sh test`. 프로젝트 수·템플릿 수를 세는 기존 테스트가 깨지면 기대값을 V1006에 맞게 고치고(단언을 약하게 하지 않음) 보고서에 적는다. `PerfBudgetIT`의 포털 카탈로그 SQL은 템플릿이 포털에서 빠지므로 그대로여야 한다.
- [ ] **Step 4: Commit** — `feat(itsm): dev data service desk template (V1006)` / "IT 서비스 데스크의 설정(SLA·우선순위 행렬·카탈로그)을 담은 비공개 템플릿 프로젝트 \"서비스 데스크 템플릿\". 티켓 없음."

### Task 3: 화면과 가짜 데이터 계층

**Files:**
- Modify: `frontend/src/pages/projects/new.tsx` (템플릿 모드에서 고른 템플릿이 서비스 데스크면 `workPackages` 선택지를 숨기고 `parts`에서 빼고 안내)
- Modify: 프로젝트 복사 대화상자(`frontend/src/pages/projects/settings.tsx`의 복사 부분 — `projects.copy`를 부르는 곳을 찾음) — 같은 규칙
- Modify: `frontend/src/i18n/ko.ts` (안내 문구 1개)
- Modify: `frontend/src/api/fake/index.ts` (`createProject`/`copyTemplate`: 서비스 데스크 원본이면 `workPackages` → `fieldError("copy", "서비스 데스크의 티켓은 복사하지 않습니다.")`, SLA·행렬·카탈로그 복사)
- Modify: 가짜 시드(서비스 데스크 시드 파일 `frontend/src/api/fake/service-desk-seed.ts`와 프로젝트 시드) — "서비스 데스크 템플릿" 프로젝트(`isTemplate: true`, 비공개, IT 서비스 데스크와 같은 모듈·유형·SLA·행렬·카탈로그). 가짜 계층의 id 규칙(읽기 쉬운 id, 예: `p-service-desk-template`, `cc-tpl-hardware`, `ci-tpl-laptop`)을 따른다 — 실제 DB의 uuid5와 같은 이름.
- Modify: `frontend/src/api/fake/db.ts` (`SCHEMA` 올림)

**Interfaces:**
- Consumes: `Project.enabledModules`, `TEMPLATE_PARTS`, Task 1의 422 문구, Task 2의 이름.
- Produces: ko 키 `projectNew.serviceDeskCopy` = "서비스 데스크는 SLA·우선순위 행렬·카탈로그를 함께 복사하고 티켓은 복사하지 않습니다."

- [ ] **Step 1: 화면** — 고른 원본의 `enabledModules.includes("service_desk")`이면: 복사 선택지에서 `workPackages` 체크박스를 렌더하지 않고 보낼 `copy`에서 뺀다, 안내 문구를 선택지 위 `<p>`로(기존 `copyAlways` 안내 옆), 공개 체크가 이미 막혀 있는지 확인(서버 422가 칸 아래에 보이면 충분, 아니면 원본이 서비스 데스크일 때 공개 체크를 끔). 접근성: 안내는 fieldset 안 텍스트, 숨긴 체크박스는 DOM에서 제거.
- [ ] **Step 2: 가짜 계층** — 서버와 같은 순서·문구: 비공개 검사 → `workPackages` 422 → 복사 → 서비스 데스크면 SLA 정책·행렬·카탈로그(분류 새 id 매핑, 분류 없는 항목은 null, 필드·단계) 복사.
- [ ] **Step 3: 시드** — 템플릿 프로젝트와 그 설정. 시드가 db를 import하는 모듈을 import하지 않게(TDZ). `SCHEMA` 올림.
- [ ] **Step 4: 확인** — `cd frontend && npm run typecheck`, 브랜드 검사 `python3 /Users/duksang/.claude/plugins/cache/replica-skill/replica-skill/1.0.0/replica-brand/sweep.py frontend --config replica/brand.json`. 가짜 모드(`npm run dev`, 빈 포트)에서 김서연으로 새 프로젝트 › 템플릿에서 시작 › "서비스 데스크 템플릿" → **작업** 선택지 없음·안내 보임 → 만들기 → 새 프로젝트 설정 › 서비스 데스크에 SLA·카탈로그 → 최민지 포털에 새 데스크. 개발 서버를 끈다.
- [ ] **Step 5: Commit** — `feat(itsm): service desk template in the new-project and copy screens and the fake layer` / "원본이 서비스 데스크이면 작업 복사를 빼고 안내를 보입니다. 가짜 데이터 계층도 같은 복사 규칙과 서비스 데스크 템플릿 시드."

### Task 4: e2e F22와 접근성

**Files:**
- Create: `frontend/e2e/f22-service-desk-template.spec.ts`
- Modify: `frontend/e2e/a11y.spec.ts`

- [ ] **Step 1: e2e** — 직렬, 앞뒤 `resetDb()`(f21과 같은 방식).
  - F22-T1 김서연: `/projects/new` › 템플릿에서 시작 › "서비스 데스크 템플릿" → 복사 선택지에 "작업" 없음·안내 문구 보임 + axe → 이름 "인사 서비스 데스크", 식별자 `hr-desk` → 만들기 → 프로젝트 설정 › 서비스 데스크에 SLA 표와 카탈로그 "노트북 신청"·"VPN 계정"·"공유 폴더 권한".
  - F22-T2 최민지: `/portal`에 "인사 서비스 데스크"와 그 카탈로그 → "공유 폴더 권한" 요청 → 요청 상세 상태 "접수", 김서연(또는 처리자)으로 목록에서 그 티켓의 해결 기한(SLA 열)이 비어 있지 않음.
  - F22-T3 API: 템플릿에서 `copy:["workPackages"]`로 만들기 → 422 "서비스 데스크의 티켓은 복사하지 않습니다."(e2e `support.ts`의 `api()` 사용).
  - 포털에 "서비스 데스크 템플릿"이 보이지 않음(T2 안에서 확인).
- [ ] **Step 2: 접근성** — `a11y.spec.ts`에 "새 프로젝트 · 서비스 데스크 템플릿에서 시작"(안내가 보이는 상태).
- [ ] **Step 3: 실행** — `bash backend/scripts/dev.sh run`, `npm run dev:api`(5174), `npx playwright test e2e/f22-service-desk-template.spec.ts e2e/f21-service-catalog.spec.ts e2e/f20-service-desk.spec.ts e2e/a11y.spec.ts e2e/f17-help.spec.ts`, 그다음 전체 e2e 한 번(기준: 156건 중 151 통과 + 5 건너뜀, 이번에 F22·a11y 추가). 템플릿 수·프로젝트 목록을 세는 기존 스펙이 V1006 때문에 깨지면 원인을 보고 기대값을 고친다(단언을 약하게 하지 않음). 개발 서버를 끈다.
- [ ] **Step 4: Commit** — `test(e2e): F22 service desk template` / "서비스 데스크 템플릿에서 인사 서비스 데스크 만들기 → 포털에 새 데스크와 카탈로그, 요청에 SLA. 티켓 복사 422. 접근성."

### Task 5: 문서(산출물 2.6), 도움말, CLAUDE.md

**Files:**
- Modify: `docs/deliverables/01`~`10`, `docs/deliverables/README.md`, `README.md`, `CLAUDE.md`, `frontend/src/features/help/topics.ts`(필요할 때만), `docs/superpowers/specs/2026-10-14-itsm-phase3-problem-design.md`(개발 데이터 번호 V1006 → V1007)

산출물은 **백그라운드 서브에이전트**에게 맡기고 결과를 검토한 뒤 커밋한다.

- [ ] **Step 1: 서브에이전트** — 판 2.6. 01·02: REQ-ITSM-023~(서비스 데스크 설정 복사, 티켓 복사 금지, 서비스 데스크 템플릿), 03(복사 규칙), 04(`copy`의 422), 05(V1006), 06(새 프로젝트·복사 화면의 안내), 07: 17.1 뒤에 새 번호 절(예: 17.10 "템플릿에서 서비스 데스크 만들기") — 기존 번호 제목 유지, 10: 새 절(9.25 "서비스 데스크 템플릿 만들기와 쓰기", 운영 중인 데스크를 템플릿으로 표시하면 포털에서 사라진다는 경고 포함), 08·09(JUnit·e2e 수), README 2.6 행, CLAUDE.md(개발 데이터 V1000–V1006, 판 2.6, 테스트 수, ITSM 3단계 개발 데이터는 V1007부터).
- [ ] **Step 2: 검토** — `git diff docs/deliverables/07* docs/deliverables/10* | grep '^-#'` 비어 있음, 숫자가 문서끼리 맞음, 사실을 코드와 대조.
- [ ] **Step 3: 도움말** — 새 화면이 없으므로 `topics.ts`는 바꾸지 않아도 됨. `npx playwright test e2e/f17-help.spec.ts` 통과.
- [ ] **Step 4: Commit** — `docs: deliverables v2.6 (service desk template)`.

---

## 자체 점검

- **설계서 대응:** §2 복사 규칙 → Task 1(+ 가짜 Task 3). §3 템플릿 프로젝트 → Task 2(+ 가짜 시드 Task 3, 운영자 절차 Task 5). §4 화면 → Task 3. §5 테스트 → Task 1·4. §6 문서 → Task 5. §7 위험: 템플릿이 포털에 안 나옴 → Task 4 확인, 운영 데스크를 템플릿으로 표시하는 경고 → Task 5, 유형·필드 연결 먼저 복사 → 기존 `copy` 순서(유형·필드 연결이 먼저)와 Task 1.
- **이름 일관성:** 422 문구·안내 문구·템플릿 id/식별자가 Global Constraints와 각 Task에서 같다.
- **남겨 둔 판단:** 복사 대화상자의 정확한 위치(settings.tsx), dev.sh test의 `--tests` 지원 여부, SQL 세기 도구 유무는 구현자가 찾아 따른다고 적었다.
