// 서비스 카탈로그의 승인 (ITSM 2단계) in the fake layer: the same rules and messages as the server's itsm/Approvals (who
// approves, rows in step order, skips) and itsm/ApprovalService (내 승인함, the approver's page, decisions, the agent's panel).
// An approver need not be a member: everything here is narrowed to the caller's own pending row, anything else is the same 404.
// The work package writes come in as ApprovalWriters (service-desk.ts passes index.ts's), so this module does not import them.
import type { AgentApproval, ApprovalDetail, ApprovalKind, ApprovalSkipReason, ApprovalSummary, Id, PortalApproval, PortalField, PortalFile } from "../types";
import { NOT_ON_PORTAL } from "../types";
import { ApiError } from "../errors";
import { nowIso } from "@/lib/dates";
import { db, newId, type DbCatalogItem, type DbJournal, type DbProject, type DbTicketApproval, type DbUser, type DbWp } from "./db";
import { activeFields, customValuesOf } from "./custom-fields";
import { clone, userRef } from "./dto";
import { ITSM, TICKET_TYPES } from "./service-desk-seed";
import { fieldError } from "./util";

export const MANAGER: ApprovalKind = "requester_manager";
export const GROUP_LEAD: ApprovalKind = "group_lead";
export const KINDS: readonly ApprovalKind[] = [MANAGER, GROUP_LEAD];

export interface StepRule {
  stepNo: number;
  kind: ApprovalKind;
}
export interface Planned {
  stepNo: number;
  kind: ApprovalKind;
  approverId: Id | null;
  decision: "pending" | "waiting" | "skipped";
  skipReason: ApprovalSkipReason | null;
}

/** What a decision needs from index.ts: journal lines, a public comment and the status move out of 승인 대기. */
export interface ApprovalWriters {
  journal(wp: DbWp, actor: DbUser, changes: DbJournal["changes"]): DbJournal;
  fanOut(wp: DbWp, journal: DbJournal, actor: DbUser): void;
  /** a public comment by the approver (the rejection reason) */
  comment(actor: DbUser, wp: DbWp, bodyHtml: string): void;
  /** 승인 대기 → 접수 | 반려, as the approver (WorkPackageService Acting.APPROVAL) */
  status(actor: DbUser, wp: DbWp, project: DbProject, statusId: Id): void;
}

export const sortSteps = <T extends StepRule>(steps: T[]): T[] =>
  steps.slice().sort((a, b) => a.stepNo - b.stepNo || KINDS.indexOf(a.kind) - KINDS.indexOf(b.kind));

/**
 * The rows for a new request (Approvals.plan): steps in order, the manager before the group lead within a step. No
 * approver, the requester themselves or the same person twice in one step is skipped; the first step with someone left
 * is pending, later ones wait. A step whose rows were all skipped is passed over.
 */
export function plan(rules: StepRule[], requesterId: Id, managerId: Id | null, groupLeadId: Id | null): Planned[] {
  const out: Planned[] = [];
  let current: number | null = null;
  for (const r of sortSteps(rules)) {
    const who = r.kind === MANAGER ? managerId : groupLeadId;
    let skip: ApprovalSkipReason | null = null;
    if (!who) skip = "no_approver";
    else if (who === requesterId) skip = "requester";
    else if (out.some((e) => e.stepNo === r.stepNo && e.skipReason === null && e.approverId === who)) skip = "duplicate";
    if (skip) {
      out.push({ stepNo: r.stepNo, kind: r.kind, approverId: skip === "no_approver" ? null : who, decision: "skipped", skipReason: skip });
      continue;
    }
    if (current === null) current = r.stepNo;
    out.push({ stepNo: r.stepNo, kind: r.kind, approverId: who, decision: r.stepNo === current ? "pending" : "waiting", skipReason: null });
  }
  return out;
}

export const needsApproval = (planned: Planned[]) => planned.some((p) => p.decision === "pending");

const activeUser = (id: Id | null | undefined): Id | null => (id && db.users.some((u) => u.id === id && u.status === "active") ? id : null);

/** The rows for a new request of this item: the requester's manager and the item group's lead; a locked or deleted one counts as none. */
export function planFor(item: DbCatalogItem, requesterId: Id): Planned[] {
  if (!item.steps.length) return [];
  const managerId = activeUser(db.users.find((u) => u.id === requesterId)?.managerId);
  const group = item.assignmentGroupId ? db.groups.find((g) => g.id === item.assignmentGroupId) : undefined;
  return plan(item.steps, requesterId, managerId, activeUser(group?.leadUserId));
}

export const kindName = (kind: ApprovalKind) => (kind === MANAGER ? "관리자" : "그룹 책임자");
const SKIP_TEXT: Record<ApprovalSkipReason, string> = {
  no_approver: "승인자 없음", requester: "요청자 본인", duplicate: "같은 승인자", step_done: "같은 단계에서 결정됨", left_approval: "승인 대기에서 나감",
};

/** One line in the request's activity, keyed "approval:<row id>" (shown as 승인). */
function line(wr: ApprovalWriters, actor: DbUser, wp: DbWp, approvalId: Id, text: string) {
  const j = wr.journal(wp, actor, { [`approval:${approvalId}`]: [null, text] });
  wr.fanOut(wp, j, actor);
}

/** Stores the planned rows of a new request; every skipped one leaves a line in the activity. */
export function store(wr: ApprovalWriters, actor: DbUser, wp: DbWp, planned: Planned[]) {
  const now = nowIso();
  for (const p of planned) {
    const row: DbTicketApproval = {
      id: newId("ta"), wpId: wp.id, stepNo: p.stepNo, kind: p.kind, approverId: p.approverId, decision: p.decision, skipReason: p.skipReason,
      decidedAt: p.decision === "skipped" ? now : null, comment: "", pendingSince: p.decision === "pending" ? now : null, remindedAt: null, createdAt: now,
    };
    db.ticketApprovals.push(row);
    if (p.decision === "skipped") line(wr, actor, wp, row.id, `${p.stepNo}단계 ${kindName(p.kind)} 건너뜀: ${SKIP_TEXT[p.skipReason!]}`);
  }
}

/** A request's rows in step order. */
export const rowsOf = (wpId: Id): DbTicketApproval[] => sortSteps(db.ticketApprovals.filter((a) => a.wpId === wpId));

/** The request left 승인 대기 some other way than the last approval: the rows still undecided are skipped (one line when any were). */
export function skipRemaining(wr: ApprovalWriters, actor: DbUser, wp: DbWp) {
  const open = rowsOf(wp.id).filter((a) => a.decision === "pending" || a.decision === "waiting");
  if (!open.length) return;
  const now = nowIso();
  for (const a of open) Object.assign(a, { decision: "skipped", skipReason: "left_approval", decidedAt: now });
  line(wr, actor, wp, open[0].id, "남은 승인 건너뜀");
}

/** The steps as the requester and an approver see them: who and how far, never comments or skip reasons. */
export const portalApprovals = (wpId: Id): PortalApproval[] =>
  rowsOf(wpId).map((a) => ({
    stepNo: a.stepNo, approverName: a.decision === "skipped" || !a.approverId ? null : (userRef(a.approverId)?.name ?? null), decision: a.decision, decidedAt: a.decidedAt,
  }));

/* ---------- 승인 지연 ---------- */

const DAY = 86_400_000;
const kstDate = (ms: number) => new Date(ms + 9 * 3_600_000).toISOString().slice(0, 10);

/** Working days after from (exclusive) up to and including to, by the work calendar (ApprovalMail.workingDaysSince). */
function workingDaysSince(from: string, to: string): number {
  const holidays = new Set(db.nonWorkingDays.map((d) => d.date));
  let n = 0;
  for (let t = Date.parse(`${from}T00:00:00Z`) + DAY; t <= Date.parse(`${to}T00:00:00Z`); t += DAY) {
    const day = new Date(t).getUTCDay() || 7;
    if (db.settings.workingDays.includes(day) && !holidays.has(new Date(t).toISOString().slice(0, 10))) n += 1;
  }
  return n;
}

/**
 * 승인 지연: the approver was reminded. The fake has no approval-mail job, so a pending row also counts once three
 * working days have passed since its turn came (when the server's job would have sent the reminder).
 */
export function isDelayed(a: DbTicketApproval, now = Date.now()): boolean {
  if (a.decision !== "pending") return false;
  if (a.remindedAt) return true;
  return !!a.pendingSince && workingDaysSince(kstDate(Date.parse(a.pendingSince)), kstDate(now)) >= 3;
}

/* ---------- the catalog item's form ---------- */

/** An item can be asked for while it is active and its type is a ticket type the desk still uses. */
export const requestable = (p: DbProject, item: DbCatalogItem) => item.isActive && TICKET_TYPES.includes(item.typeId) && p.typeIds.includes(item.typeId);

/**
 * An item's form: its fields whether or not they are marked for the portal, while they are still on for the desk and the
 * item's type; required is the item's own flag. User and version fields stay off the portal here too.
 */
export function itemFields(p: DbProject, item: DbCatalogItem): PortalField[] {
  const active = new Map(activeFields(p.id, item.typeId).map((f) => [f.id, f]));
  return item.fields
    .slice()
    .sort((a, b) => a.position - b.position)
    .flatMap((f): PortalField[] => {
      const cf = active.get(f.customFieldId);
      if (!cf || NOT_ON_PORTAL.includes(cf.format)) return [];
      return [{ id: cf.id, name: cf.name, format: cf.format, required: f.isRequired, multi: cf.isMultiValue, options: cf.options.map((o) => ({ id: o.id, value: o.value })), typeIds: [item.typeId] }];
    });
}

/* ---------- the approver: 내 승인함, the approval page, decisions ---------- */

export const NOT_FOUND = "승인 요청을 찾을 수 없습니다.";
const liveDesk = (projectId: Id) => db.projects.find((p) => p.id === projectId && !p.archivedAt && p.enabledModules.includes("service_desk"));

/** The request of a row, while its desk is live. */
function requestOf(a: DbTicketApproval): { wp: DbWp; project: DbProject } | null {
  const wp = db.workPackages.find((w) => w.id === a.wpId);
  const project = wp && liveDesk(wp.projectId);
  return wp && project ? { wp, project } : null;
}

/** My pending row and its request, or the 404 (not mine, not my turn yet, already decided, gone or no longer a desk). */
function mine(user: DbUser, id: Id) {
  const row = db.ticketApprovals.find((a) => a.id === id && a.approverId === user.id && a.decision === "pending");
  const r = row && requestOf(row);
  if (!row || !r) throw new ApiError(404, NOT_FOUND);
  return { row, ...r };
}

const itemOf = (wp: DbWp, projectId: Id): DbCatalogItem | null => {
  const t = db.serviceTickets.find((x) => x.workPackageId === wp.id);
  return (t?.catalogItemId && db.catalogItems.find((i) => i.id === t.catalogItemId && i.projectId === projectId)) || null;
};
const requesterName = (wp: DbWp) => {
  const t = db.serviceTickets.find((x) => x.workPackageId === wp.id);
  return t ? (userRef(t.requesterId)?.name ?? "") : "";
};

export function inbox(user: DbUser): ApprovalSummary[] {
  return db.ticketApprovals
    .filter((a) => a.approverId === user.id && a.decision === "pending")
    .flatMap((a) => {
      const r = requestOf(a);
      return r ? [{ a, ...r }] : [];
    })
    .sort((x, y) => x.wp.createdAt.localeCompare(y.wp.createdAt) || x.wp.number - y.wp.number)
    .slice(0, 200)
    .map(({ a, wp, project }) => ({
      id: a.id, number: wp.number, subject: wp.subject, requester: requesterName(wp), item: itemOf(wp, project.id)?.name ?? null, desk: project.name,
      stepNo: a.stepNo, requestedAt: wp.createdAt,
    }));
}

const filesOf = (wpId: Id): PortalFile[] =>
  db.attachments
    .filter((a) => a.wpId === wpId)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    .map((a) => ({ id: a.id, fileName: a.fileName, byteSize: a.byteSize, createdAt: a.createdAt }));

/** The request, the item's form fields only, its files and how far the steps are (no comments). */
export function detail(user: DbUser, id: Id): ApprovalDetail {
  const { row, wp, project } = mine(user, id);
  const item = itemOf(wp, project.id);
  const form = new Set(item ? itemFields(project, item).map((f) => f.id) : []);
  const fields = Object.entries(customValuesOf(wp))
    .filter(([fid]) => form.has(fid))
    .map(([fid, v]) => ({ name: db.customFields.find((f) => f.id === fid)?.name ?? "", value: v.text }));
  return clone({
    id: row.id, number: wp.number, subject: wp.subject, descriptionHtml: wp.descriptionHtml, requester: requesterName(wp), item: item?.name ?? null,
    desk: project.name, stepNo: row.stepNo, fields, files: filesOf(wp.id), steps: portalApprovals(wp.id),
  });
}

/** The 404 unless the row is my pending one. */
export const check = (user: DbUser, id: Id) => void mine(user, id);

/** A file of the very request my pending row belongs to (the demo's link is the stored data URL). */
export function fileUrl(user: DbUser, id: Id, fileId: Id): string {
  const { wp } = mine(user, id);
  return db.attachments.find((a) => a.id === fileId && a.wpId === wp.id)?.dataUrl ?? "#";
}

/** The reject reason is plain text from a textarea: escaped, one paragraph per line (as the server does). */
const plainToHtml = (text: string) =>
  text
    .split(/\r?\n|\r/)
    .filter((l) => l.trim())
    .map((l) => `<p>${l.trim().replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")}</p>`)
    .join("");

/**
 * Approve or reject my pending row; a decision counts once (409 for the second). Approving finishes the step (the others
 * of it are skipped), then the next waiting step is pending, or after the last one the request is 접수. Rejecting skips the
 * rest, leaves the reason as a public comment and ends the request as 반려.
 */
export function decide(wr: ApprovalWriters, user: DbUser, id: Id, approve: boolean, comment: string | undefined): "approved" | "rejected" {
  const row = db.ticketApprovals.find((a) => a.id === id && a.approverId === user.id);
  // not mine, not my turn yet (waiting) or no longer mine to decide (skipped): the same 404
  if (!row || row.decision === "waiting" || row.decision === "skipped") throw new ApiError(404, NOT_FOUND);
  const r = requestOf(row);
  if (!r) throw new ApiError(404, NOT_FOUND);
  if (!approve && !(comment ?? "").trim()) throw fieldError("comment", "반려 사유를 입력하세요.");
  if (row.decision !== "pending") throw new ApiError(409, "이미 처리된 승인입니다.", "승인함을 새로 고치세요.");
  const now = nowIso();
  Object.assign(row, { decision: approve ? "approved" : "rejected", decidedAt: now, comment: (comment ?? "").trim() });
  const { wp, project } = r;
  line(wr, user, wp, row.id, `${row.stepNo}단계 ${userRef(user.id)!.name} ${approve ? "승인" : "반려"}`);
  if (!approve) {
    skipRemaining(wr, user, wp);
    wr.comment(user, wp, plainToHtml(comment!.trim()));
    wr.status(user, wp, project, ITSM.rejected);
    return "rejected";
  }
  // anyone of the step decides it: the others of the step are done, the next step's waiting rows become pending
  for (const a of rowsOf(wp.id)) if (a.stepNo === row.stepNo && a.decision === "pending") Object.assign(a, { decision: "skipped", skipReason: "step_done", decidedAt: now });
  const waiting = rowsOf(wp.id).filter((a) => a.decision === "waiting");
  if (waiting.length) {
    const next = Math.min(...waiting.map((a) => a.stepNo));
    for (const a of waiting) if (a.stepNo === next) Object.assign(a, { decision: "pending", pendingSince: now });
    return "approved";
  }
  wr.status(user, wp, project, ITSM.received);
  return "approved";
}

/* ---------- the agent's approval panel ---------- */

export const agentRows = (wpId: Id): AgentApproval[] =>
  clone(
    rowsOf(wpId).map((a) => ({
      id: a.id, stepNo: a.stepNo, kind: a.kind, approver: userRef(a.approverId), decision: a.decision, skipReason: a.skipReason, decidedAt: a.decidedAt, comment: a.comment,
    })),
  );

/** An agent hands an undecided row (pending or waiting) to someone else: active and not the requester. */
export function replace(wr: ApprovalWriters, actor: DbUser, wp: DbWp, approvalId: Id, approverId: Id | null | undefined): AgentApproval[] {
  const row = db.ticketApprovals.find((a) => a.id === approvalId && a.wpId === wp.id);
  if (!row) throw new ApiError(404, NOT_FOUND);
  if (row.decision !== "pending" && row.decision !== "waiting") throw new ApiError(422, "결정이 끝난 승인입니다.", "아직 결정하지 않은 승인의 승인자만 바꿀 수 있습니다.");
  const who = approverId ? db.users.find((u) => u.id === approverId && u.status === "active") : undefined;
  if (!who) throw fieldError("approver", "승인자는 활성 사용자여야 합니다.");
  if (db.serviceTickets.find((t) => t.workPackageId === wp.id)?.requesterId === who.id) throw fieldError("approver", "요청자 본인은 승인자가 될 수 없습니다.");
  if (row.approverId === who.id) return agentRows(wp.id);
  const was = row.approverId ? (userRef(row.approverId)?.name ?? "없음") : "없음";
  // the new approver is told afresh (approval-mail on the server): the delay counts from now
  Object.assign(row, { approverId: who.id, remindedAt: null, pendingSince: row.decision === "pending" ? nowIso() : null });
  line(wr, actor, wp, row.id, `${row.stepNo}단계 승인자 변경: ${was} → ${who.name}`);
  return agentRows(wp.id);
}
