// S18 팀 플래너: one saved planner (queries.view = team_planner). Its assignee filter is the list of people;
// each person's work for one or two weeks is laid out by day. Drag a bar to change its dates and assignee,
// drag its ends to change the period, drop it on the bottom zone to unassign, or bring work in from the side panel.
import { useMemo, useState, type PointerEventHandler } from "react";
import { Outlet, useMatch, useNavigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import { CalendarRange, ListPlus, MoreHorizontal, Plus, UserPlus, UserX, X } from "lucide-react";
import type { IsoDate, QuerySpec, UserRef, WorkPackage, WorkPackagePatch } from "@/api";
import { useFilterOptions, useMe, useNonWorkingDays, useSavedQueryMutations, useWpQuery } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Badge, TypeLabel } from "@/components/ui/badges";
import { Segmented } from "@/components/ui/choice";
import { Input } from "@/components/ui/field";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { addDays, formatDate, formatDateRange, todayIso } from "@/lib/dates";
import { ErrorState, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { useSaveWorkPackage } from "@/features/work-packages/use-save";
import { overlapFilter, startOfWeek, usePlanningView } from "@/features/planning/use-planning-view";
import { PeriodNav, PlanningViewMenu } from "@/features/planning/view-header";
import { DraggedBar, PlannerGrid, canMoveDates, parseCell, plannerCollision, plannerKeyboard } from "@/features/planning/planner-grid";

/** The people of a planner are the values of its assignee filter ("me" is the viewer). */
function peopleIds(spec: QuerySpec | undefined, meId: string | undefined): string[] {
  const f = spec?.filters.find((x) => x.field === "assignee" && x.op === "eq");
  return [...new Set((f?.values ?? []).map((v) => (v === "me" ? meId ?? v : v)))];
}

function withPeople(spec: QuerySpec, ids: string[]): QuerySpec {
  const rest = spec.filters.filter((x) => !(x.field === "assignee" && x.op === "eq"));
  return { ...spec, filters: [{ field: "assignee", op: "eq", values: ids }, ...rest] };
}

/** Dates for work dropped on a day: keep its length when it has one, otherwise make it that one day. */
function datesFor(wp: WorkPackage, day: IsoDate): Omit<WorkPackagePatch, "lockVersion"> {
  if (wp.type.isMilestone) return { startDate: day, dueDate: day };
  if (wp.startDate && wp.dueDate) return wp.startDate === day ? {} : { startDate: day, duration: wp.duration ?? undefined, touched: ["startDate", "duration"] };
  if (wp.startDate) return wp.startDate === day ? {} : { startDate: day };
  if (wp.dueDate) return wp.dueDate === day ? {} : { dueDate: day };
  return { startDate: day, dueDate: day };
}

/* ---------- side panel: bring existing work into the planner ---------- */

function PanelItem({ wp, people, onAssign }: { wp: WorkPackage; people: UserRef[]; onAssign: (wp: WorkPackage, person: UserRef) => void }) {
  const { t } = useTranslation();
  // Pointer drag only: keyboard users pick a person from the menu instead.
  const { listeners, setNodeRef, isDragging } = useDraggable({ id: `new|${wp.number}`, data: { wp }, disabled: !wp._meta.allowedActions.edit });
  return (
    <li ref={setNodeRef} onPointerDown={listeners?.onPointerDown as PointerEventHandler<HTMLLIElement> | undefined} className={cn("flex items-start gap-2 rounded-md border border-border bg-bg p-2 shadow-card", wp._meta.allowedActions.edit && "cursor-grab touch-none", isDragging && "opacity-40")}>
      <div className="min-w-0 flex-1 text-sm">
        <div className="flex items-center gap-1.5 text-xs">
          <span className="text-fg-muted tabular-nums">#{wp.number}</span>
          <TypeLabel name={wp.type.name} color={wp.type.color} />
        </div>
        <p className="truncate text-fg" title={wp.subject}>
          {wp.subject}
        </p>
        <p className="truncate text-xs text-fg-muted">
          {wp.assignee?.name ?? t("wp.unassigned")} · {formatDateRange(wp.startDate, wp.dueDate) || t("wp.noDates")}
        </p>
      </div>
      {wp._meta.allowedActions.edit && people.length > 0 && (
        <Menu
          align="end"
          trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("planner.assignMenu", { number: wp.number })} icon={<MoreHorizontal />} onPointerDown={(e) => e.stopPropagation()} />}
          items={people.map((p) => ({ label: t("planner.assignTo", { name: p.name }), onSelect: () => onAssign(wp, p) }))}
        />
      )}
    </li>
  );
}

function AddExistingPanel({ projectId, people, onAssign, onClose }: { projectId: string; people: UserRef[]; onAssign: (wp: WorkPackage, person: UserRef) => void; onClose: () => void }) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const spec: QuerySpec = { v: 1, filters: [{ field: "status", op: "open" }], columns: ["number", "subject"], sort: [{ field: "updatedAt", dir: "desc" }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false, text: q.trim() || undefined };
  const found = useWpQuery(spec, projectId, 1, 30);
  return (
    <aside aria-labelledby="planner-add-title" className="flex max-h-[70vh] flex-col rounded-md border border-border bg-surface lg:sticky lg:top-[calc(var(--pms-layout-header-h)+16px)] lg:w-80 lg:shrink-0">
      <div className="flex items-center justify-between gap-2 border-b border-border-muted px-3 py-2">
        <h2 id="planner-add-title" className="text-sm font-semibold text-fg">
          {t("planner.addExistingTitle")}
        </h2>
        <Button variant="ghost" size="sm" iconOnly aria-label={t("app.close")} icon={<X />} onClick={onClose} />
      </div>
      <div className="flex flex-col gap-2 p-3">
        <label className="block">
          <span className="sr-only">{t("planner.search")}</span>
          <Input data-autofocus autoFocus type="search" size="sm" placeholder={t("planner.search")} value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        <p className="text-xs text-fg-muted">{t("planner.addExistingHint")}</p>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-3">
        {found.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : found.isError ? (
          <ErrorState error={found.error} onRetry={() => found.refetch()} />
        ) : found.data.rows.length === 0 ? (
          <p className="py-4 text-center text-sm text-fg-muted">{t("planner.noResults")}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {found.data.rows.map(({ wp }) => (
              <PanelItem key={wp.id} wp={wp} people={people} onAssign={onAssign} />
            ))}
          </ul>
        )}
      </div>
    </aside>
  );
}

function UnassignZone() {
  const { t } = useTranslation();
  const { setNodeRef, isOver } = useDroppable({ id: "remove" });
  return (
    <div
      ref={setNodeRef}
      className={cn(
        "sticky bottom-4 z-30 mt-3 flex h-14 items-center justify-center gap-2 rounded-md border-2 border-dashed text-sm font-medium",
        isOver ? "border-danger bg-danger-subtle text-danger" : "border-border bg-bg text-fg-muted",
      )}
    >
      <UserX className="size-4" aria-hidden="true" />
      {t("planner.unassignZone")}
    </div>
  );
}

/* ---------- the page ---------- */

export function TeamPlannerPage() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const me = useMe().data;
  const st = usePlanningView(project.id, "team_planner", id);
  const { update } = useSavedQueryMutations(project.id);
  const { save } = useSaveWorkPackage();
  const [adding, setAdding] = useState(false);
  const [active, setActive] = useState<{ wp: WorkPackage; width?: number; fromPanel: boolean } | null>(null);
  const span = st.params.get("span") === "1" ? 1 : 2;
  const days = span * 7;
  const from = startOfWeek(st.date);
  const to = addDays(from, days - 1);
  const today = todayIso();
  const base = `/projects/${project.identifier}/team-planner/${id}`;
  const panel = useMatch(`${base}/*`);
  const panelOpen = !!panel && panel.pathname !== base;
  useDocumentTitle(st.view ? `${t("nav.teamPlanner")} · ${st.view.name}` : t("nav.teamPlanner"));

  const users = useFilterOptions(project.id).data?.users;
  const ids = peopleIds(st.spec, me?.id);
  const people = useMemo(() => ids.map((uid) => users?.find((u) => u.id === uid)).filter((u): u is UserRef => !!u), [ids.join(","), users]); // eslint-disable-line react-hooks/exhaustive-deps
  const querySpec = st.spec && ids.length ? { ...st.spec, hierarchy: false, filters: [...st.spec.filters, overlapFilter(from, to)] } : undefined;
  const result = useWpQuery(querySpec, project.id, 1, 500);
  const cal = useNonWorkingDays(from, to).data;
  const holidays = useMemo(() => new Map((cal?.days ?? []).map((d) => [d.date, d.name])), [cal]);
  const items = (result.data?.rows ?? []).filter((r) => !r.context).map((r) => r.wp);

  const canEditPeople = !!st.view?.canEdit;
  const canCreate = can(project, "wp.create");
  const setPeople = (next: string[]) => {
    if (!st.view || !st.spec) return;
    const spec = withPeople(st.spec, next);
    if (st.view.canEdit && !st.dirty) update.mutate({ id: st.view.id, patch: { spec } });
    else st.setSpec(spec);
  };

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    // Space lifts a bar; Enter stays "open the details" (the bar's click).
    useSensor(KeyboardSensor, { coordinateGetter: plannerKeyboard, keyboardCodes: { start: ["Space"], cancel: ["Escape"], end: ["Space", "Enter"] } }),
  );
  const nameOf = (userId: string) => people.find((p) => p.id === userId)?.name ?? "";

  /**
   * A bar moved to a person's row. 공동 담당자: from another person's row it hands that person's place over (the others
   * stay); from the side panel the person becomes the main assignee (someone already on it just moves to the front).
   */
  const assign = async (wp: WorkPackage, userId: string, day: IsoDate | null, fromUserId?: string) => {
    const patch: Omit<WorkPackagePatch, "lockVersion"> = {};
    const team = wp.assignees.map((a) => ({ userId: a.user.id, workHours: a.workHours }));
    if (fromUserId && fromUserId !== userId && team.some((e) => e.userId === fromUserId)) {
      patch.assignees = team.filter((e) => e.userId !== userId).map((e) => (e.userId === fromUserId ? { userId, workHours: e.workHours } : e));
    } else if (!fromUserId && wp.assignee?.id !== userId) patch.assigneeId = userId;
    if (day && (canMoveDates(wp) || (!wp.startDate && !wp.dueDate))) Object.assign(patch, datesFor(wp, day));
    if (!Object.keys(patch).length) return;
    const saved = await save(wp, patch);
    if (saved && (patch.assigneeId || patch.assignees)) toast({ tone: "success", title: t("planner.assigned", { number: wp.number, name: nameOf(userId) }) });
  };
  const onDragStart = (e: DragStartEvent) => {
    const wp = e.active.data.current?.wp as WorkPackage | undefined;
    const fromPanel = String(e.active.id).startsWith("new|");
    if (wp) setActive({ wp, width: fromPanel ? undefined : e.active.rect.current.initial?.width, fromPanel });
  };
  const onDragEnd = async ({ active: a, over }: DragEndEvent) => {
    setActive(null);
    const wp = a.data.current?.wp as WorkPackage | undefined;
    if (!wp || !over) return;
    const from = a.data.current?.from as string | undefined;
    if (over.id === "remove") {
      // off this person's row only; the others keep it
      const rest = wp.assignees.filter((x) => x.user.id !== from).map((x) => ({ userId: x.user.id, workHours: x.workHours }));
      const patch = from && wp.assignees.length > 1 ? { assignees: rest } : { assigneeId: null };
      if (wp.assignee && (await save(wp, patch))) toast({ tone: "success", title: t("planner.unassigned", { number: wp.number }) });
      return;
    }
    const cell = parseCell(over.id);
    if (cell) await assign(wp, cell.userId, cell.date, from);
  };
  /** Menu path from the side panel: keep its dates when it has some, otherwise put it on today (or the first day shown). */
  const assignFromMenu = (wp: WorkPackage, person: UserRef) => void assign(wp, person.id, wp.startDate || wp.dueDate ? null : today >= from && today <= to ? today : from);

  const announce = (overId: string | number | undefined) => {
    if (overId === "remove") return t("planner.announce.remove");
    const cell = overId !== undefined ? parseCell(overId) : null;
    return cell ? t("planner.announce.over", { name: nameOf(cell.userId), date: formatDate(cell.date) }) : t("planner.announce.nowhere");
  };

  if (st.views.isPending) return <Skeleton className="m-8 h-96" />;
  if (st.views.isError) return <ErrorState error={st.views.error} onRetry={() => st.views.refetch()} />;
  if (!st.view)
    return (
      <div className="px-content pt-8 md:px-8">
        <EmptyState icon={<CalendarRange />} title={t("planning.notFound")} />
      </div>
    );
  const view = st.view;
  const candidates = (users ?? []).filter((u) => !ids.includes(u.id));
  const addPersonMenu = (primary: boolean) => (
    <Menu
      width="w-60"
      trigger={
        <Button variant={primary ? "primary" : "secondary"} icon={<UserPlus />} disabled={!canEditPeople && !primary}>
          {t("planner.addPerson")}
        </Button>
      }
      items={candidates.length ? candidates.map((u) => ({ label: u.name, onSelect: () => setPeople([...ids, u.id]) })) : [{ kind: "label", label: t("planner.addPersonEmpty") }]}
    />
  );

  return (
    <div className="flex min-h-full">
      <div className="min-w-0 flex-1 px-content pb-10 md:px-8">
        <PageHeader
          breadcrumbs={[
            { label: project.name, href: `/projects/${project.identifier}` },
            { label: t("nav.teamPlanner"), href: `/projects/${project.identifier}/team-planner` },
            { label: view.name },
          ]}
          title={
            <span className="inline-flex items-center gap-2">
              {view.name}
              {st.dirty && <Badge tone="warning">{t("planning.unsaved")}</Badge>}
            </span>
          }
          actions={
            <>
              {canCreate && (
                <Button variant="primary" icon={<Plus />} onClick={() => navigate(`${base}/new${st.search}`)}>
                  {t("planning.newItem")}
                </Button>
              )}
              <Button icon={<ListPlus />} aria-expanded={adding} onClick={() => setAdding((o) => !o)}>
                {t("planner.addExisting")}
              </Button>
              {st.dirty && <Button onClick={st.reset}>{t("planning.reset")}</Button>}
              <PlanningViewMenu view={view} projectId={project.id} listHref={`/projects/${project.identifier}/team-planner`} />
            </>
          }
        />
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <PeriodNav
            label={t("planning.weekTitle", { from: formatDate(from), to: formatDate(to) })}
            onPrev={() => st.setDate(addDays(from, -7 * span))}
            onToday={() => st.setDate(today)}
            onNext={() => st.setDate(addDays(from, 7 * span))}
          />
          <div className="flex flex-wrap items-center gap-2">
            <Segmented
              aria-label={t("planning.period")}
              value={String(span)}
              onValueChange={(v) => st.setParam("span", v === "1" ? "1" : null)}
              options={[
                { value: "1", label: t("planning.oneWeek") },
                { value: "2", label: t("planning.twoWeeks") },
              ]}
            />
            {canEditPeople && ids.length > 0 && addPersonMenu(false)}
          </div>
        </div>

        <DndContext
          sensors={sensors}
          collisionDetection={plannerCollision}
          onDragStart={onDragStart}
          onDragEnd={(e) => void onDragEnd(e)}
          onDragCancel={() => setActive(null)}
          accessibility={{
            screenReaderInstructions: { draggable: t("planner.dragInstructions") },
            announcements: {
              onDragStart: ({ active: a }) => t("planner.announce.start", { number: String(a.id).split("|")[1] }),
              onDragOver: ({ over }) => announce(over?.id),
              onDragEnd: ({ over }) => {
                if (over?.id === "remove") return t("planner.announce.endRemove");
                const cell = over ? parseCell(over.id) : null;
                return cell ? t("planner.announce.end", { name: nameOf(cell.userId), date: formatDate(cell.date) }) : t("planner.announce.nowhere");
              },
              onDragCancel: () => t("planner.announce.cancel"),
            },
          }}
        >
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start">
            <div className="min-w-0 flex-1">
              {ids.length === 0 ? (
                <div className="rounded-md border border-border">
                  <EmptyState icon={<UserPlus />} title={t("planner.empty")} action={canEditPeople && addPersonMenu(true)}>
                    {t("planner.emptyBody")}
                  </EmptyState>
                </div>
              ) : result.isError ? (
                <ErrorState error={result.error} onRetry={() => result.refetch()} />
              ) : !users || result.isPending ? (
                <Skeleton className="h-72 w-full" />
              ) : (
                <PlannerGrid
                  from={from}
                  days={days}
                  today={today}
                  people={people}
                  items={items}
                  workingDays={cal?.workingDays ?? [1, 2, 3, 4, 5]}
                  holidays={holidays}
                  onOpen={(wp) => navigate(`${base}/details/${wp.number}/overview${st.search}`)}
                  onCreate={
                    canCreate
                      ? (userId, date) => {
                          const p = new URLSearchParams(st.search);
                          p.set("assignee", userId);
                          p.set("startDate", date);
                          p.set("dueDate", date);
                          navigate(`${base}/new?${p.toString()}`);
                        }
                      : undefined
                  }
                  onRemovePerson={canEditPeople ? (u) => setPeople(ids.filter((x) => x !== u.id)) : undefined}
                  onResize={(wp, s, d) => void save(wp, { startDate: s, dueDate: d, touched: ["startDate", "dueDate"] })}
                />
              )}
              {active && !active.fromPanel && active.wp.assignee && <UnassignZone />}
              <p className="mt-2 text-xs text-fg-muted">{t("planner.help")}</p>
            </div>
            {adding && <AddExistingPanel projectId={project.id} people={people} onAssign={assignFromMenu} onClose={() => setAdding(false)} />}
          </div>
          <DragOverlay dropAnimation={null}>{active ? <DraggedBar wp={active.wp} width={active.width} /> : null}</DragOverlay>
        </DndContext>
      </div>
      {panelOpen && (
        <aside
          aria-label={t("wp.detailsPanel")}
          className="fixed inset-x-0 top-header bottom-0 z-(--pms-z-modal) flex flex-col bg-bg md:sticky md:inset-auto md:top-header md:z-auto md:h-[calc(100dvh-var(--pms-layout-header-h))] md:w-split md:shrink-0 md:border-l md:border-border-muted"
        >
          <Outlet />
        </aside>
      )}
    </div>
  );
}
