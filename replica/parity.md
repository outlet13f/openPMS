# 패리티 보고서 (openPMS ↔ OpenProject)

- 작성: 2026-10-11 (`/replica-diff`, 네 번째). 같은 날 앞선 보고서(81.6)를 대체합니다.
- 빌드: 5d641d1(코드) · f4ff7a3(캡처·features.csv만 더함) · 기준: `replica/features.csv`, `replica/screens/`(원본 17.9.1, 사용자 인스턴스) ↔ `replica/clone-screens/`(실제 API, 시드 데이터, 같은 뷰포트 1424×865 · 390×844)
- 도구: `parity.py`, `imgdiff.py`(레이아웃 모드). 차이 이미지·JSON은 `replica/diffs/`(커밋하지 않음)
- 지난 보고서 뒤에 넣은 것(남은 should 8개 전부): Gantt 날짜 없는 행에 막대 그리기(f378a86), 알림 이유별·프로젝트별 필터(57722a9), 날짜 대화상자의 선행·후속·하위 탭(20646b9), 프로젝트 템플릿(a3aee0b), 전역 역할 배정(0af87f8), 하위 프로젝트·상위-하위 보드(388400b), 목록 우클릭 메뉴와 일괄 복사(d0023b1), 상위 프로젝트 지정(5d641d1). features.csv의 "활동 필터·정렬 순서"는 정렬을 계정 설정으로 바꿀 수 있어 no → partial로 고침(f4ff7a3)

## 결론

| 항목 | 값 |
| --- | --- |
| **종합** | **84.7 / 100** (기능 96.4 × 0.8 + 레이아웃 37.9 × 0.2. 지난번 81.6) |
| **기능 패리티** | **96.4 / 100** (141개 항목, 지난번 92.5 → 96.4) |
| must | **64 / 64** 완료 |
| should | **51 / 51** 완료(이번에 남은 8개를 끝냄) |
| 레이아웃 | 33개 화면 평균 **37.9** ("different"). 이번에도 다시 재지 않았습니다. 아래 해석 참고 |
| 열린 버그 | S1 0 · S2 0 · S3 0 (`replica/bugs.md`, 찾은 11건 모두 고침). 코드에서 본 의심 하나(하위 프로젝트 보드의 열 이름)는 재현 전이라 `replica/test-plan.md` "확인할 것"에 둠 |
| 자동 테스트 | e2e 95개 통과(흐름 F00–F12 + 화면별 axe, 밝은·어두운 테마), JUnit 68개 통과. 5d641d1 코드로 두 스위트를 모두 돌림(f4ff7a3은 캡처와 CSV만 바꿈) |
| **판정** | **출시 가능(shippable)** — must·should 전부, 기능 90 이상, 열린 S1·S2 없음. 남은 것은 could 13개(없음 10 · 일부 3)뿐. 21a23fb 코드로 빌드한 배포 이미지를 빈 DB에서 점검해 통과(replica/deploy.md 재검증 3). |

### 레이아웃 점수 해석

> 2026-10-10 캡처(빌드 32bbcca)로 낸 점수를 그대로 둡니다. 그 뒤 새 상태를 따로 찍었지만(384a2f9: S06-grouped, S15-basic, S15-filters, S33-display / f4ff7a3: S19-filters, S10-tabs, S04-template, S15-parent-child, S06-row-menu, 갱신한 S35, 각각 `-mobile` 포함, S06-row-menu는 데스크톱만) 같은 상태의 원본 캡처가 없어 점수에 넣지 않았습니다. 바뀐 S35는 원본 캡처가 없는 화면입니다. 다음 `/replica-diff`에서 원본의 같은 상태를 찍어 다시 잽니다.

레이아웃 점수는 두 화면의 윤곽선을 격자로 나눠 무엇이 같은 자리에 있는지만 봅니다. 37.9는 낮아 보이지만, 화면을 직접 겹쳐 보면 **뼈대는 같습니다**: 위 헤더(메뉴·검색·만들기·알림·도움말·계정), 왼쪽 사이드바(모듈·저장된 보기), 가운데 본문, 오른쪽 분할 상세. 점수를 깎는 것은 주로

1. **데이터 차이**: 원본은 OpenProject 데모 데이터(행 14개, 긴 환영 글, 미팅·뉴스 위젯), 클론은 우리 시드(작업 21개). 홈(S02 22.9)은 원본이 마케팅 글로 화면을 채우고 클론은 할 일 요약이라 특히 낮습니다.
2. **의도한 차이**: 사이드바 모듈 수(원본 시간 추적·미팅·뉴스·위키는 범위 밖), 목록의 체크박스 열(S06 일괄 편집), 관리 화면을 탭 대신 한 화면으로 정리(S27·S28).
3. **원본이 보여 주지 못한 화면**: S17 캘린더(원본 모듈 꺼짐, 403)와 S18 팀 플래너(Enterprise 안내)는 비교할 수 없어 점수에서 뺐습니다. S11·S34–S37은 원본 캡처가 없습니다.

색은 일부러 다르므로(`/replica-brand`에서 다시 정함) 점수에 넣지 않습니다. 픽셀까지 맞추지 않습니다.

| 화면 | 이름 | 점수 | 판정 | 가장 큰 차이 |
| --- | --- | --- | --- | --- |
| S02 | 홈 | 22.9 | different | 가운데 |
| S03 | 프로젝트 목록 | 37.8 | different | 위 가운데 |
| S04 | 새 프로젝트 | 32.5 | different | 가운데 |
| S05 | 프로젝트 개요 | 34.4 | different | 가운데 |
| S06 | 작업 목록 | 49.6 | different | 왼쪽 가운데 |
| S06-filters | 목록 + 필터 | 44.7 | different | 가운데 |
| S06-mobile | 목록 (390px) | 35.4 | different | 가운데 |
| S07 | 분할 상세 | 54.3 | partly | 아래 가운데 |
| S07-mobile | 상세 (390px) | 30.9 | different | 위 가운데 |
| S08 | 전체 상세 | 31.4 | different | 오른쪽 가운데 |
| S08-relations | 관계 탭 | 36.7 | different | 왼쪽 가운데 |
| S09 | 새 작업 | 52.0 | partly | 왼쪽 아래 |
| S10 | 날짜 대화상자 | 45.1 | different | 가운데 |
| S12 | 내보내기 | 30.5 | different | 가운데 |
| S13 | Gantt | 42.8 | different | 가운데 |
| S14 | 보드 목록 | 37.6 | different | 오른쪽 위 |
| S15 | 보드 | 54.3 | partly | 가운데 |
| S16 | 새 보드 | 25.8 | different | 가운데 |
| S19 | 알림 | 28.2 | different | 가운데 |
| S20 | 알림 설정 | 38.0 | different | 왼쪽 가운데 |
| S21 | 내 페이지 | 28.7 | different | 가운데 |
| S22 | 검색 | 34.3 | different | 왼쪽 가운데 |
| S23 | 멤버 | 46.2 | different | 위 가운데 |
| S24-modules | 프로젝트 설정·모듈 | 38.1 | different | 왼쪽 가운데 |
| S25 | 프로젝트 설정·작업 | 31.3 | different | 아래 가운데 |
| S26 | 버전 | 38.9 | different | 가운데 |
| S27 | 관리·유형 | 38.0 | different | 위 가운데 |
| S28 | 관리·양식 구성 | 35.6 | different | 가운데 |
| S29 | 관리·워크플로 | 46.1 | different | 위 가운데 |
| S30 | 관리·상태 | 30.5 | different | 왼쪽 가운데 |
| S31 | 관리·역할 | 37.4 | different | 왼쪽 가운데 |
| S32 | 관리·진행률 | 52.3 | partly | 가운데 |
| S33 | 관리·근무일 | 28.1 | different | 가운데 |

## 영역별 기능 점수 (약한 순)

- 연동                             0.0  (2 features)
- 내보내기                          66.7  (2 features)
- 공통                            91.2  (8 features)
- 멤버·권한                         91.7  (6 features)  ← 83.3
- 프로젝트                          92.9  (11 features)  ← 78.6
- Gantt                         94.4  (8 features)  ← 83.3
- 목록·쿼리                         95.0  (17 features)
- 작업 패키지                        97.3  (24 features)  ← 94.6
- 인증                           100.0  (4 features)
- 계정                           100.0  (1 features)
- 관계·계층                        100.0  (7 features)
- 일정                           100.0  (10 features)  ← 92.9
- 진행률                          100.0  (4 features)
- 보드                           100.0  (8 features)  ← 94.7
- 캘린더                          100.0  (1 features)
- 팀 플래너                        100.0  (1 features)
- 알림                           100.0  (11 features)  ← 92.3
- 검색                           100.0  (2 features)
- 홈                            100.0  (2 features)
- 관리                           100.0  (12 features)

## 빠진 기능 — 만들 순서

남은 것은 모두 could입니다.

- [could] Gantt: Gantt 라벨 위치 설정(왼쪽·오른쪽·먼 오른쪽), no  (query_props tll)
- [could] 공통: 키보드 단축키, no  (사용자 설정에 끄기 옵션 있음)
- [could] 내보내기: PDF 내보내기(테이블·보고서·Gantt), no  (S12 | 한글 글꼴(OFL) 내장이 필요해 다음 단계로 미룸)
- [could] 멤버·권한: 권한 보고서(역할 × 권한 표), no  (/admin/roles/report)
- [could] 목록·쿼리: 기준선 비교(과거 시점 대비 변경 표시), no  (S06 기준선 버튼)
- [could] 목록·쿼리: 속성 강조 표시(행·셀 색상), no  (S11 강조 표시 탭)
- [could] 연동: REST API(사내 연동용), no  (원본 API v3. 형태는 새로 설계 | 빌드: 화면용 /api/v1 약 120개 경로는 있음(세션 쿠키·CSRF). 외부 연동용 API 토큰·OpenAPI 문서·버전 정책은 없음)
- [could] 연동: 웹훅, no  (관리: API 및 webhook)
- [could] 작업 패키지: 내부 코멘트(특정 역할만 보기), no  (S25 내부 코멘트 탭)
- [could] 프로젝트: 프로젝트 복사, no
- [could] 공통: 다크 모드·고대비 테마, partial  (auto-theme-switcher 관찰 | 빌드: S37 테마(시스템·라이트·다크), 다크 토큰 AA 대비 통과. 고대비 테마는 없음)
- [could] 작업 패키지: 활동 필터·정렬 순서, partial  (S08 | 빌드: 정렬은 계정 설정 '최신 활동 위로'(S37)로 바꿈. 활동 필터(코멘트만·변경만)는 없음)
- [could] 프로젝트: 프로젝트 개요 위젯 대시보드, partial  (S05 | 빌드: 고정 위젯(설명·현황·상태·멤버))

- REST API 항목의 "약 120개 경로"는 features.csv에 적힌 옛 숫자입니다. 지금 컨트롤러 매핑은 156개(dev 전용 1개 포함)입니다(`replica/backend.md`).
- "프로젝트 복사"는 템플릿 복사(`project/ProjectCopy`)가 같은 일을 대부분 합니다. 아무 프로젝트에서나(템플릿 표시 없이) 복사하는 진입점만 더하면 됩니다.

지난 보고서에서 빠진 목록에 있다가 이번에 끝난 것: Gantt 날짜 없는 항목에 막대 그리기, 알림 이유별·프로젝트별 필터, 날짜 대화상자의 선행·후속·하위 탭, 템플릿에서 프로젝트 생성, 글로벌 역할(배정 화면), 하위 프로젝트·부모-자식 보드, 일괄 복사와 우클릭 메뉴, 하위 프로젝트 계층(상위 지정). 활동 필터·정렬 순서는 no → partial.

## 일부러 뺀 것 (점수 제외)

- 시간 추적·비용·예산: 이번 범위 밖. 다음 단계 후보
- 미팅: 범위 밖
- 위키: 범위 밖
- 뉴스: 범위 밖
- 문서: 범위 밖
- 포럼: 범위 밖
- 백로그(스크럼 스프린트·스토리 포인트): 범위 밖. 스크럼 팀이 있으면 재검토
- 포트폴리오 관리: 범위 밖. 원본 EE
- 프로젝트 수명 주기(단계·게이트)와 프로젝트 단계 속성: 범위 밖. 17.x 신규 기능
- BIM·BCF: 범위 밖
- GitHub·GitLab 연동과 리포지토리: 범위 밖
- 외부 파일 저장소(Nextcloud·OneDrive): 범위 밖
- Jira 가져오기: 범위 밖. 이관이 필요하면 별도 스크립트로
- MCP·AI 기능: 범위 밖
- LDAP·SAML·SCIM: OIDC SSO로 대체. 사내에 LDAP만 있으면 재검토
- Enterprise 업셀·평가판·결제(Chargebee): 원본의 상업 구조. 사내 도구에 불필요
- OpenProject 상표·로고·아이콘·일러스트: 원본 소유 자산. 새로 디자인
- 원본 한국어 번역 문자열: 원본 소유. 우리 용어집으로 새로 작성
- 원본 데모 데이터와 도움말 문구: 원본 소유. 시드 데이터는 따로 작성
- 원본 소스 코드(GPLv3): 클린룸 원칙: 읽지도 옮기지도 않음

## Screens
- replica/clone-screens/S02.png             22.9
- replica/clone-screens/S16.png             25.8
- replica/clone-screens/S33.png             28.1
- replica/clone-screens/S19.png             28.2
- replica/clone-screens/S21.png             28.7
- replica/clone-screens/S12.png             30.5
- replica/clone-screens/S30.png             30.5
- replica/clone-screens/S07-mobile.png      30.9
- replica/clone-screens/S25.png             31.3
- replica/clone-screens/S08.png             31.4
- replica/clone-screens/S04.png             32.5
- replica/clone-screens/S22.png             34.3
- replica/clone-screens/S05.png             34.4
- replica/clone-screens/S06-mobile.png      35.4
- replica/clone-screens/S28.png             35.6
- replica/clone-screens/S08-relations.png   36.7
- replica/clone-screens/S31.png             37.4
- replica/clone-screens/S14.png             37.6
- replica/clone-screens/S03.png             37.8
- replica/clone-screens/S20.png             38.0
- replica/clone-screens/S27.png             38.0
- replica/clone-screens/S24-modules.png     38.1
- replica/clone-screens/S26.png             38.9
- replica/clone-screens/S13.png             42.8
- replica/clone-screens/S06-filters.png     44.7
- replica/clone-screens/S10.png             45.1
- replica/clone-screens/S29.png             46.1
- replica/clone-screens/S23.png             46.2
- replica/clone-screens/S06.png             49.6
- replica/clone-screens/S09.png             52.0
- replica/clone-screens/S32.png             52.3
- replica/clone-screens/S07.png             54.3
- replica/clone-screens/S15.png             54.3

## 동작 비교 (흐름별 클릭 수와 차이)

| 흐름 | 원본 | 클론 | 판단 |
| --- | --- | --- | --- |
| F01 작업 만들기 | 분할 폼 3회, 인라인 1회 + 입력 | 같음(만들기 → 유형 → 저장 / 맨 아래 행 + Enter). 더블 클릭 중복 방지 추가 | 유지 |
| F02 상태 바꾸기 | pill 2회, 보드 드래그 1회 | 같음. 보드는 키보드로도 옮김(Space·화살표) | 유지(접근성은 클론이 나음) |
| F03 보기 저장 | 약 8회 | 약 9회(필터 → 추가 → 값 → 닫기 → 보기 메뉴 → 다른 이름으로 저장 → 이름 → 저장). URL에 보기 상태가 담겨 그대로 공유 가능. 그룹화·합계 표시는 보기 메뉴에서 2–3회 | 유지 |
| F04 일정 | 드래그 1회 / 대화상자 4회 | 같음. 근무일·휴무일을 대화상자 달력에 표시. 날짜 없는 행은 Gantt에서 끌어 막대를 그림(1회, 키보드는 행의 "날짜 정하기" → 대화상자), 대화상자의 선행·후속·하위 탭에서 날짜를 끄는 작업을 바로 봄 | 유지 |
| F05 선후행 | 4–5회 | 같음 | 유지 |
| F06 새 보드 | 3–4회 | 3회(빈 보드·하위 프로젝트별·상위-하위도 같음). 카드는 끌기·키보드로 옮기고, 열 추가·순서·빼기는 메뉴로(원본은 열도 끌어다 놓기). 열의 + 로 그 자리에서 카드 만들기, 보드 필터. 상위-하위 보드의 열 추가는 작업 검색 대화상자 | 열 끌어다 놓기는 다음 후보 |
| F07 계층 | 3–4회 | 같음 | 유지 |
| F08 멘션 | 3–4회 + 메일 | 같음. 메일은 1분 모았다 보냄, 하루 요약 메일 | 유지 |
| F09 알림 | 2회 | 같음. 이유·프로젝트로 거르기는 선택 상자 2개(항목마다 읽지 않은 수, URL에 남아 항목을 열고 닫아도 유지). 필터 중 "모두 읽음"은 보이는 알림만 | 유지 |
| F10 프로젝트·팀 | 약 9회 | 약 8회. 템플릿에서 시작하면 멤버·작업·보드 등을 골라 한 번에 가져옴(+2회: 템플릿 고르기·복사할 것 확인). 상위 프로젝트는 새 프로젝트·설정의 선택 상자 | 유지 |
| F11 업무 규칙 | 6회 + 체크 | 같음. 유형 양식 구성은 원본에서 Enterprise인 그룹 편집까지 됨. 전역 역할은 관리 · 사용자의 행 메뉴 → "전역 역할" 대화상자(3회) | 유지 |
| F12 일괄 편집 | 행 체크 + 우클릭 메뉴 | 같음. 체크한 행 중 하나를 우클릭하면 체크한 전체, 아니면 그 행. 일괄 바와 같은 동작(상태·담당자·우선순위·복사·이동·삭제), 전부 성공 또는 전부 되돌림 | 유지 |
| 오류 처리 | 동시 편집 충돌 시 안내 | 409 + "최신 내용을 불러온 뒤 다시" 안내, 일괄 편집은 전부 되돌리고 항목별 이유 | 유지 |
| 기억하는 것 | 사이드바 접기, 저장된 보기, 설정 | 같음 + 테마, 내 페이지 위젯, 이탈 경고 설정 | 유지 |
| 메일 | 멘션·요약·날짜 알림·초대·재설정 | 같음(사내 SMTP 연결은 배포 때) | 유지 |

## 다음에 만들 다섯 가지

지난 다섯 가지(알림 필터, 날짜 대화상자 탭, Gantt 막대 그리기, 템플릿·상위 프로젝트, 전역 역할 배정)와 "그 뒤 후보" 중 보드 두 종류·우클릭 메뉴와 일괄 복사는 모두 끝났습니다. must·should가 다 끝났으므로 아래는 모두 could입니다. 기능 점수에서 could 하나는 약 0.3점(가중치 합 320 중 1)이라 점수는 크게 움직이지 않습니다. 쓰는 사람에게 보이는 순서로 고릅니다.

1. **PDF 내보내기** (내보내기 66.7, 가장 약한 화면 기능 영역): S12에 PDF(표·보고서). Noto Sans KR(OFL) 서브셋 내장이 먼저(`replica/architecture.md` §5 "PDF 한글"). Gantt PDF는 그다음.
2. **활동 필터** (partial → yes, 작업 패키지 97.3): S08 활동 탭에 "모두·코멘트만·변경만". 서버는 `GET /work-packages/{n}/activities`에 `filter`만 더하면 됨(정렬은 이미 계정 설정).
3. **권한 보고서** (멤버·권한 91.7): 관리 · 역할 및 권한에 역할 × 권한 표(읽기 전용). 데이터는 `GET /admin/roles`·`/admin/permissions`로 이미 있음. 전역 역할 배정이 생겨 누가 무엇을 할 수 있는지 한눈에 볼 필요가 커짐.
4. **프로젝트 복사** (프로젝트 92.9): 템플릿 복사(`ProjectCopy`)를 템플릿 표시 없이 쓰는 진입점(프로젝트 설정 또는 목록의 행 메뉴). 서버 경로 하나와 화면 대화상자 하나.
5. **Gantt 라벨 위치** (Gantt 94.4): 막대 왼쪽·오른쪽·먼 오른쪽에 무엇을 보일지. spec의 `timeline.labels`는 설계에 이미 있음.

그 뒤 후보: 키보드 단축키, 속성 강조 표시, 기준선 비교, 고대비 테마, 개요 위젯 대시보드, 내부 코멘트. 연동(REST API 토큰·OpenAPI, 웹훅)은 사내에서 붙일 시스템이 정해질 때. 기능 범위는 원본과 거의 같아졌으므로, 다음 큰 단계는 `/replica-entrepreneur`로 원본 사용자 불만을 고쳐 "원본보다 나은" 쪽으로 가는 것을 권합니다.
