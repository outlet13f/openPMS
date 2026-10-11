// 시간 기록 in the fake layer: the same rules as the server's time/TimeController (weekly sheet all or nothing, 24 hours a
// person a day, own time with time.log, everyone's with time.view, anyone's changed with time.manage).
import type { Api } from "../api";
import type { Id, IsoDate, TimeActivity, TimeEntry, TimeFilter, TimeGroupBy, TimeReport, TimeWeek } from "../types";
import { ApiError } from "../errors";
import { addDays, isWorkingDay, todayIso } from "@/lib/dates";
import { db, newId, type DbProject, type DbUser } from "./db";
import { can, findWp, requirePerm, sessionUser } from "./permissions";
import { clone, userRef, wpRef } from "./dto";
import { workCalendar } from "./scheduling";
import { fieldError, run } from "./util";

export type { DbTimeEntry } from "./time-seed";
import type { DbTimeEntry } from "./time-seed";

const activities = () => (db.timeActivities ??= []);
const entries = () => (db.timeEntries ??= []);
const DAY_LIMIT = 24;

const sees = (user: DbUser, p: DbProject | undefined, owner: Id) => !!p && (can(user, p, "time.view") || (owner === user.id && can(user, p, "time.log")));
function edits(user: DbUser, p: DbProject | undefined, e: DbTimeEntry) {
  if (!p) return false;
  const wp = db.workPackages.find((w) => w.id === e.wpId);
  return can(user, p, "time.manage") || (e.userId === user.id && can(user, p, "time.log", wp));
}

function toEntry(user: DbUser, e: DbTimeEntry): TimeEntry | null {
  const p = db.projects.find((x) => x.id === e.projectId);
  const wp = db.workPackages.find((w) => w.id === e.wpId);
  if (!wp || !p || !sees(user, p, e.userId)) return null;
  const a = activities().find((x) => x.id === e.activityId);
  return {
    id: e.id, user: userRef(e.userId)!, project: { id: p.id, identifier: p.identifier, name: p.name }, workPackage: wpRef(wp),
    activity: a ? { id: a.id, name: a.name } : null, spentOn: e.spentOn, hours: e.hours, comment: e.comment, canEdit: edits(user, p, e),
  };
}
const toEntries = (user: DbUser, list: DbTimeEntry[]) => list.map((e) => toEntry(user, e)).filter((x): x is TimeEntry => !!x);

function checkHours(h: unknown, zeroAllowed: boolean, field = "hours"): number {
  const n = typeof h === "number" ? h : Number(h);
  if (!Number.isFinite(n) || n < 0 || (!zeroAllowed && n === 0) || n > DAY_LIMIT) throw fieldError(field, zeroAllowed ? "0에서 24 사이로 입력하세요." : "0보다 크고 24 이하로 입력하세요.");
  if (Math.round(n * 100) !== n * 100) throw fieldError(field, "소수 둘째 자리까지 입력하세요.");
  return n;
}
const dayTotal = (userId: Id, day: IsoDate, leaving: Id[] = []) =>
  entries().filter((e) => e.userId === userId && e.spentOn === day && !leaving.includes(e.id)).reduce((s, e) => s + e.hours, 0);
function checkDay(userId: Id, day: IsoDate, adding: number, leaving: Id[] = []) {
  if (dayTotal(userId, day, leaving) + adding > DAY_LIMIT + 1e-9) throw fieldError("hours", "하루에 24시간을 넘게 기록할 수 없습니다.");
}
const defaultActivity = () => activities().find((a) => a.isDefault && a.active)?.id ?? null;
function activeActivity(id: Id | null | undefined) {
  if (!id) return defaultActivity();
  if (!activities().some((a) => a.id === id && a.active)) throw fieldError("activityId", "쓸 수 있는 활동을 고르세요.");
  return id;
}

/** The week containing d, starting where 관리 · 날짜 형식 says (ISO weekday; default Sunday like the server). */
function weekStartOf(d: IsoDate): IsoDate {
  const setting = db.settings.display?.weekStart ?? "default";
  const iso = setting === "default" ? 7 : Number(setting);
  const day = new Date(`${d}T00:00:00Z`).getUTCDay() || 7;
  return addDays(d, -((day - iso + 7) % 7));
}

function range(f?: IsoDate, t?: IsoDate): [IsoDate, IsoDate] {
  let from = f || `${(t || todayIso()).slice(0, 8)}01`;
  let to = t;
  if (!to) {
    const d = new Date(`${from}T00:00:00Z`);
    d.setUTCMonth(d.getUTCMonth() + 1);
    to = addDays(d.toISOString().slice(0, 10), -1);
  }
  if (to < from) throw fieldError("to", "끝 날짜가 시작 날짜보다 빠릅니다.");
  if ((Date.parse(to) - Date.parse(from)) / 86_400_000 >= 366) throw fieldError("to", "한 번에 1년까지만 볼 수 있습니다.");
  from = from.slice(0, 10);
  return [from, to];
}

function filtered(user: DbUser, params: TimeFilter, from: IsoDate, to: IsoDate) {
  const project = params.project ? db.projects.find((p) => p.identifier === params.project || p.id === params.project) : undefined;
  if (params.project && !project) throw new ApiError(404, "프로젝트를 찾을 수 없습니다.");
  return entries().filter(
    (e) =>
      e.spentOn >= from && e.spentOn <= to && (!project || e.projectId === project.id) &&
      (!params.user || params.user === "all" || e.userId === (params.user === "me" ? user.id : params.user)),
  );
}

function week(user: DbUser, start: IsoDate): TimeWeek {
  const end = addDays(start, 6);
  const cal = workCalendar();
  const days = Array.from({ length: 7 }, (_, i) => {
    const date = addDays(start, i);
    return { date, working: isWorkingDay(date, cal), holiday: db.nonWorkingDays.find((n) => n.date === date)?.name ?? null };
  });
  const mine = entries().filter((e) => e.userId === user.id && e.spentOn >= start && e.spentOn <= end);
  const byWp = new Map<Id, DbTimeEntry[]>();
  for (const e of mine) byWp.set(e.wpId, [...(byWp.get(e.wpId) ?? []), e]);
  const rows = [...byWp.entries()].flatMap(([wpId, list]) => {
    const wp = db.workPackages.find((w) => w.id === wpId);
    const p = wp && db.projects.find((x) => x.id === wp.projectId);
    if (!wp || !p) return [];
    const cells = days.map((d) => {
      const here = list.filter((e) => e.spentOn === d.date);
      return { date: d.date, hours: here.reduce((s, e) => s + e.hours, 0), entries: here.length };
    });
    return [{ workPackage: wpRef(wp), canLog: can(user, p, "time.log", wp), cells }];
  });
  rows.sort((a, b) => a.workPackage.project.name.localeCompare(b.workPackage.project.name, "ko") || a.workPackage.number - b.workPackage.number);
  return { start, days, hoursPerDay: db.settings.hoursPerDay, rows };
}

function requireAdmin(user: DbUser) {
  if (!user.isAdmin) throw new ApiError(403, "관리자만 할 수 있습니다.");
}
function activityName(name: string | undefined, self?: Id) {
  const n = (name ?? "").trim();
  if (!n || n.length > 60) throw fieldError("name", "이름을 1~60자로 입력하세요.");
  if (activities().some((a) => a.name === n && a.id !== self)) throw fieldError("name", "같은 이름의 활동이 있습니다.");
  return n;
}
function makeDefault(id: Id) {
  for (const a of activities()) a.isDefault = a.id === id;
}

export const timeApi: Api["time"] = {
  activities: () => run(() => (sessionUser(), clone(activities().filter((a) => a.active).sort((a, b) => a.position - b.position)))),
  list: (params) =>
    run(() => {
      const user = sessionUser();
      let list: DbTimeEntry[];
      if (params.workPackage != null) {
        const { wp } = findWp(user, params.workPackage);
        list = entries().filter((e) => e.wpId === wp.id && (!params.user || params.user === "all" || e.userId === (params.user === "me" ? user.id : params.user)));
      } else {
        const [from, to] = range(params.from, params.to);
        list = filtered(user, params, from, to);
      }
      return toEntries(user, [...list].sort((a, b) => b.spentOn.localeCompare(a.spentOn) || b.createdAt.localeCompare(a.createdAt)));
    }),
  create: (input) =>
    run(() => {
      const user = sessionUser();
      const { wp, project } = findWp(user, input.workPackageNumber);
      const owner = input.userId ?? user.id;
      if (owner === user.id) requirePerm(user, project, "time.log", "이 작업에 시간을 기록할 권한이 없습니다.", wp);
      else requirePerm(user, project, "time.manage", "다른 사람의 시간을 기록할 권한이 없습니다.");
      if (!input.spentOn) throw fieldError("spentOn", "날짜를 입력하세요.");
      const hours = checkHours(input.hours, false);
      checkDay(owner, input.spentOn, hours);
      const comment = (input.comment ?? "").trim();
      if (comment.length > 1000) throw fieldError("comment", "메모는 1000자 이하로 입력하세요.");
      const e: DbTimeEntry = { id: newId("te"), userId: owner, projectId: project.id, wpId: wp.id, activityId: activeActivity(input.activityId), spentOn: input.spentOn, hours, comment, createdAt: new Date().toISOString() };
      entries().push(e);
      return toEntry(user, e)!;
    }),
  update: (id, patch) =>
    run(() => {
      const user = sessionUser();
      const e = entries().find((x) => x.id === id);
      const p = e && db.projects.find((x) => x.id === e.projectId);
      if (!e || !sees(user, p, e.userId)) throw new ApiError(404, "시간 기록을 찾을 수 없습니다.");
      if (!edits(user, p, e)) throw new ApiError(403, "이 시간 기록을 바꿀 권한이 없습니다.");
      const next = { ...e };
      if (patch.spentOn !== undefined) next.spentOn = patch.spentOn;
      if (patch.hours !== undefined) next.hours = checkHours(patch.hours, false);
      if (patch.activityId !== undefined) next.activityId = activeActivity(patch.activityId);
      if (patch.comment !== undefined) next.comment = patch.comment.trim();
      checkDay(e.userId, next.spentOn, next.hours, [e.id]);
      Object.assign(e, next);
      return toEntry(user, e)!;
    }),
  remove: (id) =>
    run(() => {
      const user = sessionUser();
      const e = entries().find((x) => x.id === id);
      const p = e && db.projects.find((x) => x.id === e.projectId);
      if (!e || !sees(user, p, e.userId)) throw new ApiError(404, "시간 기록을 찾을 수 없습니다.");
      if (!edits(user, p, e)) throw new ApiError(403, "이 시간 기록을 바꿀 권한이 없습니다.");
      db.timeEntries = entries().filter((x) => x.id !== id);
    }),
  week: (start) => run(() => week(sessionUser(), weekStartOf(start ?? todayIso()))),
  saveWeek: (start, cells) =>
    run(() => {
      const user = sessionUser();
      const s = weekStartOf(start);
      const end = addDays(s, 6);
      const before = clone(entries());
      const failures: { field: string; message: string }[] = [];
      const touched = new Set<IsoDate>();
      for (const c of cells) {
        const key = `#${c.workPackageNumber} ${c.date}`;
        try {
          if (!c.date || c.date < s || c.date > end) throw fieldError("date", "이 주의 날짜가 아닙니다.");
          const hours = checkHours(c.hours ?? 0, true);
          const { wp, project } = findWp(user, c.workPackageNumber);
          const existing = entries().filter((e) => e.userId === user.id && e.wpId === wp.id && e.spentOn === c.date);
          const now = existing.reduce((sum, e) => sum + e.hours, 0);
          if (Math.abs(now - hours) < 1e-9) continue;
          if (!can(user, project, "time.log", wp)) throw new ApiError(403, "이 작업에 시간을 기록할 권한이 없습니다.");
          if (existing.length > 1) throw fieldError("hours", "여러 건으로 기록된 칸입니다. 작업의 시간 탭에서 고치세요.");
          if (!existing.length)
            entries().push({ id: newId("te"), userId: user.id, projectId: project.id, wpId: wp.id, activityId: defaultActivity(), spentOn: c.date, hours, comment: "", createdAt: new Date().toISOString() });
          else if (hours === 0) db.timeEntries = entries().filter((e) => e.id !== existing[0].id);
          else existing[0].hours = hours;
          touched.add(c.date);
        } catch (e) {
          const err = e as ApiError;
          failures.push({ field: key, message: err.message });
        }
      }
      for (const d of touched) if (dayTotal(user.id, d) > DAY_LIMIT + 1e-9) failures.push({ field: d, message: "하루에 24시간을 넘게 기록할 수 없습니다." });
      if (failures.length) {
        db.timeEntries = before;
        throw new ApiError(422, "저장하지 않았습니다.", `${failures.length}칸에서 문제가 있어 이 주의 변경을 모두 되돌렸습니다.`, { fieldErrors: failures });
      }
      return week(user, s);
    }),
  report: (params) =>
    run(() => {
      const user = sessionUser();
      const [from, to] = range(params.from, params.to);
      const groupBy: TimeGroupBy = params.groupBy;
      const sums = new Map<string, { label: string; hours: number }>();
      let total = 0;
      for (const e of toEntries(user, filtered(user, params, from, to))) {
        const [key, label] =
          groupBy === "workPackage" ? [String(e.workPackage.number), `#${e.workPackage.number} ${e.workPackage.subject}`]
          : groupBy === "activity" ? [e.activity?.id ?? "", e.activity?.name ?? "활동 없음"]
          : groupBy === "project" ? [e.project.id, e.project.name]
          : groupBy === "day" ? [e.spentOn, e.spentOn]
          : [e.user.id, e.user.name];
        const acc = sums.get(key) ?? { label, hours: 0 };
        acc.hours += e.hours;
        sums.set(key, acc);
        total += e.hours;
      }
      const rows = [...sums.entries()].map(([key, v]) => ({ key, label: v.label, hours: v.hours }));
      rows.sort(groupBy === "day" ? (a, b) => a.key.localeCompare(b.key) : (a, b) => b.hours - a.hours || a.label.localeCompare(b.label, "ko"));
      const out: TimeReport = { from, to, groupBy, rows, total };
      return out;
    }),
  exportFile: (params) =>
    run(() => {
      const user = sessionUser();
      const [from, to] = range(params.from, params.to);
      const list = toEntries(user, filtered(user, params, from, to)).sort((a, b) => a.spentOn.localeCompare(b.spentOn));
      // the demo writes CSV (the server writes Excel)
      const esc = (v: string | number) => (typeof v === "number" ? String(v) : `"${v.replace(/"/g, '""')}"`);
      const lines = [["날짜", "사람", "프로젝트", "번호", "작업", "활동", "시간", "메모"], ...list.map((e) => [e.spentOn, e.user.name, e.project.name, e.workPackage.number, e.workPackage.subject, e.activity?.name ?? "", e.hours, e.comment])];
      const blob = new Blob(["﻿" + lines.map((l) => l.map(esc).join(",")).join("\r\n")], { type: "text/csv;charset=utf-8" });
      return { blob, fileName: `공수 ${from}~${to}.csv` };
    }),
};

export const adminTimeActivities = {
  list: () => run(() => (requireAdmin(sessionUser()), clone([...activities()].sort((a, b) => a.position - b.position)))),
  create: (input: { name: string; isDefault?: boolean }) =>
    run(() => {
      requireAdmin(sessionUser());
      const a: TimeActivity = { id: newId("ta"), name: activityName(input.name), position: Math.max(0, ...activities().map((x) => x.position)) + 1, isDefault: false, active: true };
      activities().push(a);
      if (input.isDefault) makeDefault(a.id);
      return clone(a);
    }),
  update: (id: Id, patch: Partial<Pick<TimeActivity, "name" | "position" | "active" | "isDefault">>) =>
    run(() => {
      requireAdmin(sessionUser());
      const a = activities().find((x) => x.id === id);
      if (!a) throw new ApiError(404, "활동을 찾을 수 없습니다.");
      if (patch.name !== undefined) a.name = activityName(patch.name, id);
      if (patch.position !== undefined) a.position = patch.position;
      if (patch.active !== undefined) {
        if (!patch.active && a.isDefault) throw fieldError("active", "기본 활동은 끌 수 없습니다. 다른 활동을 기본으로 정한 뒤 끄세요.");
        a.active = patch.active;
      }
      if (patch.isDefault) {
        if (!a.active) throw fieldError("isDefault", "꺼 둔 활동은 기본으로 정할 수 없습니다.");
        makeDefault(id);
      }
      return clone(a);
    }),
};

/** A moved work package takes its time along (fake moveWp). */
export function followMove(wpId: Id, projectId: Id) {
  for (const e of entries()) if (e.wpId === wpId) e.projectId = projectId;
}
