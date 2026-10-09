// Scheduling rules S1–S8 from architecture.md §3.3, in a small form for the fake data layer.
// The real engine lives in the backend's `scheduling` package; this one exists so the screens behave the same.

import {
  countWorkingDays,
  dueFromDuration,
  nextWorkingDay,
  nthWorkingDayAfter,
  startFromDuration,
  type WorkCalendar,
} from "@/lib/dates";
import type { IsoDate } from "../types";
import { db, type DbJournal, type DbWp } from "./db";

export function workCalendar(): WorkCalendar {
  return { workingDays: db.settings.workingDays, holidays: new Set(db.nonWorkingDays.map((d) => d.date)) };
}

const isMilestone = (w: DbWp) => db.types.find((t) => t.id === w.typeId)?.isMilestone ?? false;
const kids = (w: DbWp) => db.workPackages.filter((x) => x.parentId === w.id);

export interface DateInput {
  startDate?: IsoDate | null;
  dueDate?: IsoDate | null;
  duration?: number | null;
  touched?: ("startDate" | "dueDate" | "duration")[];
}

/** S1–S3: given what the user changed, return a consistent start/due/duration triple. */
export function resolveDates(w: Pick<DbWp, "startDate" | "dueDate" | "duration" | "ignoreNonWorkingDays" | "typeId">, input: DateInput) {
  const cal = workCalendar();
  const ignore = w.ignoreNonWorkingDays;
  let start = input.startDate !== undefined ? input.startDate : w.startDate;
  let due = input.dueDate !== undefined ? input.dueDate : w.dueDate;
  let duration = input.duration !== undefined ? input.duration : w.duration;
  const touched = input.touched ?? (Object.keys(input).filter((k) => k !== "touched") as DateInput["touched"]);

  if (db.types.find((t) => t.id === w.typeId)?.isMilestone) {
    const d = start ?? due;
    return { startDate: d, dueDate: d, duration: d ? 1 : null };
  }
  const keepDuration = touched?.includes("duration");
  if (keepDuration && duration && start && !touched?.includes("dueDate")) due = dueFromDuration(start, duration, cal, ignore);
  else if (keepDuration && duration && due && !start) start = startFromDuration(due, duration, cal, ignore);
  if (start && due) {
    if (due < start) due = start;
    duration = countWorkingDays(start, due, cal, ignore) || 1;
  } else if (!keepDuration) duration = null;
  return { startDate: start ?? null, dueDate: due ?? null, duration: duration ?? null };
}

/** All predecessors that constrain w: its own and its ancestors' (rule S5). */
function predecessors(w: DbWp): { pred: DbWp; lag: number }[] {
  const out: { pred: DbWp; lag: number }[] = [];
  let cur: DbWp | undefined = w;
  while (cur) {
    const id: string = cur.id;
    for (const r of db.relations.filter((x) => x.type === "precedes" && x.toId === id)) {
      const pred = db.workPackages.find((x) => x.id === r.fromId);
      if (pred) out.push({ pred, lag: r.lag });
    }
    cur = cur.parentId ? db.workPackages.find((x) => x.id === cur!.parentId) : undefined;
  }
  return out;
}

export interface ScheduleChange {
  wp: DbWp;
  changes: DbJournal["changes"];
  cause: NonNullable<DbJournal["cause"]>;
}

/**
 * S4, S5, S7: after `changed` moved, recompute automatic successors and parents in dependency order.
 * With `dryRun` nothing is written; the returned list is what would change (used by schedule-preview).
 */
export function propagate(changed: DbWp[], opts: { dryRun?: boolean; overrides?: Map<string, Partial<DbWp>> } = {}): ScheduleChange[] {
  const cal = workCalendar();
  const view = (w: DbWp): DbWp => ({ ...w, ...(opts.overrides?.get(w.id) ?? {}) });
  const result = new Map<string, ScheduleChange>();
  const queue = [...changed];
  let guard = 0;

  const record = (w: DbWp, next: Pick<DbWp, "startDate" | "dueDate" | "duration">, cause: ScheduleChange["cause"]) => {
    const cur = view(w);
    if (cur.startDate === next.startDate && cur.dueDate === next.dueDate) return false;
    const entry = result.get(w.id) ?? { wp: w, changes: {}, cause };
    if (cur.startDate !== next.startDate) entry.changes.startDate = [entry.changes.startDate?.[0] ?? cur.startDate, next.startDate];
    if (cur.dueDate !== next.dueDate) entry.changes.dueDate = [entry.changes.dueDate?.[0] ?? cur.dueDate, next.dueDate];
    result.set(w.id, entry);
    const o = opts.overrides ?? new Map();
    opts.overrides = o;
    o.set(w.id, { ...(o.get(w.id) ?? {}), ...next });
    return true;
  };

  while (queue.length && guard++ < 500) {
    const w = view(queue.shift()!);

    // successors in automatic mode follow their latest predecessor
    for (const r of db.relations.filter((x) => x.type === "precedes" && x.fromId === w.id)) {
      const succ = db.workPackages.find((x) => x.id === r.toId);
      if (!succ || succ.scheduleManually || kids(succ).length) continue;
      const s = view(succ);
      let earliest: IsoDate | null = null;
      for (const { pred, lag } of predecessors(s)) {
        const p = view(pred);
        if (!p.dueDate) continue;
        const candidate = nthWorkingDayAfter(p.dueDate, lag + 1, cal, s.ignoreNonWorkingDays);
        if (!earliest || candidate > earliest) earliest = candidate;
      }
      if (!earliest) continue;
      const duration = s.duration ?? (s.startDate && s.dueDate ? countWorkingDays(s.startDate, s.dueDate, cal, s.ignoreNonWorkingDays) : 1);
      const start = nextWorkingDay(earliest, cal, s.ignoreNonWorkingDays);
      const due = isMilestone(s) ? start : dueFromDuration(start, duration, cal, s.ignoreNonWorkingDays);
      if (record(succ, { startDate: start, dueDate: due, duration }, { kind: "predecessor_moved", workPackageNumber: w.number })) queue.push(succ);
    }

    // automatic parents span their children
    if (w.parentId) {
      const parent = db.workPackages.find((x) => x.id === w.parentId);
      if (parent && !parent.scheduleManually) {
        const ks = kids(parent).map(view);
        const starts = ks.map((k) => k.startDate).filter(Boolean) as string[];
        const dues = ks.map((k) => k.dueDate).filter(Boolean) as string[];
        const start = starts.length ? starts.sort()[0] : null;
        const due = dues.length ? dues.sort()[dues.length - 1] : null;
        const duration = start && due ? countWorkingDays(start, due, cal, parent.ignoreNonWorkingDays) : null;
        if (record(parent, { startDate: start, dueDate: due, duration }, { kind: "child_moved", workPackageNumber: w.number })) queue.push(parent);
      }
    }
  }

  const changes = [...result.values()];
  if (!opts.dryRun) {
    for (const c of changes) {
      const next = opts.overrides!.get(c.wp.id)!;
      Object.assign(c.wp, { startDate: next.startDate, dueDate: next.dueDate, duration: next.duration });
    }
  }
  return changes;
}

/** S8: would `fromId precedes toId` (or making parentId the parent of childId) create a cycle? */
export function wouldCycle(fromId: string, toId: string): boolean {
  const ancestors = (id: string) => {
    const out = new Set<string>();
    let cur = db.workPackages.find((x) => x.id === id);
    while (cur?.parentId) {
      out.add(cur.parentId);
      cur = db.workPackages.find((x) => x.id === cur!.parentId);
    }
    return out;
  };
  if (ancestors(toId).has(fromId) || ancestors(fromId).has(toId)) return true;
  // walk successors of toId; reaching fromId means a cycle
  const seen = new Set<string>();
  const stack = [toId];
  while (stack.length) {
    const id = stack.pop()!;
    if (id === fromId) return true;
    if (seen.has(id)) continue;
    seen.add(id);
    for (const r of db.relations) if (r.type === "precedes" && r.fromId === id) stack.push(r.toId);
    for (const k of db.workPackages) if (k.parentId === id) stack.push(k.id);
  }
  return false;
}

/** Apply S4 once for automatic parents after seeding or creating children. */
export function refreshParent(parentId: string | null) {
  const parent = parentId ? db.workPackages.find((x) => x.id === parentId) : null;
  const firstKid = parent ? kids(parent)[0] : null;
  if (parent && firstKid) propagate([firstKid]);
}

/**
 * S11: after working days or holidays change, keep each item's start and duration and recompute its
 * finish date on the new calendar, then let successors and parents follow (architecture.md §3.3).
 * Returns the changes so the caller can write journals with cause "calendar_changed".
 */
export function rescheduleAll(): ScheduleChange[] {
  const cal = workCalendar();
  const moved: ScheduleChange[] = [];
  for (const w of db.workPackages) {
    if (!w.startDate || !w.duration || isMilestone(w) || (!w.scheduleManually && kids(w).length)) continue;
    const start = nextWorkingDay(w.startDate, cal, w.ignoreNonWorkingDays);
    const due = dueFromDuration(start, w.duration, cal, w.ignoreNonWorkingDays);
    if (start === w.startDate && due === w.dueDate) continue;
    const changes: DbJournal["changes"] = {};
    if (start !== w.startDate) changes.startDate = [w.startDate, start];
    if (due !== w.dueDate) changes.dueDate = [w.dueDate, due];
    w.startDate = start;
    w.dueDate = due;
    moved.push({ wp: w, changes, cause: { kind: "calendar_changed" } });
  }
  const follow = propagate(moved.map((m) => m.wp)).map((c) => ({ ...c, cause: { kind: "calendar_changed" as const } }));
  return [...moved, ...follow];
}
