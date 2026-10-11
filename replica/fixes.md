# 원본 사용자가 싫어하는 것과 고칠 계획

2026-10-11 작성. 근거는 `replica/reviews.csv`(137건), 주제 분류는 `replica/feedback.md`, 출처별 수집 결과는 `replica/sources.md`입니다.

## 표본과 한계

- **137건, 출처 7곳**: Hacker News 56, OpenProject 공개 커뮤니티 포럼 55, Lemmy 12, 블로그 6, AlternativeTo 4, SourceForge 2, OMR Reviews 2. 별점이 있는 리뷰는 7건뿐입니다.
- **수집하지 못한 곳**: G2, Capterra, TrustRadius, GetApp, Software Advice는 자동 조회를 막아(HTTP 403) 한 건도 가져오지 못했습니다. Reddit도 막혀 있어 비슷한 공개 포럼인 Lemmy로 대신했습니다. 기능 요청 게시판의 투표 수는 JavaScript가 있어야 보여서 세지 못했습니다(클린룸 원칙상 원본 인스턴스의 API는 부르지 않음).
- **치우침**: 개발자 커뮤니티(HN)와 원본 포럼에 몰려 있습니다. 일반 업무 사용자, 한국어 리뷰는 없습니다. 한국 쪽 근거는 한국인 이름의 포럼 글 1건과 "아시아의 400명 회사" HN 글 1건이 전부입니다.
- **검증**: 수집 스크립트가 각 문장이 원문 페이지에 그대로 있는지 확인했습니다(AlternativeTo 4건 제외). 저도 3건(HN 35149319, 40402168, 포럼 19602)을 원문과 다시 대조했고 모두 일치했습니다.
- 리뷰 문장은 조사 자료일 뿐입니다. 홍보 문구나 추천사로 쓰지 않습니다.

## 1. 싫어하는 것

| 순위 | 문제 | 리뷰 | 출처 | 인용 |
| --- | --- | --- | --- | --- |
| 1 | 중요한 기능이 유료 Enterprise 판에만 있고, 무료판 화면에 판매 배너가 뜸 | 32 | 6 | "Is there anyway to remove the banners advertising the enterprise edition. I can deal with in admin but users should not be seeing that." [포럼 15498](https://community.openproject.org/projects/OP/forums/6/topics/15498) · "they do have kanban boards, but they're in an enterprise version that I'm assuming you're not interested at." [HN](https://news.ycombinator.com/item?id=26500600) (2021년 글, 보드는 2022년 12.1부터 무료) |
| 2 | 직접 설치한 서버의 업그레이드가 어렵고 잘 깨짐(Docker, 패키지 저장소, PostgreSQL 이전) | 19 | 5 | "The compose upgrade path appears to be incomplete as it is presented, now." [포럼 19206](https://community.openproject.org/projects/OP/forums/9/topics/19206) · "the actual repository is still 15. So i added the current repo to my instance" [포럼 19214](https://community.openproject.org/projects/OP/forums/9/topics/19214) |
| 3 | 화면이 낡고 복잡하며 클릭과 페이지 이동이 많음 | 17 | 5 | "Logging the hours turned out to be painful because the UI was clunky. Too many clicks and page loads were needed." [HN 35149319](https://news.ycombinator.com/item?id=35149319) · "The UI is really outdated and painful" [AlternativeTo, 별 1개](https://alternativeto.net/software/openproject/about/) |
| 4 | 느리고 자원을 많이 씀 | 14 | 3 | "slightly clunky and slow but overall usable Rails app" [HN](https://news.ycombinator.com/item?id=33877350) · "after a restart my system slows down to the bottom, because both CPUs (or cores) are dealing with 100% ruby processes." [포럼 18993](https://community.openproject.org/projects/OP/forums/6/topics/18993) |
| 5 | 버그, 저장이 안 되는 경우 | 12 | 4 | "But sometimes neither of them, and of course in that case it won't save the newly entered info." [AlternativeTo, 별 2개](https://alternativeto.net/software/openproject/about/) |
| 6 | 너무 무겁고 배우기 어려움 | 12 | 5 | "the heavy enterprise tools like Jira or OpenProject" [HN](https://news.ycombinator.com/item?id=47148484) |
| 7 | 문서와 기능을 찾기 어려움 | 11 | 4 | "A feature matrix would be welcome here." [HN](https://news.ycombinator.com/item?id=24822280) |
| 8 | 알림·메일 (근거 적음) | 3 | 2 | "no email notification is received by the assignee" [포럼 19296](https://community.openproject.org/projects/OP/forums/6/topics/19296) |
| 9 | 용어가 헷갈림: "work package", "project" (근거 적음) | 3 | 3 | 포럼 19571 |

**openPMS에서 이미 해결된 것**
- **1번**: 판 구분과 판매 배너가 없습니다. `features.csv`의 모든 기능을 모두에게 제공합니다(SSO, 기준선, 강조 표시, 보드 종류 포함).
- **포럼 19384** ("How would user know that the input box is expecting return key entry two times to save"): 제목은 Enter 한 번이나 포커스 이동으로 저장되고, Esc로 취소됩니다(`inline-text.tsx`).
- **포럼 18920** ("you can't do part-time, for example, 7 hours"): 관리 · 근무 요일·휴무일·일별 시간에서 하루 시간을 정할 수 있습니다.
- **9번**: "work package" 대신 "작업"이라는 말을 씁니다.

## 2. 빠진 것 (이름을 대고 요청한 기능)

| 순위 | 기능 | 리뷰 | 출처 | 인용 | openPMS |
| --- | --- | --- | --- | --- | --- |
| 1 | 애자일 백로그, 스프린트, 스토리 포인트 | 20 | 7 | "Somebody suggested to use the Backlogs view." [AlternativeTo, 별 2개](https://alternativeto.net/software/openproject/about/) | 범위 밖(skip). 보드는 있음 |
| 2 | Gantt와 일정(기간, 의존, 자동 일정) | 17 | 5 | "Compared to a jira gantt chart, OpenProject sometimes requires an extra manual refresh to display changes." [포럼 14703](https://community.openproject.org/projects/OP/forums/6/topics/14703) | 있음(자동 일정, 바로 반영) |
| 3 | 시간 기록, 비용, 예산 | 12 | 3 | "Despite having set up an 8-hour workday, it is possible to log more hours than set for an activity." [포럼 19007](https://community.openproject.org/projects/OP/forums/6/topics/19007) · 위 HN 35149319 | **없음(범위 밖으로 뺐음)** |
| 4 | 편집기, 위키, 문서 | 10 | 4 | "Now when i edit the page again all my entered code is lost." [포럼 19039](https://community.openproject.org/projects/OP/forums/6/topics/19039) | 위키·문서는 범위 밖 |
| 5 | 보고서, 대시보드, 내보내기 | 9 | 3 | "when i generate a pdf file from a work package at the moment it doesnt include all the comments etc." [포럼 19162](https://community.openproject.org/projects/OP/forums/6/topics/19162) | 목록 PDF·Excel·CSV 있음 |
| 6 | 연동, API, 다른 도구에서 가져오기 | 7 | 3 | "Is it possible somehow without export into Excel and writing all formulas there?" [포럼 19492](https://community.openproject.org/projects/OP/forums/6/topics/19492) | API 토큰·웹훅 있음, 가져오기 없음 |
| 7 | 담당자 여러 명, 세밀한 권한 | 7 | 2 | "I wonder, if it´s possible to assign a single task to multiple members of the same project?" [포럼 19415](https://community.openproject.org/projects/OP/forums/6/topics/19415) | 없음 |
| 8 | 반복 작업, 템플릿, 체크리스트 (근거 적음: 출처 1곳) | 7 | 1 | "I could not find if there is a way (doesn't matter standard, workaround or coding) to create recurring tasks?" [포럼 19271](https://community.openproject.org/projects/OP/forums/6/topics/19271) | 프로젝트 템플릿 있음, 반복 작업 없음 |
| 9 | 리소스·공수 계획 | 6 | 2 | "I can't create a report of planned work per resource." [포럼 19492](https://community.openproject.org/projects/OP/forums/6/topics/19492) | 팀 플래너 있음, 사람별 공수 보고서 없음 |
| 10 | 모바일, 오프라인 (근거 적음: 출처 1곳) | 4 | 1 | - | 반응형 화면 있음 |

## 3. 아무도 풀지 않은 일 (포지셔닝 후보)

1. **업무 시간 기록이 의무인 회사의 일상 업무.** "small company of about 400 people in a country in Asia"에서 근무 시간 기록이 전사 의무였습니다. 사용자는 화면이 불편해서 API로 입력하는 스크립트를 따로 만들었습니다([HN 35149319](https://news.ycombinator.com/item?id=35149319)). 원본은 기록 기능이 있어도 매일 쓰기에는 손이 많이 갑니다. 한국 회사의 공수 보고 관행과 바로 겹칩니다. 근거는 시간 기록 12건/3곳과 화면 불편 17건/5곳입니다.
2. **IT 담당자 한 명이 회사 서버를 관리하는 경우.** 서로 다른 세 곳에서 OpenProject를 관리한 사람이 있습니다. 그중 가장 큰 곳은 "ultimately ended up paying for some "better" proprietary solution"이었습니다([HN 40402168](https://news.ycombinator.com/item?id=40402168)). 업그레이드 문제(19건/5곳)와 자원 문제(14건/3곳)가 이 집단의 부담입니다.
3. **반복되는 사업을 템플릿으로 시작하는 팀.** "Specify the new project's start date. Automatically adjust all work package start and finish dates while preserving the relative schedule structure and durations."([포럼 19602](https://community.openproject.org/projects/OP/forums/6/topics/19602), 한국인 이름의 작성자, 2026-06). 근거는 출처 1곳이라 약합니다.

## 4. 고칠 계획

근거와 고치는 비용을 함께 보고 골랐습니다. 순서가 곧 권장 순서입니다.

| # | 만들 것 | 크기 | 담당 | 근거 |
| --- | --- | --- | --- | --- |
| 1 | **주간 공수 입력표**: 한 화면에서 내 작업 × 요일 칸에 시간을 키보드로 입력하고 저장. 작업 상세의 시간 기록, 사람·프로젝트별 집계, Excel 내보내기. 하루 근무 시간을 넘으면 경고. 원본은 "범위 밖"이었던 시간 기록을 이 형태로 들여옵니다 | L | /replica-build, /replica-backend | 시간 기록 12건/3곳, 화면 불편 17건/5곳, HN 35149319, 포럼 19007 |
| 2 | **템플릿 복사 때 새 시작일로 전체 일정 이동**: 프로젝트를 만들거나 복사할 때 시작일을 정하면 모든 작업의 날짜를 근무일 기준으로 같은 간격만큼 옮김(기간과 선후 관계 유지) | S | /replica-build | 포럼 19602 (출처 1곳, 약함), Gantt 17건/5곳 |
| 3 | **업그레이드 안전장치**: 새 마이그레이션을 적용하기 전에 DB를 자동으로 백업. 관리 · 시스템 정보에 버전, 적용된 마이그레이션, 마지막 백업을 보여 줌. 운영 문서에 "이미지 바꾸고 다시 시작"하는 한 가지 절차만 둠 | M | /replica-backend, /replica-deploy | 업그레이드 19건/5곳, 포럼 19206·19214, 블로그 kronis.dev |
| 4 | **성능 예산**: 주요 화면(목록 1,000건, 보드, Gantt, 작업 상세)의 응답 시간 목표를 정하고 자동 측정 테스트로 회귀를 막음. 운영자 매뉴얼에 권장 사양을 실측으로 적음 | S | /replica-test | 느림·자원 14건/3곳 |
| 5 | **화면별 도움말 연결**: 각 화면의 도움말 버튼이 사용자 매뉴얼의 해당 절로 연결. 관리 화면에 "무엇이 되는가" 기능 목록 | S | /replica-build | 문서·찾기 11건/4곳 |
| 6 | **공동 담당자**: 작업에 담당자를 여러 명 두고 각자 예상 시간을 따로 적음. 필터·알림·보드도 함께 | L | /replica-build | 7건/2곳. 원본 직원 답변: "Multiple assignees without group creation is one of the feature requests with the highest vote count here." [포럼 19193](https://community.openproject.org/projects/OP/forums/6/topics/19193) (투표 수 자체는 확인 못함) |
| 7 | **반복 작업**: 매주·매월 같은 작업을 자동으로 만들기 | M | /replica-build | 7건, 출처 1곳(근거 약함) |

**이번에 넣지 않은 것**
- **백로그·스프린트**(20건/7곳): 요청은 가장 많지만 크기가 L 이상입니다. 사내 스크럼 팀이 있는지부터 확인하고 정합니다(`features.csv`에 "스크럼 팀이 있으면 재검토"로 남김).
- **위키·문서**(10건/4곳): 사내 위키(Confluence 등)가 이미 있을 가능성이 커서 연동이 먼저입니다.
- **가격·라이선스 불만**: `/replica-launch` 몫입니다. 사내 도입이라 가격 문제는 없고, "판 구분 없음"만 포지셔닝에 씁니다.

위 1–7은 `replica/features.csv`에 `original=no`, `clone=no`로 넣었습니다. 패리티 점수 계산에는 들어가지 않습니다.

## 5. 포지셔닝

**A. 판 구분 없는 사내 PMS**
> 무료판에서 중요한 기능이 잠겨 있고 판매 배너가 뜨는 것이 싫은 사내 팀을 위해, openPMS는 모든 기능을 모든 사람에게 배너 없이 제공합니다.
> 근거: Enterprise 판·판매 배너 32건, 출처 6곳.

**B. 운영 부담이 적은 PMS**
> 업그레이드할 때마다 서버가 깨질까 걱정하는 IT 담당자를 위해, openPMS는 컨테이너 하나와 PostgreSQL로 돌고, 업그레이드는 이미지만 바꾸면 되며, 바꾸기 전에 스스로 백업합니다.
> 근거: 업그레이드 19건/5곳, 느림·자원 14건/3곳.

**C. 매일 쓰기 편한, 한국 회사용 PMS (추천)**
> 공수 기록과 상태 보고를 클릭 많은 화면에서 해야 하는 한국 회사 직원을 위해, openPMS는 매일 하는 일을 키보드 몇 번으로 끝내게 합니다. 한 화면 주간 공수 입력, 한국어 화면, 한국 공휴일과 근무 시간 기준 일정을 갖췄습니다.
> 근거: 화면 불편·클릭 17건/5곳, 시간 기록 12건/3곳, "small company of about 400 people in a country in Asia"의 의무 시간 기록 사례.

**추천은 C입니다.**
- openPMS는 사내용이라 매일 쓰는 직원이 첫 사용자입니다.
- C는 고칠 계획 1번(주간 공수 입력표)과 바로 이어집니다.
- A와 B는 이미 사실이므로 C를 받쳐 주는 근거로 씁니다. 판 구분이 없는 것은 이미 그렇고, 업그레이드 부담은 3번 계획으로 줄입니다.
- 이름과 홍보 문구에 원본 이름은 쓰지 않습니다.

다음 단계는 `/replica-brand`(이름·말투)입니다. 사내 도입이라 이름을 바꿀 필요가 없다면 고칠 계획 1번부터 만드는 것을 권합니다.
