# Recon 맵: OpenProject (웹)

- 대상: OpenProject 17.9.1 Community Edition (사용자 본인의 셀프 호스팅 인스턴스, 한국어 UI, PostgreSQL 17)
- 범위: **작업 패키지 핵심 + 계획 뷰** — 프로젝트, 멤버·역할, 작업 패키지(유형·상태·워크플로·사용자 정의 필드), 목록 뷰와 필터, 상세 화면, 활동·코멘트, 관계·계층, 알림, Gantt, 보드, 캘린더·팀 플래너
- 용도: 사내 업무용 도구 (openPMS)
- 작성일: 2026-10-09
- 방식: 클린룸. 기능과 UX 패턴만 분석합니다. 소스 코드, JS 번들, 내부 네트워크 호출은 보지 않았습니다. 스크린샷은 `replica/screens/`에 참고용으로만 두고, 클론에는 포함하지 않습니다.

> 라이선스 참고: OpenProject는 GPLv3 오픈소스이고, 셀프 호스팅한 Community 버전은 경쟁 제품 개발을 막는 이용 약관이 없습니다. 다만 "OpenProject" 상표·로고·아이콘·일러스트·번역 문자열·데모 콘텐츠는 원본의 소유물이므로 클론에 쓰지 않고 새로 만듭니다.

## 출처

| # | 출처 | URL | 비고 |
| --- | --- | --- | --- |
| 1 | 사용자 본인 인스턴스 | http://localhost:8080/ | 사용자가 별도 Chrome 창에서 직접 로그인. 그 창을 보기 전용으로 둘러보며 화면 41장을 캡처했고 데이터는 바꾸지 않음 (폼과 대화상자는 열기만 하고 취소) |
| 2 | 인스턴스 공개 설정 API | http://localhost:8080/api/v3/configuration | 첨부 최대 5MB, 페이지당 20/100, 하루 8시간, 기간 표시 "시간만", 기능 플래그 |
| 3 | 인스턴스 정보 화면 | http://localhost:8080/admin/info | 제품 버전 v17.9.1, PostgreSQL 17.11 |
| 4 | 사용자 가이드: 작업 패키지 | https://www.openproject.org/docs/user-guide/work-packages/ | 속성, 생성·편집, 활동 |
| 5 | 사용자 가이드: 테이블 구성 | https://www.openproject.org/docs/user-guide/work-packages/work-package-table-configuration/ | 컬럼, 필터, 정렬, 그룹화, 합계, 강조 표시 |
| 6 | 사용자 가이드: 관계와 계층 | https://www.openproject.org/docs/user-guide/work-packages/work-package-relations-hierarchies/ | 관계 유형, 부모-자식 |
| 7 | 사용자 가이드: 날짜 설정 | https://www.openproject.org/docs/user-guide/work-packages/set-change-dates/ | 날짜 선택기, 근무일만, 기간 |
| 8 | 사용자 가이드: Gantt | https://www.openproject.org/docs/user-guide/gantt-chart/ | 타임라인, 줌, 드래그 |
| 9 | 사용자 가이드: 스케줄링 | https://www.openproject.org/docs/user-guide/gantt-chart/scheduling/ | 수동·자동 스케줄링, 선후행 이동 규칙 |
| 10 | 사용자 가이드: 보드 | https://www.openproject.org/docs/user-guide/agile-boards/ | 보드 유형, 드래그 앤 드롭 |
| 11 | 사용자 가이드: 팀 플래너 | https://www.openproject.org/docs/user-guide/team-planner/ | 사람별 주간 계획 (원본에서는 Enterprise 전용) |
| 12 | 사용자 가이드: 알림 | https://www.openproject.org/docs/user-guide/notifications/ | 받은 편지함, 알림 이유, 설정 |
| 13 | 사용자 가이드: 프로젝트 | https://www.openproject.org/docs/user-guide/projects/ | 생성, 템플릿, 계층, 보관 |
| 14 | 사용자 가이드: 진행률 추적 | https://www.openproject.org/docs/user-guide/time-and-costs/progress-tracking/ | 작업 기반·상태 기반 계산 |
| 15 | 관리 가이드: 워크플로 | https://www.openproject.org/docs/system-admin-guide/manage-work-packages/work-package-workflows/ | 유형 × 역할 × 상태 전환 |
| 16 | 관리 가이드: 유형 | https://www.openproject.org/docs/system-admin-guide/manage-work-packages/work-package-types/ | 양식 구성, 기본값 |
| 17 | 관리 가이드: 작업 패키지 설정 | https://www.openproject.org/docs/system-admin-guide/manage-work-packages/work-package-settings/ | 전역 작업 패키지 옵션 |
| 18 | 관리 가이드: 캘린더와 날짜 | https://www.openproject.org/docs/system-admin-guide/calendars-and-dates/ | 근무일, 휴무일, 날짜 형식 |
| 19 | 공개 API v3 문서 | https://www.openproject.org/docs/api/ | 데이터 모델 근거 |
| 20 | 가격 | https://www.openproject.org/pricing/ | Community와 Enterprise 기능 구분 |
| 21 | 릴리스 노트 | https://www.openproject.org/docs/release-notes/ | 최근 추가 기능 |

> 4~21번 문서 URL은 모두 열리는 것(HTTP 200)만 확인했고, 본문은 아직 다 읽지 않았습니다. 문서 조사 에이전트가 중간에 중지됐기 때문입니다. 그래서 이 맵은 인스턴스에서 **직접 관찰한 내용**을 근거로 삼았습니다. 문서를 근거로 한 내용은 확신도 `medium`으로 표시했습니다. `/replica-architect` 전에 #9(스케줄링), #14(진행률), #19(API)를 정독하면 좋습니다.

## 핵심 루프

팀이 프로젝트 안에서 **작업 패키지를 만들고, 필터와 저장된 보기로 찾고, 워크플로에 따라 상태를 진행시키고, Gantt와 보드로 일정과 흐름을 계획합니다.** 변경 사항은 알림으로 관련자에게 전달됩니다.

## 화면 목록

캡처 파일은 `replica/screens/`에 있습니다. "관찰한 상태"는 실제로 확인한 상태만 적었고, 보지 못한 상태는 `미관찰`로 표시했습니다.

| ID | 화면 | 경로 / 진입 방법 | 목적 | 주요 구성 요소 | 관찰한 상태 |
| --- | --- | --- | --- | --- | --- |
| S01 | 로그인 | `/login` (로그인 안 하면 모든 경로가 여기로 이동) | 인증 | 로그인 폼, 헤더(검색·도움말) | 기본 / 오류 미관찰 |
| S02 | 홈 | `/` · `01-home` | 첫 화면 | 환영 블록, 즐겨찾는 프로젝트, 새 기능, 내 미팅 위젯 | 채워짐, 즐겨찾기 **빈 상태** |
| S03 | 프로젝트 목록 | `/projects` · `02-projects` | 프로젝트 찾기·관리 | 저장된 필터(활성/내/즐겨찾기/보관), 상태 필터, 테이블(즐겨찾기·이름·상태·공용·작성일·최근 활동·디스크), 페이지당 20/100 | 채워짐 |
| S04 | 새 프로젝트 | `/projects/new` · `27-project-new` | 프로젝트 생성 | 템플릿 사용 / 빈 프로젝트 라디오 카드, 계속 | 기본 |
| S05 | 프로젝트 개요 | `/projects/:identifier` · `03-project-overview` | 프로젝트 현황 대시보드 | 위젯: 설명, 상태(정상/위험/비정상)+설명, 프로젝트 타임라인, 내 미팅, 멤버, 뉴스, 예산, 수명 주기 | 채워짐 |
| S06 | 작업 패키지 목록 | `/projects/:identifier/work_packages` (전역 `/work_packages`) · `04-wp-table`, `05-wp-filters`, `05b-wp-add-filter`, `04b-wp-table-menu` | 핵심 목록. 찾기, 인라인 편집, 계층 보기 | 사이드바의 기본 보기·공개 보기, 툴바(만들기▾, 프로젝트 포함, 기준선, 필터 n, 상세 패널, 전체 화면, ⋮), 계층 테이블, 하단 인라인 생성 행, "(1 - 13/13)" | 채워짐, 필터 패널 열림 / 결과 0건 미관찰 |
| S07 | 작업 패키지 분할 화면 | `…/work_packages/details/:id/overview` · `06-wp-split` | 목록을 유지한 채 상세 보기·편집 | 오른쪽 패널 탭(요약·활동·파일·관계(n)·위키·미팅·주시자), 부모 표시, 상태 pill, 속성 그룹, 하단 액션(주시, 공유(EE), 알림, ⋯) | 채워짐 |
| S08 | 작업 패키지 전체 화면 | `/projects/:identifier/work_packages/:id/activity` · `07-wp-full`, `07b-wp-relations` | 상세 편집, 활동, 관계 | 왼쪽 속성 폼, 오른쪽 탭, 툴바(만들기▾, 공유, 시간 기록, 주시, 알림, 전체 화면, ⋮), 활동 필터(모두 표시)·정렬(오래된 순), 코멘트 입력(@멘션) | 채워짐, 관계 카드 / 코멘트 있는 활동 미관찰 |
| S09 | 새 작업 패키지 | `…/work_packages/create_new?type=:typeId` · `08-wp-new`, `08a-wp-create-menu` | 생성 폼 (분할 패널) | 유형 선택 메뉴(일감·마일스톤·요약 작업), 제목, 리치 텍스트 설명, 속성 그룹, 저장·취소 | 빈 폼 / 검증 오류 미관찰 |
| S10 | 날짜 선택 대화상자 | 날짜 필드 클릭 (모달) · `09-datepicker` | 일정과 스케줄링 모드 설정 | 정보 배너("날짜는 자식에 의해 결정됨" + 관계 표시), 탭(날짜·선행 n·후속 n·하위 n), 수동/자동 세그먼트, 근무일만 체크, 시작·완료·기간 입력+"오늘", 2개월 달력, 취소·저장 | 자식이 있는 부모(입력 잠김) / 일반 작업 미관찰 |
| S11 | 보기 구성 대화상자 | 목록 ⋮ → 보기 구성 · `04c-wp-config-modal` | 테이블 설정 | 탭(컬럼·필터·정렬 기준·기준선·디스플레이 설정·강조 표시), 컬럼 검색·추가·재정렬, 취소·적용 | 기본 |
| S12 | 내보내기 대화상자 | 목록 ⋮ → 내보내기 · `04e-wp-export` | 파일로 내보내기 | 형식(PDF·XLS·CSV), PDF 유형(테이블·보고서·Gantt), 컬럼 선택 | 기본 |
| S13 | Gantt 차트 | `/projects/:identifier/gantt` (보기 상태가 `query_props`로 URL에 들어감) · `10-gantt` | 일정 계획 | 왼쪽 테이블(ID·타입·제목·상태·시작·완료·기간)과 오른쪽 타임라인(연/월/주차 눈금, 막대, 마일스톤 다이아몬드, 의존선, 오늘 선, 라벨), 줌 인·아웃·자동, 사이드바 즐겨찾기 보기 | 채워짐, 기한 지남 날짜 빨간색 |
| S14 | 보드 목록 | `/projects/:identifier/boards` · `11a-boards-list` | 보드 선택 | 테이블(이름·타입·작성일), + 게시판 | 채워짐 |
| S15 | 보드 상세 | `/projects/:identifier/boards/:id` · `11-boards-kanban`, `11c-boards-wbs` | 칸반 흐름 관리 | 컬럼(속성 라벨 + 값, 색상 바, + 카드), 카드(#ID·프로젝트·유형+제목·아바타·날짜), 보드에 목록 추가, 필터 | 채워짐, 빈 컬럼(닫음·거부됨) |
| S16 | 새 보드 | `/projects/:identifier/boards/new` · `11d-boards-new` | 보드 생성 | 제목, 보드 유형 라디오 카드 6종(기본·Kanban·담당자·버전·하위 프로젝트·부모-자식) | 기본 |
| S17 | 캘린더 | `/projects/:identifier/calendars` · `12-calendar` | 월·주 단위 일정 | — | **403** (데모 프로젝트에서 모듈이 꺼져 있음) / 화면 미관찰 |
| S18 | 팀 플래너 | `/projects/:identifier/team_planners` · `13-team-planner` | 사람별 주간 배정 | Enterprise 업셀 화면 | **잠김(EE)** / 실제 화면 미관찰 |
| S19 | 알림 센터 | `/notifications` (헤더 받은 편지함 아이콘) · `14-notifications` | 나와 관련된 변경 확인 | 받은 편지함, 설정, 읽지 않음/모두, 모두 읽은 상태로 표시 | **빈 상태**("모두 확인하신 것 같습니다") |
| S20 | 알림·이메일 설정 | `/my/notifications` · `21-my-notification-settings` | 알림 수신 기준 설정 | 참여(멘션·주시·담당자·담당·공유됨), 날짜 알림(시작·완료: 당일/1/3/7일 전, 기한 지남), 비참여 이벤트(새 WP·모든 코멘트·상태·우선순위·날짜 변경), 프로젝트별 재정의, 이메일 미리 알림 탭 | 기본, 프로젝트별 설정 빈 상태 |
| S21 | 내 페이지 | `/my/page` · `20-my-page` | 개인 대시보드 | 위젯(작업 패키지 테이블 등) | 채워짐 |
| S22 | 전역 검색 결과 | `/search?q=` (헤더 검색) · `28-search` | 전역 검색 | 범주 탭(작업 패키지·뉴스·변경 집합·위키·포럼·프로젝트·미팅·문서·모두), 결과 n, 다음 | 채워짐 |
| S23 | 프로젝트 멤버 | `/projects/:identifier/members` · `15-members` | 멤버·역할 관리 | 필터, 테이블(이름·이메일·역할·그룹·공유됨·상태·요금), + 멤버 | 채워짐(1명) |
| S24 | 프로젝트 설정: 모듈 | `…/settings/modules` · `18-project-settings-modules` | 기능 켜기·끄기 | 체크박스 17개(Gantt·보드·작업 패키지·팀 플래너·달력·백로그·미팅 등), 저장 | 기본 |
| S25 | 프로젝트 설정: 작업 패키지 | `…/settings/work_packages/types` · `19-project-settings-wp` | 프로젝트별 유형·카테고리·사용자 정의 필드·내부 코멘트 | 탭 4개, 유형별 사용 여부·로드맵 표시 체크 | 기본 |
| S26 | 프로젝트 설정: 버전 | `…/settings/versions` · `19b-project-settings-versions` | 버전(릴리스) 관리 | 버전 테이블, + 버전 | 확인 필요 |
| S27 | 관리: 유형 목록 | `/types` · `22-admin-types` | 전역 유형 관리 | 테이블(이름·색상·워크플로·새 프로젝트에서 활성·마일스톤), 순서 이동(맨 위·위·아래·맨 아래) | 7개 |
| S28 | 관리: 유형 편집 | `/types/:id/settings`, `…/form_configuration/edit` · `22b-admin-type-form-config` | 유형 세부, 기본값, 양식, 워크플로, 프로젝트, PDF | 양식: 활성·비활성 특성 드래그, 특성 그룹 (그룹 추가·이름 변경·관련 WP 테이블은 EE) | 기본 |
| S29 | 관리: 워크플로 | `/types/:id/workflow/edit` · `16-admin-workflow` | 상태 전환 규칙 | 역할 선택, 탭(기본 전환 등), 현재 상태 × 새 상태 체크박스 매트릭스, 모두 선택/취소, 복사, 저장 | 기본 |
| S30 | 관리: 상태 | `/statuses` · `23-admin-statuses` | 상태 정의 | 테이블(이름·색상·완료 %·기본·닫음·읽기 전용·합계에서 제외), 순서 이동 | 14개 |
| S31 | 관리: 역할 및 권한 | `/admin/roles` · `24-admin-roles` | 권한 설정 | 전체/글로벌/프로젝트 필터, 역할별 권한 수(멤버 86, 참관자 32, 프로젝트 관리자 137 …), 권한 보고서 | 기본 |
| S32 | 관리: 진행률 추적 | `/admin/settings/progress_tracking` · `25-admin-progress` | 진행률 계산 규칙 | 계산 모드(작업 기반·상태 기반), 계층 합계(작업 가중·단순 평균), 닫힘 시 100% 여부 | 기본 |
| S33 | 관리: 근무일 및 시간 | `/admin/settings/working_days_and_hours` · `26-admin-working-days` | 스케줄링 달력 | 일별 시간, 기간 형식(일+시간·시간만), 근무 요일 체크, 연도별 휴무일 목록 + 휴무일 | 휴무일 빈 상태 |
| S34 | 관리: 사용자 정의 필드 | `/custom_fields` | 필드 정의 | (미캡처) | 미관찰 |
| S35 | 관리: 사용자·그룹 | `/users`, `/admin/groups` | 계정 관리 | (미캡처) | 미관찰 |
| S36 | 관리: 우선 순위 | `/admin/settings/work_package_priorities` | 우선순위 목록 | (미캡처) | 미관찰 |
| S37 | 계정 설정 | `/my/account` 등 | 언어·시간대·테마 | (미캡처) | 미관찰 |
| M1 | 모바일: 작업 패키지 목록 | 390px 폭 · `29-mobile-wp-list` | — | 테이블이 **카드 목록**으로 바뀜(상태 pill▾·#ID·프로젝트·유형+제목·아바타·기간), 툴바 축소 | 채워짐 |
| M2 | 모바일: 작업 패키지 상세 | 390px 폭 · `30-mobile-wp-view` | — | 단일 컬럼 | 채워짐 |

공통 셸: 상단 헤더(모듈 그리드 메뉴, 로고, 전역 검색, + 빠른 생성▾, 받은 편지함, 도움말, 아바타)와 왼쪽 사이드바(프로젝트 선택기, 모듈 메뉴, 하위 메뉴의 저장된 보기와 이름 검색, 접기 버튼, EE 배지)로 이뤄집니다. 화면 상단에는 브레드크럼이 있습니다.

## 사용자 흐름

```
F01 작업 패키지 만들기
    S06 목록 -> [+ 만들기▾] -> 유형 선택 -> S09 분할 폼 -> 제목·속성 입력 -> 저장 -> S07 상세
    또는 S06 하단 "+ 새 작업 패키지 만들기" -> 행 안에서 제목 입력 -> Enter
    happy path 클릭: 3회 (인라인 생성은 1회 + 입력)  ← 이길 목표: 1회
    edge: 필수 사용자 정의 필드 누락, 프로젝트에서 꺼진 유형, 생성 권한 없음,
          필터 조건에 안 맞는 새 항목(목록에서 사라짐), 저장 전 이탈 경고

F02 상태 진행시키기 (워크플로 적용)
    S06 상태 셀 또는 S07/S08 상태 pill -> 허용된 상태 목록 -> 선택
    또는 S15 칸반 카드를 다른 상태 컬럼으로 드래그
    happy path 클릭: 2회 (보드는 드래그 1회)
    edge: 역할×유형에 없는 전환(목록에 안 보임, 드래그 거부), 읽기 전용 상태(모든 필드 잠김),
          닫힘 상태로 바꾸면 완료 100% 처리(설정에 따라), 동시 편집 충돌(lock version)

F03 필터한 보기를 저장해 팀과 공유
    S06 -> 필터 -> 필터 추가(약 30종) -> 연산자·값 -> (정렬·그룹화·컬럼은 S11) -> ⋮ -> 다른 이름으로 저장 -> 이름·공개·즐겨찾기
    happy path 클릭: 약 8회
    edge: 결과 0건, 공개 보기 저장 권한 없음, 저장 안 한 변경(제목 옆 저장 아이콘 표시),
          "프로젝트 포함"으로 하위 프로젝트까지 포함, URL로 보기 상태 공유

F04 일정 잡기·옮기기 (Gantt)
    S13 Gantt -> 막대 드래그(이동) 또는 양 끝 드래그(기간 변경)
    또는 날짜 필드 -> S10 -> 시작·완료 클릭 -> 저장
    happy path 클릭: 드래그 1회 / 대화상자 4회
    edge: 후행 작업 자동 이동(lag 반영), 부모 날짜는 자식 범위로 결정되어 직접 수정 불가,
          주말·휴무일 건너뛰기(근무일만), 수동 모드로 고정, 날짜 없는 항목에 막대 새로 그리기, 마일스톤은 날짜 1개

F05 선후행 관계 걸기
    S08 관계 탭 -> [+ 관계▾] -> 선행 단계/후임자 -> 작업 패키지 검색·선택 -> 저장
    happy path 클릭: 약 4~5회
    edge: 순환 의존, 다른 프로젝트 항목과의 관계, 조상·자손과의 선후행, 같은 관계 중복,
          "기타 관계"(중복·차단·포함·필수와 각각의 역방향)

F06 칸반 보드로 흐름 관리
    S14 -> 보드 선택 -> S15 카드 드래그 / 컬럼에서 + 카드 생성
    새 보드: S14 [+ 게시판] -> S16 제목·유형 -> 만들기 (3~4회)
    edge: 워크플로에 막힌 이동, 빈 컬럼, 보드 필터, 담당자·버전·하위 프로젝트·부모-자식 보드는
          드래그하면 해당 속성이 바뀜

F07 계층(WBS) 만들기
    S08 관계 탭 -> [+ 자식▾] -> 새 자식 만들기 / 기존 항목 연결
    또는 S15 부모-자식 보드에서 드래그
    happy path 클릭: 약 3~4회
    edge: 자기 자신이나 자손을 부모로 지정, 자식이 생긴 부모의 날짜·진행률이 파생값으로 바뀜

F08 코멘트와 @멘션으로 협업
    S08 활동 탭 -> 코멘트 입력 -> "@" 사용자 선택 -> 전송 -> 상대 S19 알림 + 이메일
    happy path 클릭: 3~4회
    edge: 프로젝트 멤버가 아닌 사용자 멘션, 코멘트 수정·삭제, 내부 코멘트(프로젝트 설정),
          활동 필터(코멘트만·변경만), 정렬 순서(사용자 설정)

F09 알림 처리
    헤더 받은 편지함(읽지 않은 수 배지) -> S19 -> 알림 클릭 -> 같은 화면 분할 패널에 해당 WP
    happy path 클릭: 2회
    edge: 빈 상태, 이유별·프로젝트별 필터, 모두 읽음, 날짜 알림, 같은 WP의 알림 묶음

F10 프로젝트를 만들고 팀 꾸리기
    S03 [+ 프로젝트] -> S04 빈 프로젝트/템플릿 -> 계속 -> 이름 등 -> 만들기 -> S23 [+ 멤버] -> 사용자·그룹·역할 -> 추가
    happy path 클릭: 약 9회
    edge: 식별자(identifier) 중복, 템플릿 복사 범위, 없는 사용자를 이메일로 초대, 마지막 관리자 제거

F11 관리자가 업무 규칙 구성
    관리 -> S27 유형 -> S28 양식 -> S29 워크플로(역할 선택, 매트릭스 체크) -> 저장
    S30 상태, S32 진행률, S33 근무일
    happy path 클릭: 6회 + 체크 수
    edge: 워크플로가 비어 있는 유형(상태 변경 불가), 사용 중인 상태·유형 삭제,
          근무일을 바꾸면 모든 WP 날짜가 다시 계산됨(경고 문구 있음)
```

## 컴포넌트

| 컴포넌트 | 변형 | 상태 | 사용 화면 |
| --- | --- | --- | --- |
| 앱 헤더 | 데스크톱, 모바일(아이콘만) | 알림 배지 | 전체 |
| 사이드바 | 전역 메뉴, 프로젝트 메뉴, 하위 메뉴(저장된 보기 + 이름 검색) | 접힘·펼침, 선택됨, EE 배지 | 전체 |
| 프로젝트 선택기 | 드롭다운(검색) | 열림·닫힘 | 전체 |
| 브레드크럼 | — | — | 대부분 |
| 버튼 | primary(초록), secondary, invisible, 아이콘 전용, 분할(만들기▾), 카운터 포함(필터 1) | 기본, hover, focus, 비활성, 눌림 | 전체 |
| 액션 메뉴 | 단순, 설명 포함(관계 유형), 하위 메뉴(기타 관계) | 열림, 키보드 탐색 | S06, S08, S13 |
| 상태 pill | 색상 배경 + ▾ | 편집 가능, 읽기 전용 | S06~S08, S15, M1 |
| 유형 라벨 | 유형 색상 굵은 글자 | — | S06, S13, S15 |
| 우선순위 점 | 색상 점 + 이름 | — | S06, S07 |
| 아바타(principal) | 이니셜 원, 이름 포함, 그룹 | hover 카드 | 전체 |
| 작업 패키지 테이블 | 평면, 계층, 그룹화, 합계 행 | 정렬 표시, 인라인 편집, 행 선택, 컨텍스트 메뉴, 빈 결과 | S06, S13, S21 |
| 일반 데이터 테이블 | 정렬 헤더, 페이지네이션 "(1 - n/n)", 페이지당 20/100 | 빈 상태 | S03, S14, S23, S27~S31 |
| 필터 패널 | 텍스트 필터, 속성 행(속성·연산자·값) | 추가·삭제, 잘못된 값 | S06, S15, S03, S23 |
| 자동완성 선택 | 단일, 다중, 사용자 선택, WP 검색 | 로딩, 결과 없음 | 전체 폼 |
| 인라인 편집 필드 | 텍스트, 선택, 날짜, 숫자, 리치 텍스트 | 표시, 편집, 저장 중, 오류 | S06~S08 |
| 리치 텍스트 편집기 | 문단 스타일, 굵게·기울임·취소선·코드·코드 블록, 더보기, @멘션, 첨부 끌어놓기 | placeholder("클릭하여 편집…") | S08, S09 |
| 날짜 선택 대화상자 | 범위, 단일(마일스톤) | 수동·자동, 근무일만, 입력 잠김(파생), 정보 배너 | S10 |
| 세그먼트 컨트롤 | 2개 옵션 | 선택됨 | S10 |
| 탭 | 카운터 포함(관계 6), 스크롤 화살표 | 선택됨 | S07, S08, S10, S11, S28 |
| 분할 패널 | 크기 조절 핸들 | 열림·닫힘·전체 화면 | S06, S19 |
| 모달 대화상자 | 설정형(탭), 확인형 | 취소·적용 | S10~S12 |
| 카드 | 보드 카드, 관계 카드(⋯ 메뉴), 모바일 WP 카드 | 드래그 중, 기한 지남 날짜 빨간색 | S08, S15, M1 |
| 보드 컬럼 | 상태·담당자·버전·하위 프로젝트·부모 | 빈 컬럼, 드롭 대상 | S15 |
| Gantt 타임라인 | 막대(일반·요약), 마일스톤 다이아몬드, 의존선, 오늘 선, 라벨 | 드래그, 크기 조절, 기한 지남, 줌 단계 | S13 |
| 라디오 카드 | 아이콘·설명 포함 | 선택됨 | S04, S16 |
| 체크박스 매트릭스 | 행·열 전체 선택 | — | S29 |
| 순서 이동 컨트롤 | 맨 위·위·아래·맨 아래 | — | S27, S30 |
| 위젯 카드 | 텍스트, 목록, 차트, 타임라인 | 빈 상태 | S02, S05, S21 |
| 활동 스트림 | 저널 항목(작성자·시각·변경 내역), 코멘트 | 필터, 정렬 | S08 |
| 빈 상태 | 아이콘 + 제목 + 설명 | — | S02, S19, S33 |
| 플래시·토스트 | 성공, 오류, 정보 배너 | 닫기 | 전체 |
| Enterprise 업셀 | 배지, 잠금 화면 | — | S18, S28 (클론에서는 불필요) |

## 추론한 데이터 모델

```
User        id, login, firstname, lastname, email, admin(bool),
            status (active | invited | locked | registered), language, time_zone
            preferences: comment_sort_desc, warn_on_leaving_unsaved, auto_hide_popups,
                         daily_reminders{enabled, times[]}, immediate_reminders{mentioned},
                         pause_reminders, workdays[]
            evidence: /api/v3/configuration의 userPreferences(관찰), S20, S23, S35
            confidence: high (preferences) / medium (나머지)

Group       id, name; members n-n User
            evidence: S23 "그룹" 컬럼, 관리 메뉴 /admin/groups, 담당자 필터의 "담당자 또는 소속 그룹"
            confidence: high

Project     id, identifier(slug, 예: demo-project), name, description,
            status (on_track | at_risk | off_track), status_explanation,
            public(bool), active(bool, 보관), parent_id(계층), template(bool),
            enabled_modules[], created_at, latest_activity_at
            evidence: S03 컬럼·필터, S05 상태 위젯, S24 모듈, S04 템플릿
            confidence: high

ProjectFavorite  user_id, project_id
            evidence: S02 즐겨찾기 위젯, S03 즐겨찾기 컬럼
            confidence: high

Role        id, name, scope (project | global), builtin (none | non_member | anonymous),
            permissions[] (권한 키 목록, 프로젝트 관리자 기준 137개)
            evidence: S31
            confidence: high

Member      id, project_id, principal_id(User | Group), roles n-n Role, created_at
            evidence: S23, S05 멤버 위젯
            confidence: high

Type        id, name, color, position, is_milestone, is_default(새 프로젝트에서 활성),
            is_in_roadmap, attribute_groups(json: 그룹명 + 특성 목록), description_template
            evidence: S27, S28 (탭: 세부 정보·기본값·양식·워크플로·프로젝트 특성·프로젝트·PDF), S25
            confidence: high
ProjectType  project_id, type_id (프로젝트별 사용 유형)        evidence: S25  confidence: high

Status      id, name, color, position, is_default, is_closed, is_readonly,
            default_done_ratio(완료 %), excluded_from_totals
            evidence: S30
            confidence: high

Workflow    type_id, role_id, old_status_id, new_status_id, author(bool), assignee(bool)
            evidence: S29 매트릭스 + "기본 전환" 탭 (작성자·담당자 전용 전환 탭이 있는지는 미확인)
            confidence: high (앞 4개) / guess (author, assignee)

Priority    id, name, position, is_default, color, active
            evidence: S06 "보통" + 색상 점, 관리 메뉴의 우선 순위
            confidence: medium

Version     id, project_id, name, description, status (open | locked | closed),
            start_date, effective_date, sharing (none | descendants | hierarchy | tree | system)
            evidence: S25 "기본적으로 로드맵에 표시됨", S26, 필터 "대상 버전", 공개 API 문서(#19)
            confidence: medium

Category    id, project_id, name, default_assignee_id
            evidence: S25 카테고리 탭, WP 속성 "카테고리"
            confidence: medium (default_assignee는 guess)

WorkPackage id, project_id, type_id, subject, description(rich text),
            status_id, priority_id, author_id, assigned_to_id(principal), responsible_id("담당"),
            version_id, category_id, parent_id,
            start_date, due_date, duration(근무일 수), ignore_non_working_days(bool; "근무일만"의 반대),
            schedule_manually(bool; 수동/자동),
            estimated_hours("작업"), remaining_hours("남은 작업"), done_ratio("완료 %"),
            derived_estimated_hours, derived_remaining_hours, derived_done_ratio(계층 합계),
            lock_version, created_at, updated_at, custom_values
            evidence: S07, S08, S09, S10, S06 컬럼, S32
            confidence: high (속성 존재) / medium (derived_* 저장 방식)

Relation    id, from_id, to_id,
            relation_type (relates | duplicates | duplicated | blocks | blocked | precedes | follows |
                           includes | partof | requires | required),
            lag(근무일), description
            evidence: S08 관계 메뉴(관련·선행·후임·중복·복제한·블록·차단한·포함·일부·필수·요구됨), S10 선행/후속 탭
            confidence: high (유형) / medium (lag, description)
            참고: 부모-자식은 Relation이 아니라 WorkPackage.parent_id로 관리 (S08에서 메뉴는 같이 보이지만 별도 그룹)

Journal     id, journable(WorkPackage 등), version, user_id, notes(코멘트), internal(bool),
            details(속성 diff), created_at
            evidence: S08 활동("익명 - 이 패키지를 생성함"), S25 "내부 코멘트" 탭, S22 "작업 패키지 편집됨"
            confidence: high (구조는 medium)

Watcher     work_package_id, user_id          evidence: S07·S08 주시자 탭   confidence: high
Attachment  id, container(WP), filename, content_type, filesize, author_id, created_at
            evidence: S08 파일 탭, 필터 "첨부 파일 이름/콘텐츠", configuration 최대 5MB   confidence: high

Query       id, name, project_id(null이면 전역), user_id, public(bool), starred(bool),
            filters(json: [{name, operator, values}]), columns[], sort_criteria[], group_by,
            show_hierarchies, display_sums, include_subprojects,
            timeline_visible, timeline_zoom_level, timeline_labels{left,right,farRight},
            highlighting, baseline(timestamps)
            evidence: S06 사이드바(기본·공개·즐겨찾기 보기), S11, S13 URL의 query_props
                      (tll·tzl·tv·hi·t·c·f 키 = 라벨·줌·타임라인 표시·계층·정렬·컬럼·필터)
            confidence: high

Notification id, recipient_id, resource(WP), actor_id, journal_id,
            reason (mentioned | assigned | responsible | watched | shared | commented | created |
                    status_changed | priority_changed | dates_changed | date_alert_start | date_alert_due | overdue),
            read(bool), mail_sent(bool), created_at
            evidence: S19, S20 (참여·비참여 이벤트, 날짜 알림)
            confidence: medium (이유 enum 이름은 guess, 의미는 S20 기준)

NotificationSetting user_id, project_id(null이면 기본값), mentioned, watched, assignee, responsible, shared,
            work_package_created, commented, status_changed, priority_changed, dates_changed,
            start_date_alert(null|0|1|3|7), due_date_alert(null|0|1|3|7), overdue(bool)
            evidence: S20
            confidence: high

CustomField id, type(WorkPackage | Project | User …), name,
            field_format (string | text | int | float | list | date | bool | user | version | link),
            possible_values(CustomOption), is_required, is_for_all, searchable, default_value,
            min_length, max_length, regexp, multi_value
            evidence: S25 사용자 정의 필드 탭, S28 양식, 관리 메뉴의 프로젝트 특성·사용자 특성
            confidence: medium (형식 목록은 공개 문서 기준)
CustomValue customized_type, customized_id, custom_field_id, value    confidence: medium

Board       id, project_id, name, kind (basic | status | assignee | version | subproject | parent_child),
            public, columns[] (컬럼마다 Query 1개 = 필터 조건)
            evidence: S14, S15, S16 ("공개: Kanban 보드")
            confidence: high (종류) / medium (컬럼 = Query 구조)

NonWorkingDay date, name                                   evidence: S33   confidence: high
Setting     working_days[1..7], hours_per_day(8), duration_format(days_and_hours | hours_only),
            progress_calc_mode(work | status), totals_mode(weighted | simple), closed_sets_100(bool),
            per_page_options[20,100], start_of_week, date_format, time_format
            evidence: S32, S33, /api/v3/configuration
            confidence: high
```

관계 요약: Project 1-n Project(하위), Project n-n User/Group(Member, Role 포함), Project n-n Type, Project 1-n Version/Category/Query/Board, Project 1-n WorkPackage, WorkPackage 1-n WorkPackage(parent_id), WorkPackage n-n WorkPackage(Relation), WorkPackage 1-n Journal/Attachment/Watcher/Notification, Type × Role × Status → Workflow, User 1-n Notification/NotificationSetting/Query, Board 1-n Query(컬럼).

## 기능 매트릭스

`features.csv`를 보세요. must 64, should 50, could 26, skip 20 (총 160행, 점수 계산 대상 140행). `replica-diff/parity.py`로 읽히는 것까지 확인했습니다 (현재 패리티 0/100).

에디션 참고: 원본에서 Enterprise 전용으로 표시된 기능은 **팀 플래너, 포트폴리오, 작업 패키지 공유(사용자와 공유됨·나와 공유됨), 양식 특성 그룹 편집**입니다. 보드 6종은 Community에서 모두 열려 있었습니다. 기능 아이디어는 보호 대상이 아니므로 사내 클론에서는 EE 기능도 직접 구현할 수 있습니다.

## 범위 밖 (복제할 수 없거나, 하지 말아야 할 것)

- **OpenProject 상표·로고·아이콘·일러스트·브랜드 색상**: 원본의 소유입니다. openPMS 고유 디자인으로 새로 만듭니다 (`/replica-brand`).
- **한국어 번역 문자열**: 원본 번역은 프로젝트에 속한 것이므로 가져오지 않고, 우리 용어집으로 새로 씁니다. 예: "일감", "담당", "후임자(이후)"처럼 어색한 번역은 오히려 개선할 기회입니다.
- **원본 소스 코드(GPLv3)**: 클린룸 원칙에 따라 읽지도, 옮기지도 않습니다.
- **데모 데이터와 도움말 문구**: 우리 시드 데이터를 따로 만듭니다.
- **범위 밖 모듈**: 시간·비용·예산, 미팅, 위키, 뉴스, 문서, 포럼, 백로그(스크럼), 포트폴리오, 프로젝트 수명 주기(단계·게이트), BIM/BCF
- **외부 연동**: GitHub/GitLab, 리포지토리, Nextcloud/OneDrive 파일 저장소, Jira 가져오기, MCP/AI, SCIM, SAML
- **원본의 상업 구조**: Enterprise 업셀, 평가판, 결제(Chargebee), 클라우드 호스팅

## 규모

- 화면 37개 + 모바일 상태 2개, 흐름 11개, 엔터티 22개
- 어려운 부분:
  1. **스케줄링 엔진**: 근무일·휴무일 계산, 선후행 lag, 수동/자동 모드, 부모 날짜 파생, 연쇄 이동, 순환 검출. 근무일 설정을 바꾸면 전체를 다시 계산해야 합니다.
  2. **쿼리 엔진과 저장된 보기**: 필터 약 30종 × 연산자, 계층·그룹화·합계, 하위 프로젝트 포함, 권한 필터링, URL 직렬화
  3. **권한과 워크플로**: 역할별 권한 100개 이상, 유형 × 역할 × 상태 전환, 읽기 전용 상태, 필드 단위 편집 가능 여부(schema)
  4. Gantt 인터랙션: 드래그·크기 조절과 의존선 실시간 갱신, 줌, 큰 목록 가상 스크롤
  5. 활동 저널 → 알림 팬아웃 → 이메일 다이제스트·날짜 알림 배치 작업
  6. 진행률 계산(작업 기반·상태 기반, 계층 가중 합계)
- 크기: **L (한 분기)**. 소규모 팀이 must 항목만 구현한 MVP(작업 패키지 CRUD + 목록·필터 + 워크플로 + 기본 Gantt + 칸반 + 알림)는 **M (몇 주~두 달)**로 볼 수 있습니다. 원본과 완전히 같은 수준은 약속하지 않습니다.

> 판단 참고: 사내용이라면 OpenProject Community를 그대로 쓰는 것도 합법적이고 비용이 가장 적게 듭니다. GPLv3는 사내에서만 쓰는 경우 소스 공개 의무가 없습니다. 클론이 의미 있는 경우는 사내 시스템과 깊게 통합하거나, UX를 단순화하거나, 원본에서 EE로 막힌 기능(팀 플래너, 공유 등)이 꼭 필요할 때입니다.
