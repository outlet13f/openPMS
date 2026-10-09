// Two-month calendar of the date dialog (components.md "DatePicker" › calendar).
// One grid per month; a single roving tab stop moves across both. Arrows move a day / a week,
// PageUp/PageDown a month, Home/End to the week's ends, Enter or Space picks the day.
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { ChevronLeft, ChevronRight } from "lucide-react";
import type { IsoDate } from "@/api";
import { useNonWorkingDays } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/cn";
import { addDays, isoWeekday, todayIso } from "@/lib/dates";
import { startOfWeek, WEEK_START } from "@/features/planning/use-planning-view";

/** First day of the month that is n months after d's month. */
export function monthStart(d: IsoDate, n = 0): IsoDate {
  const [y, m] = d.split("-").map(Number);
  const total = y * 12 + (m - 1) + n;
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, "0")}-01`;
}
const sameMonth = (a: IsoDate, b: IsoDate) => a.slice(0, 7) === b.slice(0, 7);

export function TwoMonthCalendar({
  start,
  due,
  single,
  workingOnly,
  disabled,
  onPick,
}: {
  start: IsoDate | "";
  due: IsoDate | "";
  /** milestones: one date, no range */
  single?: boolean;
  /** "근무일만": non-working days cannot be picked */
  workingOnly: boolean;
  disabled?: boolean;
  onPick: (d: IsoDate) => void;
}) {
  const { t } = useTranslation();
  const today = todayIso();
  const [first, setFirst] = useState(() => monthStart(start || due || today));
  const [focused, setFocused] = useState<IsoDate>(start || due || today);
  const gridRef = useRef<HTMLDivElement>(null);
  const moveFocus = useRef(false);
  const second = monthStart(first, 1);
  const lastDay = addDays(monthStart(first, 2), -1);
  const cal = useNonWorkingDays(first, lastDay).data;
  const holidays = useMemo(() => new Map((cal?.days ?? []).map((d) => [d.date, d.name])), [cal]);
  const working = cal?.workingDays ?? [1, 2, 3, 4, 5];
  const nonWorking = (d: IsoDate) => !working.includes(isoWeekday(d)) || holidays.has(d);
  const blocked = (d: IsoDate) => !!disabled || (workingOnly && nonWorking(d));

  // A date typed into the inputs that falls outside the two months brings them along.
  useEffect(() => {
    const target = start || due;
    if (target && target < first) setFirst(monthStart(target));
    else if (target && target > lastDay) setFirst(monthStart(target, -1));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [start, due]);

  // After a keyboard move, put DOM focus on the new day (it may be in the other month, or a new month).
  useEffect(() => {
    if (!moveFocus.current) return;
    moveFocus.current = false;
    gridRef.current?.querySelector<HTMLElement>(`[data-date="${focused}"]`)?.focus();
  }, [focused, first]);

  const go = (d: IsoDate) => {
    moveFocus.current = true;
    if (d < first) setFirst(monthStart(d));
    else if (d > lastDay) setFirst(monthStart(d, -1));
    setFocused(d);
  };
  const onKey = (e: KeyboardEvent, d: IsoDate) => {
    const dayOfWeek = (isoWeekday(d) % 7) - WEEK_START;
    const moves: Record<string, () => IsoDate> = {
      ArrowLeft: () => addDays(d, -1),
      ArrowRight: () => addDays(d, 1),
      ArrowUp: () => addDays(d, -7),
      ArrowDown: () => addDays(d, 7),
      Home: () => addDays(d, -dayOfWeek),
      End: () => addDays(d, 6 - dayOfWeek),
      PageUp: () => clampToMonth(d, -1),
      PageDown: () => clampToMonth(d, 1),
    };
    if (moves[e.key]) {
      e.preventDefault();
      go(moves[e.key]());
    }
  };

  const lo = start && due && !single ? (start < due ? start : due) : "";
  const hi = start && due && !single ? (start < due ? due : start) : "";
  const tabStop = sameMonth(focused, first) || sameMonth(focused, second) ? focused : first;
  const weekdayNames = Array.from({ length: 7 }, (_, i) => t(`planner.weekdays.${((WEEK_START + i + 6) % 7) + 1}` as "planner.weekdays.1"));

  const month = (m: IsoDate) => {
    const titleId = `cal-${m}`;
    const gridStart = startOfWeek(m);
    const weeks: IsoDate[][] = [];
    for (let d = gridStart; sameMonth(d, m) || d < m; d = addDays(d, 7)) weeks.push(Array.from({ length: 7 }, (_, i) => addDays(d, i)));
    return (
      <div key={m} className="flex flex-col gap-2">
        <h3 id={titleId} className="text-center text-sm font-semibold text-fg">
          {t("planning.monthTitle", { year: m.slice(0, 4), month: Number(m.slice(5, 7)) })}
        </h3>
        <table role="grid" aria-labelledby={titleId} className="border-collapse">
          <thead>
            <tr>
              {weekdayNames.map((w) => (
                <th key={w} scope="col" className="size-8 text-center text-xs font-medium text-fg-muted">
                  {w}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {weeks.map((week) => (
              <tr key={week[0]}>
                {week.map((d) => {
                  if (!sameMonth(d, m)) return <td key={d} role="gridcell" aria-hidden="true" className="size-8" />;
                  const off = nonWorking(d);
                  const holiday = holidays.get(d);
                  const isEdge = d === start || (!single && d === due);
                  const inRange = !!lo && d > lo && d < hi;
                  const label = [
                    t("dates.cellLabel", { year: d.slice(0, 4), month: Number(d.slice(5, 7)), day: Number(d.slice(8)), weekday: t(`admin.calendar.weekdays.${isoWeekday(d)}` as "admin.calendar.weekdays.1") }),
                    holiday ? t("planning.holiday", { name: holiday }) : off ? t("dates.nonWorking") : t("dates.working"),
                    d === today ? t("planning.today") : "",
                  ]
                    .filter(Boolean)
                    .join(", ");
                  return (
                    <td key={d} role="gridcell" aria-selected={isEdge || inRange} className="p-0">
                      <button
                        type="button"
                        data-date={d}
                        tabIndex={d === tabStop ? 0 : -1}
                        aria-label={label}
                        aria-disabled={blocked(d) || undefined}
                        title={holiday}
                        onClick={() => {
                          setFocused(d);
                          if (!blocked(d)) onPick(d);
                        }}
                        onKeyDown={(e) => onKey(e, d)}
                        className={cn(
                          "relative inline-flex size-8 items-center justify-center text-xs tabular-nums",
                          "focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-offset-0 focus-visible:outline-focus-ring",
                          off && "text-fg-disabled",
                          off && workingOnly && "bg-[repeating-linear-gradient(135deg,transparent_0,transparent_4px,var(--pms-color-border-muted)_4px,var(--pms-color-border-muted)_5px)]",
                          inRange && "bg-accent-subtle text-accent-subtle-text",
                          isEdge && "rounded-sm bg-accent font-semibold text-on-accent",
                          !isEdge && !blocked(d) && "rounded-sm hover:bg-surface-hover",
                          d === today && !isEdge && "rounded-sm ring-2 ring-warning-strong ring-inset",
                          blocked(d) ? "cursor-not-allowed" : "cursor-pointer",
                          holiday && !isEdge && "text-danger",
                        )}
                      >
                        {Number(d.slice(8))}
                      </button>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  };

  return (
    <div ref={gridRef} className="rounded-md border border-border-muted p-3">
      <div className="mb-1 flex items-center justify-between">
        <Button size="sm" variant="ghost" iconOnly aria-label={t("dates.prevMonth")} icon={<ChevronLeft />} onClick={() => setFirst(monthStart(first, -1))} />
        <Button size="sm" variant="link" onClick={() => (setFirst(monthStart(today)), setFocused(today))}>
          {t("dates.thisMonth")}
        </Button>
        <Button size="sm" variant="ghost" iconOnly aria-label={t("dates.nextMonth")} icon={<ChevronRight />} onClick={() => setFirst(monthStart(first, 1))} />
      </div>
      <div className="flex flex-col items-center gap-4 sm:flex-row sm:items-start sm:justify-around">
        {month(first)}
        {month(second)}
      </div>
    </div>
  );
}

/** The same day n months away, or the month's last day when it is shorter (31 Jan → 28 Feb). */
function clampToMonth(d: IsoDate, n: number): IsoDate {
  const target = monthStart(d, n);
  const last = addDays(monthStart(d, n + 1), -1);
  const day = `${target.slice(0, 8)}${d.slice(8)}`;
  return day > last || Number.isNaN(Date.parse(day)) ? last : day;
}
