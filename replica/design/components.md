# openPMS 컴포넌트 명세

- 입력: `replica/recon.md`의 컴포넌트 목록과 화면 S01–S37, 측정값(`tokens.json`의 `_measured`)
- 토큰: `replica/design/tokens.json` → `frontend/scripts/build-tokens.mjs` → `frontend/src/styles/{tokens,theme}.css`
- 확인 페이지: `frontend`에서 `npm run dev` 후 `http://localhost:5173/design`. 캡처는 `replica/design/screens/`에 있습니다.
- 대비 검사: `python3 replica/design/contrast.py replica/design/tokens.json`. 현재 62쌍 중 AA 실패 0이고 결과는 `contrast-report.txt`에 있습니다.

## 원칙

1. **역할만 가져오고 값은 우리 것을 씁니다.** 원본의 시그니처는 파란 헤더(#1a67a3), 초록 주요 버튼(#1f883d), 산호색 탭 밑줄입니다. openPMS는 이 셋을 모두 `accent` 하나로 통합했고, `/replica-brand`(2026-10-12)에서 값을 자주(#8b2f8f)로, 헤더를 먹색(#1e1c22)으로 정했습니다. 원본의 파랑·초록과 다른 계열입니다. 정한 까닭과 로고·말투는 `replica/brand.md`에 있습니다.
2. **아이콘은 Lucide(ISC)만 씁니다.** 원본의 아이콘·로고·일러스트는 쓰지 않습니다. 빈 상태 그림도 Lucide 아이콘으로 대신하고, 일러스트가 필요하면 새로 만듭니다.
3. **글꼴은 Pretendard Variable(SIL OFL)입니다.** 앱 안에 번들하므로 사내망에서 CDN 없이 동작합니다. 고정폭 글꼴은 시스템 기본 + D2Coding입니다.
4. **문구는 모두 새로 씁니다.** 원본 번역을 쓰지 않습니다. 용어집은 아래에 있습니다.
5. **컴포넌트는 Tailwind 역할 클래스만 씁니다**(`bg-surface`, `text-fg-muted`, `border-border-input`). 원시 hex 값은 데이터 색(유형·상태·우선순위)을 `style`로 넣을 때만 씁니다.
6. **접근성 기준은 WCAG 2.2 AA입니다.** 원본의 입력 테두리는 배경 대비 약 1.4:1이었는데, openPMS는 3.7:1로 올렸습니다. 색만으로 정보를 전달하지 않습니다. 우선순위는 점과 이름을, 기한 지남은 빨간색과 굵기, 스크린리더 문구를 함께 씁니다.

## 레이아웃 (측정값 → 토큰)

| 항목 | 원본 측정 | openPMS 토큰 | Tailwind |
| --- | --- | --- | --- |
| 헤더 높이 | 55px | 56 | `h-header` |
| 사이드바 폭 | 279 + 1px | 280 | `w-sidebar` |
| 사이드바 항목 | 36px, 간격 2px, 반경 약 6px, 활성 왼쪽 막대 4px | 36 / gap 2 / md / 4px 막대 | `h-sidebar-item` |
| 콘텐츠 좌우 여백 | 16px | 16 (md 이상 32) | `px-content` |
| 표 행 | 40 + 1px 구분선 | 40 (조밀 32) | `h-row`, `h-row-compact` |
| 버튼 높이 | 34px | sm 28 · md 32 · lg 40 | `h-control-*` |
| 툴바 간격 | 10px | 8 | `gap-toolbar` |
| 분할 패널 | 약 430px | 440 (최소 360) | `w-split`, `min-w-split-min` |
| 날짜 대화상자 | 600px | md 600 (sm 400, lg 800) | `max-w-modal-*` |
| 칸반 컬럼 | 300px (카드 256) | 300 | `w-board-col` |
| 모달 배경막 | 검정 50% | `backdrop` rgba(15,18,24,.5) | `bg-backdrop` |
| 브레이크포인트 | 390px에서 카드 목록 | sm 640 · md 768 · lg 1024 · xl 1280 | `md:` 이하에서 표가 카드로 바뀌고, `lg:` 미만에서 사이드바가 오버레이로 바뀜 |

z 순서: header 30 < sidebar-overlay 40 < modal 60 < dropdown 65 < toast 70 < tooltip 80. 드롭다운(선택 상자·메뉴)은 대화상자 안에서도 열리므로 모달보다 위에 둡니다. 사용법은 `z-(--pms-z-modal)`입니다.

모션: fast 120ms(hover·색), base 200ms(드롭다운·패널), slow 300ms(분할 패널 슬라이드), ease `cubic-bezier(.2,.8,.2,1)`. `prefers-reduced-motion`이면 모두 끕니다(index.css).

## 구현 상태 표시

- ✅ **구현됨**: `frontend/src/components/`에 있고 `/design`에서 확인할 수 있습니다.
- 🔧 **명세만 있음**: 해당 화면을 만드는 빌드 단계(architecture.md §6)에서 이 명세대로 구현합니다.

---

## 기본 컴포넌트

```
Button ✅  components/ui/button.tsx
  variants  primary, secondary, ghost, danger, danger-ghost, link
  sizes     sm 28px (text xs), md 32px (text sm), lg 40px; iconOnly는 정사각형
  states    default, hover, active, focus-visible (2px focus-ring, offset 2), disabled (50%), loading
  tokens    primary: bg accent / text on-accent / hover accent-hover
            secondary: bg surface / border border / hover surface-hover / active surface-selected
            danger: bg danger / text on-danger;  radius md;  font sm/500;  icon 16px, gap 6px
  a11y      실제 <button type="button">. loading이면 aria-busy를 붙이고 라벨은 숨기되 폭은 유지(스크린리더는 라벨을 읽음),
            클릭 차단. iconOnly는 aria-label이 필수이고 Tooltip 문구도 같아야 함. 분할 버튼(만들기▾)은 aria-haspopup="menu"
  used on   전체. 주요: S06 툴바(만들기, 필터 n, 기준선, ⋮), S09·S10 저장/취소, S16 만들기
  rule      화면당 primary는 하나. 파괴적 동작은 danger이고, 확인 대화상자 안에서만 씀
```

```
Input / Textarea ✅  components/ui/field.tsx
  sizes     sm 28, md 32 (Textarea는 rows로 높이 지정)
  states    default, hover (테두리 fg-muted), focus-visible (2px focus-ring), invalid (테두리 danger),
            disabled (bg surface-sunken, text fg-disabled), read-only (bg surface-sunken)
  tokens    bg bg, border border-input (3.7:1), radius md, text sm, placeholder fg-muted (4.5:1 이상)
  a11y      항상 Field의 <label>과 연결. placeholder를 라벨 대신 쓰지 않음
  used on   S01, S04, S09, S10, S11, S20, S23, S25, S27–S36
```

```
Field ✅  components/ui/field.tsx
  layout    stack(라벨 위, 폼) | inline(라벨 140px 왼쪽, 작업 패키지 속성 행: S07·S08)
  parts     label, required 표시(*), control, error, hint
  a11y      id·aria-describedby(error → hint 순서)·aria-invalid·aria-required를 자식 컨트롤에 자동으로 연결.
            오류 문구는 색과 글로 함께 알림. 제출 실패 시 첫 오류 필드로 포커스 이동(폼 쪽에서 처리)
```

```
Select ✅  components/ui/select.tsx (Radix Select)
  sizes     sm, md
  states    closed, open, highlighted 항목(surface-hover), selected(✓ accent), disabled 항목, placeholder, invalid
  tokens    trigger는 Input과 같음, 목록은 bg bg / border border / radius lg / shadow pop, 항목 높이 32
  a11y      화살표 키, 첫 글자 입력 이동, Esc 닫기, 닫으면 trigger로 포커스 복귀
  used on   S09 우선순위·버전·카테고리, S11, S20, S23 역할, 페이지네이션 페이지당
  note      검색이 필요한 선택(담당자, 작업 #번호, 50개 이상)은 Autocomplete를 씀
```

```
Autocomplete 🔧  (M1: 담당자, M2-1: 관계·부모)
  base      Radix Popover + combobox 패턴(aria-autocomplete="list", aria-activedescendant)
  states    비어 있음, 입력 중(250ms debounce), 로딩(Spinner), 결과 없음("일치하는 항목이 없습니다"), 오류, 선택됨
  items     사용자(Principal), 그룹, 작업(#번호 + 유형 + 제목), 프로젝트
  a11y      ↑↓ 이동, Enter 선택, Esc는 목록을 닫고 한 번 더 누르면 입력 취소. 결과 수를 aria-live로 알림
  used on   S06 필터 값, S08 관계 추가, S09 담당자, S23 멤버 추가, 헤더 검색 제안, @멘션
```

```
Checkbox ✅  components/ui/choice.tsx (Radix)
  states    unchecked, checked(bg accent), indeterminate(—), disabled, focus-visible
  tokens    16px, radius sm, border border-input, checked bg accent / on-accent
  a11y      라벨 전체가 클릭 영역. description은 라벨 안에 둬서 함께 읽힘
  used on   S10 근무일만, S24 모듈, S25 유형 사용, S29 워크플로 매트릭스, S06 행 선택
```

```
RadioCards ✅  components/ui/choice.tsx (Radix RadioGroup)
  states    default, hover(surface), checked(accent 테두리 + accent-subtle), disabled, focus-visible
  parts     라디오 점, 제목(sm/600), 설명(sm fg-muted), 오른쪽 아이콘(Lucide 32px)
  a11y      그룹 aria-label 필수. 화살표 키로 이동하며 이동 즉시 선택됨
  used on   S04 빈 프로젝트/템플릿, S16 보드 종류, S12 PDF 종류
```

```
Segmented ✅  components/ui/choice.tsx (Radix ToggleGroup single)
  states    선택(bg bg + shadow card + 굵게), 비선택(fg-muted), hover, disabled
  a11y      aria-label 필수, 화살표 키 이동. 항상 하나가 선택돼 있음(해제 불가)
  used on   S10 수동/자동, S19 읽지 않음/모두, Gantt 줌 단계
```

```
Badge / Counter ✅  components/ui/badges.tsx
  Badge tones    neutral, accent, success, warning, danger (h 20, radius sm, text xs/500)
  Counter tones  neutral(surface-selected), accent, danger (h 20, pill, 숫자는 tabular-nums)
  used on        필터 n, 관계 n, 탭 카운터, 헤더 알림 배지(99+), 프로젝트 "보관됨", 역할 "기본값"
  a11y           Counter만 단독으로 의미를 주지 않음. 버튼 라벨이나 aria-label에 숫자를 포함
```

```
StatusPill ✅  components/ui/badges.tsx
  variants  read-only(span) | editable(button + ▾, 허용 상태 메뉴를 엶)
  sizes     sm 20 (표·카드), md 24 (상세 헤더)
  tokens    bg = 상태 색(데이터), text = readableOn(bg)로 진한 글자/흰 글자 중 대비가 높은 쪽을 자동 선택, pill
  states    hover brightness 95%, 읽기 전용 상태(편집 불가)일 때는 read-only 변형
  a11y      editable이면 aria-label="상태: {이름}. 상태 바꾸기", aria-haspopup="menu".
            메뉴에는 워크플로가 허용한 상태만 표시. 허용 상태가 없으면 read-only로 렌더링
  used on   S06, S07, S08, S13, S15 카드(모바일), M1
```

```
TypeLabel / Priority ✅  components/ui/badges.tsx
  TypeLabel  유형 색 굵은 xs 텍스트. 관리자 색 선택기는 흰 배경 대비 4.5:1 미만이면 저장을 막음(isReadableAsText)
  Priority   10px 점 + 이름(항상 함께). hideLabel이면 이름을 sr-only와 title로 제공
  used on    S06, S08, S13, S15
```

```
Avatar / Principal ✅  components/ui/avatar.tsx
  sizes     xs 20 (글자 1개), sm 24, md 32 (글자 2개)
  initials  한국어 이름은 이름 부분(김서연 → 서연, xs는 서), 라틴 이름은 첫 글자 + 성 첫 글자(Alex Kim → AK)
  tokens    bg = data 팔레트 10색 중 사용자 id 해시로 고정(모두 흰 글자와 4.5:1 이상), text white
  a11y      Avatar는 aria-hidden. 이름은 Principal의 보이는 텍스트나 버튼 aria-label로 전달
  used on   헤더 계정 메뉴, S06 담당자, S08 활동, S15 카드, S23 멤버, S05 멤버 위젯
```

```
Tabs ✅  components/ui/tabs.tsx (Radix)
  style     밑줄형, 높이 40, 활성 탭은 2px accent 밑줄 + 굵게 + fg (원본 산호색 → accent로 통합)
  parts     라벨 + 선택적 Counter, 넘치면 가로 스크롤
  a11y      ←→ 이동, Home/End, 탭 목록에 aria-label 필수
  used on   S07·S08(요약·활동·파일·관계 n·주시자), S10(날짜·선행·후행·하위), S11, S19, S22, S25, S28
```

```
Dialog ✅  components/ui/overlay.tsx (Radix Dialog)
  sizes     sm 400, md 600, lg 800 (화면 폭 - 32px까지), 최대 높이는 화면 높이 - 32px이고 본문만 스크롤
  parts     banner(맨 위에 꽉 차게 붙음, 선택), title(lg), description, 닫기(×), body, footer(오른쪽 정렬, 위 구분선)
  tokens    bg bg, radius lg(12, 원본 측정과 같음), shadow modal, 배경막 backdrop
  a11y      포커스를 가둠. 첫 포커스는 [data-autofocus] 요소(없으면 첫 포커스 가능 요소).
            Esc·닫기 버튼으로 닫히고 닫히면 연 요소로 포커스 복귀(확인됨). 제목은 aria-labelledby로 연결.
            저장 안 한 변경이 있으면 닫기 전에 확인을 받음(warn_on_unsaved 설정)
  used on   S10 날짜, S11 보기 구성, S12 내보내기, 삭제 확인, 작업 이동·복사
```

```
Menu (액션 메뉴) ✅  components/ui/overlay.tsx (Radix DropdownMenu)
  entries   item(아이콘, 설명, 단축키, danger, 체크 표시), separator, label, sub(하위 메뉴)
  tokens    bg bg, border border, radius lg, shadow pop, 항목 높이 최소 32, highlighted는 surface-hover
  a11y      ↑↓·→(하위 메뉴 열기)·←·Esc·첫 글자 이동. 닫히면 trigger로 포커스 복귀
  used on   헤더 + 만들기, S06 ⋮ 보기 메뉴·행 컨텍스트 메뉴, S07·S08 ⋯, S08 관계 추가(설명형), 상태 변경
```

```
Tooltip ✅  components/ui/overlay.tsx (Radix Tooltip)
  tokens    bg fg(반전), text bg, text xs, radius md, 지연 400ms
  rule      정보를 툴팁에만 두지 않음. 아이콘 버튼의 이름을 보완하는 용도로만 씀
```

```
Banner ✅  components/ui/feedback.tsx
  tones     info, success, warning, danger (배경 *-subtle, 본문 text fg, 아이콘 역할 색)
  parts     아이콘, 제목(선택), 본문, 행동 버튼(선택), 닫기(선택). flush는 대화상자·패널 맨 위에 붙임
  a11y      warning·danger는 role="alert", 나머지는 role="status"
  used on   S10("날짜는 하위 작업에서 정해집니다"), 409 충돌 안내, S33 근무일 변경 경고, 보관된 프로젝트 안내
```

```
Toast ✅  components/ui/feedback.tsx (Radix Toast)
  tones     success, danger, info. 5초 후 자동으로 사라지고 마우스를 올리거나 포커스하면 멈춤. 오른쪽 아래에 쌓임
  parts     아이콘, 제목, 설명, 행동(다시 시도·실행 취소), 닫기
  a11y      danger는 foreground(바로 알림), 나머지는 background(정중하게 알림). 스와이프 닫기
  rule      되돌릴 수 있는 동작(삭제, 보관)은 확인 대화상자 대신 "실행 취소" 토스트를 우선 검토
```

```
EmptyState ✅  components/ui/feedback.tsx
  variants  page(py 48, 제목 lg) | compact(py 24, 제목 sm, 보드 빈 컬럼·위젯)
  parts     Lucide 아이콘 32px, 제목, 설명, 행동
  copy      무엇이 없는지 + 언제 생기는지 + 다음 행동. 예: "새 알림이 없습니다 / 담당하거나 주시하는 작업에 변화가 생기면 여기에 표시됩니다."
  used on   S02 즐겨찾기 없음, S06 결과 0건, S15 빈 컬럼, S19 빈 받은 편지함, S20 프로젝트별 설정 없음, S33 휴무일 없음
```

```
Spinner / Skeleton ✅  components/ui/spinner.tsx
  rule      300ms 넘게 걸리는 로딩만 표시. 표·카드는 Skeleton 행, 버튼은 loading, 패널은 Spinner + "불러오는 중"
  a11y      Spinner label이 있으면 role="status". 움직임 줄이기 설정이면 회전하지 않음
```

```
Breadcrumbs ✅ / Pagination ✅  components/ui/nav.tsx
  Breadcrumbs  nav aria-label="현재 위치", 마지막 항목은 aria-current="page" + 굵게, 구분자 "/"는 aria-hidden
  Pagination   "1–20 / 총 134건"(aria-live) + 페이지당 20·100 + 이전/다음. 원본의 "(1 - 13/13)"보다 읽기 쉽게
  used on      모든 페이지 상단 / S03, S06, S14, S23, S27–S31
```

## 셸

```
AppHeader ✅  components/shell/app-shell.tsx
  height    56, bg header-bg, text header-fg (15.9:1)
  parts     본문 건너뛰기 링크, 모듈 메뉴, 워드마크(플레이스홀더), 전역 검색(md 이상, 모바일은 아이콘),
            + 새로 만들기▾(accent), 알림(읽지 않은 수 배지), 도움말, 계정 메뉴(Avatar md)
  a11y      검색 input에 라벨. 알림 버튼 aria-label에 읽지 않은 수 포함. 헤더 안 포커스 링은 header-fg 색
  used on   전체
```

```
Sidebar ✅  components/shell/app-shell.tsx
  width     280. lg 미만에서는 헤더 버튼으로 여는 오버레이 드로어(🔧, z sidebar-overlay, Esc 닫기, 포커스 가둠)
  parts     프로젝트 선택기, 접기 버튼, 섹션 제목(xs/600 fg-muted), 항목(36px, 아이콘 16, 배지), 활성 항목
  active    bg surface-selected + 굵게 + 왼쪽 4px accent 막대 + aria-current="page"
  used on   전역 메뉴(S02), 프로젝트 메뉴(S05–S26), 저장된 보기 하위 메뉴(S06·S13: 기본·즐겨찾기·공개·비공개, 이름 검색)
```

```
SplitPane 🔧  (M1)
  width     오른쪽 상세 패널 440 기본, 최소 360, 최대 화면의 60%. 경계 핸들을 드래그해 조절(값은 localStorage에 저장)
  a11y      핸들은 role="separator" aria-orientation="vertical", ←→ 키로 16px씩 조절. 패널을 닫으면 원래 행으로 포커스 복귀
  mobile    md 미만에서는 분할하지 않고 전체 화면으로 열림
  used on   S06·S13·S19 → S07
```

## 도메인 구성 요소

```
WorkPackageTable 🔧  (M1 기본, M2-2 완성) — 행 모양은 /design "작업 목록 행"에서 확인
  row       높이 40(조밀 32), 아래 구분선 border-muted, hover surface, 선택 accent-subtle + aria-selected
  header    surface 배경, xs/600 fg-muted. 머리글 메뉴(정렬 오름·내림, 그룹화, 왼쪽·오른쪽으로 이동, 컬럼 숨기기)
  cells     번호(accent 링크, tabular-nums), 제목(계층 들여쓰기 20px/단계 + 펼침 버튼 20px), TypeLabel,
            StatusPill sm editable, Principal xs, 날짜(기한 지남은 danger + 굵게 + sr-only "기한 지남"), 숫자는 오른쪽 정렬
  inline    셀이 버튼 역할: 클릭이나 Enter로 편집, Esc 취소, Enter·포커스 이탈 시 저장.
            저장 중에는 셀에 Spinner, 실패하면 셀 테두리 danger + 토스트, 409면 Banner와 최신 값
  states    로딩(Skeleton 행 8개), 결과 0건(EmptyState + "필터 초기화"), 오류(Banner + 다시 시도),
            맥락 행(필터에 맞지 않는 조상은 fg-muted + "상위 항목" 표시), 그룹 머리글(+ 그룹 합계), 합계 행
  a11y      role="treegrid"(계층 모드) 또는 grid. ↑↓←→ 셀 이동, → 펼치기, ← 접기, Space 행 선택, Shift+F10·메뉴 키로 컨텍스트 메뉴
  layout    가로 스크롤 컨테이너는 반드시 position: relative.
            안쪽 sr-only 텍스트가 페이지 폭을 늘리는 버그를 /design에서 실제로 발견해 고쳤음
  virtual   100행 넘으면 TanStack Virtual
  mobile    md 미만에서는 WorkPackageCard 목록으로 바뀜(M1)
  used on   S06, S13 왼쪽, S21 위젯, 전역 목록
```

```
FilterPanel 🔧  (M2-2)
  parts     텍스트 필터 입력 + 필터 행(속성 Select | 연산자 Select | 값: Input/날짜/Autocomplete/숫자) + 삭제(×) + "필터 추가" Autocomplete
  states    빈 값(행에 "값을 입력하세요" 오류), 적용됨(툴바 Counter 갱신), 초기화
  a11y      각 행은 fieldset + legend(속성 이름). 행을 추가하면 새 행의 값 칸으로 포커스
  used on   S06, S03, S15 보드 필터, S23
```

```
DatePicker 🔧  (M2-3) — 대화상자 틀과 세그먼트는 /design에서 확인
  layout    Dialog md(600). banner(파생 날짜면 info) → 탭(날짜·선행 n·후행 n·하위 n) → 세그먼트(수동/자동)
            + 근무일만 체크 → 시작·완료·기간 입력(+ "오늘" 링크) → 2개월 달력 → 취소/저장
  calendar  날짜 칸 32px. 범위는 accent-subtle, 시작·끝은 accent/on-accent, 오늘은 warning-strong 테두리,
            비근무일은 fg-disabled + 사선(근무일만 모드에서는 선택 불가), 선행 종료일 이전은 점선 표시
  behavior  두 값을 바꾸면 세 번째를 계산(schedule-preview 호출 결과 표시). 자동 + 자식 있음이면 입력 잠금
  a11y      달력은 grid. 화살표 키로 하루·일주일 이동, PageUp/PageDown으로 월 이동, Enter 선택.
            각 칸 aria-label="2026년 10월 12일 월요일, 근무일"
  used on   S10 (S07·S08·S06 날짜 셀에서 열림)
```

```
GanttTimeline 🔧  (M2-4) — 막대·마일스톤·오늘 선·의존선은 /design "Gantt 요소"에서 확인
  layout    왼쪽 WorkPackageTable(행 공유, 같은 가상 스크롤) + 오른쪽 타임라인. 머리글은 연/월/주차(줌에 따라 일)
  marks     작업 막대: 유형 색, 높이 20, radius sm, shadow card
            요약(자식 있음): 높이 8 괄호형 막대
            마일스톤: 14px 마름모
            오늘: 2px danger 점선
            비근무일: surface 배경
            의존선: 2px fg-muted 꺾은선(선행 끝 → 후행 시작)
            기한 지남: 라벨 날짜 danger
            라벨: 왼쪽(시작일)·오른쪽(완료일)·먼 오른쪽(제목), 보기 설정으로 바꿈
  interact  막대 드래그 이동, 양끝 드래그로 기간 조절(근무일 단위로 맞춤), 빈 행에 드래그해서 새로 그리기.
            드래그 중에는 shadow drag와 날짜 툴팁, 놓으면 PATCH 후 전파 결과 반영, 실패하면 원위치 + 토스트
  zoom      일·주·월·분기·연·자동(Segmented)
  a11y      막대마다 버튼 역할(aria-label "작업 #202 릴리스 노트, 10월 5일~8일, 4근무일").
            Enter로 날짜 대화상자를 엶. 드래그는 키보드 대안(날짜 대화상자)이 항상 있음
  used on   S13, 전역 Gantt
```

```
TeamPlanner 🔧  (M3) — GanttTimeline 컴포넌트 재사용
  layout    행 = 사람(Principal), 열 = 날짜(1주/2주), 카드 = 작업 막대. 오른쪽 "기존 작업 추가" 패널(Autocomplete + 목록)
  interact  막대를 다른 사람 행으로 옮기면 담당자 변경, 좌우로 옮기면 날짜 변경, 양끝으로 기간 조절
  used on   S18
```

```
BoardColumn / WorkPackageCard ✅(카드) 🔧(컬럼·드래그, M2-5)
  column    폭 300, bg surface, radius lg, padding 8. 머리글은 값 색 점 + 이름 + Counter + 카드 추가(+) + ⋯(이름 바꾸기·삭제)
  card      bg bg, border border, radius md, shadow card, 상단 2px 유형 색 띠.
            #번호 · 프로젝트, TypeLabel + 제목(카드 전체가 링크), Avatar xs 또는 "담당자 없음", 날짜(자동 일정 아이콘, 기한 지남 danger)
  drag      dnd-kit. 드래그 중에는 rotate 1° + shadow drag, 드롭 위치 표시선은 accent 2px.
            워크플로로 거부되면 카드가 원래 자리로 돌아가고 토스트 "이 상태로 옮길 수 없습니다"
  a11y      키보드: Space로 들기, ←→ 컬럼 이동, ↑↓ 순서 이동, Space로 놓기, Esc로 취소. 각 단계를 aria-live로 알림
  empty     compact EmptyState "카드 없음 / 여기로 끌어다 놓으세요."
  used on   S15, M1(모바일 목록에서 showStatus)
```

```
ActivityStream 🔧  (M2-1)
  item      Avatar sm + 이름 + 상대 시각(title에 절대 시각) + 변경 목록("상태: 신규 → 진행 중") 또는 코멘트 본문(base 16, 리치 텍스트)
  variants  코멘트, 변경, 자동 변경(cause 표시: "선행 작업 #12의 일정 변경으로"), 내부 코멘트(warning-subtle 배경 + "내부" 배지)
  controls  필터(전체·코멘트·변경 Select), 정렬(오래된 순·최신 순), 맨 아래(또는 위) 코멘트 입력
  a11y      목록은 <ol>. 새 항목은 aria-live polite로 알림. 수정한 코멘트에는 "수정됨" 표시
  used on   S08 활동 탭, S07
```

```
RichTextEditor 🔧  (M2-1) — Tiptap
  toolbar   문단 스타일, 굵게, 기울임, 취소선, 인라인 코드, 코드 블록, 목록, 링크, 표, 더보기
  features  @멘션(Autocomplete, data-user-id로 저장), #번호 작업 링크, 붙여넣은 이미지는 첨부로 업로드
  display   읽기 모드는 클릭해서 편집(placeholder "클릭해서 내용을 입력하세요")
  a11y      툴바는 role="toolbar"이고 ←→로 이동. 단축키 Ctrl+B 등
  used on   S08·S09 설명, 코멘트, S05 프로젝트 설명
```

```
CheckboxMatrix 🔧  (M2-7) — 워크플로
  layout    행 = 현재 상태, 열 = 바뀔 상태, 대각선은 비활성. 행·열 전체 선택, 머리글 고정
  a11y      표(<table>) + 각 체크박스 aria-label "신규에서 진행 중으로 전환 허용"
  used on   S29
```

```
ReorderList 🔧  (M2-7)
  ui        드래그 핸들(dnd-kit) + 키보드 대안 메뉴(맨 위·위·아래·맨 아래). 원본의 버튼 4개 나열 대신 메뉴 하나
  used on   S27 유형, S30 상태, S36 우선순위, 보드 컬럼
```

```
WidgetCard 🔧  (M4)
  parts     제목 + ⋯, 본문(목록·표·차트·텍스트), 빈 상태 compact
  used on   S02, S05, S21
```

## 용어집 (원본 번역 대신 쓰는 openPMS 용어)

| 개념 | openPMS | 메모 |
| --- | --- | --- |
| work package | 작업 | 화면에서는 "작업", 문서·코드에서는 work package |
| task (유형) | 작업 | 유형 이름 |
| summary task | 요약 | |
| accountable | 책임자 | 원본의 "담당"은 "담당자"와 헷갈림 |
| assignee | 담당자 | |
| watcher | 주시자 | |
| precedes / follows | 선행 작업 / 후행 작업 | 원본의 "선행 단계 / 후임자(이후)"보다 명확하게 |
| parent / child | 상위 작업 / 하위 작업 | |
| relates to | 관련 항목 | |
| duration | 기간(근무일) | |
| manual / automatic scheduling | 수동 / 자동 일정 | |
| working days only | 근무일만 계산 | |
| baseline | 기준선 비교 | |
| saved view | 보기 | 기본 보기 / 공개 보기 / 내 보기 / 즐겨찾기 |
| notification inbox | 알림 | |
| board | 보드 | 원본 메뉴의 "게시판"은 포럼과 헷갈려서 쓰지 않음 |
| project attributes | 프로젝트 특성 | |
| overdue | 기한 지남 | |

## 결정 기록

- **accent 하나로 통합**: 원본은 링크와 헤더에 파랑, 주요 버튼에 초록, 탭에 산호색을 썼습니다. openPMS는 주요 동작, 링크, 활성 표시, 포커스를 모두 accent 하나로 표현합니다. 색이 하나 줄고, 리브랜딩할 때 바꿀 값도 하나입니다.
- **헤더는 어두운 중립색**: 원본의 시그니처 파란 헤더를 피했습니다. `/replica-brand`에서 브랜드 색을 정하더라도 헤더 배경과 주요 버튼 색을 같은 색으로 두지 않는 것을 권합니다(경계가 사라짐).
- **입력 테두리 대비 상향**: 원본 1.4:1 → 3.7:1 (WCAG 1.4.11).
- **데이터 팔레트 10색**: 유형·상태·우선순위의 seed 색과 아바타 색입니다. 모두 흰 배경 대비 4.5:1 이상이라 글자색으로도, 흰 글자를 올린 채움색으로도 쓸 수 있습니다. 관리자가 고른 색은 런타임에 `readableOn`과 `isReadableAsText`로 검사합니다. seed.sql도 이 팔레트로 바꿨습니다.
- **다크 모드는 could 항목**: 역할 이름이 이미 분리돼 있어서 나중에 `tokens.dark.json` 값만 추가하면 됩니다.
