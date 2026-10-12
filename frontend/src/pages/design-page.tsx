import { useState, type ReactNode } from "react";
import {
  CalendarDays,
  ChevronDown,
  Columns3,
  Filter,
  FolderKanban,
  GanttChart,
  GitBranch,
  Inbox,
  KanbanSquare,
  Link2,
  ListTodo,
  MoreHorizontal,
  Network,
  Pin,
  Plus,
  Settings,
  Trash2,
  Users,
} from "lucide-react";
import { tokens } from "@/design/tokens";
import { contrast } from "@/lib/color";
import { cn } from "@/lib/cn";
import { Button } from "@/components/ui/button";
import { Field, Input, Textarea } from "@/components/ui/field";
import { Select } from "@/components/ui/select";
import { Checkbox, RadioCards, Segmented } from "@/components/ui/choice";
import { Badge, Counter, Priority, StatusPill, TypeLabel } from "@/components/ui/badges";
import { Avatar, Principal } from "@/components/ui/avatar";
import { Tabs } from "@/components/ui/tabs";
import { Dialog, DialogClose, Menu, Tooltip } from "@/components/ui/overlay";
import { Banner, EmptyState, useToast } from "@/components/ui/feedback";
import { Breadcrumbs, Pagination } from "@/components/ui/nav";
import { Skeleton, Spinner } from "@/components/ui/spinner";
import { AppHeader, Sidebar } from "@/components/shell/app-shell";
import { WorkPackageCard } from "@/components/domain/work-package-card";

// Sample data written for openPMS (not taken from the original app).
const D = tokens.color.data;
const STATUSES = [
  { name: "신규", color: D.teal },
  { name: "진행 중", color: D.purple },
  { name: "검토 중", color: D.amber },
  { name: "보류", color: D.grey },
  { name: "완료", color: D.green },
  { name: "취소", color: D.red },
];
const TYPES = [
  { name: "작업", color: D.blue },
  { name: "마일스톤", color: D.green },
  { name: "요약", color: D.orange },
  { name: "기능", color: D.purple },
  { name: "버그", color: D.red },
];
const USERS = [
  { id: "u-1", name: "김서연" },
  { id: "u-2", name: "박준호" },
  { id: "u-3", name: "이하늘" },
  { id: "u-4", name: "Alex Kim" },
];

function Section({ id, title, children, note }: { id: string; title: string; note?: ReactNode; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-h`} className="scroll-mt-20 border-t border-border-muted py-8 first:border-t-0">
      <h2 id={`${id}-h`} className="text-xl text-fg">{title}</h2>
      {note && <p className="mt-1 max-w-3xl text-sm text-fg-muted">{note}</p>}
      <div className="mt-5">{children}</div>
    </section>
  );
}

function Row({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  return (
    <div className={cn("grid grid-cols-1 gap-2 py-2 md:grid-cols-[160px_1fr] md:items-center", className)}>
      <div className="text-xs font-semibold text-fg-muted">{label}</div>
      <div className="flex flex-wrap items-center gap-2">{children}</div>
    </div>
  );
}

function Swatch({ name, value }: { name: string; value: string }) {
  const isHex = value.startsWith("#");
  const onBg = isHex ? contrast(value, "#ffffff") : null;
  return (
    <div className="flex items-center gap-3 rounded-md border border-border-muted p-2">
      <span className="size-10 shrink-0 rounded-md border border-border-muted" style={{ background: value }} aria-hidden="true" />
      <div className="min-w-0 text-xs">
        <div className="truncate font-semibold text-fg">{name}</div>
        <div className="font-mono text-fg-muted">{value}</div>
        {onBg !== null && <div className="text-fg-muted">흰 배경 대비 {onBg.toFixed(2)}:1</div>}
      </div>
    </div>
  );
}

export function DesignPage() {
  const toast = useToast();
  const [mode, setMode] = useState("auto");
  const [boardKind, setBoardKind] = useState("status");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const flatColors: [string, string][] = Object.entries(tokens.color as Record<string, string | Record<string, string>>).flatMap(
    ([k, v]): [string, string][] => (typeof v === "string" ? [[k, v]] : Object.entries(v).map(([k2, v2]): [string, string] => [`data-${k2}`, v2])),
  );

  return (
    <div className="min-h-dvh bg-bg">
      <AppHeader unread={3} user={USERS[0]} />
      <div className="flex">
        <Sidebar
          className="sticky top-header hidden h-[calc(100dvh-var(--pms-layout-header-h))] lg:flex"
          label="디자인 시스템 목차"
          header={<p className="px-2.5 text-sm font-semibold text-fg">디자인 시스템</p>}
          sections={[
            {
              items: [
                { label: "토큰", to: "#tokens", icon: <Columns3 />, active: true },
                { label: "타이포그래피", to: "#type", icon: <ListTodo /> },
                { label: "버튼", to: "#buttons", icon: <Plus /> },
                { label: "폼 컨트롤", to: "#forms", icon: <Settings /> },
                { label: "표시 요소", to: "#display", icon: <Pin /> },
                { label: "탐색", to: "#nav", icon: <Network /> },
                { label: "피드백", to: "#feedback", icon: <Inbox /> },
                { label: "오버레이", to: "#overlays", icon: <Link2 /> },
              ],
            },
            {
              title: "도메인 구성 요소",
              items: [
                { label: "작업 목록 행", to: "#table", icon: <ListTodo /> },
                { label: "보드 카드", to: "#cards", icon: <KanbanSquare />, badge: <Counter>3</Counter> },
                { label: "Gantt 요소", to: "#gantt", icon: <GanttChart /> },
              ],
            },
          ]}
        />
        <main id="main" className="min-w-0 flex-1 px-content pb-24 md:px-8">
          <div className="pt-4">
            <Breadcrumbs items={[{ label: "openPMS", href: "/" }, { label: "개발 도구" }, { label: "디자인 시스템" }]} />
            <div className="mt-2 flex flex-wrap items-center justify-between gap-3">
              <h1 className="text-xl text-fg">디자인 시스템</h1>
              <div className="flex flex-wrap items-center gap-toolbar">
                <Button variant="primary" icon={<Plus />}>만들기</Button>
                <Button icon={<Filter />}>
                  필터 <Counter>2</Counter>
                </Button>
                <Tooltip content="보기 설정">
                  <Button iconOnly aria-label="보기 설정" icon={<MoreHorizontal />} />
                </Tooltip>
              </div>
            </div>
            <p className="mt-2 max-w-3xl text-sm text-fg-muted">
              replica/design/tokens.json에서 만든 토큰과 기본 컴포넌트입니다. 강조색은 자주, 헤더는 먹색이고 정한 까닭은 replica/brand.md에 있습니다.
            </p>
          </div>

          <Section id="tokens" title="색 역할" note="역할 이름은 고정이고 값만 바뀝니다. 데이터 팔레트는 유형·상태·우선순위·아바타에 쓰며, 모두 흰 배경 대비 4.5:1 이상입니다.">
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-4">
              {flatColors.map(([k, v]) => (
                <Swatch key={k} name={k} value={v} />
              ))}
            </div>
          </Section>

          <Section id="type" title="타이포그래피·간격·반경·그림자" note="Pretendard(OFL). 앱 본문은 14px, 긴 글은 16px입니다.">
            <div className="flex flex-col gap-3">
              {Object.entries(tokens.type).map(([k, v]) => (
                <div key={k} className="flex items-baseline gap-4">
                  <span className="w-28 shrink-0 font-mono text-xs text-fg-muted">
                    {k} {v.size}/{v.line} {v.weight}
                  </span>
                  <span style={{ fontSize: v.size, lineHeight: `${v.line}px`, fontWeight: v.weight }}>
                    일정이 겹치는 작업 12건을 확인하세요 — Schedule 2026
                  </span>
                </div>
              ))}
            </div>
            <div className="mt-6 flex flex-wrap items-end gap-3">
              {tokens.space.filter((s) => s > 0).map((s) => (
                <div key={s} className="flex flex-col items-center gap-1">
                  <span className="block bg-accent-subtle outline-1 outline-accent" style={{ width: s, height: s }} aria-hidden="true" />
                  <span className="font-mono text-xs text-fg-muted">{s}</span>
                </div>
              ))}
            </div>
            <div className="mt-6 flex flex-wrap gap-4">
              {Object.entries(tokens.radius).map(([k, v]) => (
                <div key={k} className="flex size-20 items-center justify-center border border-border-input bg-surface text-xs text-fg-muted" style={{ borderRadius: Math.min(v, 40) }}>
                  radius {k}
                </div>
              ))}
              {Object.keys(tokens.shadow).map((k) => (
                <div key={k} className="flex size-20 items-center justify-center rounded-md bg-bg text-xs text-fg-muted" style={{ boxShadow: `var(--pms-shadow-${k})` }}>
                  shadow {k}
                </div>
              ))}
            </div>
          </Section>

          <Section id="buttons" title="버튼" note="높이 sm 28 · md 32 · lg 40. 아이콘만 있는 버튼은 aria-label이 필수이며 툴팁도 같은 문구를 씁니다.">
            {(["primary", "secondary", "ghost", "danger", "danger-ghost", "link"] as const).map((v) => (
              <Row key={v} label={v}>
                <Button variant={v} size="sm">작게</Button>
                <Button variant={v}>기본</Button>
                <Button variant={v} size="lg">크게</Button>
                <Button variant={v} icon={<Plus />}>아이콘</Button>
                <Button variant={v} className="focus-ring">포커스</Button>
                <Button variant={v} disabled>비활성</Button>
                <Button variant={v} loading>저장 중</Button>
              </Row>
            ))}
            <Row label="아이콘 전용">
              <Button iconOnly size="sm" aria-label="삭제" icon={<Trash2 />} />
              <Button iconOnly aria-label="설정" icon={<Settings />} />
              <Button iconOnly size="lg" variant="ghost" aria-label="더 보기" icon={<MoreHorizontal />} />
              <Button variant="primary" icon={<Plus />}>
                만들기 <ChevronDown />
              </Button>
            </Row>
          </Section>

          <Section id="forms" title="폼 컨트롤" note="입력 테두리는 배경 대비 3:1 이상입니다(원본 약 1.4:1에서 개선). 오류는 색과 문구로 함께 알리고 aria-describedby로 연결합니다.">
            <div className="grid max-w-form grid-cols-1 gap-5">
              <Field label="제목" required hint="작업 목록과 알림에 표시됩니다.">
                <Input placeholder="예: 3분기 릴리스 일정 확정" />
              </Field>
              <Field label="식별자" required error="이미 사용 중인 식별자입니다. 다른 값을 입력하세요.">
                <Input defaultValue="release-q3" />
              </Field>
              <Field label="작성자" hint="작성자는 바꿀 수 없습니다.">
                <Input value="김서연" disabled readOnly />
              </Field>
              <Field label="설명">
                <Textarea placeholder="배경, 완료 조건, 참고 링크를 적어 주세요." />
              </Field>
              <Field label="우선순위">
                <Select
                  defaultValue="normal"
                  options={[
                    { value: "low", label: "낮음" },
                    { value: "normal", label: "보통" },
                    { value: "high", label: "높음" },
                    { value: "urgent", label: "긴급" },
                  ]}
                />
              </Field>
              <Field label="담당자" layout="inline">
                <Select placeholder="담당자 선택" options={USERS.map((u) => ({ value: u.id, label: u.name }))} />
              </Field>
              <div className="flex flex-col gap-3">
                <Checkbox label="근무일만 계산" description="주말과 휴무일은 기간에 넣지 않습니다." defaultChecked />
                <Checkbox label="하위 프로젝트 포함" checked="indeterminate" />
                <Checkbox label="보관된 프로젝트 보기" disabled />
              </div>
              <div>
                <p className="mb-2 text-sm font-medium text-fg" id="sched-label">일정 방식</p>
                <Segmented
                  aria-label="일정 방식"
                  value={mode}
                  onValueChange={setMode}
                  options={[
                    { value: "manual", label: "수동", icon: <Pin /> },
                    { value: "auto", label: "자동", icon: <CalendarDays /> },
                  ]}
                />
              </div>
            </div>
            <div className="mt-6 max-w-modal-lg">
              <p className="mb-2 text-sm font-medium text-fg">보드 종류</p>
              <RadioCards
                aria-label="보드 종류"
                value={boardKind}
                onValueChange={setBoardKind}
                options={[
                  { value: "basic", title: "빈 보드", description: "목록과 카드를 직접 구성합니다.", icon: <Columns3 /> },
                  { value: "status", title: "상태별", description: "카드를 옮기면 상태가 바뀝니다.", icon: <KanbanSquare /> },
                  { value: "assignee", title: "담당자별", description: "사람마다 열이 생기고, 옮기면 담당자가 바뀝니다.", icon: <Users /> },
                  { value: "parent", title: "부모-자식", description: "옮기면 상위 작업이 바뀝니다.", icon: <GitBranch />, disabled: true },
                ]}
              />
            </div>
          </Section>

          <Section id="display" title="표시 요소" note="상태 pill 글자색은 배경색에서 자동으로 고릅니다. 우선순위는 점과 이름을 항상 함께 보여줍니다.">
            <Row label="상태 pill">
              {STATUSES.map((s) => (
                <StatusPill key={s.name} name={s.name} color={s.color} />
              ))}
            </Row>
            <Row label="상태 pill (편집)">
              {STATUSES.slice(0, 3).map((s) => (
                <StatusPill key={s.name} editable name={s.name} color={s.color} />
              ))}
              <StatusPill editable name="밝은 사용자 색" color="#f5d76e" />
            </Row>
            <Row label="유형">
              {TYPES.map((t) => (
                <TypeLabel key={t.name} name={t.name} color={t.color} />
              ))}
            </Row>
            <Row label="우선순위">
              <Priority name="낮음" color={D.grey} />
              <Priority name="보통" color={D.blue} />
              <Priority name="높음" color={D.amber} />
              <Priority name="긴급" color={D.red} />
            </Row>
            <Row label="배지·카운터">
              <Badge>기본값</Badge>
              <Badge tone="accent">공개</Badge>
              <Badge tone="success">활성</Badge>
              <Badge tone="warning">보관됨</Badge>
              <Badge tone="danger">잠김</Badge>
              <Counter>6</Counter>
              <Counter tone="accent">12</Counter>
              <Counter tone="danger">99+</Counter>
            </Row>
            <Row label="아바타">
              {USERS.map((u) => (
                <Avatar key={u.id} id={u.id} name={u.name} size="md" />
              ))}
              {USERS.slice(0, 2).map((u) => (
                <Principal key={u.id} id={u.id} name={u.name} />
              ))}
            </Row>
            <Row label="로딩">
              <Spinner label="불러오는 중" />
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-4 w-24" />
            </Row>
          </Section>

          <Section id="nav" title="탐색">
            <Tabs
              aria-label="작업 상세 탭"
              items={[
                { value: "activity", label: "활동", content: <p className="text-sm text-fg-muted">변경 이력과 코멘트가 시간 순서로 표시됩니다.</p> },
                { value: "files", label: "파일", count: 2, content: <p className="text-sm text-fg-muted">첨부 파일 2개</p> },
                { value: "relations", label: "관계", count: 6, content: <p className="text-sm text-fg-muted">선행 2 · 후행 1 · 하위 3</p> },
                { value: "watchers", label: "주시자", content: <p className="text-sm text-fg-muted">주시자 없음</p> },
              ]}
            />
            <div className="mt-6 max-w-modal-lg">
              <Pagination page={page} pageSize={pageSize} total={134} onPage={setPage} onPageSize={setPageSize} />
            </div>
          </Section>

          <Section id="feedback" title="피드백">
            <div className="flex max-w-modal-lg flex-col gap-3">
              <Banner tone="info" title="날짜는 하위 작업에서 정해집니다." action={<Button size="sm">관계 보기</Button>}>
                일정을 바꾸려면 하위 작업의 날짜를 조정하세요.
              </Banner>
              <Banner tone="success">보기를 저장했습니다.</Banner>
              <Banner tone="warning" title="선행 작업이 끝나기 전에 시작합니다." onDismiss={() => {}}>
                #128 배포 준비가 10월 14일에 끝납니다.
              </Banner>
              <Banner tone="danger" title="다른 사람이 먼저 수정했습니다.">
                최신 내용을 불러온 뒤 다시 저장하세요.
              </Banner>
              <div className="flex gap-2">
                <Button onClick={() => toast({ tone: "success", title: "작업을 만들었습니다.", description: "#214 3분기 릴리스 일정 확정" })}>성공 토스트</Button>
                <Button onClick={() => toast({ tone: "danger", title: "저장하지 못했습니다.", description: "네트워크 연결을 확인하세요.", action: { label: "다시 시도", onClick: () => {} } })}>
                  오류 토스트
                </Button>
              </div>
              <div className="rounded-lg border border-border">
                <EmptyState icon={<Inbox />} title="새 알림이 없습니다" action={<Button variant="secondary">알림 설정</Button>}>
                  담당하거나 주시하는 작업에 변화가 생기면 여기에 표시됩니다.
                </EmptyState>
              </div>
            </div>
          </Section>

          <Section id="overlays" title="오버레이" note="대화상자는 포커스를 가두고 Esc로 닫히며, 닫히면 연 버튼으로 포커스가 돌아갑니다.">
            <div className="flex flex-wrap gap-2">
              <Dialog
                title="날짜"
                size="md"
                banner={
                  <Banner flush tone="info" action={<Button size="sm">관계 보기</Button>}>
                    날짜는 하위 작업에서 정해집니다.
                  </Banner>
                }
                trigger={<Button>대화상자 열기</Button>}
                footer={
                  <>
                    <DialogClose asChild>
                      <Button>취소</Button>
                    </DialogClose>
                    <Button variant="primary">저장</Button>
                  </>
                }
              >
                <div className="flex flex-col gap-4">
                  <Segmented aria-label="일정 방식" value={mode} onValueChange={setMode} options={[{ value: "manual", label: "수동", icon: <Pin /> }, { value: "auto", label: "자동", icon: <CalendarDays /> }]} />
                  <div className="grid grid-cols-3 gap-3">
                    <Field label="시작일"><Input defaultValue="2026-10-12" data-autofocus /></Field>
                    <Field label="완료일"><Input defaultValue="2026-10-16" /></Field>
                    <Field label="기간(근무일)"><Input defaultValue="5" /></Field>
                  </div>
                </div>
              </Dialog>
              <Menu
                trigger={<Button icon={<Plus />}>관계 추가 <ChevronDown /></Button>}
                items={[
                  { label: "관련 항목", description: "서로 연결만 하고 일정에는 영향이 없습니다." },
                  { label: "선행 작업", description: "이 작업은 선택한 작업이 끝난 뒤 시작합니다." },
                  { label: "후행 작업", description: "선택한 작업은 이 작업이 끝난 뒤 시작합니다." },
                  { kind: "separator" },
                  { label: "하위 작업 새로 만들기", icon: <Plus /> },
                  { kind: "sub", label: "다른 관계", items: [{ label: "중복" }, { label: "차단" }, { label: "포함" }, { label: "필요" }] },
                  { kind: "separator" },
                  { label: "관계 삭제", icon: <Trash2 />, danger: true },
                ]}
                width="w-80"
              />
            </div>
          </Section>

          <Section id="table" title="작업 목록 행" note="행 높이 40px, 셀 hover 시 인라인 편집 버튼으로 바뀝니다. 계층은 들여쓰기 20px와 펼침 버튼으로 표시합니다.">
            <div className="relative overflow-x-auto rounded-md border border-border">
              <table className="w-full min-w-[720px] border-collapse text-sm">
                <thead>
                  <tr className="h-row border-b border-border bg-surface text-left text-xs font-semibold text-fg-muted">
                    <th scope="col" className="w-16 px-3">번호</th>
                    <th scope="col" className="px-3">제목</th>
                    <th scope="col" className="w-24 px-3">유형</th>
                    <th scope="col" className="w-28 px-3">상태</th>
                    <th scope="col" className="w-36 px-3">담당자</th>
                    <th scope="col" className="w-28 px-3">완료일</th>
                  </tr>
                </thead>
                <tbody>
                  {[
                    { n: 201, depth: 0, kids: true, s: "3분기 릴리스", t: TYPES[2], st: STATUSES[1], u: USERS[0], due: "2026.10.30", sel: false },
                    { n: 202, depth: 1, kids: false, s: "릴리스 노트 초안 작성", t: TYPES[0], st: STATUSES[2], u: USERS[1], due: "2026.10.08", over: true, sel: true },
                    { n: 203, depth: 1, kids: false, s: "배포 체크리스트 점검", t: TYPES[0], st: STATUSES[0], u: USERS[2], due: "2026.10.21", sel: false },
                    { n: 204, depth: 0, kids: false, s: "릴리스", t: TYPES[1], st: STATUSES[0], u: USERS[3], due: "2026.10.30", sel: false },
                  ].map((r) => (
                    <tr key={r.n} aria-selected={r.sel} className={cn("h-row border-b border-border-muted last:border-b-0 hover:bg-surface", r.sel && "bg-accent-subtle hover:bg-accent-subtle")}>
                      <td className="px-3 text-accent tabular-nums"><a href="#" className="hover:underline">{r.n}</a></td>
                      <td className="px-3">
                        <span className="inline-flex items-center gap-1" style={{ paddingLeft: r.depth * 20 }}>
                          {r.kids ? (
                            <button type="button" aria-expanded="true" aria-label="하위 작업 접기" className="inline-flex size-5 items-center justify-center rounded-sm text-fg-muted hover:bg-surface-hover">
                              <ChevronDown className="size-4" />
                            </button>
                          ) : (
                            <span className="inline-block w-5" aria-hidden="true" />
                          )}
                          <span className="text-fg">{r.s}</span>
                        </span>
                      </td>
                      <td className="px-3"><TypeLabel name={r.t.name} color={r.t.color} /></td>
                      <td className="px-3"><StatusPill size="sm" editable name={r.st.name} color={r.st.color} /></td>
                      <td className="px-3"><Principal id={r.u.id} name={r.u.name} size="xs" /></td>
                      <td className={cn("px-3 tabular-nums", r.over ? "font-semibold text-danger" : "text-fg")}>
                        {r.due}
                        {r.over && <span className="sr-only"> (기한 지남)</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Section>

          <Section id="cards" title="보드 카드" note="카드 상단 2px 띠는 유형 색입니다. 카드 전체가 링크이고, 보드에서는 드래그 핸들이기도 합니다.">
            <div className="relative flex gap-4 overflow-x-auto pb-2">
              {[STATUSES[0], STATUSES[1], STATUSES[4]].map((st, ci) => (
                <div key={st.name} className="flex w-board-col shrink-0 flex-col gap-2 rounded-lg bg-surface p-2">
                  <div className="flex items-center justify-between px-1 pt-1">
                    <h3 className="flex items-center gap-2 text-sm font-semibold text-fg">
                      <span className="size-2.5 rounded-full" style={{ background: st.color }} aria-hidden="true" />
                      {st.name}
                      <Counter>{ci === 2 ? 0 : 2}</Counter>
                    </h3>
                    <Button variant="ghost" size="sm" iconOnly aria-label={`${st.name}에 카드 추가`} icon={<Plus />} />
                  </div>
                  {ci === 2 ? (
                    <EmptyState compact title="카드 없음">여기로 끌어다 놓으세요.</EmptyState>
                  ) : (
                    <>
                      <WorkPackageCard
                        dragging={ci === 1}
                        wp={{ number: 202 + ci, subject: ci ? "배포 체크리스트 점검" : "릴리스 노트 초안 작성", projectName: "웹 플랫폼", type: TYPES[0], assignee: USERS[ci], startDate: "2026-10-05", dueDate: "2026-10-08", overdue: ci === 0 }}
                      />
                      <WorkPackageCard
                        wp={{ number: 210 + ci, subject: "로그인 화면 접근성 점검", projectName: "웹 플랫폼", type: TYPES[4], assignee: USERS[2], dueDate: "2026-10-21", autoScheduled: true }}
                      />
                    </>
                  )}
                </div>
              ))}
            </div>
            <div className="mt-6 w-[390px] max-w-full rounded-lg border border-border">
              <p className="border-b border-border-muted px-3 py-2 text-xs font-semibold text-fg-muted">모바일 목록(390px)</p>
              <div className="flex flex-col gap-2 p-2">
                <WorkPackageCard showStatus wp={{ number: 202, subject: "릴리스 노트 초안 작성", projectName: "웹 플랫폼", type: TYPES[0], status: STATUSES[2], assignee: USERS[1], startDate: "2026-10-05", dueDate: "2026-10-08", overdue: true }} />
              </div>
            </div>
          </Section>

          <Section id="gantt" title="Gantt 요소" note="막대는 유형 색, 요약 작업은 괄호형 막대, 마일스톤은 마름모, 오늘은 위험 색 점선입니다. 비근무일은 surface로 칠합니다.">
            <div className="relative overflow-hidden rounded-md border border-border" style={{ height: 152 }}>
              <div className="absolute inset-0 grid grid-cols-14" aria-hidden="true">
                {Array.from({ length: 14 }, (_, i) => (
                  <div key={i} className={cn("border-r border-border-muted", (i % 7 === 5 || i % 7 === 6) && "bg-surface")} />
                ))}
              </div>
              <div className="absolute top-0 bottom-0 border-l-2 border-dashed border-danger" style={{ left: `${(4.5 / 14) * 100}%` }} aria-hidden="true" />
              {/* summary bar */}
              <div className="absolute h-2" style={{ top: 22, left: `${(1 / 14) * 100}%`, width: `${(9 / 14) * 100}%`, background: D.orange }} aria-label="요약: 3분기 릴리스">
                <span className="absolute -bottom-1.5 left-0 h-1.5 w-1.5" style={{ background: D.orange, clipPath: "polygon(0 0,100% 0,0 100%)" }} />
                <span className="absolute right-0 -bottom-1.5 h-1.5 w-1.5" style={{ background: D.orange, clipPath: "polygon(0 0,100% 0,100% 100%)" }} />
              </div>
              {/* task bars */}
              <div className="absolute h-5 rounded-sm shadow-card" style={{ top: 52, left: `${(1 / 14) * 100}%`, width: `${(4 / 14) * 100}%`, background: D.blue }} aria-label="작업: 릴리스 노트 초안" />
              <div className="absolute h-5 rounded-sm shadow-card" style={{ top: 88, left: `${(7 / 14) * 100}%`, width: `${(3 / 14) * 100}%`, background: D.blue }} aria-label="작업: 배포 체크리스트" />
              {/* dependency line: predecessor end → successor start */}
              <div className="absolute border-t-2 border-fg-muted" style={{ top: 62, left: `${(5 / 14) * 100}%`, width: `${(1.5 / 14) * 100}%` }} aria-hidden="true" />
              <div className="absolute border-l-2 border-fg-muted" style={{ top: 62, left: `${(6.5 / 14) * 100}%`, height: 36 }} aria-hidden="true" />
              <div className="absolute border-t-2 border-fg-muted" style={{ top: 96, left: `${(6.5 / 14) * 100}%`, width: `${(0.5 / 14) * 100}%` }} aria-hidden="true" />
              {/* milestone */}
              <div className="absolute size-3.5 rotate-45" style={{ top: 126, left: `calc(${(10 / 14) * 100}% - 7px)`, background: D.green }} aria-label="마일스톤: 릴리스" />
            </div>
          </Section>

          <footer className="flex items-center gap-2 border-t border-border-muted pt-6 text-xs text-fg-muted">
            <FolderKanban className="size-4" aria-hidden="true" />
            openPMS 디자인 시스템 · 토큰 {Object.keys(tokens.color).length}개 역할 · 대비 확인 {tokens.pairs.length}쌍
          </footer>
        </main>
      </div>
    </div>
  );
}
