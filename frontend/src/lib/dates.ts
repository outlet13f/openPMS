// Calendar dates are ISO strings ("2026-10-09") with no time zone (architecture.md §2).
// All arithmetic happens at UTC midnight so daylight saving can never shift a day.

import type { IsoDate, IsoDateTime } from "@/api/types";

export const DEFAULT_TZ = "Asia/Seoul";

const pad = (n: number) => String(n).padStart(2, "0");

export function parseIso(d: IsoDate): Date {
  const [y, m, day] = d.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, day));
}

export function toIso(d: Date): IsoDate {
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

/** Today in the user's time zone. "Today", overdue and date alerts are all per user (architecture.md §5). */
export function todayIso(tz = DEFAULT_TZ, now = new Date()): IsoDate {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
  return parts; // en-CA formats as YYYY-MM-DD
}

export function addDays(d: IsoDate, n: number): IsoDate {
  const x = parseIso(d);
  x.setUTCDate(x.getUTCDate() + n);
  return toIso(x);
}

export function diffDays(a: IsoDate, b: IsoDate): number {
  return Math.round((parseIso(b).getTime() - parseIso(a).getTime()) / 86_400_000);
}

/** ISO weekday: Monday 1 … Sunday 7. */
export function isoWeekday(d: IsoDate): number {
  const w = parseIso(d).getUTCDay();
  return w === 0 ? 7 : w;
}

export interface WorkCalendar {
  workingDays: number[];
  holidays: Set<IsoDate>;
}

export function isWorkingDay(d: IsoDate, cal: WorkCalendar, ignoreNonWorking = false) {
  return ignoreNonWorking || (cal.workingDays.includes(isoWeekday(d)) && !cal.holidays.has(d));
}

/** First working day on or after d. */
export function nextWorkingDay(d: IsoDate, cal: WorkCalendar, ignore = false): IsoDate {
  let x = d;
  for (let i = 0; i < 400 && !isWorkingDay(x, cal, ignore); i++) x = addDays(x, 1);
  return x;
}

/** Working days from start to due, both included (rule S1). */
export function countWorkingDays(start: IsoDate, due: IsoDate, cal: WorkCalendar, ignore = false): number {
  if (due < start) return 0;
  let n = 0;
  for (let x = start; x <= due; x = addDays(x, 1)) if (isWorkingDay(x, cal, ignore)) n++;
  return n;
}

/** The date that makes [start, result] contain `duration` working days. */
export function dueFromDuration(start: IsoDate, duration: number, cal: WorkCalendar, ignore = false): IsoDate {
  let x = nextWorkingDay(start, cal, ignore);
  let left = Math.max(1, duration) - 1;
  while (left > 0) {
    x = addDays(x, 1);
    if (isWorkingDay(x, cal, ignore)) left--;
  }
  return x;
}

export function startFromDuration(due: IsoDate, duration: number, cal: WorkCalendar, ignore = false): IsoDate {
  let x = due;
  while (!isWorkingDay(x, cal, ignore)) x = addDays(x, -1);
  let left = Math.max(1, duration) - 1;
  while (left > 0) {
    x = addDays(x, -1);
    if (isWorkingDay(x, cal, ignore)) left--;
  }
  return x;
}

/** The (n)th working day strictly after d — used for "starts lag+1 working days after the predecessor ends" (rule S5). */
export function nthWorkingDayAfter(d: IsoDate, n: number, cal: WorkCalendar, ignore = false): IsoDate {
  let x = d;
  let left = Math.max(1, n);
  while (left > 0) {
    x = addDays(x, 1);
    if (isWorkingDay(x, cal, ignore)) left--;
  }
  return x;
}

/* ---------- display ---------- */

export function formatDate(d: IsoDate | null | undefined): string {
  if (!d) return "";
  const [y, m, day] = d.split("-");
  return `${y}.${m}.${day}`;
}

export function formatDateRange(start: IsoDate | null, due: IsoDate | null): string {
  if (start && due) return start === due ? formatDate(start) : `${formatDate(start)} – ${formatDate(due)}`;
  return formatDate(start ?? due);
}

const WEEKDAY_KO = ["", "월", "화", "수", "목", "금", "토", "일"];

export function formatDateLong(d: IsoDate): string {
  const [y, m, day] = d.split("-").map(Number);
  return `${y}년 ${m}월 ${day}일 ${WEEKDAY_KO[isoWeekday(d)]}요일`;
}

export function formatDateTime(dt: IsoDateTime, tz = DEFAULT_TZ): string {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  })
    .format(new Date(dt))
    .replace(/\.\s/g, ".")
    .replace(/\.(?=\d{2}:)/, " ");
}

const rtf = new Intl.RelativeTimeFormat("ko", { numeric: "auto" });

export function relativeTime(dt: IsoDateTime, now = Date.now()): string {
  const sec = Math.round((new Date(dt).getTime() - now) / 1000);
  const abs = Math.abs(sec);
  if (abs < 60) return "방금 전";
  if (abs < 3600) return rtf.format(Math.round(sec / 60), "minute");
  if (abs < 86_400) return rtf.format(Math.round(sec / 3600), "hour");
  if (abs < 86_400 * 30) return rtf.format(Math.round(sec / 86_400), "day");
  return formatDateTime(dt).slice(0, 10);
}

export function nowIso(): IsoDateTime {
  return new Date().toISOString();
}
