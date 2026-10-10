// Progress (architecture.md §3.4 P1–P5, S32 진행률 추적) in the fake data layer.
// backend: wp/Progress.java holds the same rules.
import type { ProgressSettings } from "../types";
import { db, type DbWp } from "./db";

export function progressSettings(): ProgressSettings {
  return { mode: db.settings.progressMode ?? "work", closedSets100: db.settings.closedSets100 ?? false, totals: db.settings.progressTotals ?? "weighted" };
}

const round2 = (n: number) => Math.round(n * 100) / 100;

/** % and remaining work are not typed by hand in status mode, nor on a closed status when closedSets100 is on. */
export function progressLocked(w: Pick<DbWp, "statusId">): boolean {
  const s = progressSettings();
  const status = db.statuses.find((x) => x.id === w.statusId)!;
  return s.mode === "status" || (s.closedSets100 && status.isClosed);
}

/**
 * The % and remaining work an item ends up with (P1–P3). `given` says what the person typed: in work mode a typed
 * remaining work sets the %, a typed % or work sets the remaining work.
 */
export function deriveProgress(
  w: Pick<DbWp, "statusId" | "workHours" | "doneRatio" | "remainingHours">,
  given: { remaining?: boolean; other?: boolean } = {},
): { doneRatio: number | null; remainingHours: number | null } {
  const s = progressSettings();
  const status = db.statuses.find((x) => x.id === w.statusId)!;
  const work = w.workHours;
  if (s.closedSets100 && status.isClosed) return { doneRatio: 100, remainingHours: work === null ? null : 0 };
  if (s.mode === "status") {
    const ratio = status.defaultDoneRatio ?? 0;
    return { doneRatio: ratio, remainingHours: work === null ? null : round2(work * (1 - ratio / 100)) };
  }
  if (given.remaining && work !== null && work > 0 && w.remainingHours !== null)
    return { doneRatio: Math.round(((work - w.remainingHours) / work) * 100), remainingHours: w.remainingHours };
  if (given.other && work !== null) return { doneRatio: w.doneRatio, remainingHours: round2(work * (1 - (w.doneRatio ?? 0) / 100)) };
  return { doneRatio: w.doneRatio, remainingHours: w.remainingHours };
}

/** P4/P5: the item and all its descendants, statuses excluded from totals left out; null without children. */
export function totalsOf(w: DbWp): { workHours: number | null; remainingHours: number | null; doneRatio: number | null } | null {
  if (!db.workPackages.some((x) => x.parentId === w.id)) return null;
  const all: DbWp[] = [w];
  for (let i = 0; i < all.length; i++) for (const k of db.workPackages) if (k.parentId === all[i].id) all.push(k);
  const counted = all.filter((x) => !db.statuses.find((s) => s.id === x.statusId)?.excludedFromTotals);
  const works = counted.filter((x) => x.workHours !== null);
  const work = works.length ? round2(works.reduce((n, x) => n + x.workHours!, 0)) : null;
  const rems = counted.filter((x) => x.remainingHours !== null);
  const remaining = rems.length ? round2(rems.reduce((n, x) => n + x.remainingHours!, 0)) : null;
  let ratio: number | null;
  // no remaining work recorded: nothing done yet (as the work mode would derive it)
  const left = works.reduce((n, x) => n + (x.remainingHours ?? x.workHours!), 0);
  if (progressSettings().totals === "weighted") ratio = work ? Math.round(((work - left) / work) * 100) : null;
  else {
    const rs = counted.filter((x) => x.doneRatio !== null);
    ratio = rs.length ? Math.round(rs.reduce((n, x) => n + x.doneRatio!, 0) / rs.length) : null;
  }
  return { workHours: work, remainingHours: remaining, doneRatio: ratio };
}
