# 설계: ITSM 2단계 — 서비스 카탈로그와 승인

- 날짜: 2026-10-14
- 상태: 설계 합의(구현 계획 전)
- 선행: 1단계(`2026-10-14-itsm-design.md`) — 서비스 데스크 프로젝트, `service_tickets`, SLA, 포털
- 관련 요구사항: REQ-ITSM-1xx(이 단계에서 번호를 정한다)

## 1. 목적

직원이 포털에서 "노트북 신청", "VPN 계정", "공유 폴더 권한"처럼 정해진 **서비스 항목**을 골라 항목별 양식으로 요청하고, 필요하면 **승인**을 거친 뒤 처리 그룹에 넘어가게 한다. 승인 엔진은 5단계 변경 관리의 CAB 승인에서도 다시 쓴다.

### 합의한 것

| 항목 | 결정 |
| --- | --- |
| 양식 | 카탈로그 항목마다 기존 작업 패키지 사용자 정의 필드를 골라 양식을 만든다. 새 필드 엔진은 만들지 않는다 |
| 승인자 | 요청자의 소속 관리자, 처리 그룹 책임자 두 가지 |
| 승인 규칙 | 단계 순서대로 진행하고, 같은 단계에 여러 명이면 한 명만 승인하면 다음으로 간다. 한 명이라도 반려하면 요청이 끝난다 |
| SLA | 승인 대기 동안 SLA는 멈춘다(1단계 `sla_pause` 상태 재사용) |

### 범위 밖

- 금액·조건에 따른 분기 승인(예: 100만 원 이상이면 임원 승인)
- 요청 하나로 여러 처리 그룹에 하위 작업 자동 생성
- 승인 위임·대리 승인, 메일 답장으로 승인
- 장바구니(여러 항목을 한 번에 요청)

## 2. 데이터 (마이그레이션 V21 `service_catalog.sql`, 개발 데이터 V1005)

번호는 1단계가 V20을 쓴 뒤를 가정한다. 그 사이에 다른 마이그레이션이 들어오면 다음 번호로 민다.

- `users`에 `manager_id uuid references users (id) on delete set null`을 더한다. 자기 자신은 안 된다(check).
- `user_groups`에 `lead_user_id uuid references users (id) on delete set null`을 더한다(처리 그룹 책임자).
- `catalog_categories`: `id`, `project_id`(서비스 데스크), `name`, `position`. unique `(project_id, name)`
- `catalog_items`
  - `id`, `project_id`, `category_id`, `name`, `description_html`, `description_text`
  - `type_id`(기본 "서비스 요청"), `default_priority_id`, `assignment_group_id`(받는 처리 그룹)
  - `is_active`(포털에 보이는지), `position`
  - unique `(project_id, name)`
- `catalog_item_fields`: `catalog_item_id`, `custom_field_id`, `position`, `is_required`(항목별 필수 여부). PK `(catalog_item_id, custom_field_id)`. 여기 든 필드는 그 항목의 포털 양식에 보인다(`custom_fields.portal_visible`과 별개로, 항목 양식에서는 항상 보임)
- `catalog_item_approval_steps`: `catalog_item_id`, `step_no`(1부터), `approver_kind`(`requester_manager` | `group_lead`). PK `(catalog_item_id, step_no, approver_kind)`. 같은 `step_no`에 두 종류를 두면 "둘 중 한 명"이 된다
- `service_tickets`에 `catalog_item_id uuid references catalog_items (id) on delete set null`을 더한다
- `ticket_approvals` (요청마다 만든 승인 단계)
  - `id`, `work_package_id`, `step_no`, `approver_id`, `approver_kind`
  - `decision`(`pending` | `approved` | `rejected` | `skipped`), `decided_at`, `comment`
  - unique `(work_package_id, step_no, approver_id)`
  - 인덱스: `(approver_id) where decision = 'pending'`(내 승인함)
- 상태: "승인 대기"(`sla_pause = true`), "반려"(`is_closed = true`)를 더하고 워크플로를 잇는다. 요청이 승인을 마치면 "접수"로 간다

## 3. 승인 동작 (`com.openpms.itsm.Approvals`)

### 요청할 때

1. 항목의 승인 단계를 읽어 단계마다 승인자를 정한다.
   - `requester_manager`: `users.manager_id`
   - `group_lead`: 항목 처리 그룹의 `lead_user_id`
2. 승인자를 정할 수 없거나(관리자 없음, 책임자 없음) 승인자가 요청자 자신이면 그 사람은 `skipped`로 남긴다. 한 단계의 모든 승인자가 `skipped`이면 그 단계는 건너뛴다. 건너뛴 사실은 저널에 남긴다.
3. 승인 단계가 하나라도 남으면 상태를 "승인 대기"로 두고, 1단계 승인자에게 알림·메일을 보낸다. 없으면 바로 "접수"로 간다.

### 결정할 때

- 승인자는 자기 `pending` 행만 결정할 수 있다. 조건부 `update ... where decision = 'pending' returning`으로 처리해 두 사람이 동시에 눌러도 한 번만 반영한다.
- 승인: 같은 단계의 다른 `pending`은 `skipped`로 바꾸고 다음 단계로 간다. 마지막 단계면 "접수"로 보내고 SLA를 다시 움직인다.
- 반려: 남은 `pending`을 모두 `skipped`로 바꾸고 "반려"로 닫는다. 사유(필수)는 요청자에게 보이는 코멘트로 남긴다.
- 승인자를 바꿔야 하면(퇴사 등) `handle_service_tickets` 권한이 있는 처리자가 `pending` 행의 승인자를 다른 사람으로 바꾼다. 저널에 남긴다.
- 모든 결정과 변경은 작업 패키지 저장과 같은 트랜잭션에서 하고 저널에 기록한다.

## 4. 화면

### 포털

- 첫 화면에 카탈로그(분류별 카드)와 "기타 문의·장애 신고"를 둔다.
- 항목을 고르면 항목 설명과 양식(항목 필드 + 제목·설명·첨부)을 보여 준다. 필수 검사는 서버가 다시 한다.
- 내 요청 상세에 승인 진행(단계, 승인자 이름, 결정, 시각)을 보여 준다.
- **내 승인함**(`/portal/approvals`): 승인자는 서비스 데스크 멤버가 아니어도 된다. 내가 `pending` 승인자인 요청의 제목, 요청자, 항목, 양식 값만 보고 승인·반려한다. 권한은 `ticket_approvals.approver_id = 현재 사용자`로만 준다. 남의 요청은 404.

### 처리자·관리 화면

- 프로젝트 설정 › 서비스 데스크 › 카탈로그: 분류, 항목, 항목 양식(필드 고르기·순서·필수), 승인 단계
- 관리 › 사용자: 관리자(상사) 지정. 관리 › 그룹: 책임자 지정
- 작업 패키지 상세에 승인 패널(진행 상황, 승인자 바꾸기)
- 목록 필터: 카탈로그 항목, 승인 상태

### SSO에서 관리자 받기 (선택)

환경 변수 `OPENPMS_OIDC_MANAGER_CLAIM`을 정하면, 로그인할 때 그 클레임의 값(관리자 메일)으로 `manager_id`를 맞춘다. 그 메일의 사용자가 없으면 바꾸지 않는다. 비워 두면 관리자가 화면에서 정한다.

## 5. 보안

- 승인함과 승인 API는 승인자 본인만. 포털 상세와 같은 404 규칙
- 승인자에게 보이는 범위는 요청 제목·요청자·항목·양식 값·첨부 목록까지. 내부 코멘트와 다른 승인자의 코멘트는 숨긴다(결정 여부와 이름만)
- 요청자가 자기 요청을 승인할 수 없다(`skipped` 처리)
- 처리 그룹 책임자나 관리자 지정을 바꿔도 이미 만든 `pending` 행은 바뀌지 않는다(처리자가 명시적으로 바꿈)

## 6. 테스트

- JUnit
  - `ApprovalsTest`: 승인자 정하기(관리자 없음, 책임자 없음, 본인), 단계 건너뛰기
  - `ApprovalsIT`: 승인·반려·동시 결정·승인자 바꾸기, 승인 대기 동안 SLA 멈춤
  - `PortalApprovalSecurityIT`: 남의 승인함·결정 404, 승인자에게 내부 코멘트 없음
  - `CatalogFormIT`: 항목별 필수 필드, 비활성 항목은 접수 불가
  - `PerfBudgetIT`: 포털 카탈로그, 내 승인함
- e2e: 직원이 항목 요청 → 관리자가 포털 승인함에서 승인 → 그룹 책임자 승인 → 처리 그룹에 접수 → 해결. 반려 흐름 하나
- 가짜 데이터 계층: 카탈로그·승인 시드(`fake/service-desk-seed.ts`), `SCHEMA` 올림

## 7. 문서

01·02(REQ), 04(포털 카탈로그·승인 API), 05(V21), 06(포털 카탈로그·승인함), 07(새 절: 서비스 요청하기, 승인하기), 10(새 절: 카탈로그 관리, 관리자·책임자 지정, `OPENPMS_OIDC_MANAGER_CLAIM`), 08·09. 도움말 `topics.ts` 연결.

## 8. 위험과 대응

| 위험 | 대응 |
| --- | --- |
| 관리자 정보가 비어 있어 승인이 비거나 건너뛰어짐 | 건너뛴 사실을 저널과 처리자 화면에 표시, 관리 › 사용자에 "관리자 없음" 필터 |
| 승인자가 응답하지 않아 요청이 멈춤 | 승인 대기 3근무일이 지나면 승인자에게 다시 알리고 처리자 목록에 "승인 지연" 표시(`SlaJobs`에 더함) |
| 승인함이 멤버 아닌 사람에게 두 번째 진입로가 됨 | 승인 API를 포털과 같은 별도 컨트롤러 규칙으로, `PortalApprovalSecurityIT`로 고정 |
