# 설계: ITSM (ITIL 프로세스) — 로드맵과 1단계 장애·서비스 요청

- 날짜: 2026-10-14
- 상태: 설계 합의(구현 계획 전)
- 관련 요구사항: 새 REQ-ITSM-001 ~ (1단계), 2단계부터는 단계별 설계서에서 정한다

## 1. 목적

openPMS에 ITIL 전체 프로세스(장애·서비스 요청·문제·변경·CMDB·지식 베이스)를 갖춘 ITSM을 넣는다. 한 설계서로 다루기에는 크므로 6단계로 나누고, 이 문서는 **전체 로드맵**과 **1단계(기반 + 장애·서비스 요청)의 상세 설계**를 담는다. 2단계부터는 단계마다 설계서 → 구현 계획 → 구현을 따로 돈다.

### 합의한 것

| 항목 | 결정 |
| --- | --- |
| 목표 | ITIL 전체 프로세스. 6단계로 나누어 순서대로 만든다 |
| 요청자 | 사내 전 직원(openPMS 계정, SSO 포함). 서비스 데스크 프로젝트의 멤버가 아니어도 포털에서 접수한다 |
| 저장 방식 | 티켓 = 작업 패키지 + ITSM 확장 테이블(1:1). 별도 티켓 엔진은 만들지 않는다 |
| SLA 시계 | SLA 정책마다 달력을 고른다(24×7 또는 근무 시간). 휴무일은 기존 `non_working_days`. 멈춤 상태에서는 시계가 멈춘다 |
| 처리 그룹 | 기존 `user_groups`를 처리 그룹(큐)으로 쓴다 |
| 우선순위 | 영향도 × 긴급도 행렬로 접수 때 자동으로 정하고, 처리자가 바꿀 수 있다 |

### 범위 밖 (1단계)

- 메일 접수(IMAP)와 계정 없는 외부 요청자
- 서비스 카탈로그·승인(2단계), 문제(3), CMDB(4), 변경(5), 지식 베이스(6)
- 만족도 조사, 근무 교대표, 자동 배정 규칙(라운드 로빈 등)
- 처리 그룹별로 다른 달력

## 2. 로드맵

| 단계 | 범위 | 기반 |
| --- | --- | --- |
| 1 | 장애·서비스 요청, SLA, 요청자 포털, 처리 그룹 | V20 |
| 2 | 서비스 카탈로그: 항목별 요청 양식(사용자 정의 필드), 승인 단계(관리자·지정 승인자) | 1 |
| 3 | 문제 관리: 문제 유형, 장애와의 연결(`relations`), 근본 원인, 알려진 오류 | 1 |
| 4 | CMDB: 구성 항목과 관계, 수기 입력과 API 동기화, 티켓과 연결, 영향 분석 | 1 |
| 5 | 변경 관리: 표준·일반·긴급 변경, 위험도·영향, CAB 승인, 변경 일정(캘린더 재사용), 같은 CI 일정 충돌 막기 | 1, 2(승인), 4(CI) |
| 6 | 지식 베이스: 문서, 포털 검색, 해결할 때 문서 추천 | 1, 3 |

### 단계 사이에 오가는 것

의존 관계: 1단계가 기반이고 2·3·4단계는 1단계 위에 만든다. 5단계는 2단계 승인 엔진과 4단계 CI를, 6단계는 3단계 알려진 오류와 4단계 업무 서비스를 쓴다. 5단계는 CI 변경을 4단계 CMDB에 반영한다.

| 단계 | 앞 단계에서 받는 것 | 뒤 단계로 넘겨주는 것 |
| --- | --- | --- |
| 1 장애·서비스 요청 | 기존 작업 패키지·워크플로·저널·알림·웹훅 | 서비스 데스크 프로젝트, `service_tickets`, SLA 시계, 포털, 처리 그룹 → 2~6단계 모두 |
| 2 서비스 카탈로그 | 1: 포털, SLA 멈춤 상태, 처리 그룹 | 승인 엔진 `ticket_approvals`, 포털 내 승인함 → 5(CAB 승인) |
| 3 문제 관리 | 1: 장애(`service_tickets`) | 알려진 오류·임시 해결책 → 6(문서로 만들기). 장애·문제에서 변경 만들기 → 5 |
| 4 CMDB | 1: 티켓(CI 연결) | CI와 `work_package_cis` → 5(충돌 막기, CMDB 반영). 업무 서비스 CI → 6(문서 분류), 1(포털 서비스 고르기) |
| 5 변경 관리 | 2: 승인 엔진, 4: CI, 1·3: 장애·문제 | CI 변경 반영 → 4(CMDB 갱신). 실패한 변경의 장애 만들기 제안 → 1 |
| 6 지식 베이스 | 1: 포털, 3: 알려진 오류, 4: 업무 서비스(선택) | 포털 자가 해결 → 1(접수 감소), 답변에 문서 넣기 → 1·3 |

CI 기반 충돌 확인을 위해 CMDB(4)를 변경(5)보다 먼저 만든다(2026-10-14 결정). 단계별 설계서: `2026-10-14-itsm-phase2-catalog-design.md` ~ `2026-10-14-itsm-phase6-knowledge-design.md`.

모든 단계는 같은 틀을 따른다. 단, CMDB의 구성 항목과 지식 베이스 문서는 작업이 아니므로 작업 패키지가 아닌 별도 테이블로 둔다. 새 프로세스는 작업 패키지 **유형** + 1:1 **확장 테이블**로 만들고, 프로세스 사이의 연결은 기존 `relations`로 맺는다.

## 3. 데이터 (마이그레이션 V20 `service_desk.sql`, 개발 데이터 V1004)

- `projects_known_modules` check 제약에 `service_desk`를 더한다.
- `statuses`에 `sla_pause boolean not null default false`를 더한다.
- `custom_fields`에 `portal_visible boolean not null default false`를 더한다(포털 접수 양식과 포털 상세에 보이는 필드).
- `service_calendars`
  - `id`, `name`(unique), `mode`(`24x7` | `business`), `time_zone`(기본 `Asia/Seoul`)
  - `hours jsonb`: 요일별 `[{"day": 1, "start": "09:00", "end": "18:00"}, ...]`. `mode = '24x7'`이면 쓰지 않는다
  - 휴무일은 `mode = 'business'`일 때만 `non_working_days`에서 뺀다
- `sla_policies`
  - `id`, `project_id`, `type_id`, `priority_id`, `calendar_id`, `first_response_minutes`(> 0), `resolve_minutes`(> 0, ≥ 첫 응답)
  - unique `(project_id, type_id, priority_id)`. 맞는 정책이 없으면 SLA 없이 접수한다(필드는 null)
- `priority_matrix`
  - `project_id`, `impact`(1–3), `urgency`(1–3), `priority_id`. PK `(project_id, impact, urgency)`
- `service_tickets` (작업 패키지와 1:1)
  - `work_package_id` PK, FK on delete cascade
  - `requester_id`(not null, users), `channel`(`portal` | `agent` | `api`), `impact`, `urgency`
  - `assignment_group_id`(user_groups, on delete set null), `sla_policy_id`(on delete set null)
  - `first_response_due_at`, `first_responded_at`, `resolve_due_at`, `resolved_at`
  - `paused_since`, `paused_total_seconds`(기본 0)
  - `response_breached`, `resolve_breached`, `response_warned`, `resolve_warned`(boolean, 알림을 한 번만 보내기 위해)
  - 인덱스: `requester_id`, `assignment_group_id`, 미해결 티켓의 `resolve_due_at`(`where resolved_at is null`)
- 기본 데이터(V20): 유형 "장애"·"서비스 요청", 상태 "접수 → 처리 중 → 답변 대기(`sla_pause`) → 해결(`is_closed`) → 종료(`is_closed`)", 워크플로(처리자 역할의 전환, 요청자는 `applies_to = 'author'`로 답변 대기 → 처리 중, 해결 → 처리 중(재오픈), 해결 → 종료), 기본 달력 하나(평일 09–18시)
- 개발 데이터(V1004): 서비스 데스크 프로젝트, 처리 그룹 둘, SLA 정책(P1은 24×7, 나머지는 근무 시간), 행렬, 상태가 여러 가지인 티켓

새 모듈을 켜는 것은 기존 프로젝트 설정의 모듈 켜기를 그대로 쓴다.

## 4. 권한과 요청자 포털

### 권한 (역할의 `permissions`에 추가)

- `view_service_tickets`: 서비스 데스크 프로젝트의 티켓과 SLA 정보 보기
- `handle_service_tickets`: 배정, 상태 변경, 영향도·긴급도·처리 그룹 변경, 내부 코멘트
- `manage_service_desk`: SLA 정책, 달력, 우선순위 행렬 설정

처리자는 서비스 데스크 프로젝트의 멤버로서 이 권한을 받는다. 처리자 쪽 화면과 API는 기존 작업 패키지 화면과 API를 넓혀서 쓴다.

### 포털 (`/portal`, 로그인한 직원 누구나)

- 서비스 데스크 목록(이름·설명만), 접수(유형, 제목, 설명, 영향도, 긴급도, 첨부), 내 티켓 목록과 상세, 답변, 첨부, 해결 확인·재오픈·취소
- `PortalController`는 기존 작업 패키지 API를 거치지 않는 **별도 경로**다. 모든 조회와 변경은 `service_tickets.requester_id = 현재 사용자`로 좁힌다
- 남의 티켓은 403이 아니라 **404**를 돌려줘 존재 여부를 드러내지 않는다
- 응답은 포털 전용 모델로 보낸다. 내부 코멘트(`journals.is_internal`), 처리 그룹 구성원, 다른 사용자의 연락처, "포털 공개"로 표시하지 않은 사용자 정의 필드는 넣지 않는다
- 상태 전환은 워크플로의 `author` 조건으로 검사한다(포털이 따로 규칙을 갖지 않는다)
- 알림: 접수 확인, 처리자 답변, 해결을 기존 알림과 `MailJobs`로 보낸다

### 보안 확인 항목

- 포털 ID 추측(IDOR): 목록, 상세, 댓글, 첨부 다운로드, 상태 전환 모두
- 첨부 다운로드 경로에서 소유를 확인
- 포털 댓글의 HTML sanitize(기존 sanitize 재사용)
- 기존 작업 패키지 API로 들어와도 멤버가 아니면 서비스 데스크 티켓이 보이지 않는지
- 응답 크기와 SQL 수 예산(`PerfBudgetIT`)

## 5. SLA 동작

### 계산 (`com.openpms.itsm.SlaClock`, 순수 함수)

- `addMinutes(start, minutes, calendar, holidays)`: 24×7이면 단순 더하기, 근무 시간이면 근무 구간만 센다
- `elapsedMinutes(from, to, calendar, holidays)`: 멈춤 시간을 빼고 기한을 다시 셀 때 쓴다
- DB에 접근하지 않는다. 휴무일은 호출하는 쪽이 넘긴다

### 갱신 (`SlaService`, 작업 패키지 저장과 같은 트랜잭션)

| 사건 | 처리 |
| --- | --- |
| 접수 | 정책을 고르고(프로젝트 + 유형 + 우선순위) 두 기한을 저장한다 |
| 처리자의 첫 공개 코멘트 또는 첫 상태 변경 | `first_responded_at`을 기록한다(한 번만) |
| `sla_pause` 상태로 들어감 | `paused_since`를 기록한다 |
| `sla_pause` 상태에서 나옴 | 멈춘 시간을 달력 기준으로 세어 `paused_total_seconds`에 더하고 `resolve_due_at`을 그만큼 미룬다 |
| 우선순위·유형 변경 | 정책을 다시 고르고, 접수 시각 + 새 목표 + 누적 멈춤으로 기한을 다시 계산한다 |
| `is_closed` 상태가 됨 | `resolved_at`을 기록한다 |
| 재오픈 | `resolved_at`을 지우고 위반 표시는 그대로 둔다 |

`WorkPackageService`에 훅을 하나 두고, 서비스 데스크 프로젝트일 때만 `SlaService`를 부른다. `synchronized`나 `computeIfAbsent` 안에서 DB를 기다리지 않는다(BUG-012).

### 위반 감지 (`SlaJobs`, 기존 `Jobs`에 1분 주기로 등록)

- 기한이 지났는데 응답·해결이 없으면 `*_breached`를 켜고 담당자와 처리 그룹에 알림·메일을 보내며, 웹훅 `service_ticket.sla_breached`를 낸다
- 남은 시간이 목표의 20% 이하가 되면 `*_warned`를 켜고 "임박" 알림을 한 번만 보낸다
- 멈춘 티켓은 건너뛴다
- 조건부 `update ... where not *_breached returning`으로 표시하므로, 여러 번 돌거나 겹쳐 돌아도 알림은 한 번만 간다

## 6. 처리자 화면

- 작업 패키지 목록에 열과 필터를 더한다: 요청자, 처리 그룹, 접수 경로, SLA 남은 시간, 위반 여부. 정렬 "기한 임박 순"
- 상세 화면에 SLA 패널(응답·해결 진행 막대, 멈춤 표시, 위반 배지)과 영향도·긴급도·처리 그룹 입력을 둔다
- 서비스 데스크 대시보드(프로젝트 개요 위젯): 처리 그룹별 미처리 건수, 오늘 위반, 평균 첫 응답 시간
- 관리 화면(프로젝트 설정 › 서비스 데스크): 달력, SLA 정책 표, 우선순위 행렬
- 가짜 데이터 계층(`frontend/src/api/fake`)에도 같은 규칙을 넣고 `db.ts`의 `SCHEMA`를 올린다. 서비스 데스크 시드는 TDZ 순환을 피해 별도 모듈(`fake/service-desk-seed.ts`)에 둔다
- 색은 브랜드 토큰만 쓴다(`sweep.py`가 깨끗해야 함)

## 7. 테스트

### JUnit

- `SlaClockTest`: 주말, 휴무일, 근무 시간 시작·끝 경계, 근무 시간 밖 접수, 24×7, 멈춤 누적
- `SlaServiceIT`: 접수, 첫 응답, 멈춤과 재개, 우선순위 변경, 해결, 재오픈 때 기한
- `PortalSecurityIT`: 남의 티켓·댓글·첨부는 404, 내부 코멘트가 응답에 없음, 멤버가 아니어도 접수할 수 있음, 멤버가 아니면 작업 패키지 API로 티켓이 보이지 않음
- `SlaJobsIT`: 위반·임박 알림이 한 번만 감, 멈춘 티켓은 건너뜀
- `PerfBudgetIT`에 포털 목록과 처리자 목록(SLA 열)을 더하고, `NoPinningTest`가 새 코드를 덮는지 확인

### e2e

- 직원이 포털에서 접수 → 처리자가 배정·답변 → 답변 대기(SLA 멈춤) → 요청자 답변 → 해결 → 요청자 확인
- 관리 화면에서 SLA 정책을 만들고 바꾼 뒤 새 티켓의 기한에 반영되는지
- 포털 화면 `axe()` 접근성 검사, `perf.spec.ts`에 포털 측정 추가

## 8. 문서 (판 2.4, 백그라운드 서브에이전트)

- 01·02: REQ-ITSM-xxx와 추적
- 03 아키텍처, 04 인터페이스(포털·서비스 데스크 API, 웹훅 이벤트), 05 데이터베이스(V20), 06 화면, 08·09 시험
- 07 사용자 매뉴얼·10 운영자 매뉴얼: **새 절 번호**로 덧붙인다(기존 번호는 바꾸지 않음). 도움말 `topics.ts`에 포털과 서비스 데스크 화면을 잇는다
- 줄바꿈 규칙(03·04·05·10은 CRLF)을 지킨다

## 9. 위험과 대응

| 위험 | 대응 |
| --- | --- |
| 멤버가 아닌 사용자가 들어오는 첫 경로라 권한 누수 가능성 | 포털을 별도 컨트롤러로 두고, 모든 쿼리를 `requester_id`로 좁히고, `PortalSecurityIT`로 고정 |
| 작업 패키지 저장 경로에 SLA 갱신이 붙어 느려짐 | 서비스 데스크 프로젝트에서만 부르고, `PerfBudgetIT`로 SQL 수 확인 |
| 근무 시간 계산의 경계 오류 | 순수 함수로 분리해 경계 사례를 단위 테스트 |
| 위반 알림 중복 | 조건부 update로 한 번만 표시 |
