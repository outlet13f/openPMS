# 설계: ITSM 4단계 — CMDB (구성 관리)

- 날짜: 2026-10-14
- 상태: 설계 합의(구현 계획 전)
- 선행: 1단계(서비스 데스크, `service_tickets`). 3단계 문제와도 잇는다
- 다음 단계로 넘겨주는 것: CI와 `work_package_cis` → 5(충돌 막기, CMDB 반영). 업무 서비스 CI → 6(문서 분류), 1(포털 서비스 고르기)
- 관련 요구사항: REQ-ITSM-3xx(이 단계에서 번호를 정한다)

## 1. 목적

서버, 애플리케이션, 네트워크 장비, 업무 서비스 같은 **구성 항목(CI)**과 그 사이의 의존 관계를 관리한다. 장애·요청·문제·변경을 CI에 이어 "이 서버가 멈추면 어느 서비스가 영향받는가"를 보여 준다. 5단계 변경 관리는 CI를 기준으로 일정 충돌을 막는다(그래서 CMDB를 변경보다 먼저 만든다).

### 합의한 것

| 항목 | 결정 |
| --- | --- |
| 저장 | CI는 작업이 아니므로 작업 패키지가 아닌 별도 테이블로 둔다 |
| 입력 | 화면에서 수기 입력 + 외부 자산 관리·클라우드에서 API로 동기화(기존 API 토큰) |
| CI 클래스 | 관리자가 클래스(서버, DB, 애플리케이션, 업무 서비스 등)와 클래스별 속성을 정한다 |
| 티켓 연결 | 티켓 하나에 CI 여러 개. 요청자는 포털에서 "업무 서비스" 클래스 중 포털 공개 CI만 고를 수 있다 |

### 범위 밖

- 네트워크 자동 탐색(에이전트·스캔). 탐색은 외부 도구가 하고 API로 넣는다
- 라이선스·구매·감가상각 같은 자산 재무 관리
- 관계도의 자유 편집 캔버스(관계는 표로 편집, 그림은 보기 전용)

## 2. 데이터 (마이그레이션 V23 `cmdb.sql`, 개발 데이터 V1007)

- `projects_known_modules`에 `cmdb`를 더한다. CMDB는 인스턴스에 하나이고, 이 모듈을 켠 프로젝트(서비스 데스크)에서 CI 연결을 쓴다
- `ci_classes`
  - `id`, `name`(unique), `icon`(앱이 정한 키), `position`
  - `attributes jsonb`: `[{"key": "ip", "label": "IP 주소", "format": "string", "required": false}, ...]`. `format`은 `string` | `integer` | `date` | `bool` | `list`(+ `options`) | `link`
  - `portal_selectable boolean`(이 클래스의 CI를 포털에서 고를 수 있는지. 기본 "업무 서비스"만 true)
- `configuration_items`
  - `id`, `class_id`, `name`, `description_text`, `status`(`planned` | `active` | `maintenance` | `retired` | `unverified`)
  - `owner_group_id`(user_groups, 책임 처리 그룹), `owner_user_id`
  - `attributes jsonb`(클래스 정의에 맞춰 앱이 검사), `portal_visible boolean`
  - `source text`(기본 `manual`), `external_id text`, `last_synced_at`, `lock_version`
  - unique `(source, external_id)` where `external_id is not null`, `name`에 trgm 인덱스, `(class_id, status)` 인덱스
- `ci_relations`
  - `id`, `from_ci_id`, `to_ci_id`, `type`(`depends_on` | `runs_on` | `connects_to` | `part_of`)
  - unique `(from_ci_id, to_ci_id, type)`, 자기 자신 금지
  - 영향 방향: `from`이 `to`에 의존한다(= `to`가 멈추면 `from`이 영향받는다). `connects_to`는 양방향으로 본다
- `ci_journals`: `id`, `ci_id`, `user_id`(동기화면 토큰 소유자), `changes jsonb`, `notes_text`, `created_at`. 속성·상태·관계 변경 이력
- `work_package_cis`: `work_package_id`, `ci_id`, `role`(`affected` | `cause`), `created_by`, `created_at`. PK `(work_package_id, ci_id)`. 장애·요청·문제·변경 모두 이 테이블로 CI에 잇는다
- 권한: `view_cmdb`, `edit_cmdb`(수기 입력·관계 편집), `manage_cmdb`(클래스 정의), `sync_cmdb`(API 동기화 토큰용)

## 3. API 동기화

- 기존 API 토큰(`ApiTokenFilter`)으로 부른다. 토큰 소유자에게 `sync_cmdb` 권한이 있어야 한다
- `PUT /api/v1/cmdb/sources/{source}/items/{externalId}`: CI 하나 upsert. `source`는 `manual`이 아닌 소문자 식별자(`^[a-z][a-z0-9-]{0,49}$`)
- `POST /api/v1/cmdb/sources/{source}/batch`: 최대 500건 upsert와 관계 upsert. 기존 `Idempotency-Key`(V16)를 지원한다
- `POST /api/v1/cmdb/sources/{source}/sync-runs/{runId}/complete`: "전체 동기화" 모드. 이번 실행에서 보지 못한 그 출처의 CI를 `unverified`로 바꾼다(지우지 않음). `retired`는 사람이 정한다
- 같은 CI를 사람이 화면에서 고친 필드는 동기화가 덮어쓴다. 사람이 지킬 값은 동기화가 보내지 않는 속성에 둔다고 매뉴얼에 적는다(필드별 잠금은 범위 밖)
- 변경마다 `ci_journals`에 남긴다. 값이 바뀌지 않은 upsert는 이력을 남기지 않는다
- 웹훅 이벤트 `cmdb.ci.changed`(배치는 실행당 하나로 묶음)

## 4. 영향 분석

- `ImpactAnalysis.upstreamOf(ciId)`: 이 CI가 멈추면 영향받는 CI들. 재귀 CTE, 깊이 상한 6, 이미 방문한 CI는 건너뛰어 순환에 안전
- 결과에는 경로(어떤 관계를 거쳐 영향받는지)와 깊이를 담는다
- 화면: CI 상세의 "영향받는 항목" 표와 간단한 관계 그림(SVG, 보기 전용, 깊이 3까지)
- 장애 상세: 연결된 CI의 영향받는 업무 서비스를 보여 주고, 같은 업무 서비스에 걸린 미해결 장애 수를 함께 보여 준다(3단계 문제 묶기의 단서)

## 5. 화면

- 새 화면 `/cmdb`(모듈을 켠 사람에게 메뉴): CI 목록(클래스·상태·책임 그룹·출처 필터, 이름 검색), CI 상세(속성, 관계, 연결된 티켓, 이력, 영향 분석), CI 만들기·고치기
- 관리 › CMDB 클래스: 클래스와 속성 정의
- 작업 패키지 상세: "구성 항목" 칸(여러 개, 역할 `affected`/`cause`), 목록 필터 "CI", "업무 서비스"
- 포털 접수 양식: "어떤 서비스에 문제가 있나요?"(포털 공개 업무 서비스 CI, 선택)
- 가짜 데이터 계층에 CI·관계 시드(`fake/cmdb-seed.ts`), `SCHEMA` 올림

## 6. 보안

- 포털은 `portal_visible`인 업무 서비스 CI의 이름·설명만 받는다. 속성·관계·책임자는 보내지 않는다
- 동기화 API는 `sync_cmdb` 권한 토큰만. 출처 이름으로 `manual` CI를 건드릴 수 없다
- 속성 JSON 크기 상한(16 KB)과 클래스 정의 검사. 알 수 없는 키는 거부한다
- 영향 분석의 깊이·결과 수 상한(1,000)으로 큰 그래프에서 응답이 커지지 않게 한다

## 7. 테스트

- JUnit
  - `CiAttributesTest`: 클래스 정의에 따른 속성 검사
  - `CmdbSyncIT`: upsert, 변경 없는 upsert는 이력 없음, 배치와 Idempotency-Key, 전체 동기화의 `unverified`, `manual` 보호, 권한 없는 토큰 거부
  - `ImpactAnalysisIT`: 깊이, 순환, `connects_to` 양방향, 상한
  - `PortalSecurityIT`에 포털 CI 목록이 공개 필드만 내는지 추가
  - `PerfBudgetIT`: CI 목록, 영향 분석(관계 2,000개 시드)
- e2e: 클래스 만들기 → CI 둘과 관계 → 장애에 CI 연결 → CI 상세에 연결 티켓과 영향받는 서비스. 동기화는 API 테스트(e2e의 request)로
- `backend/scripts`에 동기화 예시 스크립트(`cmdb_sync_example.py`, 토큰은 환경 변수)

## 8. 문서

01·02, 03(CMDB 구성), 04(동기화 API·웹훅), 05(V23), 06, 07(새 절: 구성 항목 보기·연결), 10(새 절: CMDB 클래스, 동기화 토큰·스크립트), 08·09. 도움말 연결.

## 9. 위험과 대응

| 위험 | 대응 |
| --- | --- |
| 동기화가 사람이 고친 값을 덮어씀 | 매뉴얼에 규칙 명시, 이력으로 되돌릴 근거 제공 |
| 외부 시스템이 CI를 대량으로 지움 | 삭제 API 없음, 전체 동기화는 `unverified`로만 |
| 관계 그래프가 커서 느림 | 깊이·결과 상한, `PerfBudgetIT` |
