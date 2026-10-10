# 패리티 보고서 (openPMS ↔ OpenProject)

- 작성: 2026-10-11 (`/replica-diff`, 세 번째). 2026-10-10 보고서(78.6)를 대체합니다.
- 빌드: 84f6e6c · 기준: `replica/features.csv`, `replica/screens/`(원본 17.9.1, 사용자 인스턴스) ↔ `replica/clone-screens/`(실제 API, 시드 데이터, 같은 뷰포트 1424×865 · 390×844)
- 도구: `parity.py`, `imgdiff.py`(레이아웃 모드). 차이 이미지·JSON은 `replica/diffs/`(커밋하지 않음)
- 지난 보고서 뒤에 넣은 것: 사내 SSO(a677079), 배포 파일(11fb59b), 목록 그룹화·합계(2bb76a8), 보드 보강(88153b7), 날짜·시간 형식과 주 시작 요일(84f6e6c)

## 결론

| 항목 | 값 |
| --- | --- |
| **종합** | **81.6 / 100** (기능 92.5와 레이아웃 37.9의 가중 합. 지난번 78.6) |
| **기능 패리티** | **92.5 / 100** (141개 항목, 지난번 88.8 → 92.5) |
| must | **64 / 64** 완료 |
| 레이아웃 | 33개 화면 평균 **37.9** ("different"). 이번에는 다시 찍지 않았습니다. 아래 해석 참고 |
| 열린 버그 | S1 0 · S2 0 · S3 0 (`replica/bugs.md`, 찾은 6건 모두 고침) |
| 자동 테스트 | e2e 83개 통과(흐름 F00–F11 + 화면별 axe, 밝은·어두운 테마), JUnit 58개 통과. 접근성 케이스 하나가 한 번 Windows 소켓 오류(`ERR_NO_BUFFER_SPACE`)로 실패했고 다시 돌리니 통과. 결함이 아니라 실행 환경 문제 |
| **판정** | **출시 가능(shippable)** — must 전부, 기능 90 이상, 열린 S1·S2 없음. 세 기능을 넣은 배포 이미지는 다시 빌드·기동 점검 중이고 결과는 `replica/deploy.md`에 따로 적습니다. "원본보다 나음"은 `/replica-entrepreneur`의 개선을 넣은 뒤 |

### 레이아웃 점수 해석

> 2026-10-10 캡처(빌드 32bbcca)로 낸 점수를 그대로 둡니다. 그룹화 머리글·합계 행(S06), 빈 보드·보드 필터(S15·S16), 날짜 형식 절(S33)은 캡처에 없습니다. 다음 `/replica-diff`에서 다시 찍습니다.

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
- 프로젝트                          78.6  (11 features)
- 멤버·권한                         83.3  (6 features)
- Gantt                         83.3  (8 features)
- 공통                            91.2  (8 features)  ← 79.4
- 알림                            92.3  (11 features)
- 일정                            92.9  (10 features)
- 작업 패키지                        94.6  (24 features)
- 보드                            94.7  (8 features)  ← 68.4
- 목록·쿼리                         95.0  (17 features)  ← 87.5
- 인증                           100.0  (4 features)  ← 80.0
- 계정                           100.0  (1 features)
- 관계·계층                        100.0  (7 features)
- 진행률                          100.0  (4 features)
- 캘린더                          100.0  (1 features)
- 팀 플래너                        100.0  (1 features)
- 검색                           100.0  (2 features)
- 홈                            100.0  (2 features)
- 관리                           100.0  (12 features)

## 빠진 기능 — 만들 순서

- [should] Gantt: Gantt 날짜 없는 항목에 막대 그려 날짜 지정, no
- [should] 알림: 알림 이유별·프로젝트별 필터, no  (S19 | 빌드: 읽지 않음/모두만)
- [should] 일정: 날짜 대화상자의 선행·후속·하위 탭, no  (S10)
- [should] 프로젝트: 템플릿에서 프로젝트 생성, no  (S04·프로젝트 설정 템플릿)
- [should] 멤버·권한: 글로벌 역할(프로젝트 생성 권한 등), partial  (S31 | 빌드: 전역 역할 만들기·권한 편집. 사람에게 배정하는 UI 없음)
- [should] 보드: 담당자·버전·하위 프로젝트·부모-자식 보드, partial  (S16. 원본 Community에서 모두 사용 가능 | 빌드: 담당자·버전 보드. 하위 프로젝트·상위-하위 없음)
- [should] 작업 패키지: 일괄 편집·이동·복사·삭제(우클릭 컨텍스트 메뉴), partial  (S06 | 빌드: 목록의 행 체크박스(넓은 화면) → 상태·담당자·우선순위·다른 프로젝트로 이동·삭제. 전부 성공하거나 전부 되돌리고, 안 되는 항목마다 이유를 보여 줌. 우클릭 메뉴와 일괄 복사는 없음)
- [should] 프로젝트: 하위 프로젝트 계층, partial  (parent_id | 빌드: 목록 표시·하위 포함 조회만. 상위 지정 UI 없음)
- [could] Gantt: Gantt 라벨 위치 설정(왼쪽·오른쪽·먼 오른쪽), no  (query_props tll)
- [could] 공통: 키보드 단축키, no  (사용자 설정에 끄기 옵션 있음)
- [could] 내보내기: PDF 내보내기(테이블·보고서·Gantt), no  (S12 | 한글 글꼴(OFL) 내장이 필요해 다음 단계로 미룸)
- [could] 멤버·권한: 권한 보고서(역할 × 권한 표), no  (/admin/roles/report)
- [could] 목록·쿼리: 기준선 비교(과거 시점 대비 변경 표시), no  (S06 기준선 버튼)
- [could] 목록·쿼리: 속성 강조 표시(행·셀 색상), no  (S11 강조 표시 탭)
- [could] 연동: REST API(사내 연동용), no  (원본 API v3. 형태는 새로 설계 | 빌드: 화면용 /api/v1 약 120개 경로는 있음(세션 쿠키·CSRF). 외부 연동용 API 토큰·OpenAPI 문서·버전 정책은 없음)
- [could] 연동: 웹훅, no  (관리: API 및 webhook)
- [could] 작업 패키지: 내부 코멘트(특정 역할만 보기), no  (S25 내부 코멘트 탭)
- [could] 작업 패키지: 활동 필터·정렬 순서, no  (S08 모두 표시·오래된 순)
- [could] 프로젝트: 프로젝트 복사, no
- [could] 공통: 다크 모드·고대비 테마, partial  (auto-theme-switcher 관찰 | 빌드: S37 테마(시스템·라이트·다크), 다크 토큰 AA 대비 통과. 고대비 테마는 없음)
- [could] 프로젝트: 프로젝트 개요 위젯 대시보드, partial  (S05 | 빌드: 고정 위젯(설명·현황·상태·멤버))

지난 보고서에서 빠진 목록에 있다가 이번에 끝난 것: 날짜·시간 형식과 주 시작 요일, 그룹화와 그룹별 합계, 합계 행 표시, 기본 보드(수동 목록), 보드 필터, 보드 컬럼에서 카드 생성·기존 항목 추가, 사내 SSO(OIDC) 로그인.

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
| F04 일정 | 드래그 1회 / 대화상자 4회 | 같음. 근무일·휴무일을 대화상자 달력에 표시 | 유지 |
| F05 선후행 | 4–5회 | 같음 | 유지 |
| F06 새 보드 | 3–4회 | 3회(빈 보드도 같음). 카드는 끌기·키보드로 옮기고, 열 추가·순서·빼기는 메뉴로(원본은 열도 끌어다 놓기). 열의 + 로 그 자리에서 카드 만들기, 보드 필터 | 열 끌어다 놓기는 다음 후보 |
| F07 계층 | 3–4회 | 같음 | 유지 |
| F08 멘션 | 3–4회 + 메일 | 같음. 메일은 1분 모았다 보냄, 하루 요약 메일 | 유지 |
| F09 알림 | 2회 | 같음. 이유별·프로젝트별 필터는 없음 | 필터 추가 후보 |
| F10 프로젝트·팀 | 약 9회 | 약 8회. 템플릿에서 만들기는 없음 | 템플릿 후보 |
| F11 업무 규칙 | 6회 + 체크 | 같음. 유형 양식 구성은 원본에서 Enterprise인 그룹 편집까지 됨 | 유지 |
| 오류 처리 | 동시 편집 충돌 시 안내 | 409 + "최신 내용을 불러온 뒤 다시" 안내, 일괄 편집은 전부 되돌리고 항목별 이유 | 유지 |
| 기억하는 것 | 사이드바 접기, 저장된 보기, 설정 | 같음 + 테마, 내 페이지 위젯, 이탈 경고 설정 | 유지 |
| 메일 | 멘션·요약·날짜 알림·초대·재설정 | 같음(사내 SMTP 연결은 배포 때) | 유지 |

## 다음에 만들 다섯 가지

지난 다섯 가지(배포 검증, 사내 SSO, 목록 그룹화·합계, 보드 보강, 날짜·시간 형식)는 모두 끝났습니다. 빠진 목록에서 다음 다섯 가지를 고릅니다.

1. **알림 이유별·프로젝트별 필터** (should, 알림 92.3): S19 왼쪽에 이유(멘션·담당·책임·주시·날짜 알림)와 프로젝트 목록. 설계(`replica/architecture.md` §4.2 `GET /notifications`)에는 `reason`·`projectId`가 있지만 구현에는 아직 없어 서버와 화면을 같이 고침.
2. **날짜 대화상자의 선행·후속·하위 탭** (should, 일정 92.9): S10에서 날짜를 정하면서 어떤 작업이 날짜를 끌고 있는지 바로 봄. 관계 데이터는 이미 있음.
3. **Gantt에서 날짜 없는 항목에 막대 그리기** (should, Gantt 83.3): 빈 행을 끌어 시작·완료일을 정함. 지금은 날짜 대화상자를 열어야 함.
4. **프로젝트 템플릿과 상위 프로젝트 지정** (should 2개, 프로젝트 78.6. should가 남은 영역 중 가장 약함): 템플릿에서 만들기(S04)와 S04·S24의 상위 프로젝트 선택. 둘 다 프로젝트 생성·설정 화면을 같이 고침.
5. **글로벌 역할 배정 UI** (should partial, 멤버·권한 83.3): 관리 → 사용자에서 사람에게 전역 역할(프로젝트 생성 등)을 줌. 역할·권한 편집은 이미 있어 배정 화면만 남음.

그 뒤 후보: 하위 프로젝트·상위-하위 보드, 우클릭 메뉴와 일괄 복사, PDF 내보내기(한글 글꼴). 이 다섯 가지 뒤에는 `/replica-entrepreneur`로 원본 사용자 불만을 고쳐 "원본보다 나은" 단계로 가는 것을 권합니다.
