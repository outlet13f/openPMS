# 설계: ITSM 6단계 — 지식 베이스

- 날짜: 2026-10-14
- 상태: 설계 합의(구현 계획 전)
- 선행: 1단계(포털), 3단계(알려진 오류). 4단계 CMDB가 있으면 업무 서비스로 문서를 분류한다
- 관련 요구사항: REQ-ITSM-5xx(이 단계에서 번호를 정한다)

## 1. 목적

자주 묻는 해결 방법과 알려진 오류의 임시 해결책을 **문서**로 남긴다. 직원은 접수하기 전에 포털에서 찾아 스스로 해결하고, 처리자는 답변할 때 문서를 골라 보낸다.

### 합의한 것

| 항목 | 결정 |
| --- | --- |
| 저장 | 문서는 작업이 아니므로 작업 패키지가 아닌 별도 테이블로 둔다(판 이력 포함) |
| 공개 절차 | 초안 → 검토 → 게시. 처리자가 쓰고 검토자가 승인해야 게시된다. 자기 문서는 자기가 승인할 수 없다 |
| 공개 범위 | 문서마다 `portal`(전 직원) 또는 `internal`(처리자만) |
| 포털 | 검색, 접수 양식에서 제목을 입력하면 비슷한 문서 추천 |

### 범위 밖

- 문서 댓글·별점 토론(간단한 "도움이 됐어요" 수만 센다)
- 여러 언어 판
- 외부 공개(로그인 없이 보기)
- 전문 검색 엔진(Elasticsearch 등). PostgreSQL trgm과 `tsvector`(simple)로 한다

## 2. 데이터 (마이그레이션 V25 `knowledge_base.sql`, 개발 데이터 V1009)

- `kb_categories`: `id`, `project_id`, `name`, `position`. unique `(project_id, name)`
- `kb_articles`
  - `id`, `number`(전역 순번, 화면에 `KB-12`), `project_id`(서비스 데스크), `category_id`, `service_ci_id`(4단계 업무 서비스 CI, 선택)
  - `title`, `body_html`(서버 sanitize), `body_text`, `audience`(`portal` | `internal`)
  - `status`(`draft` | `in_review` | `published` | `archived`)
  - `author_id`, `reviewer_id`, `published_at`, `published_revision`
  - `source_problem_id`(3단계 문제에서 만들었을 때), `helpful_count`, `view_count`, `lock_version`
  - 인덱스: `title`·`body_text`에 `gin_trgm_ops`, `(project_id, status, audience)`
- `kb_article_revisions`: `article_id`, `revision`, `title`, `body_html`, `body_text`, `edited_by`, `created_at`. PK `(article_id, revision)`. 게시본은 `published_revision`이 가리키는 판이다. 게시된 문서를 고치면 새 판이 초안이 되고, 검토를 마칠 때까지 포털은 이전 게시본을 본다
- `kb_article_links`: `article_id`, `work_package_id`, `kind`(`used_in_reply` | `resolved_by`), `created_by`, `created_at`. PK `(article_id, work_package_id, kind)`
- `kb_article_feedback`: `article_id`, `user_id`, `helpful boolean`, `created_at`. PK `(article_id, user_id)`(한 사람 한 표)
- `attachments`에 `kb_article_id uuid references kb_articles (id) on delete cascade`를 더하고, `work_package_id`와 `kb_article_id`가 동시에 채워지지 않게 check를 둔다. 기존 고아 첨부 정리 작업은 두 열이 모두 null일 때만 지운다
- 권한: `view_kb_internal`, `write_kb_articles`, `review_kb_articles`

## 3. 동작 (`com.openpms.itsm.Knowledge`)

### 쓰기와 검토

- 처리자(`write_kb_articles`)가 초안을 쓴다. 장애 상세의 "문서로 만들기"는 제목·해결 코멘트로, 문제 상세(알려진 오류)의 "문서로 만들기"는 제목·임시 해결책으로 초안을 채우고 `source_problem_id`를 남긴다
- "검토 요청"하면 `in_review`가 되고 `review_kb_articles` 권한자에게 알린다
- 검토자는 게시하거나 사유와 함께 돌려보낸다(`draft`). 작성자와 같은 사람은 게시할 수 없다(서버가 거부). 마지막 판을 고친 사람도 같은 규칙을 따른다
- 게시하면 `published_revision`을 지금 판으로 바꾸고 `published_at`을 기록한다
- 보관(`archived`)하면 포털과 추천에서 빠진다. 링크된 티켓의 기록은 남는다

### 검색과 추천

- 포털 검색: 로그인한 직원에게 `published`이면서 `portal`인 문서만. 제목 trgm 유사도와 본문 `tsvector`(simple) 일치를 합쳐 순서를 매긴다. 결과는 20건씩
- 포털 접수 양식: 제목을 입력하면(입력을 멈추고 400 ms 뒤) 비슷한 문서 상위 3개를 보여 준다. "이 문서로 해결됐어요"를 누르면 접수하지 않고 끝나며 해결 수를 센다(도움 피드백으로 기록)
- 처리자 답변: 티켓 제목으로 문서를 추천하고(내부 문서 포함), 고르면 문서 링크와 요약을 답변 초안에 넣는다. 요청자에게 보낼 때 내부 문서이면 경고하고 링크를 넣지 않는다
- 답변에 문서를 넣으면 `used_in_reply`, 그 티켓이 해결되면 `resolved_by` 링크가 남는다

## 4. 화면

- 포털 `/portal/kb`(검색, 분류, 문서 보기, "도움이 됐어요"), 포털 접수 양식의 추천
- 처리자 `/projects/:id/kb`: 문서 목록(상태·범위·분류·서비스 필터), 편집기(기존 서식 편집기 재사용), 판 비교(이전 판과 나란히), 검토 대기함
- 작업 패키지 상세: "관련 문서" 패널(추천, 링크된 문서)
- 대시보드 위젯: 많이 본 문서, 포털에서 스스로 해결된 수(이번 달), 검토 대기 수
- 가짜 데이터 계층에 같은 규칙, `SCHEMA` 올림

## 5. 보안

- 포털 API는 `published` + `portal`만 낸다. 초안, 내부 문서, 다른 판, 작성자·검토자 정보는 내지 않는다. 숨은 문서 번호로 접근하면 404
- 게시본만 포털에 보이므로, 고치는 중인 초안이 새지 않는다(`published_revision` 기준으로 조회)
- 본문 HTML은 기존 sanitize, 첨부 다운로드는 문서가 포털 공개 게시본일 때만 포털에서 허용
- 피드백은 한 사람 한 표, 조회 수는 같은 사람이 하루에 한 번만 센다(캐시 없이 DB 조건부 insert)

## 6. 테스트

- JUnit
  - `KnowledgeWorkflowIT`: 초안·검토·게시·돌려보내기, 본인 게시 거부, 게시 뒤 수정 시 포털은 이전 게시본
  - `KnowledgeSearchIT`: 범위·상태 필터, 순서, 한국어 제목 검색
  - `PortalKbSecurityIT`: 내부·초안·보관 문서 404, 첨부 접근
  - `AttachmentOwnerCheckIT`: 첨부 두 주인 금지, 고아 정리가 문서 첨부를 지우지 않음
  - `PerfBudgetIT`: 포털 검색, 접수 양식 추천
- e2e: 처리자가 장애에서 문서 초안 → 검토자 게시 → 직원이 포털 접수 양식에서 추천을 보고 "해결됐어요" → 대시보드 수 증가. 내부 문서가 포털에 안 보이는지

## 7. 문서

01·02, 04(KB API), 05(V25, 첨부 변경), 06, 07(새 절: 지식 베이스 찾기·쓰기·검토), 10(새 절: KB 권한·분류), 08·09. 도움말 연결.

## 8. 위험과 대응

| 위험 | 대응 |
| --- | --- |
| 첨부 테이블 변경이 기존 고아 정리에 영향 | 정리 조건을 두 열 모두 null로 바꾸고 IT로 고정 |
| 한국어 검색 품질(형태소 없음) | trgm 유사도 중심, simple `tsvector`는 보조. 모자라면 다음 판에서 다시 본다 |
| 추천 호출이 잦아 부하 | 입력 지연 400 ms, 최소 2글자, 결과 3건, `PerfBudgetIT` |
