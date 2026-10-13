# 버그 목록

`/replica-test` 2026-10-10. 그 뒤 기능을 만들며 e2e가 찾은 것, 2026-10-11 산출물 점검에서 나온 것(BUG-007–011), 같은 날 성능 예산 부하 측정에서 나온 것(BUG-012–014, `replica/perf.md`), 2026-10-13 임시 비밀번호 최종 리뷰의 후속 점검에서 나온 것(BUG-015–016)도 이어 적습니다. 재현한 것만 적습니다. 의심만 가는 것은 `replica/test-plan.md`의 "확인할 것"에 있습니다.
빌드: 2e89d53 · 브라우저: Chrome(Playwright), 1424px · 실제 API(Spring Boot) + 시드 데이터

| ID | 심각도 | 제목 | 상태 |
| --- | --- | --- | --- |
| BUG-001 | S3 | 저장을 두 번 누르면 작업이 두 개 생김 | 고침 (2872068) |
| BUG-002 | S3 | 오류가 난 첫 칸으로 포커스가 가지 않음 (로그인·새 작업·새 프로젝트) | 고침 (2872068) |
| BUG-003 | S3 | 서식 편집기(코멘트·설명)에 textbox가 가질 수 없는 aria-expanded | 고침 (2872068) |
| BUG-004 | S3 | 캘린더: 다른 달 날짜 숫자 대비 1.5:1, "+N 개" 링크의 허용되지 않는 aria 속성 | 고침 (2872068) |
| BUG-005 | S3 | 팀 플래너: 기한 지난 작업 제목 대비 4.48:1 | 고침 (2872068) |
| BUG-006 | S3 | 접기 단추를 다시 눌러도 다시 접힘 (목록 트리·그룹, Gantt) | 고침 (2bb76a8) |
| BUG-007 | S3 | 로그인 실패로 잠긴 사람이 관리 · 사용자에 '활성'으로만 보이고 풀 메뉴가 없음 | 고침 (384a2f9) |
| BUG-008 | S2 | 리버스 프록시 뒤에서 모든 사람이 로그인 요청 제한 하나를 같이 씀 | 고침 (384a2f9, Podman 기본값 90006c2) |
| BUG-009 | S3 | SSO로 들어온 사람은 비밀번호를 만들 수 없음 | 고침 (384a2f9) |
| BUG-010 | S3 | 합계의 완료 %가 진행률 설정을 따르지 않고, 남은 작업이 비어 있으면 끝난 것으로 셈 (신규 합계 100%) | 고침 (384a2f9, 31e39bd) |
| BUG-011 | S4 | 보기 메뉴에서 빠르게 연달아 바꾸면 앞의 변경이 사라짐 | 고침 (31e39bd) |
| BUG-012 | S1 | CPU 2개 서버에서 동시에 여러 명이 쓰면 모든 요청이 30초 멈추고 일부는 오류 | 고침 (a81a4f6) |
| BUG-013 | S3 | 카드 1,000장 보드가 1.6초 걸리고 브라우저 메모리를 250 MB 씀 | 고침 (a81a4f6) |
| BUG-014 | S3 | 화면 파일(JS 1.4 MB)을 압축도 캐시도 없이 페이지를 열 때마다 다시 받음 | 고침 (a81a4f6) |
| BUG-015 | S2 | API 토큰이 글자 하나를 퍼센트 인코딩한 주소로 토큰·비밀번호·연동 설정 경로에 닿음 | 고침 (69c2760) |
| BUG-016 | S2 | 관리자가 아닌 사용자 관리자가 관리자 계정을 잠그고 이름을 바꾸고 삭제할 수 있음 | 고침 (0e229d6) |

S1은 BUG-012 하나입니다(2026-10-11 성능 예산 부하 측정, 같은 날 고침). S2는 BUG-008(2026-10-11 산출물 점검), BUG-015(2026-10-13, 임시 비밀번호 작업 중 찾은 우회가 기존 닫힌 경로에도 있었음), BUG-016(2026-10-13, 임시 비밀번호의 '관리자 계정은 관리자만' 규칙이 잠그기·삭제에는 없었음) 셋입니다.

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

### BUG-006: 접기 단추를 다시 눌러도 다시 접힘

- 심각도: S3 (펼치려면 한 번 더 눌러야 함. 데이터는 바뀌지 않음)
- 흐름 / 케이스: F03 / F03-G1
- 화면: S06 작업 목록(계층 트리, 그룹 머리글), S13 Gantt(왼쪽 목록의 트리)
- 빌드: 2bb76a8 작업 중(그룹화를 만들던 작업 트리), 개발 서버(Vite) + 실제 API

단계
1. 웹 플랫폼 개편 → 작업 목록 → 보기 메뉴 → 그룹화 → 상태
2. 첫 그룹 머리글을 눌러 접기
3. 같은 머리글을 다시 눌러 펼치기

기대: 그룹이 펼쳐지고 `aria-expanded="true"`
실제: 다시 접힌 채로 남을 수 있음(`aria-expanded="false"`)
증거: `frontend/e2e/f03-grouping.spec.ts` F03-G1 (고치기 전 두 번째 클릭 뒤 `toHaveAttribute("aria-expanded", "true")` 실패)
원인: 접기 상태(Set)를 바꾸는 React 상태 updater가 이전 Set을 직접 고쳤음(`s.add`·`s.delete`). StrictMode는 개발 모드에서 updater를 두 번 돌리는데, 두 번째 실행이 이미 고쳐진 Set을 보고 반대로 뒤집음. 목록 트리와 Gantt의 접기 단추도 같은 코드였음. 운영 빌드는 updater를 한 번만 돌리지만 이전 상태를 고치는 것 자체가 React 규칙 위반
상태: fixed in 2bb76a8
고침: updater가 이전 Set을 건드리지 않고 새 Set을 만들어 돌려줌(목록은 `flip` 함수, Gantt는 같은 방식으로 한 줄). 트리·그룹·Gantt 세 곳 모두

### BUG-007: 로그인 실패로 잠긴 사람이 '활성'으로만 보이고 풀 메뉴가 없음

- 심각도: S3 (관리자는 잠그기 → 잠금 해제로 돌아가서 풀 수 있었음. 다만 왜 로그인이 안 되는지 화면에서 알 수 없음)
- 흐름 / 케이스: F11 / F11-E2
- 화면: S35 관리 · 사용자
- 빌드: 3f2161d(384a2f9 이전), 실제 API

단계
1. 한도윤(doyoon.han@openpms.local)으로 틀린 비밀번호 5번 → 맞는 비밀번호도 423 "잠긴 계정입니다…"
2. 관리자로 관리 → 사용자
3. "잠김" 필터, 한도윤 행 메뉴

기대: 잠금 중이라는 것과 끝나는 시각이 보이고, "잠김" 필터로 찾고, 행 메뉴로 바로 풂
실제: 상태 "활성", "잠김" 필터에 없음, 행 메뉴에 **잠금 해제** 없음(운영자 매뉴얼 1.1 9.2.4는 **잠그기** → **잠금 해제** 우회를 안내)
증거: `backend/src/test/java/com/openpms/auth/LockoutIT.java`, `frontend/e2e/f11-admin.spec.ts` F11-E2
원인: 실패 잠금은 `users.status`가 아니라 `users.locked_until`인데 `AdminUser`에 그 값이 없었고, 목록 필터는 `status`만 봄
상태: fixed in 384a2f9
고침: `AdminUser.lockedUntil`(상태가 active이고 잠금 시각이 미래일 때만, 스냅샷이 아니라 DB에서 바로 읽음). 화면은 "일시 잠김" 배지와 "로그인 실패가 많아 {시각}까지", `status=locked` 필터가 일시 잠김도 포함, 행 메뉴 **잠금 해제**(`PATCH status=active`가 실패 횟수와 `locked_until`을 지움)

### BUG-008: 리버스 프록시 뒤에서 모든 사람이 로그인 요청 제한 하나를 같이 씀

- 심각도: S2 (로그인이 몰리는 시간에 한 사람의 시도까지 합쳐 분당 20회를 넘으면 그 1분 동안 모든 사람이 로그인하지 못함)
- 흐름 / 케이스: F00 / F00-N3
- 화면: S01 로그인(재설정 메일 요청도 같음)
- 빌드: 3f2161d(384a2f9 이전), 운영 구성(사내 리버스 프록시 → 127.0.0.1:8090)

단계
1. 프록시를 거쳐 서로 다른 사람이 1분 안에 로그인을 21번 시도(틀린 비밀번호 포함)
2. 21번째 이후의 다른 사람이 로그인

기대: 클라이언트 주소마다 분당 20회
실제: 앱이 보는 주소가 모두 프록시라 한 버킷을 같이 써서 429 "요청이 너무 많습니다."
증거: `backend/src/test/java/com/openpms/auth/ClientAddressIT.java`(무작위 포트의 실제 HTTP, 한도 3으로 낮춤). 고치기 전 설정(프록시 헤더 처리 없음)에서는 모든 요청이 127.0.0.1로 보여 두 번째 사람의 요청도 429가 됨. 운영자 매뉴얼 1.1 10.5가 "배포 때 확인할 것"으로 남겨 둔 문제
원인: 요청 제한 키가 `req.getRemoteAddr()`이고 `application.yml`에 프록시 헤더 처리 설정이 없었음
상태: fixed in 384a2f9(후속 90006c2: Podman 네트워크를 기본 신뢰 범위에 추가)
고침: `server.forward-headers-strategy: ${OPENPMS_FORWARD_HEADERS:native}`, `server.tomcat.remoteip.internal-proxies: ${OPENPMS_TRUSTED_PROXIES:127.x·::1·172.16–31.x}`(90006c2에서 10.88–89.x 추가). 믿는 프록시가 보낸 `X-Forwarded-For`·`X-Forwarded-Proto`만 쓰고, 앞에 가짜 주소를 붙여도 프록시가 붙인 주소를 씀. `.env.example`에 두 변수와 nginx 설정 줄. 운영 절차는 `replica/deploy.md` "프록시 헤더와 클라이언트 주소"
후속 고침(90006c2): 1.2 배포 스모크에서 rootless Podman이 게시 포트로 들어온 연결을 컨테이너 자신의 주소(예: 10.89.1.3, `/proc/net/tcp`)에서 온 것으로 앱에 넘기는 것을 찾음. 이 대역이 기본값에 없어 헤더가 무시되고 프록시 뒤의 모든 사람이 다시 한 버킷을 같이 씀(81053e7 이미지로 재현). 기본값에 Podman 네트워크 `10\.8[89]\.\d+\.\d+`(10.88.x·10.89.x)를 더함. Docker의 192.168.x 풀은 여전히 기본값 밖이라 그때는 변수를 정함(루트 `.env.example`과 `backend/.env.example` 주석). 90006c2 이미지로 확인: `X-Forwarded-For` 주소 29개 → 429 없음, 주소 하나 → 21번째에 429, 앞에 가짜 주소(`198.51.100.1, 198.51.100.7`)를 붙여도 429 유지(`replica/deploy.md` "검증 기록")

### BUG-009: SSO로 들어온 사람은 비밀번호를 만들 수 없음

- 심각도: S3 (SSO로는 계속 들어올 수 있음. 공급자를 지우거나 IdP가 멈추면 들어올 길이 없음)
- 흐름 / 케이스: F00 / F00-S4
- 화면: S37 계정 설정
- 빌드: 3f2161d(384a2f9 이전), 실제 API + 테스트 IdP

단계
1. SSO 공급자의 계정 자동 생성을 켜고 새 사람(정다은)으로 SSO 로그인
2. 계정 설정 → 비밀번호

기대: 비밀번호가 없다는 것과 만드는 방법
실제: "현재 비밀번호"를 요구하는 바꾸기 폼만 있어 쓸 수 없음. 관리자가 비밀번호 재설정을 꺼 두면 다른 방법도 없음
증거: `frontend/e2e/f00-sso.spec.ts` F00-S4(확장), `backend/src/test/java/com/openpms/auth/OidcIT.java`(확장)
원인: 화면과 `Me`에 비밀번호 유무를 알릴 값이 없었고, 비밀번호를 처음 정하는 경로는 초대 수락과 재설정뿐이었음
상태: fixed in 384a2f9
고침: `Me.hasPassword`, `Me.signInWith`. 비밀번호가 없으면 "비밀번호" 절에 안내와 **비밀번호 만들기 링크 받기** → `POST /me/password/setup`(202, 비밀번호가 있으면 422 "이미 비밀번호가 있습니다.", 사용자당 분당 3회). 본인 주소로 재설정과 같은 일회용 링크(유효 `auth.password_reset_ttl_minutes`)를 보내며 재설정 허용 설정과 무관. 링크가 본인 메일로만 가므로 빌린 세션만으로는 비밀번호를 더할 수 없음

### BUG-010: 합계의 완료 %가 진행률 설정을 따르지 않고, 남은 작업이 비어 있으면 끝난 것으로 셈

- 심각도: S3 (잘못된 숫자를 보여 주지만 데이터는 바뀌지 않음)
- 흐름 / 케이스: F03 / F03-G4, F03-G6
- 화면: S06 작업 목록(합계 표시), S07·S08 상세의 ∑ 합계
- 빌드: 3f2161d(384a2f9 이전), 실제 API + 시드 데이터

단계
1. 웹 플랫폼 개편 → 작업 목록 → 완료 % 열 → 보기 메뉴 → 그룹화 → 상태 → 합계 표시
2. "신규" 그룹 합계, "취소" 작업이 있는 그룹, 전체 합계를 봄
3. 관리 → 진행률 추적 → 상위 작업의 합계 완료 %를 **단순 평균**으로 바꾸고 다시 봄

기대: 작업량만 있고 남은 작업이 비어 있는 작업은 시작 전(0%)으로 셈, 합계 제외 상태(취소)는 빠짐, 단순 평균이면 작업들의 % 평균
실제: 신규 그룹 합계 100%(작업량 − 0) ÷ 작업량. 취소 작업도 들어감. 설정을 바꿔도 목록 합계는 늘 작업량 가중
증거: `backend/src/test/java/com/openpms/query/GroupingIT.java`(가중 + 제외, 단순 평균), `backend/src/test/java/com/openpms/wp/ProgressTest.java`(남은 작업 없음 → 25%)
원인: 목록 합계는 보이는 모든 작업의 작업량·남은 작업 합으로 %를 계산했고(`QueryEngine.finish`), 비어 있는 남은 작업은 0으로 더해짐. 작업의 ∑(`Progress.totals`)도 남은 작업이 비면 0으로 봄
상태: fixed in 384a2f9(설정·제외 상태), 31e39bd(남은 작업 없음)
고침: 완료 % 합계는 `progress.totals`를 따르고 `excluded_from_totals` 상태를 뺌. 가중일 때 남은 작업이 비면 작업량으로 셈(목록 합계와 `Progress.totals` 둘 다, 가짜 계층도 같이). 작업량·남은 작업 합계는 그대로 모든 작업

### BUG-011: 보기 메뉴에서 빠르게 연달아 바꾸면 앞의 변경이 사라짐

- 심각도: S4 (다시 고르면 됨)
- 흐름 / 케이스: F03 / F03-G1·G2 주변
- 화면: S06 작업 목록(보기 메뉴의 열·그룹화·합계 표시, 도구 막대의 하위 프로젝트 포함·계층)
- 빌드: 384a2f9, 개발 서버(Vite) + 실제 API

단계
1. 보기 메뉴 → 그룹화 → 상태
2. 주소가 바뀌기 전에 곧바로 보기 메뉴 → 합계 표시

기대: 그룹화와 합계가 둘 다 적용
실제: 두 번째 변경이 지난 렌더의 spec 위에 만들어져 그룹화가 빠질 수 있음
증거: 31e39bd 작업 중 코드 경로로 확인. 이 경우만 따로 재현하는 자동 테스트는 없음(`replica/test-plan.md` "확인할 것")
원인: 메뉴 항목이 렌더 때의 `spec`으로 새 spec을 만들고, 라우터는 URL 변경을 transition으로 적용함
상태: fixed in 31e39bd
고침: `useListState().setSpec`이 최신 spec을 받는 updater를 받고(`useRef`), 보기 메뉴·도구 막대가 모두 updater로 바꿈. 별개로, 저장된 보기를 받기 전에 바꾼 것은 저장된 보기가 도착하면 덮일 수 있어 e2e(`f03-grouping`)는 목록이 뜬 뒤 메뉴를 열도록 고침(384a2f9). 앱 쪽은 그대로임

### BUG-012: CPU 2개 서버에서 동시에 여러 명이 쓰면 모든 요청이 30초 멈추고 일부는 오류

- 심각도: S1 (서버 전체가 30초 동안 응답하지 않고, 그동안의 요청이 500으로 끝남. 작은 사내 VM에서 아침 로그인 몰림만으로 일어날 수 있음)
- 흐름 / 케이스: 전체 (부하 측정 PERF-L1, PERF-L3)
- 화면: 모든 화면 (API 전체)
- 빌드: 0268278, 운영 이미지, 앱 컨테이너 CPU 2개·메모리 1 GB

단계
1. 작업 1,000건 프로젝트를 넣음(`perf-1000.sql`)
2. `scripts/perf_load.py --users 50 --think 2` (50명이 각자 로그인한 뒤 목록·Gantt·보드·상세를 돎) 또는 `--users 20 --think 0`

기대: 모든 요청이 1초 안에 답하고 오류 없음
실제: 시작 직후 응답이 멈추고 30초 뒤 `HikariPool-1 - Connection is not available, request timed out after 30000ms (total=10, active=10, idle=0, waiting=20)`로 500. 어떤 로그에는 `active=2, idle=8, waiting=0`인데도 30초를 기다림(커넥션은 있는데 기다리던 요청이 실행되지 못함). 그동안 DB의 커넥션은 모두 `idle`(ClientRead)
증거: 운영 이미지 로그, 개발 컨테이너(CPU 2개)의 `jcmd Thread.dump_to_file`(가상 스레드 50개 중 다수가 `Snapshot$1.get`(synchronized) → `HikariPool.getConnection`), `-Djdk.tracePinnedThreads=full` 출력(`ConcurrentHashMap.computeIfAbsent <== monitors:1` ← `Snapshot.shares`, 60초에 259번)
원인: 요청은 가상 스레드에서 돌고, Java 21에서는 가상 스레드가 모니터(synchronized, `ConcurrentHashMap.computeIfAbsent`의 잠금) 안에서 기다리면 캐리어 스레드를 놓지 않음. `Snapshot`의 지연 로더가 synchronized 안에서, `shares`가 computeIfAbsent 안에서 DB 커넥션과 쿼리를 기다렸음. 캐리어는 CPU 수만큼(2개)이라 둘이 묶이면 커넥션을 쥔 요청도 실행되지 못해 풀이 30초 타임아웃까지 멈춤. CPU 8개인 개발 PC에서는 드물어 보이지 않았음. 로그인도 트랜잭션으로 커넥션을 쥔 채 비밀번호 해시(약 0.1초)를 확인해 몰릴 때 풀을 더 빨리 비웠음
상태: fixed in a81a4f6
고침: 지연 로더는 `ReentrantLock`, `shares`는 맵 밖에서 조회한 뒤 `putIfAbsent`. 로그인은 트랜잭션 없이(각 갱신은 한 문장). 회귀 테스트: `PerfBudgetIT.noRequestKeepsItsCarrierThreadWhileWaiting`(가상 스레드 20개로 주요 화면을 동시에 요청하며 JFR `jdk.VirtualThreadPinned`를 받아, 우리 코드의 고정이 하나라도 있으면 실패. 고치기 전 코드로 실패 확인), `NoPinningTest`(`src/main/java`에 synchronized 금지). 고친 뒤 같은 조건: 50명 p99 110 ms, 100명 p99 236 ms, 20명 무휴식 화면 88번/초 p99 593 ms, 오류·고정·풀 타임아웃 0건

### BUG-013: 카드 1,000장 보드가 1.6초 걸리고 브라우저 메모리를 250 MB 씀

- 심각도: S3 (쓸 수는 있지만 느리고, 사양이 낮은 PC에서는 더 느림)
- 흐름 / 케이스: F06 / PERF-3
- 화면: S15 보드
- 빌드: 0268278, 빌드된 화면(8090)

단계
1. `scripts/dev.sh perf-seed`
2. 성능 측정 프로젝트 → 보드 → 상태 보드

기대: 1.5초 안에 다 그려짐
실제: 1,564 ms, JS 힙 246 MB(개발 서버 2,739 ms, 259 MB). 열마다 모든 카드(신규 406장 등)를 끌어 놓기 가능한 카드로 한 번에 그림
증거: `frontend/e2e/perf.spec.ts` PERF-3 첫 실행
상태: fixed in a81a4f6
고침: 열마다 50장을 먼저 그리고 **100장 더 보기**(접근 이름에 열 이름과 남은 장수). 열 머리 개수는 전체 장수. 열 자체에 놓은 카드는 보이는 마지막 자리로. 고친 뒤 505 ms, JS 힙 30–70 MB

### BUG-014: 화면 파일(JS 1.4 MB)을 압축도 캐시도 없이 페이지를 열 때마다 다시 받음

- 심각도: S3 (로컬에서는 티가 안 나지만 사내 VPN·느린 망에서 페이지마다 1.4 MB)
- 흐름 / 케이스: 전체 / PERF-5
- 화면: 모든 화면
- 빌드: 0268278, 백엔드가 내주는 빌드된 화면

단계
1. `curl -sI -H "Accept-Encoding: gzip" http://localhost:8090/assets/index-*.js`

기대: gzip으로 압축되고, 이름에 해시가 붙은 파일은 오래 캐시
실제: `Content-Encoding` 없음(1,368,556 B 그대로), `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`. API JSON도 압축 없음(보드 2.3 MB)
원인: `server.compression`을 켜지 않았고, 정적 파일에 캐시 설정이 없어 Spring Security의 기본 no-store가 붙음
상태: fixed in a81a4f6
고침: `server.compression`(JSON·HTML·CSS·JS·SVG·CSV, 2 KB 이상). `/assets/**`는 `max-age=31536000, public, immutable`, 나머지(`index.html`과 화면 주소)는 `no-cache`. 고친 뒤 JS 390 KB 한 번, 다시 열면 17 KB, 보드 응답 약 92–135 KB. 10 Mbps·왕복 40 ms에서 목록 처음 1.0초·다시 0.6초

### BUG-015: API 토큰이 글자 하나를 퍼센트 인코딩한 주소로 토큰·비밀번호·연동 설정 경로에 닿음

- 심각도: S2 (토큰을 가진 사람이 토큰으로 새 토큰을 만들어 폐기를 피하거나, 관리자의 토큰이면 웹훅을 걸어 작업 데이터를 바깥 주소로 보낼 수 있음. 비밀번호 변경은 현재 비밀번호가 있어야 함)
- 흐름 / 케이스: F15 / F15-A2 (`ApiTokensIT`)
- 화면: 없음 (API)
- 빌드: e091c2e, 실제 API(MockMvc)

단계
1. 관리자가 쓰기 API 토큰을 만듦
2. `GET /api/v1/me/%61pi-tokens`에 `Authorization: Bearer <토큰>` (`%61`은 `a`)

기대: 403 "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다."
실제: 200 (내 토큰 목록). 같은 방식으로 `POST /api/v1/me/%61pi-tokens`(새 토큰), `GET /api/v1/admin/%77ebhooks`, `PUT /api/v1/me/p%61ssword`에도 닿음
증거: `ApiTokensIT.aPercentEncodedLetterOpensNoClosedPath`가 고치기 전 코드에서 `Status expected:<403> but was:<200>`로 실패
원인: `ApiTokenFilter`가 닫힌 경로를 디코딩 전 요청 주소(`getRequestURI`)로 비교함. Spring Security의 방화벽은 `%2F`·`%25`·`%3B` 같은 일부 인코딩만 거부하고 글자를 인코딩한 `%61`은 통과시키며, Spring MVC는 디코딩한 경로로 라우팅함. 임시 비밀번호 작업(bc3a14e)에서 같은 우회를 그 경로에 대해 찾아 그 컨트롤러에서 따로 막았고, 기존 닫힌 경로는 이번에 고침
상태: fixed in 69c2760
고침: `ApiTokenFilter`가 Spring과 같은 디코딩된 경로(`UrlPathHelper.getPathWithinApplication`)로 판단. 회귀 테스트: `ApiTokensIT.aPercentEncodedLetterOpensNoClosedPath`(인코딩한 경로 네 가지가 403, 토큰으로 만든 토큰 없음). JUnit 117/117. 실제 서버(Tomcat)를 거치는 e2e F15는 다음 전체 실행 때 확인(경로 디코딩은 MockMvc와 같음). 디코딩 전 주소를 쓰는 나머지 두 곳(SSO 공급자 이름 확인, 중복 방지 저장)은 더 엄격하게 막히는 쪽이라 그대로 둠

### BUG-016: 관리자가 아닌 사용자 관리자가 관리자 계정을 잠그고 이름을 바꾸고 삭제할 수 있음

- 심각도: S2 (사용자·그룹 관리 전역 권한만 받은 사람이 관리자를 잠가 내쫓거나 관리자 계정을 익명화(삭제)할 수 있음. 관리자 지정·해제와 전역 역할은 이미 관리자만 가능했음)
- 흐름 / 케이스: F11 / F11-N2, F19-T3 (`GlobalRolesIT`)
- 화면: S35 관리 · 사용자 (행 메뉴 잠그기·잠금 해제·삭제)
- 빌드: e847c71, 실제 API(MockMvc)

단계
1. 관리자가 전역 역할 '사용자 관리자'(`global.manage_users`)를 일반 사용자에게 줌
2. 관리자가 둘 이상인 상태에서, 그 사용자 관리자가 `PATCH /api/v1/admin/users/<다른 관리자>` `{"status":"locked"}`

기대: 403 "관리자 계정은 관리자만 다룰 수 있습니다." (임시 비밀번호 발급·보기와 같은 규칙)
실제: 200 (잠김, 그 관리자의 세션 종료). 이름 바꾸기와 `DELETE`(익명화)도 됨. 관리자가 한 명뿐이면 "마지막 관리자입니다."(422)가 대신 막아 개발 데이터에서는 드러나지 않았음
증거: `GlobalRolesIT.aUserManagerWhoIsNotAnAdministratorCannotLockRenameOrDeleteAnAdministrator`가 고치기 전 코드에서 `Status expected:<403> but was:<200>`로 실패(처음에는 관리자가 한 명이라 422로 실패해 둘째 관리자를 만들어 다시 확인)
원인: `AdminController.updateUser`·`removeUser`가 사용자 관리 권한만 보고, 대상이 관리자인지는 `isAdmin` 변경 때만 확인함
상태: fixed in 0e229d6
고침: 두 곳에서 대상이 관리자이고 요청한 사람이 관리자가 아니면 403. 화면은 사용자 관리자에게 관리자 행의 잠그기·잠금 해제·삭제를 보이지 않음(관리자 행에는 메뉴 단추 자체가 없음), 가짜 데이터 계층도 같은 403. 회귀 테스트: 위 JUnit, e2e F19-T3(관리자 행에 메뉴 없음, `update` 403). JUnit 118/118, e2e F11·F19 11/11
