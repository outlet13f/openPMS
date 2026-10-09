// Gantt timeline (components.md "GanttTimeline"): hierarchy list on the left, bars on the right, sharing rows.
// Drag a bar to move it, drag its ends to resize; Enter on a bar opens the date dialog (keyboard path).
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { QueryResult, WorkPackage } from "@/api";
import { useNonWorkingDays } from "@/api/hooks";
import { TypeLabel } from "@/components/ui/badges";
import { cn } from "@/lib/cn";
import { addDays, diffDays, formatDate, formatDateRange, isoWeekday, todayIso } from "@/lib/dates";
import { useSaveWorkPackage } from "@/features/work-packages/use-save";
import { DateDialog } from "@/features/work-packages/date-dialog";

export type Zoom = "day" | "week" | "month";
const DAY_W: Record<Zoom, number> = { day: 32, week: 14, month: 5 };
const ROW_H = 40;
const HEADER_H = 48;
const BAR_H = 20;

/** The list column is 320px on wide screens and 168px on phones so the timeline stays visible. */
function useLeftWidth() {
  const query = "(min-width: 640px)";
  const [wide, setWide] = useState(() => typeof window === "undefined" || window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setWide(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return wide ? 320 : 168;
}

type Drag = { number: number; mode: "move" | "start" | "end"; x0: number; start: string; due: string; delta: number };

function useRange(rows: QueryResult["rows"]) {
  return useMemo(() => {
    const today = todayIso();
    let min = addDays(today, -14);
    let max = addDays(today, 56);
    for (const { wp } of rows) {
      if (wp.startDate && wp.startDate < min) min = wp.startDate;
      if (wp.dueDate && wp.dueDate > max) max = wp.dueDate;
    }
    const from = addDays(min, -(isoWeekday(min) - 1) - 7); // Monday a week before the first bar
    const to = addDays(max, 21);
    return { from, to, days: diffDays(from, to) + 1, today };
  }, [rows]);
}

export function GanttChart({ result, zoom, detailsHref }: { result: QueryResult; zoom: Zoom; detailsHref: (wp: WorkPackage) => string }) {
  const { t } = useTranslation();
  const dayW = DAY_W[zoom];
  const LEFT_W = useLeftWidth();
  const { from, to, days, today } = useRange(result.rows);
  const cal = useNonWorkingDays(from, to).data;
  const { save } = useSaveWorkPackage();
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [drag, setDrag] = useState<Drag | null>(null);
  const [dialogWp, setDialogWp] = useState<WorkPackage | null>(null);
  const moved = useRef(false);
  const width = days * dayW;
  const x = (d: string) => diffDays(from, d) * dayW;
  // Open with today about a quarter into the visible timeline (and again when the zoom changes).
  const scrollRef = useRef<HTMLDivElement>(null);
  const todayX = diffDays(from, today) * dayW;
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollLeft = Math.max(0, todayX - (el.clientWidth - LEFT_W) * 0.25);
  }, [zoom, todayX, LEFT_W]);

  const rows = useMemo(() => {
    const out: QueryResult["rows"] = [];
    let hideBelow: number | null = null;
    for (const r of result.rows) {
      if (hideBelow !== null && r.depth > hideBelow) continue;
      hideBelow = null;
      out.push(r);
      if (r.hasChildren && collapsed.has(r.wp.id)) hideBelow = r.depth;
    }
    return out;
  }, [result.rows, collapsed]);
  const rowIndex = new Map(rows.map((r, i) => [r.wp.number, i]));

  /* ---------- header scale ---------- */
  const months: { label: string; left: number; width: number }[] = [];
  const ticks: { label: string; left: number; width: number }[] = [];
  const shade: { left: number; width: number; holiday?: string }[] = [];
  const holidays = new Map((cal?.days ?? []).map((d) => [d.date, d.name]));
  const working = cal?.workingDays ?? [1, 2, 3, 4, 5];
  for (let i = 0; i < days; i++) {
    const d = addDays(from, i);
    const [y, m, day] = d.split("-");
    if (i === 0 || day === "01") months.push({ label: `${y}년 ${Number(m)}월`, left: i * dayW, width: 0 });
    if (zoom === "day") ticks.push({ label: String(Number(day)), left: i * dayW, width: dayW });
    else if (isoWeekday(d) === 1) ticks.push({ label: zoom === "week" ? `${Number(m)}.${Number(day)}` : "", left: i * dayW, width: 7 * dayW });
    if (dayW >= 12 && (!working.includes(isoWeekday(d)) || holidays.has(d))) shade.push({ left: i * dayW, width: dayW, holiday: holidays.get(d) });
  }
  months.forEach((mo, i) => (mo.width = (months[i + 1]?.left ?? width) - mo.left));

  /* ---------- drag ---------- */
  const startDrag = (e: ReactPointerEvent, wp: WorkPackage, mode: Drag["mode"]) => {
    if (!wp.startDate || !wp.dueDate) return;
    e.stopPropagation();
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    moved.current = false;
    setDrag({ number: wp.number, mode, x0: e.clientX, start: wp.startDate, due: wp.dueDate, delta: 0 });
  };
  const onMove = (e: ReactPointerEvent) => {
    if (!drag) return;
    const delta = Math.round((e.clientX - drag.x0) / dayW);
    if (delta !== drag.delta) {
      moved.current = true;
      setDrag({ ...drag, delta });
    }
  };
  const preview = (wp: WorkPackage) => {
    if (!drag || drag.number !== wp.number) return { start: wp.startDate, due: wp.dueDate };
    const s = drag.mode === "end" ? drag.start : addDays(drag.start, drag.delta);
    let d = drag.mode === "start" ? drag.due : addDays(drag.due, drag.delta);
    if (d < s) d = s;
    return { start: wp.type.isMilestone ? addDays(drag.start, drag.delta) : s, due: wp.type.isMilestone ? addDays(drag.start, drag.delta) : d };
  };
  const endDrag = async (wp: WorkPackage) => {
    const d = drag;
    setDrag(null);
    if (!d || d.delta === 0) return;
    const s = d.mode === "end" ? d.start : addDays(d.start, d.delta);
    const due = d.mode === "start" ? d.due : addDays(d.due, d.delta);
    if (wp.type.isMilestone) await save(wp, { startDate: s, dueDate: s });
    else if (d.mode === "move") await save(wp, { startDate: s, duration: wp.duration ?? undefined, touched: ["startDate", "duration"] });
    else await save(wp, { startDate: s, dueDate: due < s ? s : due, touched: ["startDate", "dueDate"] });
  };

  /* ---------- dependency lines ---------- */
  const lines = (result.relations ?? [])
    .map((r) => {
      const a = rows.find((x) => x.wp.number === r.fromNumber)?.wp;
      const b = rows.find((x) => x.wp.number === r.toNumber)?.wp;
      if (!a?.dueDate || !b?.startDate) return null;
      const pa = preview(a);
      const pb = preview(b);
      const x1 = x(pa.due!) + dayW;
      const y1 = rowIndex.get(a.number)! * ROW_H + ROW_H / 2;
      const x2 = x(pb.start!);
      const y2 = rowIndex.get(b.number)! * ROW_H + ROW_H / 2;
      const mid = Math.max(x1 + 6, Math.min(x2 - 6, x1 + 12));
      return { key: `${r.fromNumber}-${r.toNumber}`, d: `M${x1},${y1} H${mid} V${y2} H${x2 - 3}`, x2, y2 };
    })
    .filter((l): l is NonNullable<typeof l> => !!l);

  return (
    <div ref={scrollRef} role="group" aria-label={t("gantt.chart")} data-gantt-scroll className="relative overflow-auto rounded-md border border-border" style={{ maxHeight: "calc(100dvh - 220px)" }}>
      <div className="relative" style={{ width: LEFT_W + width, minHeight: HEADER_H + rows.length * ROW_H }}>
        {/* header */}
        <div className="sticky top-0 z-20 flex border-b border-border bg-surface" style={{ height: HEADER_H }}>
          <div className="sticky left-0 z-30 flex items-end border-r border-border bg-surface px-3 pb-2 text-xs font-semibold text-fg-muted" style={{ width: LEFT_W }}>
            {t("wp.columns.subject")}
          </div>
          <div className="relative" style={{ width }} aria-hidden="true">
            {months.map((m) => (
              <div key={m.left} className="absolute top-0 h-6 truncate border-l border-border px-1.5 text-xs leading-6 font-semibold text-fg" style={{ left: m.left, width: m.width }}>
                {m.width > 50 ? m.label : ""}
              </div>
            ))}
            {ticks.map((tk) => (
              <div key={tk.left} className="absolute top-6 h-6 border-l border-border-muted text-center text-[11px] leading-6 text-fg-muted tabular-nums" style={{ left: tk.left, width: tk.width }}>
                {tk.label}
              </div>
            ))}
          </div>
        </div>

        {/* body background: non-working days, today */}
        <div className="pointer-events-none absolute bottom-0" style={{ left: LEFT_W, top: HEADER_H, width }} aria-hidden="true">
          {shade.map((s) => (
            <div key={s.left} className="absolute top-0 bottom-0 bg-surface" style={{ left: s.left, width: s.width }} title={s.holiday} />
          ))}
          {today >= from && today <= to && <div data-gantt-today className="absolute top-0 bottom-0 border-l-2 border-dashed border-danger" style={{ left: x(today) + dayW / 2 }} />}
          <svg className="absolute inset-0 overflow-visible" width={width} height={rows.length * ROW_H}>
            <defs>
              <marker id="gantt-arrow" viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M0,0 L6,3 L0,6 z" className="fill-fg-muted" />
              </marker>
            </defs>
            {lines.map((l) => (
              <path key={l.key} d={l.d} fill="none" strokeWidth={1.5} className="stroke-fg-muted" markerEnd="url(#gantt-arrow)" />
            ))}
          </svg>
        </div>

        {/* rows */}
        <ul aria-label={t("gantt.rows")}>
          {rows.map(({ wp, depth, hasChildren, context }) => {
            const p = preview(wp);
            const derived = wp._meta.derived.includes("dates");
            const editable = wp._meta.editableFields.includes("dates") && !derived;
            const summary = wp.childCount > 0;
            const label = `#${wp.number} ${wp.subject}, ${formatDateRange(p.start, p.due) || t("wp.noDates")}${wp.duration ? `, ${t("wp.daysUnit", { value: wp.duration })}` : ""}`;
            return (
              <li key={wp.id} className={cn("flex border-b border-border-muted", context && "text-fg-muted")} style={{ height: ROW_H }}>
                <div className="sticky left-0 z-10 flex min-w-0 items-center gap-1 border-r border-border bg-bg px-3 text-sm" style={{ width: LEFT_W, paddingLeft: 12 + depth * 16 }}>
                  {hasChildren ? (
                    <button
                      type="button"
                      aria-expanded={!collapsed.has(wp.id)}
                      aria-label={collapsed.has(wp.id) ? t("wp.expand") : t("wp.collapse")}
                      onClick={() => setCollapsed((s) => (s.has(wp.id) ? (s.delete(wp.id), new Set(s)) : new Set(s.add(wp.id))))}
                      className="inline-flex size-5 shrink-0 items-center justify-center rounded-sm text-fg-muted hover:bg-surface-hover"
                    >
                      {collapsed.has(wp.id) ? <ChevronRight className="size-4" /> : <ChevronDown className="size-4" />}
                    </button>
                  ) : (
                    <span className="w-5 shrink-0" aria-hidden="true" />
                  )}
                  <span className="shrink-0 text-xs text-fg-muted tabular-nums">#{wp.number}</span>
                  <TypeLabel name={wp.type.name} color={wp.type.color} className="shrink-0" />
                  <Link to={detailsHref(wp)} className="min-w-0 truncate hover:underline" title={wp.subject}>
                    {wp.subject}
                  </Link>
                </div>
                <div className="relative" style={{ width }} onPointerMove={onMove}>
                  {p.start && p.due && (
                    <>
                      {wp.type.isMilestone ? (
                        <button
                          type="button"
                          aria-label={label}
                          disabled={!wp._meta.allowedActions.edit}
                          onPointerDown={(e) => editable && startDrag(e, wp, "move")}
                          onPointerUp={() => void endDrag(wp)}
                          onClick={(e) => (e.detail === 0 || !moved.current) && setDialogWp(wp)}
                          className={cn("absolute size-3.5 rotate-45 rounded-[2px] shadow-card", editable && "cursor-grab active:cursor-grabbing")}
                          style={{ left: x(p.start) + dayW / 2 - 7, top: ROW_H / 2 - 7, backgroundColor: wp.type.color }}
                        />
                      ) : summary && derived ? (
                        <button
                          type="button"
                          aria-label={`${label}. ${t("wp.derivedDates")}`}
                          onClick={() => setDialogWp(wp)}
                          className="absolute h-2"
                          style={{ left: x(p.start), width: x(p.due) - x(p.start) + dayW, top: ROW_H / 2 - 6, backgroundColor: wp.type.color }}
                        >
                          <span className="absolute -bottom-1.5 left-0 size-1.5" style={{ backgroundColor: wp.type.color, clipPath: "polygon(0 0,100% 0,0 100%)" }} />
                          <span className="absolute right-0 -bottom-1.5 size-1.5" style={{ backgroundColor: wp.type.color, clipPath: "polygon(0 0,100% 0,100% 100%)" }} />
                        </button>
                      ) : (
                        <button
                          type="button"
                          aria-label={label}
                          disabled={!wp._meta.allowedActions.edit}
                          onPointerDown={(e) => editable && startDrag(e, wp, "move")}
                          onPointerUp={() => void endDrag(wp)}
                          onClick={(e) => (e.detail === 0 || !moved.current) && setDialogWp(wp)}
                          className={cn(
                            "group absolute rounded-sm shadow-card disabled:cursor-default",
                            editable && "cursor-grab active:cursor-grabbing",
                            drag?.number === wp.number && "shadow-drag ring-2 ring-focus-ring",
                          )}
                          style={{ left: x(p.start), width: Math.max(dayW, x(p.due) - x(p.start) + dayW), top: (ROW_H - BAR_H) / 2, height: BAR_H, backgroundColor: wp.type.color }}
                        >
                          {editable && (
                            <>
                              <span
                                aria-hidden="true"
                                onPointerDown={(e) => startDrag(e, wp, "start")}
                                className="absolute inset-y-0 left-0 w-2 cursor-ew-resize rounded-l-sm opacity-0 group-hover:bg-black/20 group-hover:opacity-100"
                              />
                              <span
                                aria-hidden="true"
                                onPointerDown={(e) => startDrag(e, wp, "end")}
                                className="absolute inset-y-0 right-0 w-2 cursor-ew-resize rounded-r-sm opacity-0 group-hover:bg-black/20 group-hover:opacity-100"
                              />
                            </>
                          )}
                        </button>
                      )}
                      <span
                        className={cn("pointer-events-none absolute truncate text-xs whitespace-nowrap", wp._meta.overdue ? "font-semibold text-danger" : "text-fg-muted")}
                        style={{ left: x(p.due) + dayW + (wp.type.isMilestone ? 10 : 6), top: (ROW_H - 16) / 2, maxWidth: 260 }}
                        aria-hidden="true"
                      >
                        {drag?.number === wp.number ? formatDateRange(p.start, p.due) : `${formatDate(p.due)} ${wp.subject}`}
                      </span>
                    </>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      </div>
      {dialogWp && <DateDialog wp={dialogWp} open={!!dialogWp} onOpenChange={(o) => !o && setDialogWp(null)} />}
    </div>
  );
}
