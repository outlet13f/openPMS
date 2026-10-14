// 서비스 데스크 (ITSM 1단계) in the fake layer: the same rules and messages as the server's itsm/ServiceDesk (ticket rows and SLA
// deadlines on save), ServiceDeskConfig(Controller) (calendars, policies, matrix), ServiceTicketController and the
// requester's portal (PortalService, PortalController). SLA time comes from sla-clock.ts (Asia/Seoul only).
import type { Api } from "../api";
import type { CustomValue, Id, IdName, Level, PortalField, PortalFile, PortalSummary, PortalTicket, ServiceCalendar, ServiceDeskSettings, WorkPackage, WorkPackageInput, WorkPackagePatch } from "../types";
import { ApiError } from "../errors";
import { nowIso } from "@/lib/dates";
import { db, newId, type DbJournal, type DbProject, type DbServiceCalendar, type DbServiceTicket, type DbSlaPolicy, type DbUser, type DbWp } from "./db";
import { allowedFor, can, findProject, findWp, requirePerm, sessionUser } from "./permissions";
import { clone, statusRef, ticketOf, toWp, userRef } from "./dto";
import { customValuesOf, enabledIn } from "./custom-fields";
import { addMinutes, overlapping, workingMs, type Calendar } from "./sla-clock";
import { ITSM, TICKET_TYPES } from "./service-desk-seed";
import { fieldError, run } from "./util";

/* ---------- the work package writes index.ts owns (connected there, so this module does not import it) ---------- */

export type Acting = "member" | "portal";
interface Writers {
  /** createWp without the permission check (the portal decided) */
  insert(user: DbUser, project: DbProject, input: WorkPackageInput, acting: Acting): DbWp;
  /** applyPatch without the permission check: the saved row */
  patch(user: DbUser, wp: DbWp, project: DbProject, patch: WorkPackagePatch, acting: Acting): DbWp;
  /** PATCH /work-packages/{n} as a member (wp.edit checked) */
  update(user: DbUser, number: number, patch: WorkPackagePatch): WorkPackage;
  journal(wp: DbWp, actor: DbUser, changes: DbJournal["changes"], notesHtml?: string, cause?: DbJournal["cause"], isInternal?: boolean): DbJournal;
  fanOut(wp: DbWp, journal: DbJournal, actor: DbUser, mentioned?: string[]): void;
}
let writers: Writers | null = null;
export function connectWriters(w: Writers) {
  writers = w;
}
const w = () => {
  if (!writers) throw new Error("service desk writers are not connected");
  return writers;
};

/* ---------- SLA (ServiceDesk) ---------- */

const MIN = 60_000;
const holidays = () => new Set(db.nonWorkingDays.map((d) => d.date));
const iso = (ms: number) => new Date(ms).toISOString();

export const appliesTo = (p: DbProject, typeId: Id) => p.enabledModules.includes("service_desk") && TICKET_TYPES.includes(typeId);

function calendarOf(id: Id): Calendar {
  const c = db.serviceCalendars.find((x) => x.id === id);
  return !c || c.mode === "24x7" ? { allDay: true, windows: [] } : { allDay: false, windows: c.hours };
}

const policyFor = (projectId: Id, typeId: Id, priorityId: Id): DbSlaPolicy | undefined =>
  db.slaPolicies.find((x) => x.projectId === projectId && x.typeId === typeId && x.priorityId === priorityId);

export const matrixPriority = (projectId: Id, impact: number, urgency: number): Id | null =>
  db.priorityMatrix.find((x) => x.projectId === projectId && x.impact === impact && x.urgency === urgency)?.priorityId ?? null;

/** Deadlines from the ticket's start, the current policy and the pauses so far. Breaches stay; a later deadline may warn again. */
function recompute(t: DbServiceTicket, wp: DbWp, now: number) {
  const policy = policyFor(wp.projectId, wp.typeId, wp.priorityId);
  if (!policy) {
    t.slaPolicyId = null;
    t.firstResponseDueAt = null;
    t.resolveDueAt = null;
    return;
  }
  const cal = calendarOf(policy.calendarId);
  const start = Date.parse(t.createdAt);
  const respond = addMinutes(start, policy.firstResponseMinutes, cal, holidays());
  const resolve = addMinutes(start, policy.resolveMinutes + t.pausedTotalSeconds / 60, cal, holidays());
  const same = (a: number, b: string | null) => b !== null && Date.parse(b) === a;
  if (!same(respond, t.firstResponseDueAt) && respond > now) t.responseWarned = false;
  if (!same(resolve, t.resolveDueAt) && resolve > now) t.resolveWarned = false;
  t.slaPolicyId = policy.id;
  t.firstResponseDueAt = iso(respond);
  t.resolveDueAt = iso(resolve);
}

function firstResponse(user: DbUser, t: DbServiceTicket, now: number) {
  if (!t.firstRespondedAt && user.id !== t.requesterId) t.firstRespondedAt = iso(now);
}

function closePause(t: DbServiceTicket, wp: DbWp, now: number) {
  const policy = policyFor(wp.projectId, wp.typeId, wp.priorityId);
  const cal = policy ? calendarOf(policy.calendarId) : { allDay: true, windows: [] };
  t.pausedTotalSeconds += Math.floor(workingMs(Date.parse(t.pausedSince!), now, cal, holidays()) / 1000);
  t.pausedSince = null;
}

export interface Draft {
  requesterId: Id;
  channel: DbServiceTicket["channel"];
  impact: Level;
  urgency: Level;
  groupId: Id | null;
}

function level(v: unknown, key: string): Level {
  if (v === undefined || v === null) return 2;
  const n = typeof v === "number" ? v : Number(v);
  if (!Number.isInteger(n) || n < 1 || n > 3) throw fieldError(key, "1(높음)·2(보통)·3(낮음) 중에서 고르세요.");
  return n as Level;
}

function activeRequester(id: unknown): Id {
  const u = typeof id === "string" ? db.users.find((x) => x.id === id && x.status === "active") : undefined;
  if (!u) throw fieldError("requester", "요청자는 활성 사용자여야 합니다.");
  return u.id;
}

function group(id: unknown): Id | null {
  if (id === undefined || id === null || id === "") return null;
  if (!db.groups.some((g) => g.id === id)) throw fieldError("assignmentGroup", "알 수 없는 그룹입니다.");
  return id as Id;
}

/** Requester, impact, urgency and group of a new ticket. The requester is the caller unless an agent names someone. */
export function ticketDraft(user: DbUser, input: WorkPackageInput, channel: DbServiceTicket["channel"]): Draft {
  const requesterId = activeRequester(channel === "portal" ? user.id : (input.requesterId ?? user.id));
  return { requesterId, channel, impact: level(input.impact, "impact"), urgency: level(input.urgency, "urgency"), groupId: channel === "portal" ? null : group(input.assignmentGroupId) };
}

/** The service_tickets row of a new ticket (called right after the work package row is in). */
export function ticketCreated(wp: DbWp, d: Draft) {
  const t: DbServiceTicket = {
    workPackageId: wp.id, requesterId: d.requesterId, channel: d.channel, impact: d.impact, urgency: d.urgency, assignmentGroupId: d.groupId,
    slaPolicyId: null, firstResponseDueAt: null, firstRespondedAt: null, resolveDueAt: null, resolvedAt: null, pausedSince: null, pausedTotalSeconds: 0,
    responseBreached: false, resolveBreached: false, responseWarned: false, resolveWarned: false,
    requesterSeenAt: null, requesterMailedAt: null, requesterMailedStatusId: null, createdAt: wp.createdAt, updatedAt: wp.createdAt,
  };
  recompute(t, wp, Date.now());
  db.serviceTickets.push(t);
}

/**
 * A work package is about to be saved (after = before + the changes). Throws before anything is stored when a ticket
 * would become an ordinary item or the other way round; otherwise pauses, resolves and recomputes the SLA.
 */
export function ticketUpdated(user: DbUser, before: DbWp, after: DbWp) {
  const p = db.projects.find((x) => x.id === after.projectId)!;
  const was = appliesTo(p, before.typeId);
  const is = appliesTo(p, after.typeId);
  if (was !== is) throw fieldError("type", "티켓 유형(장애·서비스 요청)과 다른 유형 사이는 바꿀 수 없습니다.");
  if (!is) return;
  const t = db.serviceTickets.find((x) => x.workPackageId === after.id);
  if (!t) return; // made before the module was switched on
  const now = Date.now();
  const from = db.statuses.find((s) => s.id === before.statusId)!;
  const to = db.statuses.find((s) => s.id === after.statusId)!;
  const statusChanged = from.id !== to.id;
  if (statusChanged) {
    if (to.slaPause && !t.pausedSince && !to.isClosed) t.pausedSince = iso(now);
    if ((!to.slaPause || to.isClosed) && t.pausedSince) closePause(t, after, now);
    if (to.isClosed && !from.isClosed) t.resolvedAt = iso(now);
    if (!to.isClosed && from.isClosed) t.resolvedAt = null;
    firstResponse(user, t, now);
    // the requester made this change themselves: the requester mail must not announce it back
    if (user.id === t.requesterId) t.requesterMailedStatusId = to.id;
  }
  if (statusChanged || before.priorityId !== after.priorityId || before.typeId !== after.typeId) recompute(t, after, now);
  t.updatedAt = iso(now);
}

/** A public comment by anyone but the requester is the first response. */
export function ticketCommented(user: DbUser, wp: DbWp, internal: boolean) {
  if (internal) return;
  const t = db.serviceTickets.find((x) => x.workPackageId === wp.id);
  if (!t) return;
  firstResponse(user, t, Date.now());
}

/** A service desk shows every requester's tickets to its members: it can never be public. */
export function staysPrivate(isPublic: boolean, modules: readonly string[], field: "isPublic" | "enabledModules") {
  if (isPublic && modules.includes("service_desk")) throw fieldError(field, "서비스 데스크를 쓰는 프로젝트는 공개할 수 없습니다.");
}

/** Tickets stay in their desk (WorkPackageTransfers). */
export function cannotMoveTickets(moving: DbWp[]) {
  if (moving.some((x) => db.serviceTickets.some((t) => t.workPackageId === x.id)))
    throw new ApiError(422, "서비스 데스크 티켓은 다른 프로젝트로 옮길 수 없습니다.", "필요하면 새 작업을 만들고 관계로 이으세요.");
}

/** Custom fields the requester cannot see: their required check is waived on the portal. */
export const hiddenFieldIds = () => new Set(db.customFields.filter((f) => !f.portalVisible).map((f) => f.id));

/* ---------- calendars, policies, matrix (ServiceDeskConfig) ---------- */

const toCalendar = (c: DbServiceCalendar): ServiceCalendar => clone({ id: c.id, name: c.name, mode: c.mode, timeZone: c.timeZone, hours: c.hours });

function requireAdmin(user: DbUser) {
  if (!user.isAdmin) throw new ApiError(403, "관리자만 서비스 달력을 고칠 수 있습니다.");
}

const TIME = /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/;

function checkHours(raw: unknown, business: boolean): ServiceCalendar["hours"] {
  const list = Array.isArray(raw) ? (raw as Record<string, unknown>[]) : [];
  const out: ServiceCalendar["hours"] = [];
  for (const h of list) {
    const day = h?.day;
    const start = h?.start;
    const end = h?.end;
    if (typeof day !== "number" || typeof start !== "string" || typeof end !== "string" || !TIME.test(start) || !TIME.test(end)) throw fieldError("hours", "근무 시간 형식을 확인하세요.");
    const d = Math.trunc(day);
    if (d < 1 || d > 7) throw fieldError("hours", "요일은 1(월)부터 7(일)까지입니다.");
    if (end <= start) throw fieldError("hours", "근무 시간의 끝이 시작보다 늦어야 합니다.");
    out.push({ day: d, start, end });
  }
  if (business && overlapping(out)) throw fieldError("hours", "같은 요일의 근무 시간이 겹칩니다.");
  return list as ServiceCalendar["hours"];
}

function saveCalendar(id: Id | null, input: Partial<Omit<ServiceCalendar, "id">>): ServiceCalendar {
  const existing = id === null ? null : db.serviceCalendars.find((x) => x.id === id);
  if (id !== null && !existing) throw new ApiError(404, "달력을 찾을 수 없습니다.");
  const next = existing ? { ...existing } : ({ id: newId("cal"), name: "", mode: "24x7", timeZone: "Asia/Seoul", hours: [], createdAt: nowIso(), updatedAt: nowIso() } as DbServiceCalendar);
  if (!existing || input.name !== undefined) {
    const name = (input.name ?? "").trim();
    if (!name || name.length > 100) throw fieldError("name", "이름은 1~100자로 입력하세요.");
    if (db.serviceCalendars.some((x) => x.name === name && x.id !== id)) throw fieldError("name", "같은 이름의 달력이 있습니다.");
    next.name = name;
  }
  if (!existing || input.mode !== undefined) {
    if (input.mode !== "24x7" && input.mode !== "business") throw fieldError("mode", "24x7 또는 business만 쓸 수 있습니다.");
    next.mode = input.mode;
  }
  if (!existing || input.timeZone !== undefined) {
    const zone = input.timeZone ?? "Asia/Seoul";
    try {
      new Intl.DateTimeFormat("en", { timeZone: zone });
    } catch {
      throw fieldError("timeZone", "알 수 없는 시간대입니다.");
    }
    next.timeZone = zone;
  }
  if (!existing || input.hours !== undefined) next.hours = clone(checkHours(input.hours, next.mode === "business"));
  if (next.mode === "business" && next.hours.length === 0) throw fieldError("hours", "근무 시간 달력에는 근무 시간이 하나 이상 있어야 합니다.");
  next.updatedAt = nowIso();
  if (existing) Object.assign(existing, next);
  else db.serviceCalendars.push(next);
  return toCalendar(next);
}

/** The desk a person may see the service desk of (service.view), or the same 404 as a missing project. */
function deskFor(user: DbUser, project: string): DbProject {
  const p = findProject(user, project);
  if (!can(user, p, "service.view")) throw new ApiError(404, "프로젝트를 찾을 수 없습니다.");
  return p;
}

function settingsOf(projectId: Id): ServiceDeskSettings {
  const policies = db.slaPolicies
    .filter((x) => x.projectId === projectId)
    .sort((a, b) => a.typeId.localeCompare(b.typeId) || a.priorityId.localeCompare(b.priorityId))
    .map((x) => ({ id: x.id, typeId: x.typeId, priorityId: x.priorityId, calendarId: x.calendarId, firstResponseMinutes: x.firstResponseMinutes, resolveMinutes: x.resolveMinutes }));
  const matrix = db.priorityMatrix
    .filter((x) => x.projectId === projectId)
    .sort((a, b) => a.impact - b.impact || a.urgency - b.urgency)
    .map((x) => ({ impact: x.impact, urgency: x.urgency, priorityId: x.priorityId }));
  return clone({ policies, matrix });
}

const intOf = (v: unknown) => (typeof v === "number" && Number.isInteger(v) ? v : typeof v === "string" && /^-?\d+$/.test(v.trim()) ? Number(v) : null);

/** Replaces both lists, all or nothing. Policies are matched by (type, priority) so tickets keep pointing at a policy that stays. */
function replaceSettings(projectId: Id, input: ServiceDeskSettings): ServiceDeskSettings {
  const policies = Array.isArray(input?.policies) ? input.policies : [];
  const cells = Array.isArray(input?.matrix) ? input.matrix : [];
  const seen = new Set<string>();
  const checked = policies.map((p, i) => {
    const at = `policies[${i}]`;
    if (!TICKET_TYPES.includes(p.typeId)) throw fieldError(`${at}.typeId`, "장애·서비스 요청 유형만 고를 수 있습니다.");
    if (!db.priorities.some((x) => x.id === p.priorityId)) throw fieldError(`${at}.priorityId`, "알 수 없는 우선순위입니다.");
    if (!db.serviceCalendars.some((x) => x.id === p.calendarId)) throw fieldError(`${at}.calendarId`, "알 수 없는 달력입니다.");
    const respond = intOf(p.firstResponseMinutes);
    const resolve = intOf(p.resolveMinutes);
    if (respond === null || respond <= 0) throw fieldError(`${at}.firstResponseMinutes`, "첫 응답 목표는 1분 이상이어야 합니다.");
    if (resolve === null || resolve < respond) throw fieldError(`${at}.resolveMinutes`, "해결 목표는 첫 응답 목표보다 짧을 수 없습니다.");
    if (seen.has(`${p.typeId}/${p.priorityId}`)) throw fieldError(`${at}.priorityId`, "같은 유형·우선순위의 정책이 두 번 있습니다.");
    seen.add(`${p.typeId}/${p.priorityId}`);
    return { typeId: p.typeId, priorityId: p.priorityId, calendarId: p.calendarId, firstResponseMinutes: respond, resolveMinutes: resolve };
  });
  const cellKeys = new Set<string>();
  const matrix = cells.map((c, i) => {
    const at = `matrix[${i}]`;
    const impact = intOf(c.impact);
    const urgency = intOf(c.urgency);
    if (impact === null || impact < 1 || impact > 3) throw fieldError(`${at}.impact`, "영향도는 1~3입니다.");
    if (urgency === null || urgency < 1 || urgency > 3) throw fieldError(`${at}.urgency`, "긴급도는 1~3입니다.");
    if (!db.priorities.some((x) => x.id === c.priorityId)) throw fieldError(`${at}.priorityId`, "알 수 없는 우선순위입니다.");
    if (cellKeys.has(`${impact}/${urgency}`)) throw fieldError(`${at}.urgency`, "같은 칸이 두 번 있습니다.");
    cellKeys.add(`${impact}/${urgency}`);
    return { projectId, impact: impact as Level, urgency: urgency as Level, priorityId: c.priorityId };
  });
  const keep = new Set<Id>();
  for (const p of checked) {
    const existing = policyFor(projectId, p.typeId, p.priorityId);
    if (existing) Object.assign(existing, p);
    else db.slaPolicies.push({ id: newId("sla"), projectId, ...p });
    keep.add(policyFor(projectId, p.typeId, p.priorityId)!.id);
  }
  const gone = new Set(db.slaPolicies.filter((x) => x.projectId === projectId && !keep.has(x.id)).map((x) => x.id));
  db.slaPolicies = db.slaPolicies.filter((x) => !gone.has(x.id));
  db.serviceTickets.forEach((t) => t.slaPolicyId && gone.has(t.slaPolicyId) && (t.slaPolicyId = null)); // on delete set null
  db.priorityMatrix = [...db.priorityMatrix.filter((x) => x.projectId !== projectId), ...matrix];
  return settingsOf(projectId);
}

/** Midnight today in the person's zone, in ms. */
function startOfToday(zone: string) {
  const now = Date.now();
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" })
      .formatToParts(new Date(now))
      .map((p) => [p.type, Number(p.value)]),
  );
  const offset = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second) - Math.floor(now / 1000) * 1000;
  return Date.UTC(parts.year, parts.month - 1, parts.day) - offset;
}

export const serviceDeskApi: Api["serviceDesk"] = {
  calendars: () =>
    run(() => {
      sessionUser();
      return db.serviceCalendars.slice().sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)).map(toCalendar);
    }),
  createCalendar: (input) => run(() => (requireAdmin(sessionUser()), saveCalendar(null, input))),
  updateCalendar: (id, patch) => run(() => (requireAdmin(sessionUser()), saveCalendar(id, patch))),
  deleteCalendar: (id) =>
    run(() => {
      requireAdmin(sessionUser());
      if (db.slaPolicies.some((x) => x.calendarId === id)) throw new ApiError(409, "SLA 정책이 쓰는 달력은 지울 수 없습니다.", "정책에서 다른 달력을 고른 뒤 지우세요.");
      if (!db.serviceCalendars.some((x) => x.id === id)) throw new ApiError(404, "달력을 찾을 수 없습니다.");
      db.serviceCalendars = db.serviceCalendars.filter((x) => x.id !== id);
    }),
  settings: (project) => run(() => settingsOf(deskFor(sessionUser(), project).id)),
  saveSettings: (project, settings) =>
    run(() => {
      const user = sessionUser();
      const p = deskFor(user, project);
      requirePerm(user, p, "service.manage", "서비스 데스크 설정을 바꿀 권한이 없습니다.");
      return replaceSettings(p.id, settings);
    }),
  groups: (project) =>
    run(() => {
      deskFor(sessionUser(), project);
      return db.groups
        .slice()
        .sort((a, b) => a.name.localeCompare(b.name, "ko"))
        .map((g): IdName => ({ id: g.id, name: g.name }));
    }),
  updateTicket: (number, patch) =>
    run(() => {
      const user = sessionUser();
      const { wp, project } = findWp(user, number);
      if (!can(user, project, "service.view")) throw new ApiError(404, "작업을 찾을 수 없습니다.");
      requirePerm(user, project, "service.handle", "티켓을 처리할 권한이 없습니다.");
      const t = db.serviceTickets.find((x) => x.workPackageId === wp.id);
      if (!t) throw new ApiError(404, "서비스 데스크 티켓이 아닙니다.");
      // every value is checked before anything changes
      const requesterId = patch.requesterId !== undefined ? activeRequester(patch.requesterId) : t.requesterId;
      const impact = patch.impact !== undefined ? level(patch.impact, "impact") : t.impact;
      const urgency = patch.urgency !== undefined ? level(patch.urgency, "urgency") : t.urgency;
      const groupId = patch.assignmentGroupId !== undefined ? group(patch.assignmentGroupId) : t.assignmentGroupId;
      const changes: DbJournal["changes"] = {};
      if (requesterId !== t.requesterId) changes.requesterId = [t.requesterId, requesterId];
      if (impact !== t.impact) changes.impact = [t.impact, impact];
      if (urgency !== t.urgency) changes.urgency = [t.urgency, urgency];
      if (groupId !== t.assignmentGroupId) changes.assignmentGroupId = [t.assignmentGroupId, groupId];
      Object.assign(t, { requesterId, impact, urgency, assignmentGroupId: groupId });
      if (Object.keys(changes).length) {
        t.updatedAt = nowIso();
        const j = w().journal(wp, user, changes);
        w().fanOut(wp, j, user);
      }
      if (patch.applyMatrix) {
        const priority = matrixPriority(project.id, t.impact, t.urgency);
        if (priority && priority !== wp.priorityId) return w().update(user, number, { lockVersion: wp.lockVersion, priorityId: priority });
      }
      return toWp(wp, user);
    }),
  summary: (project) =>
    run(() => {
      const user = sessionUser();
      const p = deskFor(user, project);
      const now = Date.now();
      const rows = db.workPackages
        .filter((x) => x.projectId === p.id)
        .map((x) => ({ wp: x, t: ticketOf(x, now) }))
        .filter((r): r is { wp: DbWp; t: DbServiceTicket } => r.t !== null);
      const counts = new Map<Id | null, number>();
      for (const r of rows) if (!db.statuses.find((s) => s.id === r.wp.statusId)?.isClosed) counts.set(r.t.assignmentGroupId, (counts.get(r.t.assignmentGroupId) ?? 0) + 1);
      const openByGroup = [...counts.entries()]
        .sort((a, b) => b[1] - a[1])
        .map(([id, count]) => {
          const g = id ? db.groups.find((x) => x.id === id) : undefined;
          return { group: g ? { id: g.id, name: g.name } : null, count };
        });
      const today = startOfToday(user.timeZone);
      const since = (v: string | null) => v !== null && Date.parse(v) >= today;
      const breachedToday = rows.filter((r) => (r.t.resolveBreached || r.t.responseBreached) && (since(r.t.resolveDueAt) || since(r.t.firstResponseDueAt))).length;
      const answered = rows.filter((r) => r.t.firstRespondedAt && Date.parse(r.t.createdAt) >= now - 30 * 86_400_000);
      const avg = answered.length ? answered.reduce((s, r) => s + (Date.parse(r.t.firstRespondedAt!) - Date.parse(r.t.createdAt)) / MIN, 0) / answered.length : null;
      return { openByGroup, breachedToday, avgFirstResponseMinutes: avg === null ? null : Math.trunc(avg) };
    }),
};

/* ---------- the requester's portal (PortalService, PortalController) ---------- */

const NOT_FOUND = "요청을 찾을 수 없습니다.";
const plain = (html: string) => html.replace(/<[^>]*>/g, " ").replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim();
const live = (p: DbProject | undefined): p is DbProject => !!p && !p.archivedAt && p.enabledModules.includes("service_desk");

function desks(): DbProject[] {
  return db.projects.filter((p) => live(p) && !p.isTemplate).sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

function deskByIdentifier(identifier: string): DbProject {
  const p = desks().find((x) => x.identifier === identifier);
  if (!p) throw new ApiError(404, "서비스 데스크를 찾을 수 없습니다.");
  return p;
}

/** Portal-visible fields on in this desk for at least one ticket type. */
function portalFields(p: DbProject): PortalField[] {
  return db.customFields
    .filter((f) => f.portalVisible && enabledIn(f, p.id) && f.typeIds.some((t) => TICKET_TYPES.includes(t)))
    .sort((a, b) => a.position - b.position)
    .map((f) => ({ id: f.id, name: f.name, format: f.format, required: f.isRequired, multi: f.isMultiValue, options: f.options.map((o) => o.value), typeIds: f.typeIds.filter((t) => TICKET_TYPES.includes(t)) }));
}

/** My ticket by number; anyone else's, or one in an archived desk or a desk without the module, is the same 404. */
function find(user: DbUser, number: number): { wp: DbWp; project: DbProject; t: DbServiceTicket } {
  const wp = db.workPackages.find((x) => x.number === number);
  const t = wp && db.serviceTickets.find((x) => x.workPackageId === wp.id && x.requesterId === user.id);
  const project = wp && db.projects.find((x) => x.id === wp.projectId);
  if (!wp || !t || !live(project)) throw new ApiError(404, NOT_FOUND);
  return { wp, project, t };
}

/** Public news: a comment or a status change by someone else, after the requester last looked. */
function hasNews(t: DbServiceTicket): boolean {
  const seen = Date.parse(t.requesterSeenAt ?? t.createdAt);
  return db.journals.some(
    (j) => j.wpId === t.workPackageId && !j.isInternal && j.userId !== t.requesterId && Date.parse(j.updatedAt) > seen && (!!plain(j.notesHtml) || "statusId" in j.changes),
  );
}

const statusName = (id: unknown) => (typeof id === "string" ? (db.statuses.find((s) => s.id === id)?.name ?? null) : null);

function toTicket(user: DbUser, wp: DbWp, project: DbProject, withActivity: boolean): PortalTicket {
  const status = db.statuses.find((s) => s.id === wp.statusId)!;
  const type = db.types.find((x) => x.id === wp.typeId)!;
  const allowed = allowedFor([ITSM.requesterRole], user.id, wp, user.id).map((id) => db.statuses.find((s) => s.id === id)!).map((s) => ({ id: s.id, name: s.name }));
  const visible = new Set(portalFields(project).map((f) => f.id));
  const fields = Object.entries(customValuesOf(wp))
    .filter(([id]) => visible.has(id))
    .map(([id, v]) => ({ name: db.customFields.find((f) => f.id === id)?.name ?? "", value: v.text }));
  const activity = withActivity
    ? db.journals
        .filter((j) => j.wpId === wp.id && !j.isInternal)
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
        .flatMap((j) => {
          const st = j.changes.statusId;
          const from = st ? statusName(st[0]) : null;
          const to = st ? statusName(st[1]) : null;
          if (!j.notesHtml && to === null) return [];
          return [{ id: j.id, author: userRef(j.userId)!.name, fromMe: j.userId === user.id, notesHtml: j.notesHtml, statusFrom: from, statusTo: to, at: j.createdAt }];
        })
    : [];
  const files: PortalFile[] = db.attachments
    .filter((a) => a.wpId === wp.id)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    .map((a) => ({ id: a.id, fileName: a.fileName, byteSize: a.byteSize, createdAt: a.createdAt }));
  return clone({
    number: wp.number, subject: wp.subject, descriptionHtml: wp.descriptionHtml, desk: project.name, type: { id: type.id, name: type.name },
    status: statusRef(status.id), closed: status.isClosed, createdAt: wp.createdAt, updatedAt: wp.updatedAt, lockVersion: wp.lockVersion,
    allowedStatuses: allowed, fields, activity, files,
  });
}

/** Opening a ticket marks it seen ("새 소식" off). */
function open(user: DbUser, number: number): PortalTicket {
  const { wp, project, t } = find(user, number);
  t.requesterSeenAt = nowIso();
  return toTicket(user, wp, project, true);
}

const FAKE_MAX_BYTES = 1024 * 1024;

export const portalApi: Api["portal"] = {
  desks: () =>
    run(() => {
      sessionUser();
      return desks().map((p) => ({
        identifier: p.identifier,
        name: p.name,
        descriptionText: plain(p.descriptionHtml),
        types: p.typeIds.filter((t) => TICKET_TYPES.includes(t)).map((t) => db.types.find((x) => x.id === t)!).map((t) => ({ id: t.id, name: t.name })),
        fields: portalFields(p),
      }));
    }),
  submit: (identifier, input) =>
    run(() => {
      const user = sessionUser();
      const p = deskByIdentifier(identifier);
      const typeId = input?.typeId;
      if (!typeId || !TICKET_TYPES.includes(typeId) || !p.typeIds.includes(typeId)) throw fieldError("type", "장애 신고나 서비스 요청을 고르세요.");
      // only what the portal offers goes on: no assignee, status, priority, dates or hidden fields
      const allowedFields = new Set(portalFields(p).filter((f) => f.typeIds.includes(typeId)).map((f) => f.id));
      const customValues: Record<Id, CustomValue> = {};
      for (const [k, v] of Object.entries(input.customValues ?? {})) if (allowedFields.has(k)) customValues[k] = v as CustomValue;
      const safe: WorkPackageInput = { typeId, subject: input.subject, descriptionHtml: input.descriptionHtml, customValues };
      if (input.impact !== undefined) safe.impact = input.impact;
      if (input.urgency !== undefined) safe.urgency = input.urgency;
      const wp = w().insert(user, p, safe, "portal");
      return toTicket(user, wp, p, false);
    }),
  tickets: (state) =>
    run(() => {
      const user = sessionUser();
      const closed = new Set(db.statuses.filter((s) => s.isClosed).map((s) => s.id));
      return db.serviceTickets
        .filter((t) => t.requesterId === user.id)
        .map((t) => ({ t, wp: db.workPackages.find((x) => x.id === t.workPackageId)! }))
        .filter(({ wp }) => !!wp && (state === "open" ? !closed.has(wp.statusId) : state === "closed" ? closed.has(wp.statusId) : true))
        .sort((a, b) => b.wp.updatedAt.localeCompare(a.wp.updatedAt))
        .slice(0, 200)
        .filter(({ wp }) => live(db.projects.find((p) => p.id === wp.projectId)))
        .map(({ t, wp }): PortalSummary => ({
          number: wp.number, subject: wp.subject, desk: db.projects.find((p) => p.id === wp.projectId)!.name, type: db.types.find((x) => x.id === wp.typeId)?.name ?? "",
          status: statusRef(wp.statusId), createdAt: wp.createdAt, updatedAt: wp.updatedAt, hasNews: hasNews(t),
        }));
    }),
  ticket: (number) => run(() => open(sessionUser(), number)),
  comment: (number, bodyHtml) =>
    run(() => {
      const user = sessionUser();
      const { wp, project } = find(user, number);
      if (!plain(bodyHtml ?? "")) throw fieldError("comment", "코멘트를 입력하세요.");
      const j = w().journal(wp, user, {}, bodyHtml, null, false);
      w().fanOut(wp, j, user, Array.from(bodyHtml.matchAll(/data-user-id="([^"]+)"/g), (m) => m[1]));
      ticketCommented(user, wp, false);
      // a reply on 답변 대기 moves the ticket back to the agents when the 요청자 workflow allows it
      const status = db.statuses.find((s) => s.id === wp.statusId)!;
      if (status.slaPause && allowedFor([ITSM.requesterRole], user.id, wp, user.id).includes(ITSM.handling))
        w().patch(user, wp, project, { lockVersion: wp.lockVersion, statusId: ITSM.handling }, "portal");
      return open(user, number);
    }),
  setStatus: (number, statusId, lockVersion) =>
    run(() => {
      const user = sessionUser();
      const { wp, project } = find(user, number);
      if (!statusId) throw fieldError("status", "상태를 고르세요.");
      w().patch(user, wp, project, { lockVersion: lockVersion ?? -1, statusId }, "portal");
      return open(user, number);
    }),
  attach: async (number, file) => {
    // the demo keeps bytes in localStorage, so it takes small files only (the real API takes 25MB)
    const dataUrl = file.size > FAKE_MAX_BYTES ? "" : await new Promise<string>((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result));
      r.onerror = () => reject(r.error);
      r.readAsDataURL(file);
    });
    return run(() => {
      const user = sessionUser();
      const { wp } = find(user, number); // someone else's ticket is a 404 before any bytes are kept
      if (file.size > FAKE_MAX_BYTES) throw new ApiError(413, "파일이 너무 큽니다.", "데모에서는 파일 하나를 1MB까지 올릴 수 있습니다.");
      if (!file.size) throw fieldError("file", "빈 파일은 올릴 수 없습니다.");
      const a = { id: newId("att"), wpId: wp.id, fileName: file.name.split(/[\\/]/).pop() || "file", contentType: file.type || "application/octet-stream", byteSize: file.size, authorId: user.id, createdAt: nowIso(), dataUrl };
      db.attachments.push(a);
      const j = w().journal(wp, user, { [`attachment:${a.id}`]: [null, a.fileName] });
      w().fanOut(wp, j, user);
      return { id: a.id, fileName: a.fileName, byteSize: a.byteSize, createdAt: a.createdAt };
    });
  },
  // the demo has no file server: the link is the stored data URL, only for a file of my own ticket
  fileUrl: (number, id) => {
    try {
      const { wp } = find(sessionUser(), number);
      return db.attachments.find((a) => a.id === id && a.wpId === wp.id)?.dataUrl ?? "#";
    } catch {
      return "#";
    }
  },
};
