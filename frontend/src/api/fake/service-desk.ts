// 서비스 데스크 (ITSM 1단계) in the fake layer: the same rules and messages as the server's itsm/ServiceDesk (ticket rows and SLA
// deadlines on save), ServiceDeskConfig(Controller) (calendars, policies, matrix), ServiceTicketController and the
// requester's portal (PortalService, PortalController). SLA time comes from sla-clock.ts (Asia/Seoul only).
// ITSM 2단계: the catalog (ServiceCatalog(Controller)), item requests on the portal and the approval routes; the approval
// rules themselves are in approvals.ts.
import type { Api } from "../api";
import type { CatalogCategory, CatalogItem, CatalogItemInput, CustomValue, Id, IdName, Level, PortalCategory, PortalField, PortalFile, PortalSummary, PortalTicket, ServiceCalendar, ServiceCatalog, ServiceDeskSettings, WorkPackage, WorkPackageInput, WorkPackagePatch } from "../types";
import { NOT_ON_PORTAL } from "../types";
import { ApiError } from "../errors";
import { nowIso } from "@/lib/dates";
import { db, newId, type DbCatalogCategory, type DbCatalogItem, type DbJournal, type DbProject, type DbServiceCalendar, type DbServiceTicket, type DbSlaPolicy, type DbUser, type DbWp } from "./db";
import * as approvals from "./approvals";
import { allowedFor, can, findProject, findWp, requirePerm, sessionUser } from "./permissions";
import { clone, statusRef, ticketOf, toWp, userRef } from "./dto";
import { customValuesOf, enabledIn } from "./custom-fields";
import { addMinutes, overlapping, workingMs, type Calendar } from "./sla-clock";
import { ITSM, TICKET_TYPES } from "./service-desk-seed";
import { fieldError, run } from "./util";

/* ---------- the work package writes index.ts owns (connected there, so this module does not import it) ---------- */

/** member: a project member; portal: a requester on their own ticket; approval: an approver deciding (승인 대기 → 접수 | 반려 only) */
export type Acting = "member" | "portal" | "approval";
interface Writers {
  /** createWp without the permission check (the portal decided); waived: more fields whose required check is waived when empty */
  insert(user: DbUser, project: DbProject, input: WorkPackageInput, acting: Acting, waived?: Set<Id>): DbWp;
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

/** What the approval rules write through (approvals.ts does not import index.ts). */
const approvalWriters = (): approvals.ApprovalWriters => ({
  journal: (wp, actor, changes) => w().journal(wp, actor, changes),
  fanOut: (wp, j, actor) => w().fanOut(wp, j, actor),
  comment: (actor, wp, bodyHtml) => {
    if (!plain(bodyHtml ?? "")) throw fieldError("comment", "코멘트를 입력하세요.");
    const j = w().journal(wp, actor, {}, bodyHtml, null, false);
    w().fanOut(wp, j, actor);
    ticketCommented(actor, wp, false);
  },
  status: (actor, wp, project, statusId) => void w().patch(actor, wp, project, { lockVersion: wp.lockVersion, statusId }, "approval"),
});

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
  // made straight in a closed or SLA-pause status (an agent's choice, or a copy): the clocks start in that state
  const status = db.statuses.find((s) => s.id === wp.statusId);
  if (status?.isClosed) t.resolvedAt = wp.createdAt;
  else if (status?.slaPause) t.pausedSince = wp.createdAt;
  recompute(t, wp, Date.now());
  db.serviceTickets.push(t);
}

/**
 * A copy of a ticket (작업 복사, 프로젝트 복사; backend ServiceDesk.copied): the original's requester, impact, urgency and
 * group, and no receipt mail for it. modulesOf is the target project, or for a project copy the template.
 */
export function ticketCopied(modulesOf: DbProject, originalId: Id, copy: DbWp) {
  if (!appliesTo(modulesOf, copy.typeId)) return;
  const original = db.serviceTickets.find((x) => x.workPackageId === originalId);
  let t = db.serviceTickets.find((x) => x.workPackageId === copy.id);
  if (!t) {
    if (!original) return;
    ticketCreated(copy, { requesterId: original.requesterId, channel: "agent", impact: original.impact, urgency: original.urgency, groupId: original.assignmentGroupId });
    t = db.serviceTickets.find((x) => x.workPackageId === copy.id)!;
  } else if (original) {
    Object.assign(t, { requesterId: original.requesterId, impact: original.impact, urgency: original.urgency, assignmentGroupId: original.assignmentGroupId });
  }
  Object.assign(t, { requesterMailedAt: nowIso(), requesterMailedStatusId: copy.statusId });
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
    // leaving 승인 대기 is the approvers' decision (or the end of the request), not an answer to the requester
    if (from.id !== ITSM.awaitingApproval) firstResponse(user, t, now);
    // out of 승인 대기 other than by the last approval (cancelled, an administrator's change): the open approvals are skipped
    if (from.id === ITSM.awaitingApproval) approvals.skipRemaining(approvalWriters(), user, before);
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

/** Custom fields the requester cannot see (not marked for the portal, or user / version fields, never on the portal form): their required check is waived on the portal. */
export const hiddenFieldIds = () => new Set(db.customFields.filter((f) => !f.portalVisible || NOT_ON_PORTAL.includes(f.format)).map((f) => f.id));

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
      // a new requester starts fresh: a receipt mail and everything new on the portal (like the server)
      if (requesterId !== t.requesterId) Object.assign(t, { requesterSeenAt: null, requesterMailedAt: null, requesterMailedStatusId: null });
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
      // each breach flag goes with its own deadline, and that deadline fell today (the person's zone)
      const today = startOfToday(user.timeZone);
      const isToday = (v: string | null) => v !== null && Date.parse(v) >= today && Date.parse(v) < today + 86_400_000;
      const breachedToday = rows.filter((r) => (r.t.responseBreached && isToday(r.t.firstResponseDueAt)) || (r.t.resolveBreached && isToday(r.t.resolveDueAt))).length;
      const answered = rows.filter((r) => r.t.firstRespondedAt && Date.parse(r.t.createdAt) >= now - 30 * 86_400_000);
      const avg = answered.length ? answered.reduce((s, r) => s + (Date.parse(r.t.firstRespondedAt!) - Date.parse(r.t.createdAt)) / MIN, 0) / answered.length : null;
      return { openByGroup, breachedToday, avgFirstResponseMinutes: avg === null ? null : Math.trunc(avg) };
    }),
  catalog: (project) => run(() => catalogOf(deskFor(sessionUser(), project).id)),
  createCategory: (project, input) => run(() => saveCategory(catalogDesk(project).id, null, input as Record<string, unknown>)),
  updateCategory: (project, id, patch) => run(() => saveCategory(catalogDesk(project).id, id, patch as Record<string, unknown>)),
  deleteCategory: (project, id) =>
    run(() => {
      const p = catalogDesk(project);
      if (!db.catalogCategories.some((c) => c.id === id && c.projectId === p.id)) throw new ApiError(404, "분류를 찾을 수 없습니다.");
      db.catalogCategories = db.catalogCategories.filter((c) => c.id !== id);
      db.catalogItems.forEach((i) => i.categoryId === id && (i.categoryId = null)); // on delete set null
    }),
  createItem: (project, input) => run(() => saveItem(catalogDesk(project), null, input)),
  updateItem: (project, id, patch) => run(() => saveItem(catalogDesk(project), id, patch)),
  deleteItem: (project, id) =>
    run(() => {
      const p = catalogDesk(project);
      if (!db.catalogItems.some((i) => i.id === id && i.projectId === p.id)) throw new ApiError(404, "항목을 찾을 수 없습니다.");
      db.catalogItems = db.catalogItems.filter((i) => i.id !== id);
      db.serviceTickets.forEach((t) => t.catalogItemId === id && (t.catalogItemId = null)); // on delete set null
    }),
  approvals: (number) => run(() => approvals.agentRows(agentFind(sessionUser(), number).wp.id)),
  replaceApprover: (number, approvalId, approverId) =>
    run(() => {
      const user = sessionUser();
      const { wp, project } = agentFind(user, number);
      requirePerm(user, project, "service.handle", "티켓을 처리할 권한이 없습니다.");
      return approvals.replace(approvalWriters(), user, wp, approvalId, approverId);
    }),
};

/* ---------- the catalog (ServiceCatalog) ---------- */

/** The agent's approval panel: a work package I can see in a desk I may see the service desk of. */
function agentFind(user: DbUser, number: number) {
  const found = findWp(user, number);
  if (!can(user, found.project, "service.view")) throw new ApiError(404, "작업을 찾을 수 없습니다.");
  return found;
}

/** A desk whose catalog I may change (service.manage), or the 404 / 403. */
function catalogDesk(project: string): DbProject {
  const user = sessionUser();
  const p = deskFor(user, project);
  requirePerm(user, p, "service.manage", "카탈로그를 고칠 권한이 없습니다.");
  return p;
}

const byPositionName = <T extends { position: number; name: string }>(a: T, b: T) => a.position - b.position || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0);

const toCategory = (c: DbCatalogCategory): CatalogCategory => ({ id: c.id, name: c.name, position: c.position });
const toItem = (i: DbCatalogItem): CatalogItem =>
  clone({
    id: i.id, categoryId: i.categoryId, name: i.name, descriptionHtml: i.descriptionHtml, typeId: i.typeId, defaultPriorityId: i.defaultPriorityId,
    assignmentGroupId: i.assignmentGroupId, isActive: i.isActive, position: i.position,
    fields: i.fields.slice().sort((a, b) => a.position - b.position), steps: approvals.sortSteps(i.steps),
  });

function catalogOf(projectId: Id): ServiceCatalog {
  return {
    categories: db.catalogCategories.filter((c) => c.projectId === projectId).sort(byPositionName).map(toCategory),
    items: db.catalogItems.filter((i) => i.projectId === projectId).sort(byPositionName).map(toItem),
  };
}

const has = (o: Record<string, unknown>, k: string) => Object.prototype.hasOwnProperty.call(o, k) && o[k] !== undefined;

function catalogName(input: Record<string, unknown>): string {
  const name = typeof input.name === "string" ? input.name.trim() : "";
  if (!name || name.length > 100) throw fieldError("name", "이름은 1~100자로 입력하세요.");
  return name;
}

function positionOf(v: unknown, field = "position"): number {
  if (v === null) return 0;
  const n = intOf(v);
  if (n === null) throw fieldError(field, "숫자를 입력하세요.");
  return n;
}

function saveCategory(projectId: Id, id: Id | null, input: Record<string, unknown>): CatalogCategory {
  const existing = id === null ? null : db.catalogCategories.find((c) => c.id === id && c.projectId === projectId);
  if (id !== null && !existing) throw new ApiError(404, "분류를 찾을 수 없습니다.");
  const next: DbCatalogCategory = existing ? { ...existing } : { id: newId("cc"), projectId, name: "", position: 0 };
  if (!existing || has(input, "name")) {
    const name = catalogName(input);
    if (db.catalogCategories.some((c) => c.projectId === projectId && c.name === name && c.id !== id)) throw fieldError("name", "같은 이름의 분류가 있습니다.");
    next.name = name;
  }
  if (has(input, "position")) next.position = positionOf(input.position);
  if (existing) Object.assign(existing, next);
  else db.catalogCategories.push(next);
  return toCategory(next);
}

const uuidOf = (v: unknown) => (v === null || v === undefined || v === "" ? null : String(v));

/** A form field must be a work package custom field that is on for this project and for the item's type. */
function checkFields(p: DbProject, typeId: Id, raw: unknown): DbCatalogItem["fields"] {
  if (raw === null) return [];
  if (!Array.isArray(raw) || !raw.every((x) => x && typeof x === "object")) throw fieldError("fields", "목록이어야 합니다.");
  const seen = new Set<Id>();
  return (raw as Record<string, unknown>[]).map((f, i) => {
    const at = `fields[${i}]`;
    const cfId = uuidOf(f.customFieldId);
    const cf = cfId ? db.customFields.find((x) => x.id === cfId) : undefined;
    if (!cf) throw fieldError(`${at}.customFieldId`, "알 수 없는 사용자 정의 필드입니다.");
    if (!cf.isForAll && !db.projectCustomFields.some((x) => x.projectId === p.id && x.customFieldId === cf.id)) throw fieldError(`${at}.customFieldId`, "이 프로젝트에서 켠 필드만 고를 수 있습니다.");
    if (!cf.typeIds.includes(typeId)) throw fieldError(`${at}.customFieldId`, "이 항목의 유형에 쓰는 필드만 고를 수 있습니다.");
    if (seen.has(cf.id)) throw fieldError(`${at}.customFieldId`, "같은 필드가 두 번 있습니다.");
    seen.add(cf.id);
    const position = has(f, "position") ? positionOf(f.position, `${at}.position`) : i;
    return { customFieldId: cf.id, position, isRequired: f.isRequired === true };
  });
}

function checkSteps(raw: unknown): DbCatalogItem["steps"] {
  if (raw === null) return [];
  if (!Array.isArray(raw) || !raw.every((x) => x && typeof x === "object")) throw fieldError("steps", "목록이어야 합니다.");
  const seen = new Set<string>();
  return (raw as Record<string, unknown>[]).map((s, i) => {
    const at = `steps[${i}]`;
    const stepNo = intOf(s.stepNo);
    if (stepNo === null || stepNo < 1 || stepNo > 9) throw fieldError(`${at}.stepNo`, "단계는 1~9입니다.");
    const kind = s.kind;
    if (kind !== "requester_manager" && kind !== "group_lead") throw fieldError(`${at}.kind`, "승인자 종류는 요청자의 관리자 또는 그룹 리더입니다.");
    if (seen.has(`${stepNo}/${kind}`)) throw fieldError(`${at}.kind`, "같은 단계에 같은 승인자 종류가 두 번 있습니다.");
    seen.add(`${stepNo}/${kind}`);
    return { stepNo, kind };
  });
}

/** Creates (id null) or updates an item. Only the keys sent change; sending fields or steps replaces the whole list. */
function saveItem(p: DbProject, id: Id | null, patch: CatalogItemInput): CatalogItem {
  const input = (patch ?? {}) as Record<string, unknown>;
  const existing = id === null ? null : db.catalogItems.find((i) => i.id === id && i.projectId === p.id);
  if (id !== null && !existing) throw new ApiError(404, "항목을 찾을 수 없습니다.");
  const next: DbCatalogItem = existing
    ? { ...existing }
    : { id: newId("ci"), projectId: p.id, categoryId: null, name: "", descriptionHtml: "", typeId: ITSM.serviceRequest, defaultPriorityId: null, assignmentGroupId: null, isActive: true, position: 0, fields: [], steps: [] };
  if (!existing || has(input, "name")) {
    const name = catalogName(input);
    if (db.catalogItems.some((i) => i.projectId === p.id && i.name === name && i.id !== id)) throw fieldError("name", "같은 이름의 항목이 있습니다.");
    next.name = name;
  }
  if (has(input, "typeId")) {
    const typeId = uuidOf(input.typeId);
    if (!typeId || !TICKET_TYPES.includes(typeId) || !p.typeIds.includes(typeId)) throw fieldError("typeId", "이 프로젝트에서 켠 장애·서비스 요청 유형만 고를 수 있습니다.");
    next.typeId = typeId;
  } else if (!existing && !p.typeIds.includes(next.typeId)) {
    throw fieldError("typeId", "이 프로젝트에서 서비스 요청 유형이 꺼져 있습니다.");
  }
  if (Object.prototype.hasOwnProperty.call(input, "categoryId")) {
    const categoryId = uuidOf(input.categoryId);
    if (categoryId && !db.catalogCategories.some((c) => c.id === categoryId && c.projectId === p.id)) throw fieldError("categoryId", "이 프로젝트의 분류가 아닙니다.");
    next.categoryId = categoryId;
  }
  if (Object.prototype.hasOwnProperty.call(input, "defaultPriorityId")) {
    const priorityId = uuidOf(input.defaultPriorityId);
    if (priorityId && !db.priorities.some((x) => x.id === priorityId)) throw fieldError("defaultPriorityId", "알 수 없는 우선순위입니다.");
    next.defaultPriorityId = priorityId;
  }
  if (Object.prototype.hasOwnProperty.call(input, "assignmentGroupId")) {
    const groupId = uuidOf(input.assignmentGroupId);
    if (groupId && !db.groups.some((g) => g.id === groupId)) throw fieldError("assignmentGroupId", "알 수 없는 그룹입니다.");
    next.assignmentGroupId = groupId;
  }
  if (has(input, "descriptionHtml")) next.descriptionHtml = typeof input.descriptionHtml === "string" ? input.descriptionHtml : "";
  if (Object.prototype.hasOwnProperty.call(input, "isActive")) next.isActive = input.isActive === null || input.isActive === undefined ? true : input.isActive === true;
  if (has(input, "position")) next.position = positionOf(input.position);
  if (Object.prototype.hasOwnProperty.call(input, "fields")) next.fields = checkFields(p, next.typeId, input.fields ?? null);
  else if (existing && has(input, "typeId")) {
    // the type changed: the form that is kept must still fit it
    try {
      checkFields(p, next.typeId, existing.fields.map((f) => ({ customFieldId: f.customFieldId })));
    } catch {
      throw fieldError("typeId", "항목 양식의 필드가 새 유형에 맞지 않습니다. 양식을 먼저 고치세요.");
    }
  }
  if (Object.prototype.hasOwnProperty.call(input, "steps")) next.steps = checkSteps(input.steps ?? null);
  if (existing) Object.assign(existing, next);
  else db.catalogItems.push(next);
  return toItem(next);
}

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

/** Portal-visible fields on in this desk for at least one ticket type; user and version fields never go on the portal. */
function portalFields(p: DbProject): PortalField[] {
  return db.customFields
    .filter((f) => f.portalVisible && !NOT_ON_PORTAL.includes(f.format) && enabledIn(f, p.id) && f.typeIds.some((t) => TICKET_TYPES.includes(t)))
    .sort((a, b) => a.position - b.position)
    .map((f) => ({
      id: f.id, name: f.name, format: f.format, required: f.isRequired, multi: f.isMultiValue,
      options: f.options.map((o) => ({ id: o.id, value: o.value })), typeIds: f.typeIds.filter((t) => TICKET_TYPES.includes(t)),
    }));
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

/** A desk's active, requestable items by category; items without a (known) category sit under a made-up 기타 with no id. */
function portalCatalog(p: DbProject): PortalCategory[] {
  const items = db.catalogItems.filter((i) => i.projectId === p.id && approvals.requestable(p, i)).sort(byPositionName);
  const toPortal = (i: DbCatalogItem) => ({ id: i.id, name: i.name, descriptionHtml: i.descriptionHtml, typeId: i.typeId, fields: approvals.itemFields(p, i) });
  const categories = db.catalogCategories.filter((c) => c.projectId === p.id).sort(byPositionName);
  const known = new Set(categories.map((c) => c.id));
  const out: PortalCategory[] = categories
    .map((c) => ({ id: c.id as Id | null, name: c.name, items: items.filter((i) => i.categoryId === c.id).map(toPortal) }))
    .filter((c) => c.items.length > 0);
  const loose = items.filter((i) => !i.categoryId || !known.has(i.categoryId)).map(toPortal);
  if (loose.length) out.push({ id: null, name: "기타", items: loose });
  return clone(out);
}

const ticketItem = (wp: DbWp, project: DbProject): DbCatalogItem | null => {
  const t = db.serviceTickets.find((x) => x.workPackageId === wp.id);
  return (t?.catalogItemId && db.catalogItems.find((i) => i.id === t.catalogItemId && i.projectId === project.id)) || null;
};

const blank = (v: unknown) => v === null || v === undefined || (typeof v === "string" && !v.trim()) || (Array.isArray(v) && v.length === 0);

function toTicket(user: DbUser, wp: DbWp, project: DbProject, withActivity: boolean): PortalTicket {
  const status = db.statuses.find((s) => s.id === wp.statusId)!;
  const type = db.types.find((x) => x.id === wp.typeId)!;
  const allowed = allowedFor([ITSM.requesterRole], user.id, wp, user.id).map((id) => db.statuses.find((s) => s.id === id)!).map((s) => ({ id: s.id, name: s.name }));
  const visible = new Set(portalFields(project).map((f) => f.id));
  // the item's form is shown with the request even where the fields are not marked for the portal
  const item = ticketItem(wp, project);
  if (item) approvals.itemFields(project, item).forEach((f) => visible.add(f.id));
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
    allowedStatuses: allowed, fields, activity, files, item: item ? { id: item.id, name: item.name } : null, approvals: approvals.portalApprovals(wp.id),
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
        catalog: portalCatalog(p),
      }));
    }),
  submit: (identifier, input) =>
    run(() => {
      const user = sessionUser();
      const p = deskByIdentifier(identifier);
      // a catalog item decides the type, the form, the priority, the group and the approvals
      const itemId = input?.catalogItemId;
      const item = itemId ? db.catalogItems.find((i) => i.id === itemId && i.projectId === p.id && approvals.requestable(p, i)) : null;
      if (itemId && !item) throw fieldError("catalogItem", "요청할 수 없는 항목입니다.");
      const typeId = item ? item.typeId : input?.typeId;
      if (!typeId || !TICKET_TYPES.includes(typeId) || !p.typeIds.includes(typeId)) throw fieldError("type", "장애 신고나 서비스 요청을 고르세요.");
      // only what the portal offers goes on: no assignee, status, priority, dates or hidden fields
      const form = item ? approvals.itemFields(p, item) : portalFields(p).filter((f) => f.typeIds.includes(typeId));
      const allowedFields = new Set(form.map((f) => f.id));
      const customValues: Record<Id, CustomValue> = {};
      for (const [k, v] of Object.entries(input.customValues ?? {})) if (allowedFields.has(k)) customValues[k] = v as CustomValue;
      // the item's form says what is required, whatever the fields' own flags (those are waived for an item request)
      if (item) for (const f of form) if (f.required && blank(customValues[f.id])) throw fieldError(`cf:${f.id}`, `${f.name}: 값을 입력하세요.`);
      const safe: WorkPackageInput = { typeId, subject: input.subject, descriptionHtml: input.descriptionHtml, customValues };
      if (input.impact !== undefined) safe.impact = input.impact;
      if (input.urgency !== undefined) safe.urgency = input.urgency;
      if (!item) return toTicket(user, w().insert(user, p, safe, "portal"), p, false);
      const planned = approvals.planFor(item, user.id);
      // 승인 대기 is an SLA pause: the ticket starts paused
      safe.statusId = approvals.needsApproval(planned) ? ITSM.awaitingApproval : ITSM.received;
      if (item.defaultPriorityId && db.priorities.some((x) => x.id === item.defaultPriorityId)) safe.priorityId = item.defaultPriorityId;
      const wp = w().insert(user, p, safe, "portal", new Set(db.customFields.map((f) => f.id)));
      const t = db.serviceTickets.find((x) => x.workPackageId === wp.id)!;
      Object.assign(t, { catalogItemId: item.id, assignmentGroupId: item.assignmentGroupId });
      approvals.store(approvalWriters(), user, wp, planned);
      return toTicket(user, wp, p, false);
    }),
  tickets: (state) =>
    run(() => {
      const user = sessionUser();
      // 해결 waits for the requester's confirmation, so it stays among the open ones
      const closed = new Set(db.statuses.filter((s) => s.isClosed && s.id !== ITSM.resolved).map((s) => s.id));
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
  approvals: () => run(() => approvals.inbox(sessionUser())),
  approval: (id) => run(() => approvals.detail(sessionUser(), id)),
  decide: (id, decision, comment) =>
    run(() => {
      const user = sessionUser();
      if (decision !== "approve" && decision !== "reject") {
        approvals.check(user, id); // someone else's row is the 404 whatever the body says
        throw fieldError("decision", "승인 또는 반려를 고르세요.");
      }
      return { decision: approvals.decide(approvalWriters(), user, id, decision === "approve", comment) };
    }),
  // the stored data URL, only for a file of the request my pending row belongs to
  approvalFileUrl: (id, fileId) => {
    try {
      return approvals.fileUrl(sessionUser(), id, fileId);
    } catch {
      return "#";
    }
  },
};
