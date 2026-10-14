# 설계: ITSM 5단계 — 변경 관리

- 날짜: 2026-10-14
- 상태: 설계 합의(구현 계획 전)
- 선행: 1단계(서비스 데스크), 2단계(승인 엔진 `ticket_approvals`), 4단계(CMDB `work_package_cis`)
- 관련 요구사항: REQ-ITSM-4xx(이 단계에서 번호를 정한다)

## 1. 목적

배포·설정 변경·장비 교체 같은 **변경**을 위험도에 맞는 승인을 거쳐 정해진 시간 창에 실행하고, 같은 구성 항목(CI)을 동시에 건드리는 변경을 막는다. 실행 결과와 사후 검토를 남겨 장애·문제와 잇는다.

### 합의한 것

| 항목 | 결정 |
| --- | --- |
| 저장 | 변경 = 작업 패키지 유형 "변경" + 1:1 확장 `change_requests` |
| 변경 종류 | 표준(사전 승인, 승인 없음) · 일반(CAB 승인) · 긴급(긴급 CAB가 빠르게 승인, 사후 검토 필수) |
| 승인 | 2단계 승인 엔진을 쓴다. 단계 순서 + 단계 안에서는 한 명 |
| 충돌 | 같은 CI에 실행 시간이 겹치는 변경이 있으면 막는다. 긴급 변경만 사유를 남기고 허용한다 |
| 변경 금지 기간 | 이번 단계에 두지 않는다 |

### 범위 밖

- 변경 금지 기간(블랙아웃)
- 위험도 자동 점수 계산(설문 기반). 위험도는 사람이 고른다
- CI/CD 도구와의 자동 연동(배포 파이프라인이 변경을 만들고 닫기). API·웹훅으로 나중에 붙일 수 있다
- 릴리스 관리(변경 여러 개 묶음)

## 2. 데이터 (마이그레이션 V24 `change_management.sql`, 개발 데이터 V1008)

- 유형 "변경"과 상태 "작성 → 검토 → 승인 대기 → 예정 → 실행 중 → 완료 → 사후 검토 → 종료", 그리고 "반려"·"취소"(`is_closed`)
- `standard_change_templates`
  - `id`, `project_id`, `name`, `description_html`, `implementation_plan_html`, `backout_plan_html`, `default_risk`, `max_duration_minutes`, `is_active`
  - 변경 관리자가 만든 "미리 승인된" 절차. 표준 변경은 반드시 템플릿에서 만든다
- `change_requests` (작업 패키지와 1:1)
  - `work_package_id` PK, FK on delete cascade
  - `change_class`(`standard` | `normal` | `emergency`), `template_id`(표준일 때 not null)
  - `risk`(`low` | `medium` | `high`), `impact_html`, `implementation_plan_html`, `backout_plan_html`, `test_plan_html`
  - `planned_start timestamptz`, `planned_end timestamptz`(`planned_end > planned_start`), `actual_start`, `actual_end`
  - `outcome`(`successful` | `partial` | `failed` | `backed_out`), `review_html`, `reviewed_at`
  - `conflict_override_reason text`(긴급 변경이 충돌을 무시할 때 필수)
  - 인덱스: `(planned_start, planned_end)`
- `change_settings` (프로젝트별 한 행): `cab_group_id`, `ecab_group_id`(user_groups)
- 2단계 `ticket_approvals`·`catalog_item_approval_steps`의 `approver_kind`에 `group_member`(그룹의 아무나 한 명)를 더한다. 변경 승인은 `change_approval_rules`로 정한다
- `change_approval_rules`: `project_id`, `change_class`, `risk`, `step_no`, `approver_kind`(`group_lead` | `group_member`), `group_source`(`cab` | `ecab` | `owner_group`). 예: 일반·고위험 = 1단계 CI 책임 그룹 책임자, 2단계 CAB 한 명. `owner_group`은 변경에 걸린 CI들의 책임 그룹을 모두 모아, 그 책임자들을 같은 단계에 둔다(한 명이 승인하면 다음 단계). 책임 그룹이 없는 CI는 빼고, 하나도 없으면 그 단계는 건너뛴다(2단계 규칙과 같음)
- 충돌 방지 잠금: 앱이 트랜잭션 안에서 `pg_advisory_xact_lock(hashtext('ci:' || ci_id))`를 CI id 순서대로 잡은 뒤 겹침을 검사한다(교착을 피하려고 순서 고정). `synchronized`는 쓰지 않는다

## 3. 동작 (`com.openpms.itsm.Changes`)

### 만들기

- 일반·긴급: 처리자가 변경을 만들고 위험도, 계획, 실행 시간, CI(`work_package_cis`)를 채운다
- 표준: 템플릿을 골라 만든다. 계획·위험도가 템플릿에서 채워지고, 실행 시간은 `max_duration_minutes`를 넘을 수 없다
- 장애·문제 상세에서 "변경 만들기"로 만들면 관계(`relations`의 `relates`)와 CI를 이어 받는다

### 충돌 검사 (실행 시간이나 CI를 저장할 때마다)

1. 변경의 CI들에 advisory lock을 잡는다
2. 같은 CI에 `[planned_start, planned_end)`가 겹치고 상태가 취소·반려·종료가 아닌 다른 변경을 찾는다
3. 있으면: 표준·일반은 저장을 거부하고 겹치는 변경 목록(번호, 제목, 시간, CI)을 돌려준다. 긴급은 `conflict_override_reason`이 있어야 저장하며, 겹친 변경의 담당자에게 알린다
4. 검사와 저장은 같은 트랜잭션이다. 두 사람이 동시에 같은 CI에 일정을 잡아도 하나만 성공한다

### 승인

- "검토 → 승인 대기"로 갈 때 `change_approval_rules`로 승인 단계를 만든다(2단계 엔진). 표준은 승인 없이 "예정"으로 간다
- 승인을 마치면 "예정". 반려하면 "반려"
- 승인 뒤에 실행 시간·CI·위험도를 바꾸면 승인을 다시 받는다(진행 중이던 승인은 `skipped`, 새로 만듦). 저널에 남긴다
- 긴급: ECAB 한 명이 승인하면 "예정". "완료" 뒤 "사후 검토"를 건너뛸 수 없다

### 실행과 마감

- "실행 중"이 되면 `actual_start`, "완료"가 되면 `actual_end`와 `outcome`(필수)을 기록한다
- `failed`·`backed_out`이면 변경 담당자에게 "장애 만들기"를 제안한다(자동 생성 안 함)
- 계획 종료 시각이 지났는데 "실행 중"이면 `SlaJobs`가 담당자에게 알린다
- "사후 검토"에서 `review_html`을 채워야 "종료"로 간다. 일반 변경 중 `outcome = successful`이고 위험도 `low`이면 사후 검토를 건너뛸 수 있다

## 4. 화면

- 작업 패키지 상세(변경): 변경 종류·위험도·계획들·실행 시간·CI·승인 패널·결과·사후 검토, 충돌이 나면 겹치는 변경 목록을 보여 주는 오류 상자
- **변경 일정** `/projects/:id/changes/calendar`: 기존 FullCalendar(MIT 패키지)로 변경을 실행 시간 막대로 보여 준다. 종류·위험도·CI 필터, 막대 색은 위험도(브랜드 토큰)
- 프로젝트 설정 › 서비스 데스크 › 변경: CAB·ECAB 그룹, 승인 규칙 표, 표준 변경 템플릿
- 포털 승인함(2단계)에 변경 승인도 나온다. CAB 위원이 멤버가 아니어도 승인할 수 있다. 승인자에게는 제목, 종류, 위험도, 계획, 실행 시간, CI 이름만 보인다
- CI 상세(4단계)에 "예정된 변경" 표
- 가짜 데이터 계층에 같은 규칙(충돌 검사 포함), `SCHEMA` 올림

## 5. 보안

- 변경은 포털 요청자에게 보이지 않는다. 승인자 보기만 포털 승인함 규칙(본인 `pending`만, 나머지 404)
- 충돌 무시는 긴급 변경에서만, 사유 필수, 저널과 웹훅(`change.conflict_overridden`)에 남긴다
- 표준 변경 템플릿을 바꾸는 권한(`manage_change_templates`)을 따로 둔다

## 6. 테스트

- JUnit
  - `ChangeConflictIT`: 겹침 경계(끝 = 다른 시작은 겹치지 않음), 취소·종료 변경 무시, 긴급 사유, **동시 저장 두 건 중 하나만 성공**, CI 여러 개의 잠금 순서
  - `ChangeApprovalIT`: 종류·위험도별 규칙, 승인 뒤 변경 시 재승인, ECAB, `group_member`
  - `ChangeLifecycleIT`: 표준 템플릿 제한, 결과 필수, 사후 검토 규칙
  - `NoPinningTest`: advisory lock 경로가 캐리어 스레드를 붙잡지 않는지
  - `PerfBudgetIT`: 변경 일정(한 달, 변경 300건)
- e2e: 일반 변경 작성 → CAB 위원이 포털 승인함에서 승인 → 일정에 표시 → 같은 CI에 겹치는 변경 저장이 막힘 → 긴급 변경은 사유와 함께 저장 → 실행·완료·사후 검토 → 종료

## 7. 문서

01·02, 03(충돌 잠금), 04(변경 API·웹훅), 05(V24), 06(변경 상세·일정), 07(새 절: 변경 요청·승인·일정), 10(새 절: CAB 설정, 승인 규칙, 표준 변경 템플릿), 08·09. 도움말 연결.

## 8. 위험과 대응

| 위험 | 대응 |
| --- | --- |
| 동시 저장 경쟁으로 충돌이 새어 나감 | CI별 advisory lock을 정해진 순서로, 동시성 IT로 고정 |
| 승인 뒤 계획을 몰래 바꿈 | 핵심 필드 변경 시 재승인, 저널 기록 |
| 긴급 변경 남용 | 사후 검토 필수, 대시보드 위젯 "이번 달 긴급 변경 수·충돌 무시 수" |
