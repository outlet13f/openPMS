// 백엔드 itsm/SlaClock과 같은 규칙: 24x7은 그대로 더하고, 근무 시간 달력은 요일별 창만 센다(휴무일 제외).
// 가짜 계층은 Asia/Seoul(UTC+9, 서머타임 없음)만 계산한다. 다른 시간대의 달력도 9시간 고정 오프셋으로 센다.
export interface Window {
  /** ISO 1(월)~7(일) */
  day: number;
  start: string;
  end: string;
}
export interface Calendar {
  allDay: boolean;
  windows: Window[];
}

const OFFSET = 9 * 3600_000;
const DAY = 86_400_000;
const MIN = 60_000;
const minutesOf = (hhmm: string) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));
/** 서울 기준 날짜 번호 */
const localDay = (t: number) => Math.floor((t + OFFSET) / DAY);
const isoDay = (dayNo: number) => ((new Date(dayNo * DAY).getUTCDay() + 6) % 7) + 1;
const dateKey = (dayNo: number) => new Date(dayNo * DAY).toISOString().slice(0, 10);
const at = (dayNo: number, minutes: number) => dayNo * DAY - OFFSET + minutes * MIN;
/** About ten years of days: a business calendar always has a window, so this only stops a broken one. */
const MAX_DAYS = 3660;

function windowsOn(cal: Calendar, dayNo: number, holidays: Set<string>): [number, number][] {
  if (holidays.has(dateKey(dayNo))) return [];
  return cal.windows
    .filter((w) => w.day === isoDay(dayNo))
    .map((w) => [at(dayNo, minutesOf(w.start)), at(dayNo, minutesOf(w.end))] as [number, number])
    .sort((a, b) => a[0] - b[0]);
}

/** start (ms) + minutes of calendar time → ms */
export function addMinutes(start: number, minutes: number, cal: Calendar, holidays: Set<string>): number {
  if (minutes < 0) throw new Error("음수 시간은 더할 수 없습니다.");
  if (cal.allDay) return start + minutes * MIN;
  if (cal.windows.length === 0) throw new Error("근무 시간이 없는 달력입니다.");
  let left = minutes * MIN;
  let cursor = start;
  for (let i = 0; i < MAX_DAYS; i++) {
    const day = localDay(cursor);
    for (const [open, close] of windowsOn(cal, day, holidays)) {
      const from = Math.max(cursor, open);
      if (from >= close) continue;
      const room = close - from;
      if (left <= room) return from + left;
      left -= room;
      cursor = close;
    }
    cursor = at(day + 1, 0);
  }
  throw new Error("SLA 기한을 계산할 수 없습니다.");
}

/** Calendar time in [from, to), in ms. */
export function workingMs(from: number, to: number, cal: Calendar, holidays: Set<string>): number {
  if (to <= from) return 0;
  if (cal.allDay) return to - from;
  let total = 0;
  for (let day = localDay(from), i = 0; at(day, 0) < to && i < MAX_DAYS; day++, i++) {
    for (const [open, close] of windowsOn(cal, day, holidays)) {
      const a = Math.max(from, open);
      const b = Math.min(to, close);
      if (a < b) total += b - a;
    }
  }
  return total;
}

/** Same check as SlaClock.Calendar.business: windows of one day may touch (12:00 end, 12:00 start) but not overlap. */
export function overlapping(windows: Window[]): boolean {
  for (let i = 0; i < windows.length; i++)
    for (let j = i + 1; j < windows.length; j++) {
      const a = windows[i];
      const b = windows[j];
      if (a.day === b.day && minutesOf(a.start) < minutesOf(b.end) && minutesOf(b.start) < minutesOf(a.end)) return true;
    }
  return false;
}
