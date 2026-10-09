// S17 캘린더: one saved calendar (queries.view = calendar) as a month or week grid (FullCalendar, MIT parts only).
// Drag an item to move it, drag its ends to change the period, drag across empty days to create one there.
// Keyboard: items are focusable and Enter opens the details panel, where dates can be edited.
import "@/styles/calendar.css";
import { useEffect, useMemo, useRef, useState } from "react";
import { Outlet, useMatch, useNavigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import interactionPlugin, { type EventResizeDoneArg } from "@fullcalendar/interaction";
import koLocale from "@fullcalendar/core/locales/ko";
import type { DateSelectArg, DayCellContentArg, EventClickArg, EventContentArg, EventDropArg, EventInput, EventMountArg } from "@fullcalendar/core";
import { CalendarDays, Filter, Plus, Save } from "lucide-react";
import type { IsoDate, WorkPackage } from "@/api";
import { useFilterOptions, useNonWorkingDays, useSavedQueryMutations, useWpQuery } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Badge, Counter } from "@/components/ui/badges";
import { Segmented } from "@/components/ui/choice";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { addDays, formatDate, formatDateRange, isoWeekday, todayIso, toIso } from "@/lib/dates";
import { ErrorState, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { FilterPanel } from "@/features/work-packages/filter-panel";
import { useSaveWorkPackage } from "@/features/work-packages/use-save";
import { overlapFilter, startOfWeek, usePlanningView, WEEK_START } from "@/features/planning/use-planning-view";
import { PeriodNav, PlanningViewMenu } from "@/features/planning/view-header";

type Mode = "month" | "week";

/** The first of the month n months after d's month. */
function shiftMonth(d: IsoDate, n: number): IsoDate {
  const [y, m] = d.split("-").map(Number);
  const total = y * 12 + (m - 1) + n;
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, "0")}-01`;
}

/** The days the grid shows: whole weeks around the month, or the one week. */
function visibleRange(mode: Mode, anchor: IsoDate) {
  if (mode === "week") {
    const from = startOfWeek(anchor);
    return { from, to: addDays(from, 6) };
  }
  const first = shiftMonth(anchor, 0);
  const last = addDays(shiftMonth(anchor, 1), -1);
  return { from: startOfWeek(first), to: addDays(startOfWeek(last), 6) };
}

/**
 * Content for FullCalendar's cells and events as plain DOM nodes. Returning JSX makes the React connector
 * flushSync from inside a render ("flushSync was called from inside a lifecycle method"). Text goes in through
 * textContent only, so subjects and holiday names can never become markup.
 */
function node(tag: "span", className: string, content: string | HTMLElement[]): HTMLElement {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (typeof content === "string") el.textContent = content;
  else el.append(...content);
  return el;
}

function canMoveDates(wp: WorkPackage) {
  return wp._meta.allowedActions.edit && wp._meta.editableFields.includes("dates") && !wp._meta.derived.includes("dates");
}

export function CalendarPage() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const st = usePlanningView(project.id, "calendar", id);
  const { update } = useSavedQueryMutations(project.id);
  const { save } = useSaveWorkPackage();
  const [filtersOpen, setFiltersOpen] = useState(false);
  const calRef = useRef<FullCalendar>(null);
  const mode: Mode = st.params.get("mode") === "week" ? "week" : "month";
  const range = useMemo(() => visibleRange(mode, st.date), [mode, st.date]);
  const base = `/projects/${project.identifier}/calendar/${id}`;
  const panel = useMatch(`${base}/*`);
  const panelOpen = !!panel && panel.pathname !== base;

  const querySpec = st.spec ? { ...st.spec, hierarchy: false, filters: [...st.spec.filters, overlapFilter(range.from, range.to)] } : undefined;
  const result = useWpQuery(querySpec, project.id, 1, 500);
  const cal = useNonWorkingDays(range.from, range.to).data;
  const options = useFilterOptions(project.id).data;
  useDocumentTitle(st.view ? `${t("nav.calendar")} · ${st.view.name}` : t("nav.calendar"));

  // FullCalendar keeps its own current date and view; follow the URL.
  const fcView = mode === "month" ? "dayGridMonth" : "dayGridWeek";
  useEffect(() => {
    const api = calRef.current?.getApi();
    if (!api) return;
    if (api.view.type !== fcView) api.changeView(fcView, st.date);
    else api.gotoDate(st.date);
  }, [fcView, st.date]);
  // The grid only listens to window resizes; tell it when the split panel opens or closes.
  useEffect(() => {
    calRef.current?.getApi().updateSize();
  }, [panelOpen]);

  const holidays = useMemo(() => new Map((cal?.days ?? []).map((d) => [d.date, d.name])), [cal]);
  const working = cal?.workingDays ?? [1, 2, 3, 4, 5];

  const events: EventInput[] = useMemo(
    () =>
      (result.data?.rows ?? [])
        .filter((r) => !r.context && (r.wp.startDate || r.wp.dueDate))
        .map(({ wp }) => {
          const start = (wp.startDate ?? wp.dueDate)!;
          const end = (wp.dueDate ?? wp.startDate)!;
          const movable = canMoveDates(wp);
          return {
            id: String(wp.number),
            title: wp.subject,
            start,
            end: addDays(end, 1), // all-day events end the day after
            allDay: true,
            editable: movable,
            durationEditable: movable && !wp.type.isMilestone && !!wp.startDate && !!wp.dueDate,
            borderColor: wp.type.color,
            classNames: ["pms-event", ...(wp._meta.overdue ? ["pms-event-overdue"] : [])],
            extendedProps: { wp },
          };
        }),
    [result.data],
  );

  const wpOf = (e: { extendedProps: Record<string, unknown> }) => e.extendedProps.wp as WorkPackage;
  const datesLabel = (wp: WorkPackage) => formatDateRange(wp.startDate, wp.dueDate) || t("wp.noDates");

  const onDrop = async (info: EventDropArg) => {
    const wp = wpOf(info.event);
    const s = info.event.startStr.slice(0, 10);
    const saved =
      wp.type.isMilestone || (wp.startDate && !wp.dueDate) || (!wp.startDate && wp.dueDate)
        ? await save(wp, wp.type.isMilestone ? { startDate: s, dueDate: s } : wp.startDate ? { startDate: s } : { dueDate: s })
        : await save(wp, { startDate: s, duration: wp.duration ?? undefined, touched: ["startDate", "duration"] });
    if (!saved) info.revert();
  };
  const onResize = async (info: EventResizeDoneArg) => {
    const wp = wpOf(info.event);
    const s = info.event.startStr.slice(0, 10);
    const due = addDays(info.event.endStr.slice(0, 10), -1);
    const saved = await save(wp, { startDate: s, dueDate: due < s ? s : due, touched: ["startDate", "dueDate"] });
    if (!saved) info.revert();
  };
  const onSelect = (info: DateSelectArg) => {
    info.view.calendar.unselect();
    const p = new URLSearchParams(st.search);
    p.set("startDate", info.startStr.slice(0, 10));
    p.set("dueDate", addDays(info.endStr.slice(0, 10), -1));
    navigate(`${base}/new?${p.toString()}`);
  };

  const title =
    mode === "month"
      ? t("planning.monthTitle", { year: st.date.slice(0, 4), month: Number(st.date.slice(5, 7)) })
      : t("planning.weekTitle", { from: formatDate(range.from), to: formatDate(range.to) });
  const step = (dir: 1 | -1) => st.setDate(mode === "month" ? shiftMonth(st.date, dir) : addDays(st.date, 7 * dir));
  const filterCount = (st.spec?.filters.length ?? 0) + (st.spec?.text ? 1 : 0);
  const canCreate = can(project, "wp.create");

  if (st.views.isPending) return <Skeleton className="m-8 h-96" />;
  if (st.views.isError) return <ErrorState error={st.views.error} onRetry={() => st.views.refetch()} />;
  if (!st.view)
    return (
      <div className="px-content pt-8 md:px-8">
        <EmptyState icon={<CalendarDays />} title={t("planning.notFound")} />
      </div>
    );
  const view = st.view;

  return (
    <div className="flex min-h-full">
      <div className="min-w-0 flex-1 px-content pb-10 md:px-8">
        <PageHeader
          breadcrumbs={[
            { label: project.name, href: `/projects/${project.identifier}` },
            { label: t("nav.calendar"), href: `/projects/${project.identifier}/calendar` },
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
              <Button aria-expanded={filtersOpen} icon={<Filter />} onClick={() => setFiltersOpen((o) => !o)}>
                {t("wp.filters")} {filterCount > 0 && <Counter>{filterCount}</Counter>}
              </Button>
              {st.dirty && view.canEdit && (
                <Button
                  icon={<Save />}
                  loading={update.isPending}
                  onClick={() =>
                    st.spec &&
                    update.mutate({ id: view.id, patch: { spec: st.spec } }, { onSuccess: () => (st.reset(), toast({ tone: "success", title: t("planning.saved") })) })
                  }
                >
                  {t("planning.save")}
                </Button>
              )}
              {st.dirty && <Button onClick={st.reset}>{t("planning.reset")}</Button>}
              <PlanningViewMenu view={view} projectId={project.id} listHref={`/projects/${project.identifier}/calendar`} />
            </>
          }
        />
        {filtersOpen && st.spec && <FilterPanel spec={st.spec} onSpec={st.setSpec} options={options} />}
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <PeriodNav label={title} onPrev={() => step(-1)} onToday={() => st.setDate(todayIso())} onNext={() => step(1)} />
          <Segmented
            aria-label={t("planning.period")}
            value={mode}
            onValueChange={(v) => st.setParam("mode", v === "week" ? "week" : null)}
            options={[
              { value: "month", label: t("planning.month") },
              { value: "week", label: t("planning.week") },
            ]}
          />
        </div>
        {result.isError ? (
          <ErrorState error={result.error} onRetry={() => result.refetch()} />
        ) : (
          <section aria-label={t("planning.calendarRegion")} aria-busy={result.isPending} className="pms-calendar relative">
            <FullCalendar
              ref={calRef}
              plugins={[dayGridPlugin, interactionPlugin]}
              initialView={fcView}
              initialDate={st.date}
              locale={koLocale}
              timeZone="UTC"
              now={todayIso()}
              firstDay={WEEK_START}
              headerToolbar={false}
              height="auto"
              fixedWeekCount={false}
              dayMaxEventRows={mode === "month" ? 4 : false}
              events={events}
              eventInteractive
              eventResizableFromStart
              selectable={canCreate}
              selectMirror
              eventClick={(info: EventClickArg) => {
                info.jsEvent.preventDefault();
                navigate(`${base}/details/${info.event.id}/overview${st.search}`);
              }}
              eventDrop={(info) => void onDrop(info)}
              eventResize={(info) => void onResize(info)}
              select={onSelect}
              eventContent={(arg: EventContentArg) => {
                const wp = wpOf(arg.event);
                return {
                  domNodes: [
                    node("span", "flex min-w-0 items-baseline gap-1 overflow-hidden px-1 text-xs leading-5", [
                      node("span", "shrink-0 text-fg-muted tabular-nums", `#${wp.number}`),
                      node("span", "truncate", wp.subject),
                    ]),
                  ],
                };
              }}
              eventDidMount={(arg: EventMountArg) => {
                const wp = wpOf(arg.event);
                const label = t("planning.eventLabel", { number: wp.number, subject: wp.subject, dates: datesLabel(wp) });
                arg.el.setAttribute("aria-label", canMoveDates(wp) ? label : `${label}. ${t("planning.readOnly")}`);
                arg.el.title = label;
              }}
              dayHeaderContent={(arg) => {
                // week view: "10.12" over "월" (the default "10. 12. (월)" wraps to three lines on phones)
                const d = toIso(arg.date);
                const weekday = t(`planner.weekdays.${isoWeekday(d)}` as "planner.weekdays.1");
                if (mode === "month") return { domNodes: [node("span", "", weekday)] };
                return { domNodes: [node("span", "block tabular-nums", `${Number(d.slice(5, 7))}.${Number(d.slice(8))}`), node("span", "block font-normal", weekday)] };
              }}
              dayCellClassNames={(arg) => {
                const d = toIso(arg.date);
                return !working.includes(isoWeekday(d)) || holidays.has(d) ? ["pms-nonworking"] : [];
              }}
              dayCellContent={(arg: DayCellContentArg) => {
                const holiday = holidays.get(toIso(arg.date));
                // the day number alone ("9", not "9일"); a holiday's name shows where the cell is wide enough
                const cell = node("span", "flex min-w-0 items-baseline gap-1", [
                  node("span", holiday ? "text-danger" : "", arg.dayNumberText.replace("일", "")),
                  ...(holiday ? [node("span", "pms-holiday hidden sm:inline", holiday)] : []),
                ]);
                if (holiday) cell.title = t("planning.holiday", { name: holiday });
                return { domNodes: [cell] };
              }}
            />
            {result.isPending && <div className="pointer-events-none absolute inset-0 bg-bg/40" aria-hidden="true" />}
          </section>
        )}
        <p className="mt-2 text-xs text-fg-muted">{t("planning.calendarHelp")}</p>
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
