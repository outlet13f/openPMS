# 버그 목록

`/replica-test` 2026-10-10. 재현한 것만 적습니다. 의심만 가는 것은 `replica/test-plan.md`의 "확인할 것"에 있습니다.
빌드: 2e89d53 · 브라우저: Chrome(Playwright), 1424px · 실제 API(Spring Boot) + 시드 데이터

| ID | 심각도 | 제목 | 상태 |
| --- | --- | --- | --- |
| BUG-001 | S3 | 저장을 두 번 누르면 작업이 두 개 생김 | 고침 (2872068) |
| BUG-002 | S3 | 오류가 난 첫 칸으로 포커스가 가지 않음 (로그인·새 작업·새 프로젝트) | 고침 (2872068) |
| BUG-003 | S3 | 서식 편집기(코멘트·설명)에 textbox가 가질 수 없는 aria-expanded | 고침 (2872068) |
| BUG-004 | S3 | 캘린더: 다른 달 날짜 숫자 대비 1.5:1, "+N 개" 링크의 허용되지 않는 aria 속성 | 고침 (2872068) |
| BUG-005 | S3 | 팀 플래너: 기한 지난 작업 제목 대비 4.48:1 | 고침 (2872068) |

S1·S2는 없었습니다.

### BUG-001: 저장을 두 번 누르면 작업이 두 개 생김

- 심각도: S3 (중복은 삭제로 되돌릴 수 있음. 다만 알림·활동도 두 번 남음)
- 흐름 / 케이스: F01 / F01-E2
- 화면: S09 새 작업 (목록의 인라인 행도 Enter 두 번이면 같음)

단계
1. 웹 플랫폼 개편 → 만들기 → 작업
2. 제목 "두 번 눌러도 하나" 입력
3. 저장을 더블 클릭

기대: 작업 1개
실제: 같은 제목의 작업 2개 (#22, #23)
증거: `frontend/e2e/f01-create.spec.ts` F01-E2 (고치기 전 "Expected: 1, Received: 2")
원인: 두 번째 클릭이 React가 버튼을 '처리 중'으로 바꾸기 전에 도착해 요청이 두 번 감
상태: fixed in 2872068
고침: 폼과 인라인 행에 요청 중 표시(ref)를 두어 한 번에 하나만 보냄. 서버 쪽 중복 방지(Idempotency-Key)는 "확인할 것"에 남김

### BUG-002: 오류가 난 첫 칸으로 포커스가 가지 않음

- 심각도: S3 (키보드·스크린 리더 사용자는 오류 위치를 찾아가야 함)
- 흐름 / 케이스: F00 / F00-N2, F01 / F01-H1
- 화면: S01 로그인, S09 새 작업, S04 새 프로젝트

단계
1. /login에서 아무것도 입력하지 않고 로그인
2. (또는) 새 작업 폼에서 제목 없이 저장

기대: 오류가 난 첫 입력칸에 포커스
실제: 포커스가 버튼에 남음
증거: F00-N2, F01-H1 `toBeFocused` 실패
원인: `Field`가 입력의 `id`를 자기가 만든 id로 덮어써서, 폼이 `getElementById("login-email")` 같은 id로 찾지 못함
상태: fixed in 2872068
고침: 입력에 이미 id가 있으면 `Field`가 그 id를 쓰고 label도 거기에 연결

### BUG-003: 서식 편집기에 textbox가 가질 수 없는 aria-expanded

- 심각도: S3 (axe critical: 스크린 리더가 잘못된 상태를 읽을 수 있음)
- 흐름 / 케이스: F02-H1, F01-H1 (axe), S08
- 화면: 코멘트 입력, 설명 편집, 새 작업 폼

단계: 작업 상세 활동 탭을 axe로 검사
기대: 위반 없음
실제: `aria-allowed-attr (critical): .tiptap` — role=textbox에 aria-expanded
상태: fixed in 2872068
고침: 멘션 목록은 textbox가 가질 수 있는 `aria-autocomplete="list"` + `aria-activedescendant` + `aria-controls`로 알리고 aria-expanded는 뺌

### BUG-004: 캘린더의 대비와 "+N 개" 링크

- 심각도: S3
- 흐름 / 케이스: a11y S17
- 화면: S17 캘린더(월 보기)

단계: 저장된 캘린더 "팀 일정"을 열고 axe로 검사
실제
- `color-contrast (serious)`: 다른 달 날짜 숫자 #c7cacf on #f6f7f9 = 1.53:1 (FullCalendar가 30% 투명도로 흐리게 함)
- `aria-allowed-attr (critical)`: href 없는 `<a class="fc-more-link">`에 aria-expanded
상태: fixed in 2872068
고침: 다른 달 날짜는 투명도 없이 흐린 글자색(대비 통과)만 씀, "+N 개"에 role="button"

### BUG-005: 팀 플래너의 기한 지난 제목 대비

- 심각도: S3
- 흐름 / 케이스: a11y S18
- 화면: S18 팀 플래너

단계: "웹팀 주간 계획"을 열고 axe로 검사
실제: `color-contrast (serious)`: 기한 지난 작업 제목 #c62f2f on 막대 #e6e9ee = 4.48:1 (기준 4.5)
상태: fixed in 2872068
고침: 막대 위에서는 한 단계 진한 danger 토큰(#a72323)을 씀
