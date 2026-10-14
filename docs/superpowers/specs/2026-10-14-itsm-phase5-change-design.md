# 설계: ITSM 5단계 — 변경 관리

- 날짜: 2026-10-14
- 상태: 설계 합의(구현 계획 전)
- 선행: 1단계(서비스 데스크), 2단계(승인 엔진 `ticket_approvals`), 4단계(CMDB `work_package_cis`)
- 다음 단계로 넘겨주는 것: CI 변경 반영 → 4(CMDB 갱신). 실패한 변경의 장애 만들기 제안 → 1
- 관련 요구사항: REQ-ITSM-4xx(이 단계에서 번호를 정한다)

## 1. 목적

배포·설정 변경·장비 교체 같은 **변경**을 위험도에 맞는 승인을 거쳐 정해진 시간 창에 실행하고, 같은 구성 항목(CI)을 동시에 건드리는 변경을 막는다. CAB 승인 뒤에는 **실행 계획서(Runbook)**로 일정 확인부터 배포, 배포 후 검증, CMDB 반영까지 표준 절차를 밟게 한다. 실행 결과와 사후 검토를 남겨 장애·문제와 잇는다.

### 합의한 것

| 항목 | 결정 |
| --- | --- |
| 저장 | 변경 = 작업 패키지 유형 "변경" + 1:1 확장 `change_requests` |
| 변경 종류 | 표준(사전 승인, 승인 없음) · 일반(CAB 승인) · 긴급(긴급 CAB가 빠르게 승인, 사후 검토 필수) |
| 승인 | 2단계 승인 엔진을 쓴다. 단계 순서 + 단계 안에서는 한 명 |
| 충돌 | 같은 CI에 실행 시간이 겹치는 변경이 있으면 막는다. 긴급 변경만 사유를 남기고 허용한다. 겹치지는 않지만 간격이 안전 버퍼(기본 30분)보다 짧으면 막지 않고 경고한다 |
| 변경 금지 기간 | 이번 단계에 두지 않는다 |
| 실행 계획서 | 변경이 "예정"이 되면(CAB 승인 뒤, 표준은 바로) 관리자 템플릿으로 5개 절의 체크리스트를 만든다. 외부 AI는 쓰지 않는다 |
| 상태 잠금 | 템플릿에서 "필수"로 표시한 항목이 끝나야 다음 상태로 넘어간다. 나머지는 권고 |
| CMDB 반영 | 계획할 때 "CI 변경 예정"을 적고, 마감 때 한 번에 반영한다 |

### 범위 밖

- 변경 금지 기간(블랙아웃)
- 위험도 자동 점수 계산(설문 기반). 위험도는 사람이 고른다
- CI/CD 도구와의 자동 연동(배포 파이프라인이 변경을 만들고 닫기). API·웹훅으로 나중에 붙일 수 있다
- 릴리스 관리(변경 여러 개 묶음)
- AI로 실행 계획서 초안 쓰기(변경·인프라 정보가 외부로 나가므로 이번에는 두지 않는다)
- 실행 계획서 항목의 자동 확인(모니터링 지표를 불러와 자동 체크). 증적 링크를 사람이 남긴다

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
- `change_settings` (프로젝트별 한 행): `cab_group_id`, `ecab_group_id`(user_groups), `min_gap_minutes`(같은 CI의 변경 사이 권장 간격, 기본 30, 0이면 경고 안 함)
- 2단계 `ticket_approvals`·`catalog_item_approval_steps`의 `approver_kind`에 `group_member`(그룹의 아무나 한 명)를 더한다. 변경 승인은 `change_approval_rules`로 정한다
- `change_approval_rules`: `project_id`, `change_class`, `risk`, `step_no`, `approver_kind`(`group_lead` | `group_member`), `group_source`(`cab` | `ecab` | `owner_group`). 예: 일반·고위험 = 1단계 CI 책임 그룹 책임자, 2단계 CAB 한 명. `owner_group`은 변경에 걸린 CI들의 책임 그룹을 모두 모아, 그 책임자들을 같은 단계에 둔다(한 명이 승인하면 다음 단계). 책임 그룹이 없는 CI는 빼고, 하나도 없으면 그 단계는 건너뛴다(2단계 규칙과 같음). 단, 규칙에 `require_owner_group = true`이면 건너뛰지 않고 전환을 막는다(3절 승인)
- `change_approval_rules`에 `require_owner_group boolean not null default false`를 더한다. 기본 규칙에서 일반·고위험(`normal`·`high`)은 true
- `runbook_templates`: `id`, `project_id`, `name`, `change_class`(null = 모든 종류), `ci_class_id`(null = 공통), `notice_lead_minutes`(사용자 사전 공지를 시작 몇 분 전에 할지, 기본 일반 1440 · 긴급 60), `is_active`, `position`
- `runbook_template_items`: `id`, `template_id`, `stage`(1–5), `position`, `text`(자리 표시자 포함), `is_required`, `needs_evidence`(메모·링크 필수), `applies_when`(`always` | `deployed` | `rolled_back`, 기본 `always`. 3절 결과에 따라 쓰는 항목)
- `change_runbooks`(변경과 1:1): `work_package_id` PK, `generation`(다시 만들 때마다 1씩), `notice_at`, `generated_at`, `generated_by`, `template_ids uuid[]`
- `change_runbook_items`: `id`, `work_package_id`, `stage`, `position`, `text`(자리 표시자를 채운 스냅숏), `is_required`, `needs_evidence`, `status`(`open` | `done` | `na`), `na_reason`(`na`이면 필수), `evidence_text`, `done_by`, `done_at`, `source`(`template` | `manual`), `applies_when`(템플릿에서 복사)
- `change_ci_updates`: `id`, `work_package_id`, `action`(`update_attributes` | `create` | `retire`), `ci_id`(`create`이면 null), `class_id`(`create`일 때), `payload jsonb`(속성 키 → 새 값, `create`면 이름 포함), `applied_at`, `applied_by`, `created_ci_id`
- 충돌 방지 잠금: 앱이 트랜잭션 안에서 변경의 CI 행을 `select id from configuration_items where id = any(?) order by id for no key update`로 잠근 뒤 겹침을 검사한다. id 순서로 잡아 교착을 피한다. 해시 기반 advisory lock(`hashtext`는 32비트라 서로 다른 CI가 같은 키가 되어 쓸데없이 기다릴 수 있음)은 쓰지 않는다. CI 기본 키가 `uuid`라 고유한 정수 키가 없기 때문이다. `for no key update`는 같은 잠금끼리만 막고 `work_package_cis` 삽입의 FK 확인(`for key share`)과는 부딪치지 않는다. 저장소의 기존 방식(`TemporaryPasswords`의 `forNoKeyUpdate()`)과 같다. `synchronized`는 쓰지 않는다
- 인덱스: 충돌 검사를 위해 `work_package_cis (ci_id)`

## 3. 동작 (`com.openpms.itsm.Changes`)

### 만들기

- 일반·긴급: 처리자가 변경을 만들고 위험도, 계획, 실행 시간, CI(`work_package_cis`)를 채운다
- 표준: 템플릿을 골라 만든다. 계획·위험도가 템플릿에서 채워지고, 실행 시간은 `max_duration_minutes`를 넘을 수 없다
- 장애·문제 상세에서 "변경 만들기"로 만들면 관계(`relations`의 `relates`)와 CI를 이어 받는다

### 충돌 검사 (실행 시간이나 CI를 저장할 때마다)

1. 변경의 CI 행을 id 순서로 잠근다(2절)
2. 같은 CI에 `[planned_start, planned_end)`가 겹치고 상태가 취소·반려·종료가 아닌 다른 변경을 찾는다
3. 있으면: 표준·일반은 저장을 거부하고 겹치는 변경 목록(번호, 제목, 시간, CI)을 돌려준다. 긴급은 `conflict_override_reason`이 있어야 저장하며, 겹친 변경의 담당자에게 알린다
4. 검사와 저장은 같은 트랜잭션이다. 두 사람이 동시에 같은 CI에 일정을 잡아도 하나만 성공한다
5. 겹치지 않더라도 같은 CI의 다른 변경과 간격이 `min_gap_minutes`보다 짧으면(예: 앞 작업 02:00 종료, 뒤 작업 02:00 시작) 저장은 하되 응답에 경고(상대 변경, 간격)를 담는다. 판정 구간 자체는 반개구간을 그대로 둔다

판정 규칙: 구간은 반개구간 `[planned_start, planned_end)`이므로 앞 작업의 끝 = 뒤 작업의 시작이면 충돌이 아니다. 지연·롤백 여유가 없는 이런 배치는 5번 경고와 변경 일정·실행 계획서 1절의 표시로 사람이 판단한다

### 승인

- "검토 → 승인 대기"로 갈 때 `change_approval_rules`로 승인 단계를 만든다(2단계 엔진). 표준은 승인 없이 "예정"으로 간다
- 거버넌스 공백 막기: 적용되는 규칙에 `require_owner_group`이 켜져 있으면(기본: 일반·고위험) 다음 경우 "승인 대기"로 넘어가지 못하고, 이유와 해당 CI 목록을 돌려준다
  - 변경에 CI가 하나도 없음
  - 걸린 CI 중 책임 그룹(`owner_group_id`)이 없는 것이 있음
  - 책임 그룹은 있으나 책임자(`lead_user_id`)가 없음
  처리자는 CMDB에서 책임 그룹·책임자를 정한 뒤 다시 시도한다. 이 경우에는 건너뛰기(`skipped`)를 쓰지 않는다
- 승인을 마치면 "예정". 반려하면 "반려"
- 승인 뒤에 실행 시간·CI·위험도를 바꾸면 승인을 다시 받는다(진행 중이던 승인은 `skipped`, 새로 만듦). 저널에 남긴다
- 긴급: ECAB 한 명이 승인하면 "예정". "완료" 뒤 "사후 검토"를 건너뛸 수 없다

### 실행과 마감

- "실행 중"이 되면 `actual_start`, "완료"가 되면 `actual_end`와 `outcome`(필수)을 기록한다
- 각 전환은 4절 실행 계획서의 필수 항목 검사를 먼저 통과해야 한다
- `failed`·`backed_out`이면 변경 담당자에게 "장애 만들기"를 제안한다(자동 생성 안 함)
- 계획 종료 시각이 지났는데 "실행 중"이면 `SlaJobs`가 담당자에게 알린다
- "사후 검토"에서 `review_html`을 채워야 "종료"로 간다. 일반 변경 중 `outcome = successful`이고 위험도 `low`이면 사후 검토를 건너뛸 수 있다

## 4. 실행 계획서 (Runbook, `com.openpms.itsm.Runbooks`)

### 만들기

- 변경이 "예정"이 되는 전환과 같은 트랜잭션에서 만든다. 일반·긴급은 CAB·ECAB 승인을 마친 뒤, 표준은 승인 없이 바로
- 템플릿 고르기: `change_class`가 맞거나 null인 템플릿 중 `ci_class_id`가 null(공통)인 것 하나 + 변경에 걸린 CI들의 클래스마다 맞는 템플릿. 항목은 절별로 공통 → 클래스 순서로 합친다
- 자리 표시자는 만들 때의 값으로 채워 고정한다(뒤에 변경 내용이 바뀌어도 이미 만든 문구는 그대로)

| 자리 표시자 | 값 |
| --- | --- |
| `{{cr.number}}`, `{{cr.title}}` | 변경 번호(`#123`), 제목 |
| `{{cr.class}}`, `{{cr.risk}}` | 변경 종류, 위험도 |
| `{{ci.names}}`, `{{ci.classes}}` | 대상 CI 이름, 클래스 |
| `{{planned_start}}`, `{{planned_end}}` | 계획 시간(사용자 표시 형식) |
| `{{assignee}}` | 배포 담당자(작업 패키지 담당자) |
| `{{notice_at}}` | 사용자 사전 공지 시각 = 계획 시작 − `notice_lead_minutes`(여러 템플릿이면 가장 이른 시각) |
| `{{backout_summary}}` | 롤백 계획 앞부분 200자(평문) |

- 승인 뒤 실행 시간·CI·위험도가 바뀌어 다시 승인을 받으면, 다시 "예정"이 될 때 계획서를 새로 만든다(`generation` + 1). 이전 체크 기록은 저널에 남기고 지운다

### 5개 절과 상태 잠금

| 절 | 기본 항목(V24 기본 템플릿, SOP 문체) | 필수 항목이 끝나야 하는 전환 |
| --- | --- | --- |
| 1 배포 일정·충돌 방지 | 변경 일정에 `{{planned_start}}`–`{{planned_end}}` 등록 확인(필수, 자동 표시) · 같은 CI 충돌 없음(필수, 충돌 검사 결과 표시) · 앞뒤 변경과의 간격 확인(안전 버퍼 경고가 있으면 표시, 지연 시 연락 담당 기록) · 관련 팀·외부 의존 작업과 일정 공유(필수) · 작업 시간대의 모니터링 담당자 지정 | 예정 → 실행 중 |
| 2 빌드·스테이징 검증 | QA 완료 증적(필수, 링크) · 스테이징 반영과 동일 빌드 확인(필수) · 롤백 절차 리허설 결과(필수, `{{backout_summary}}` 표시) · 운영 데이터 백업·스냅숏 확인(필수) · 배포 산출물 해시·버전 기록 | 예정 → 실행 중 |
| 3 배포 실행 | `{{notice_at}}`에 사용자 사전 공지(필수) · 작업 시작 선언과 관계자 호출(필수) · 절차서 단계별 수행 기록(필수) · 롤백 결정 시점(계획 종료 30분 전) 판단 기록(필수) · 작업 종료 선언 | 실행 중 → 완료 |
| 4 배포 후 검증 | 배포됨(`deployed`): 헬스 체크 통과(필수) · 모니터링 지표 기준 이내(오류율·응답 시간·CPU·메모리, 필수, 증적) · 핵심 기능 시험(필수) · 사용자 영향·문의 확인 · 일정 시간 관찰(기본 30분)<br>롤백됨(`rolled_back`): 이전 버전·설정으로 복귀 확인(필수, 증적) · 롤백 후 헬스 체크 통과(필수) · 모니터링 지표가 변경 전 기준으로 돌아옴(필수, 증적) · 데이터 정합성 확인(필수) · 롤백 사실 사용자 공지(필수) · 일정 시간 관찰(기본 30분) | 완료 → 사후 검토, 완료 → 종료 |
| 5 마감·CMDB 반영 | 결과(`outcome`) 기록(필수, 자동 표시) · CMDB 반영(필수, CI 변경 예정이 있을 때) · 관련 장애·문제 갱신 · 완료 공지 · 사후 검토 메모 | → 종료 |

- 항목 처리: `done`(증적이 필요한 항목은 `evidence_text` 필수) 또는 `na`(사유 필수). 처리자와 시각을 남기고 저널에 기록한다. 되돌리기(`open`)도 기록한다
- 처리 권한: 변경 담당자와 `handle_service_tickets` 권한자. 담당자는 항목을 더할 수 있다(`source = manual`, 필수 여부를 고를 수 있음)
- 잠금은 서버가 검사한다. 미완료 필수 항목이 있으면 전환을 거부하고 항목 목록을 돌려준다
- 결과에 따라 쓰는 항목이 바뀐다. 결과(`outcome`)를 기록하는 "완료" 전환에서 항목의 `applies_when`을 본다
  - `successful`·`partial` → `deployed` 항목과 `always` 항목이 쓰인다
  - `backed_out`·`failed` → `rolled_back` 항목과 `always` 항목이 쓰인다. 롤백 검증 항목은 템플릿대로 **필수**로 남고 자동으로 `na`가 되지 않는다(2차 장애 방지). 사람이 사유를 남겨 `na`로 할 수는 있지만 사후 검토에서 함께 본다
  - 쓰이지 않는 쪽 항목은 시스템이 `na`(사유 "결과가 ○○라 해당 없음")로 바꾼다. 결과를 고치면 다시 계산한다
  - `failed`인데 롤백하지 않았다면(앞으로 고쳐 나가는 경우) 롤백 검증 대신 복구 확인을 해야 하므로, `rolled_back` 항목 문구는 "이전 상태 또는 정상 상태로 복귀 확인"으로 쓴다
- 결과가 `failed`·`backed_out`이면 3단계 장애 만들기 제안이 함께 나온다
- 템플릿 검사: 4절에 `deployed`나 `rolled_back` 항목이 있으면 반대쪽 항목도 하나 이상 있어야 저장된다(롤백 검증이 빠진 템플릿 방지)

### CMDB 반영 (5절)

- 계획할 때(작성·검토 상태) 담당자가 "CI 변경 예정"을 적는다
  - 속성 변경: 예) `os_version` 8.6 → 9.4, `memory_gb` 16 → 32
  - CI 추가: 예) 웹 서버 2대 증설 → 클래스와 이름·속성을 적은 `create` 2건
  - CI 폐기: 예) 구형 장비 교체 → `retire`
- CAB 승인 화면에도 CI 변경 예정이 보인다. 승인 뒤에 예정을 바꾸면 재승인 대상이다
- 마감 때 "CMDB 반영"을 누르면 모든 예정을 **한 트랜잭션**으로 적용한다. 속성은 4단계 클래스 정의로 다시 검사하고, `ci_journals`에 변경 번호를 남긴다. 하나라도 실패하면 아무것도 바꾸지 않고 이유를 보여 준다
- 계획 뒤 실제 작업이 달라졌으면(예: 8대 계획, 6대 증설) 반영 전에 예정을 고친다. 이 수정도 저널에 남는다
- API 동기화로 들어온 CI(`source ≠ manual`)이면 "다음 동기화가 이 값을 덮어쓸 수 있음"을 경고한다. 출처 시스템도 함께 고쳐야 한다고 항목 문구에 넣는다
- 반영하면 5절의 "CMDB 반영" 항목이 자동으로 `done`이 된다

### 내보내기

- `GET /api/v1/changes/{id}/runbook.md`: SOP 양식 Markdown. 머리표(CR 번호·제목, 대상 시스템·인프라, 작업 시간, 배포 담당자, 롤백 계획 요약, 공지 시각) + 5개 절의 `- [ ]` / `- [x]` 체크리스트(처리자·시각·증적·해당 없음 사유 포함) + CI 변경 예정·반영 결과
- 인쇄용 보기(기존 인쇄 스타일). 내보내기 권한은 변경 보기 권한과 같다

## 5. 화면

- 작업 패키지 상세(변경): 변경 종류·위험도·계획들·실행 시간·CI·승인 패널·결과·사후 검토, 충돌이 나면 겹치는 변경 목록을 보여 주는 오류 상자
- **변경 일정** `/projects/:id/changes/calendar`: 기존 FullCalendar(MIT 패키지)로 변경을 실행 시간 막대로 보여 준다. 종류·위험도·CI 필터, 막대 색은 위험도(브랜드 토큰). 같은 CI의 변경 간격이 `min_gap_minutes`보다 짧으면 두 막대에 경고 표시
  - 일정 API `GET /api/v1/projects/{id}/changes/calendar?from&to`는 막대에 필요한 가벼운 항목만 고른다: `id`, `number`, `subject`, `change_class`, `risk`, 상태, `planned_start`, `planned_end`, `ci_ids`, 간격 경고 여부. 본문 HTML(`impact_html`, `implementation_plan_html`, `backout_plan_html`, `test_plan_html`, `review_html`)은 조회하지 않는다(jOOQ에서 컬럼을 명시해 고르고 `select *`를 쓰지 않음). 막대를 누르면 상세 API로 따로 불러온다
  - 기간은 최대 62일. 조건은 `planned_start < :to and planned_end > :from`, CI id는 `array_agg`로 한 쿼리에 모은다(N+1 없음)
- 프로젝트 설정 › 서비스 데스크 › 변경: CAB·ECAB 그룹, 승인 규칙 표, 표준 변경 템플릿
- 포털 승인함(2단계)에 변경 승인도 나온다. CAB 위원이 멤버가 아니어도 승인할 수 있다. 승인자에게는 제목, 종류, 위험도, 계획, 실행 시간, CI 이름만 보인다
- CI 상세(4단계)에 "예정된 변경" 표
- 변경 상세의 "실행 계획서" 탭: 절별 진행률, 필수 미완료 표시, 항목 체크·증적·해당 없음, 항목 더하기, Markdown 내려받기·인쇄. "CI 변경 예정" 표와 "CMDB 반영" 버튼
- 프로젝트 설정 › 서비스 데스크 › 변경 › 실행 계획서 템플릿: 템플릿(종류·CI 클래스·공지 리드 시간)과 절별 항목(문구, 필수, 증적 필요). 자리 표시자 도움말과 미리 보기
- 가짜 데이터 계층에 같은 규칙(충돌 검사 포함), `SCHEMA` 올림

## 6. 보안

- 변경은 포털 요청자에게 보이지 않는다. 승인자 보기만 포털 승인함 규칙(본인 `pending`만, 나머지 404)
- 충돌 무시는 긴급 변경에서만, 사유 필수, 저널과 웹훅(`change.conflict_overridden`)에 남긴다
- 표준 변경 템플릿과 실행 계획서 템플릿을 바꾸는 권한(`manage_change_templates`)을 따로 둔다
- 실행 계획서 항목의 증적은 평문으로 저장하고 화면에서 이스케이프한다. 링크는 `http(s)`만 링크로 만든다
- CMDB 반영은 변경 담당자 또는 `edit_cmdb` 권한자만. 반영 내용은 4단계 동기화와 같은 검사를 거친다

## 7. 테스트

- JUnit
  - `ChangeConflictIT`: 겹침 경계(끝 = 다른 시작은 겹치지 않음), 간격이 `min_gap_minutes`보다 짧으면 저장되고 경고, 0이면 경고 없음, 취소·종료 변경 무시, 긴급 사유, **동시 저장 두 건 중 하나만 성공**, CI 여러 개를 서로 반대 순서로 고른 두 저장이 교착 없이 끝남
  - `ChangeApprovalIT`: 종류·위험도별 규칙, 승인 뒤 변경 시 재승인, ECAB, `group_member`, `require_owner_group`(CI 없음·책임 그룹 없음·책임자 없음이면 "승인 대기" 거부, 규칙이 꺼져 있으면 건너뛰기)
  - `ChangeLifecycleIT`: 표준 템플릿 제한, 결과 필수, 사후 검토 규칙
  - `RunbookGenerationIT`: 템플릿 고르기·합치기(종류 + CI 클래스), 자리 표시자 스냅숏, 공지 시각, 재승인 때 다시 만들기
  - `RunbookGateIT`: 전환마다 필수 항목 잠금, 증적·해당 없음 사유 필수, 권한. 결과별 4절: `successful`이면 롤백 검증 항목이 자동 `na`, `backed_out`·`failed`이면 롤백 검증 필수 항목이 남아 "종료"가 막힘, 결과를 고치면 다시 계산. 롤백 검증이 빠진 템플릿 저장 거부
  - `CmdbApplyIT`: 속성 변경·추가·폐기를 한 트랜잭션으로, 하나 실패 시 전부 취소, `ci_journals`에 변경 번호, 동기화 CI 경고
  - `RunbookExportTest`: Markdown 내보내기 스냅숏(체크 상태·증적·사유)
  - `NoPinningTest`: CI 행 잠금을 기다리는 경로가 캐리어 스레드를 붙잡지 않는지
  - `PerfBudgetIT`: 변경 일정(한 달, 변경 300건, 각 변경에 큰 본문 HTML): p95, SQL 수(기간과 무관하게 일정), 응답 크기 상한(본문 HTML이 응답에 없음을 크기로 확인)
- e2e: 일반 변경 작성(CI 변경 예정 포함) → CAB 위원이 포털 승인함에서 승인 → 실행 계획서 생성 → 1·2절 필수 미완료로 "실행 중" 거부 → 항목 처리 후 실행 → 3·4절 처리 → 완료 → CMDB 반영 → 종료 → Markdown 내려받기. 같은 CI에 겹치는 변경 저장이 막히고, 긴급 변경은 사유와 함께 저장되는 흐름 하나. 롤백(`backed_out`) 흐름 하나: 롤백 검증 항목을 끝내야 종료됨

## 8. 문서

01·02, 03(충돌 잠금, 실행 계획서 생성·잠금), 04(변경 API·실행 계획서 API·Markdown 내보내기·웹훅), 05(V24), 06(변경 상세·실행 계획서 탭·일정), 07(새 절: 변경 요청·승인·일정, 실행 계획서 따라 배포하기, CMDB 반영), 10(새 절: CAB 설정, 승인 규칙, 표준 변경 템플릿, 실행 계획서 템플릿), 08·09. 도움말 연결.

## 9. 위험과 대응

| 위험 | 대응 |
| --- | --- |
| 동시 저장 경쟁으로 충돌이 새어 나감 | CI 행 잠금을 id 순서로, 동시성 IT로 고정 |
| 앞 작업 끝과 뒤 작업 시작이 붙어 지연·롤백 여유가 없음 | 판정은 반개구간 유지, 안전 버퍼보다 짧으면 저장 응답·변경 일정·실행 계획서 1절에서 경고 |
| 핵심 CI의 책임 그룹이 비어 승인이 건너뛰어짐 | 일반·고위험은 `require_owner_group`으로 "승인 대기" 전환을 막음 |
| 롤백 뒤 검증을 건너뛰어 2차 장애 | 결과가 롤백·실패면 롤백 검증 항목이 필수로 남음, 롤백 검증 없는 템플릿 저장 거부 |
| 변경 일정이 본문 HTML까지 읽어 느려짐 | 일정 전용 가벼운 조회, `PerfBudgetIT`의 응답 크기 상한 |
| 승인 뒤 계획을 몰래 바꿈 | 핵심 필드 변경 시 재승인, 저널 기록 |
| 긴급 변경 남용 | 사후 검토 필수, 대시보드 위젯 "이번 달 긴급 변경 수·충돌 무시 수" |
| 체크리스트가 형식적으로 채워짐 | 핵심 항목은 증적 필수, 처리자·시각 기록, 사후 검토에서 계획서 진행 기록을 함께 본다 |
| 필수 잠금이 긴급 대응을 막음 | 긴급 변경 기본 템플릿은 필수 항목을 줄이고, 필수 항목은 사유와 함께 "해당 없음"으로 넘길 수 있다(롤백 검증 항목도 사유가 있으면 가능하지만 사후 검토에서 함께 본다) |
| CMDB 반영 누락으로 CMDB가 실제와 어긋남 | CI 변경 예정이 있으면 반영이 "종료"의 필수 조건, 대시보드에 "반영 안 된 완료 변경" 수 |
