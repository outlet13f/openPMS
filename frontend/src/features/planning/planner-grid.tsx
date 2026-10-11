// Team planner grid (S18, components.md "GanttTimeline" family): people down the side, days across, one bar per
// work package in its assignee's row. Cells are drop targets ("cell|<userId>|<date>"); bars are dnd-kit
// draggables ("wp|<number>"); the side panel adds "new|<number>". Bar ends resize with plain pointer events.
import { useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { useTranslation } from "react-i18next";
import { useDraggable, useDroppable, type CollisionDetection, type KeyboardCoordinateGetter } from "@dnd-kit/core";
import { MoreHorizontal, UserMinus } from "lucide-react";
import type { IsoDate, UserRef, WorkPackage } from "@/api";
import { Avatar } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Menu } from "@/components/ui/overlay";
import { cn } from "@/lib/cn";
import { addDays, diffDays, formatDate, formatDateRange, isoWeekday } from "@/lib/dates";

export const LANE_H = 30;
const BAR_H = 24;
const ROW_PAD = 6;

export const cellId = (userId: string, date: IsoDate) => `cell|${userId}|${date}`;
export const parseCell = (id: string | number) => {
  const [kind, userId, date] = String(id).split("|");
  return kind === "cell" ? { userId, date: date as IsoDate } : null;
};

export function canMoveDates(wp: WorkPackage) {
  return wp._meta.allowedActions.edit && wp._meta.editableFields.includes("dates") && !wp._meta.derived.includes("dates");
}

/**
 * Where a drop lands: under the dragged bar's first day (so a bar keeps its offset from the pointer), or under
 * the pointer for items dragged in from the side panel. The unassign zone wins when the pointer is over it.
 */
export const plannerCollision: CollisionDetection = ({ active, collisionRect, droppableRects, droppableContainers, pointerCoordinates }) => {
  const hit = (id: string | number, x: number, y: number) => {
    const r = droppableRects.get(id);
    return !!r && x >= r.left && x < r.left + r.width && y >= r.top && y < r.top + r.height;
  };
  const containers = droppableContainers.filter((c) => !c.disabled);
  if (pointerCoordinates) {
    const zone = containers.find((c) => c.id === "remove" && hit(c.id, pointerCoordinates.x, pointerCoordinates.y));
    if (zone) return [{ id: zone.id, data: { droppableContainer: zone, value: 0 } }];
  }
  const anyCell = containers.find((c) => parseCell(c.id));
  const dayW = anyCell ? droppableRects.get(anyCell.id)?.width ?? 0 : 0;
  const fromPanel = String(active.id).startsWith("new|");
  const probe =
    fromPanel && pointerCoordinates
      ? pointerCoordinates
      : { x: collisionRect.left + Math.min(dayW, collisionRect.width) / 2, y: pointerCoordinates?.y ?? collisionRect.top + collisionRect.height / 2 };
  const cell = containers.find((c) => parseCell(c.id) && hit(c.id, probe.x, probe.y));
  return cell ? [{ id: cell.id, data: { droppableContainer: cell, value: 0 } }] : [];
};

/** Keyboard dragging: left/right moves a day, up/down moves to the next person. */
export const plannerKeyboard: KeyboardCoordinateGetter = (event, { context: { collisionRect, droppableRects, droppableContainers } }) => {
  if (!collisionRect || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.code)) return undefined;
  event.preventDefault();
  const cells = droppableContainers
    .getEnabled()
    .filter((c) => parseCell(c.id))
    .map((c) => droppableRects.get(c.id))
    .filter((r): r is NonNullable<typeof r> => !!r);
  if (!cells.length) return undefined;
  const dayW = cells[0].width;
  const px = collisionRect.left + Math.min(dayW, collisionRect.width) / 2;
  const py = collisionRect.top + collisionRect.height / 2;
  const current = cells.find((r) => px >= r.left && px < r.left + r.width && py >= r.top && py < r.top + r.height) ?? cells[0];
  let target: (typeof cells)[number] | undefined;
  if (event.code === "ArrowLeft" || event.code === "ArrowRight") {
    const dir = event.code === "ArrowRight" ? 1 : -1;
    target = cells.find((r) => Math.abs(r.top - current.top) < 1 && Math.abs(r.left - (current.left + dir * dayW)) < 2);
  } else {
    const rowTops = [...new Set(cells.map((r) => Math.round(r.top)))].sort((a, b) => a - b);
    const i = rowTops.indexOf(Math.round(current.top));
    const nextTop = rowTops[i + (event.code === "ArrowDown" ? 1 : -1)];
    if (nextTop !== undefined) target = cells.find((r) => Math.round(r.top) === nextTop && Math.abs(r.left - current.left) < 2);
  }
  if (!target) return undefined;
  // keep the bar's small inset within the cell; a new row puts it on the first lane so it stays inside that row
  const vertical = event.code === "ArrowUp" || event.code === "ArrowDown";
  return { x: target.left + (collisionRect.left - current.left), y: vertical ? target.top + ROW_PAD : collisionRect.top };
};

/* ---------- layout ---------- */

type Placed = { wp: WorkPackage; startIdx: number; endIdx: number; lane: number; clippedStart: boolean; clippedEnd: boolean };

/** Bars of one person, packed into as few lanes as possible (earliest first). */
function placeBars(items: WorkPackage[], from: IsoDate, days: number): { bars: Placed[]; lanes: number } {
  const sorted = items
    .map((wp) => {
      const s = (wp.startDate ?? wp.dueDate)!;
      const e = (wp.dueDate ?? wp.startDate)!;
      const a = diffDays(from, s);
      const b = diffDays(from, e);
      return { wp, startIdx: Math.max(0, a), endIdx: Math.min(days - 1, b), clippedStart: a < 0, clippedEnd: b > days - 1 };
    })
    .filter((x) => x.endIdx >= 0 && x.startIdx <= days - 1)
    .sort((x, y) => x.startIdx - y.startIdx || y.endIdx - x.endIdx || x.wp.number - y.wp.number);
  const laneEnds: number[] = [];
  const bars = sorted.map((x) => {
    let lane = laneEnds.findIndex((end) => end < x.startIdx);
    if (lane < 0) lane = laneEnds.length;
    laneEnds[lane] = x.endIdx;
    return { ...x, lane };
  });
  return { bars, lanes: Math.max(1, laneEnds.length) };
}

/* ---------- pieces ---------- */

function Cell({ userId, date, shaded, today, holiday, onCreate }: { userId: string; date: IsoDate; shaded: boolean; today: boolean; holiday?: string; onCreate?: () => void }) {
  const { setNodeRef, isOver } = useDroppable({ id: cellId(userId, date), data: { userId, date } });
  return (
    <div
      ref={setNodeRef}
      data-cell={`${userId}|${date}`}
      title={holiday}
      onClick={onCreate}
      className={cn("border-l border-border-muted", shaded && "bg-surface", today && "bg-accent-subtle/60", isOver && "bg-surface-selected", onCreate && "cursor-cell")}
    />
  );
}

function Bar({
  bar,
  days,
  personId,
  assigneeName,
  onOpen,
  resizePreview,
  onResizeStart,
}: {
  bar: Placed;
  days: number;
  /** the row's person: an item shared by several people (공동 담당자) has a bar in each of their rows */
  personId: string;
  assigneeName: string;
  onOpen: (wp: WorkPackage) => void;
  resizePreview: { startIdx: number; endIdx: number } | null;
  onResizeStart: (e: ReactPointerEvent, wp: WorkPackage, edge: "start" | "end") => void;
}) {
  const { t } = useTranslation();
  const { wp } = bar;
  const movable = canMoveDates(wp);
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `wp|${wp.number}|${personId}`,
    data: { wp, from: personId },
    disabled: !movable,
    attributes: { roleDescription: t("boards.cardRole") },
  });
  const s = resizePreview?.startIdx ?? bar.startIdx;
  const e = resizePreview?.endIdx ?? bar.endIdx;
  const label = t("planner.barLabel", { number: wp.number, subject: wp.subject, assignee: assigneeName, dates: formatDateRange(wp.startDate, wp.dueDate) || t("wp.noDates") });
  const resizable = movable && !wp.type.isMilestone && !!wp.startDate && !!wp.dueDate;
  return (
    <li className="absolute" style={{ left: `${(s / days) * 100}%`, width: `${((e - s + 1) / days) * 100}%`, top: ROW_PAD + bar.lane * LANE_H, height: BAR_H }}>
      <button
        ref={setNodeRef}
        type="button"
        {...attributes}
        {...listeners}
        aria-label={movable ? label : `${label}. ${t("planning.readOnly")}`}
        title={label}
        onClick={() => onOpen(wp)}
        className={cn(
          "group relative mx-0.5 flex h-full w-[calc(100%-4px)] min-w-0 items-center gap-1 overflow-hidden border-l-[3px] bg-surface-selected px-1.5 text-left text-xs text-fg shadow-card hover:bg-surface-hover",
          bar.clippedStart ? "rounded-r-sm" : "rounded-sm",
          bar.clippedEnd && "rounded-r-none",
          movable ? "cursor-grab touch-none" : "cursor-pointer",
          isDragging && "opacity-40",
          resizePreview && "ring-2 ring-focus-ring",
        )}
        style={{ borderLeftColor: wp.type.color }}
      >
        <span className="shrink-0 text-fg-muted tabular-nums">#{wp.number}</span>
        {/* on the bar's grey the plain danger red is 4.48:1; the darker one passes (BUG-005) */}
        <span className={cn("truncate", wp._meta.overdue && "font-semibold text-danger-hover")}>{wp.subject}</span>
        {resizable && (
          <>
            <span
              aria-hidden="true"
              onPointerDown={(ev) => onResizeStart(ev, wp, "start")}
              onClick={(ev) => ev.stopPropagation()}
              className="absolute inset-y-0 left-0 w-2 cursor-ew-resize opacity-0 group-hover:bg-black/10 group-hover:opacity-100"
            />
            <span
              aria-hidden="true"
              onPointerDown={(ev) => onResizeStart(ev, wp, "end")}
              onClick={(ev) => ev.stopPropagation()}
              className="absolute inset-y-0 right-0 w-2 cursor-ew-resize opacity-0 group-hover:bg-black/10 group-hover:opacity-100"
            />
          </>
        )}
      </button>
    </li>
  );
}

/** Rendered inside DragOverlay while a bar or panel item is being dragged. */
export function DraggedBar({ wp, width }: { wp: WorkPackage; width?: number }) {
  return (
    <div
      className="flex h-6 min-w-24 items-center gap-1 overflow-hidden rounded-sm border-l-[3px] bg-surface-selected px-1.5 text-xs text-fg shadow-drag"
      style={{ borderLeftColor: wp.type.color, width }}
    >
      <span className="shrink-0 text-fg-muted tabular-nums">#{wp.number}</span>
      <span className="truncate">{wp.subject}</span>
    </div>
  );
}

/* ---------- the grid ---------- */

export function PlannerGrid({
  from,
  days,
  today,
  people,
  items,
  workingDays,
  holidays,
  onOpen,
  onCreate,
  onRemovePerson,
  onResize,
}: {
  from: IsoDate;
  days: number;
  today: IsoDate;
  people: UserRef[];
  items: WorkPackage[];
  workingDays: number[];
  holidays: Map<IsoDate, string>;
  onOpen: (wp: WorkPackage) => void;
  /** Click on an empty cell (pointer only; the toolbar button is the keyboard path). */
  onCreate?: (userId: string, date: IsoDate) => void;
  onRemovePerson?: (user: UserRef) => void;
  onResize: (wp: WorkPackage, start: IsoDate, due: IsoDate) => void;
}) {
  const { t } = useTranslation();
  const dates = useMemo(() => Array.from({ length: days }, (_, i) => addDays(from, i)), [from, days]);
  const bodyRef = useRef<HTMLDivElement>(null);
  const [resize, setResize] = useState<{ number: number; edge: "start" | "end"; x0: number; delta: number } | null>(null);
  const shaded = (d: IsoDate) => !workingDays.includes(isoWeekday(d)) || holidays.has(d);

  const placed = useMemo(
    // 공동 담당자: an item stands in the row of everyone who has it
    () => new Map(people.map((p) => [p.id, placeBars(items.filter((wp) => (wp.assignees?.length ? wp.assignees.some((a) => a.user.id === p.id) : wp.assignee?.id === p.id)), from, days)])),
    [people, items, from, days],
  );

  const dayWidth = () => (bodyRef.current ? bodyRef.current.getBoundingClientRect().width / days : 1);
  const onResizeStart = (e: ReactPointerEvent, wp: WorkPackage, edge: "start" | "end") => {
    e.stopPropagation(); // keep dnd-kit from starting a move
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    setResize({ number: wp.number, edge, x0: e.clientX, delta: 0 });
  };
  const onResizeMove = (e: ReactPointerEvent) => {
    if (!resize) return;
    const delta = Math.round((e.clientX - resize.x0) / dayWidth());
    if (delta !== resize.delta) setResize({ ...resize, delta });
  };
  const onResizeEnd = () => {
    const r = resize;
    setResize(null);
    if (!r || r.delta === 0) return;
    const wp = items.find((w) => w.number === r.number);
    if (!wp?.startDate || !wp.dueDate) return;
    let s = wp.startDate;
    let d = wp.dueDate;
    if (r.edge === "start") s = addDays(s, r.delta) > d ? d : addDays(s, r.delta);
    else d = addDays(d, r.delta) < s ? s : addDays(d, r.delta);
    onResize(wp, s, d);
  };
  const previewOf = (bar: Placed) => {
    if (!resize || resize.number !== bar.wp.number) return null;
    let s = bar.startIdx;
    let e = bar.endIdx;
    if (resize.edge === "start") s = Math.min(e, Math.max(0, s + resize.delta));
    else e = Math.max(s, Math.min(days - 1, e + resize.delta));
    return { startIdx: s, endIdx: e };
  };

  // narrow enough that two weeks still fit beside the "기존 작업 추가" panel on a laptop screen; phones scroll
  const minDayRem = days > 7 ? 2.25 : 4.5;
  const cols = { gridTemplateColumns: `repeat(${days}, minmax(${minDayRem}rem, 1fr))` };
  // the people column (7.5rem on phones, 12rem from sm) plus the narrowest days; wider screens stretch the days
  const minWidth = { minWidth: `calc(var(--planner-left) + ${days * minDayRem}rem)` };

  return (
    <div role="group" aria-label={t("planner.grid")} className="relative overflow-x-auto rounded-md border border-border" onPointerMove={onResizeMove} onPointerUp={onResizeEnd}>
      <div className="[--planner-left:7.5rem] sm:[--planner-left:12rem]" style={minWidth}>
        {/* day header */}
        <div className="sticky top-0 z-20 flex border-b border-border bg-surface">
          <div className="sticky left-0 z-30 flex w-30 shrink-0 items-end border-r border-border bg-surface px-3 pb-1.5 text-xs font-semibold text-fg-muted sm:w-48">{t("planner.people")}</div>
          <div className="grid flex-1" style={cols}>
            {dates.map((d, i) => {
              const holiday = holidays.get(d);
              // narrow columns: the month only on the first column and on the 1st ("10.4", "5", …, "11.1")
              const day = Number(d.slice(8));
              const dayLabel = i === 0 || day === 1 ? `${Number(d.slice(5, 7))}.${day}` : String(day);
              return (
                <div
                  key={d}
                  className={cn("min-w-0 overflow-hidden border-l border-border-muted px-1 py-1 text-xs", d === today ? "bg-accent-subtle text-accent-subtle-text" : shaded(d) ? "text-fg-muted" : "text-fg")}
                  title={holiday}
                >
                  <div className="truncate font-semibold tabular-nums" aria-label={formatDate(d)}>
                    {dayLabel}
                  </div>
                  <div className={cn("truncate", holiday && "text-danger")}>{holiday ?? t(`planner.weekdays.${isoWeekday(d)}` as "planner.weekdays.1")}</div>
                </div>
              );
            })}
          </div>
        </div>

        {/* one row per person */}
        {people.map((person, rowIndex) => {
          const { bars, lanes } = placed.get(person.id) ?? { bars: [], lanes: 1 };
          return (
            <section key={person.id} aria-label={person.name} className="flex border-b border-border-muted last:border-b-0" style={{ minHeight: lanes * LANE_H + ROW_PAD * 2 }}>
              <div className="sticky left-0 z-10 flex w-30 shrink-0 items-start gap-2 border-r border-border bg-bg px-2 py-1.5 sm:w-48 sm:px-3">
                <Avatar id={person.id} name={person.name} size="sm" className="hidden sm:inline-flex" />
                <span className="min-w-0 flex-1 truncate pt-0.5 text-sm text-fg">{person.name}</span>
                {onRemovePerson && (
                  <Menu
                    align="end"
                    trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("planner.personMenu", { name: person.name })} icon={<MoreHorizontal />} className="-my-1" />}
                    items={[{ label: t("planner.removePerson", { name: person.name }), icon: <UserMinus />, onSelect: () => onRemovePerson(person) }]}
                  />
                )}
              </div>
              <div ref={rowIndex === 0 ? bodyRef : undefined} className="relative flex-1">
                <div className="absolute inset-0 grid" style={cols} aria-hidden="true">
                  {dates.map((d) => (
                    <Cell key={d} userId={person.id} date={d} shaded={shaded(d)} today={d === today} holiday={holidays.get(d)} onCreate={onCreate && (() => onCreate(person.id, d))} />
                  ))}
                </div>
                {bars.length > 0 ? (
                  <ul aria-label={person.name} className="pointer-events-none relative h-full [&>li]:pointer-events-auto">
                    {bars.map((b) => (
                      <Bar key={b.wp.id} bar={b} days={days} personId={person.id} assigneeName={person.name} onOpen={onOpen} resizePreview={previewOf(b)} onResizeStart={onResizeStart} />
                    ))}
                  </ul>
                ) : (
                  <p className="sr-only">{t("planner.noWork")}</p>
                )}
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
}
