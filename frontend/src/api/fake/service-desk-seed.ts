// 서비스 데스크 seed for the fake layer (no imports of the database: seed.ts builds it). The same rows as the server's
// V20__service_desk.sql (types, statuses, 요청자 role, workflows, calendars) and V1004__dev_service_desk.sql (the
// service-desk project, groups, members, policies, matrix and six tickets), with readable ids instead of uuids; ITSM 2단계
// adds V21__service_catalog.sql (승인 대기·반려, the workflow out of 승인 대기) and V1005__dev_service_catalog.sql (managers,
// group leads, two categories, three items, the 노트북 기종 field and one request awaiting approval).
import type { ApprovalDecision, ApprovalKind, ApprovalSkipReason, Id, IsoDateTime } from "../types";
import type { DbCustomField, DbJournal, DbRole, DbStatus, DbType, DbWorkflow, DbWp } from "./db";

export interface DbServiceCalendar {
  id: Id;
  name: string;
  mode: "24x7" | "business";
  timeZone: string;
  /** ISO day (1 = Monday), "HH:mm" */
  hours: { day: number; start: string; end: string }[];
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}
export interface DbSlaPolicy { id: Id; projectId: Id; typeId: Id; priorityId: Id; calendarId: Id; firstResponseMinutes: number; resolveMinutes: number }
export interface DbMatrixCell { projectId: Id; impact: 1 | 2 | 3; urgency: 1 | 2 | 3; priorityId: Id }
/** service_tickets: one per ticket, the same columns in camelCase */
export interface DbServiceTicket {
  workPackageId: Id;
  requesterId: Id;
  channel: "portal" | "agent" | "api";
  impact: 1 | 2 | 3;
  urgency: 1 | 2 | 3;
  assignmentGroupId: Id | null;
  slaPolicyId: Id | null;
  firstResponseDueAt: IsoDateTime | null;
  firstRespondedAt: IsoDateTime | null;
  resolveDueAt: IsoDateTime | null;
  resolvedAt: IsoDateTime | null;
  pausedSince: IsoDateTime | null;
  pausedTotalSeconds: number;
  responseBreached: boolean;
  resolveBreached: boolean;
  responseWarned: boolean;
  resolveWarned: boolean;
  /** when the requester last opened it on the portal ("새 소식") */
  requesterSeenAt: IsoDateTime | null;
  /** ITSM 2단계: the catalog item it was requested from (absent = none) */
  catalogItemId?: Id | null;
  requesterMailedAt: IsoDateTime | null;
  requesterMailedStatusId: Id | null;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

/** catalog_categories */
export interface DbCatalogCategory { id: Id; projectId: Id; name: string; position: number }
/** catalog_items with their catalog_item_fields and catalog_item_approval_steps */
export interface DbCatalogItem {
  id: Id;
  projectId: Id;
  categoryId: Id | null;
  name: string;
  descriptionHtml: string;
  typeId: Id;
  defaultPriorityId: Id | null;
  assignmentGroupId: Id | null;
  isActive: boolean;
  position: number;
  fields: { customFieldId: Id; position: number; isRequired: boolean }[];
  steps: { stepNo: number; kind: ApprovalKind }[];
}
/**
 * ticket_approvals. The fake sends no approval mail: pendingSince (when the row's turn came) stands in for notified_at,
 * and a pending row counts as 승인 지연 once it was reminded or three working days have passed since (fake/approvals.ts).
 */
export interface DbTicketApproval {
  id: Id;
  wpId: Id;
  stepNo: number;
  kind: ApprovalKind;
  approverId: Id | null;
  decision: ApprovalDecision;
  skipReason: ApprovalSkipReason | null;
  decidedAt: IsoDateTime | null;
  comment: string;
  pendingSince: IsoDateTime | null;
  remindedAt: IsoDateTime | null;
  createdAt: IsoDateTime;
}

/** The fixed rows the server's ItsmIds names. */
export const ITSM = {
  incident: "t-incident",
  serviceRequest: "t-service-request",
  received: "s-received",
  handling: "s-handling",
  waiting: "s-waiting-requester",
  resolved: "s-resolved",
  closed: "s-sd-closed",
  cancelled: "s-cancel",
  /** ITSM 2단계 (V21): 승인 대기 stops the SLA clock; 반려 is closed */
  awaitingApproval: "s-awaiting-approval",
  rejected: "s-rejected",
  requesterRole: "r-requester",
  module: "service_desk",
} as const;
export const TICKET_TYPES: readonly string[] = [ITSM.incident, ITSM.serviceRequest];

export function seedServiceDesk(now: number, firstNumber: number) {
  const iso = (ms: number) => new Date(ms).toISOString();
  const MIN = 60_000;
  const HOUR = 60 * MIN;
  const DAY = 24 * HOUR;
  const t0 = iso(now);

  const types: DbType[] = [
    { id: ITSM.incident, name: "장애", color: "#c02c39", position: 6, isMilestone: false, isDefault: false, isInRoadmap: false },
    { id: ITSM.serviceRequest, name: "서비스 요청", color: "#8b2f8f", position: 7, isMilestone: false, isDefault: false, isInRoadmap: false },
  ];
  const statuses: DbStatus[] = [
    { id: ITSM.received, name: "접수", color: "#8b2f8f", position: 7, isDefault: false, isClosed: false, isReadonly: false, defaultDoneRatio: 0, excludedFromTotals: false, slaPause: false },
    { id: ITSM.handling, name: "처리 중", color: "#975800", position: 8, isDefault: false, isClosed: false, isReadonly: false, defaultDoneRatio: 0, excludedFromTotals: false, slaPause: false },
    { id: ITSM.waiting, name: "답변 대기", color: "#8a5300", position: 9, isDefault: false, isClosed: false, isReadonly: false, defaultDoneRatio: 0, excludedFromTotals: false, slaPause: true },
    { id: ITSM.resolved, name: "해결", color: "#1e7a4c", position: 10, isDefault: false, isClosed: true, isReadonly: false, defaultDoneRatio: 100, excludedFromTotals: false, slaPause: false },
    { id: ITSM.closed, name: "종료", color: "#5f6873", position: 11, isDefault: false, isClosed: true, isReadonly: true, defaultDoneRatio: 100, excludedFromTotals: false, slaPause: false },
    { id: ITSM.awaitingApproval, name: "승인 대기", color: "#5f6a12", position: 12, isDefault: false, isClosed: false, isReadonly: false, defaultDoneRatio: 0, excludedFromTotals: false, slaPause: true },
    { id: ITSM.rejected, name: "반려", color: "#c02c39", position: 13, isDefault: false, isClosed: true, isReadonly: true, defaultDoneRatio: 0, excludedFromTotals: true, slaPause: false },
  ];
  const roles: DbRole[] = [{ id: ITSM.requesterRole, name: "요청자", scope: "project", builtin: "requester", permissions: [] }];

  // 처리자(프로젝트 관리자·멤버): ITSM 유형에서 ITSM 상태와 취소 사이를 모두 오간다
  const desk = [ITSM.received, ITSM.handling, ITSM.waiting, ITSM.resolved, ITSM.closed, ITSM.cancelled];
  const workflows: DbWorkflow[] = TICKET_TYPES.flatMap((typeId) =>
    ["r-manager", "r-member"].flatMap((roleId) => desk.flatMap((from) => desk.filter((to) => to !== from).map((to) => ({ typeId, roleId, from, to })))),
  );
  // 요청자(포털이 요청자 본인의 티켓에서만 검사하므로 "always"): 답변하면 처리 중으로, 해결을 확인(종료)하거나 다시 열기, 끝나지 않은 요청 취소
  const requesterMoves: [string, string][] = [
    [ITSM.waiting, ITSM.handling],
    [ITSM.resolved, ITSM.handling],
    [ITSM.resolved, ITSM.closed],
    [ITSM.received, ITSM.cancelled],
    [ITSM.handling, ITSM.cancelled],
    [ITSM.waiting, ITSM.cancelled],
  ];
  for (const typeId of TICKET_TYPES)
    for (const [from, to] of requesterMoves) workflows.push({ typeId, roleId: ITSM.requesterRole, from, to });
  // V21: agents cannot skip the approvals (승인 대기 only goes to 취소); the requester may cancel too. 반려 ends the request.
  for (const typeId of TICKET_TYPES)
    for (const roleId of ["r-manager", "r-member", ITSM.requesterRole]) workflows.push({ typeId, roleId, from: ITSM.awaitingApproval, to: ITSM.cancelled, appliesTo: "always" });

  const weekdays = [1, 2, 3, 4, 5].map((day) => ({ day, start: "09:00", end: "18:00" }));
  const serviceCalendars: DbServiceCalendar[] = [
    { id: "cal-business", name: "평일 근무 시간", mode: "business", timeZone: "Asia/Seoul", hours: weekdays, createdAt: t0, updatedAt: t0 },
    { id: "cal-24x7", name: "24시간", mode: "24x7", timeZone: "Asia/Seoul", hours: [], createdAt: t0, updatedAt: t0 },
  ];

  // 개발 데이터: 처리자는 박준호·이하늘(1선 지원), 요청자는 멤버가 아닌 최민지·한도윤
  const projectId = "p-service-desk";
  const project = {
    id: projectId, identifier: "service-desk", name: "IT 서비스 데스크",
    descriptionHtml: "<p>사내 IT 장애 신고와 서비스 요청을 받습니다.</p>",
    status: null, statusExplanation: "", isPublic: false, isTemplate: false, archivedAt: null as string | null, parentId: null as string | null,
    enabledModules: ["work_packages", "boards", "service_desk"] as ("work_packages" | "boards" | "service_desk")[],
    typeIds: [...TICKET_TYPES], createdAt: t0, updatedAt: t0, latestActivityAt: t0,
  };
  // V1005: 그룹 책임자 1선 지원 → 박준호, 인프라 → alex.kim
  const groups = [
    { id: "g-sd-l1", name: "1선 지원", memberIds: ["u-junho", "u-haneul"], leadUserId: "u-junho" as string | null },
    { id: "g-sd-infra", name: "인프라", memberIds: ["u-alex"], leadUserId: "u-alex" as string | null },
  ];
  // V1005: 요청자의 관리자(상사) — 최민지와 한도윤 모두 alex.kim
  const managerOf: Record<Id, Id> = { "u-minji": "u-alex", "u-doyoon": "u-alex" };
  const members = ["u-junho", "u-haneul"].map((userId, i) => ({ id: `m-sd-${i + 1}`, projectId, userId, groupId: null as string | null, roleIds: ["r-member"], createdAt: t0 }));

  // 정책: 장애 긴급 24시간(15분/2시간), 장애 높음·보통·낮음과 서비스 요청 보통은 평일 근무 시간
  const policy = (id: string, typeId: string, priorityId: string, calendarId: string, firstResponseMinutes: number, resolveMinutes: number): DbSlaPolicy =>
    ({ id, projectId, typeId, priorityId, calendarId, firstResponseMinutes, resolveMinutes });
  const slaPolicies = [
    policy("sla-incident-urgent", ITSM.incident, "pr-urgent", "cal-24x7", 15, 120),
    policy("sla-incident-high", ITSM.incident, "pr-high", "cal-business", 30, 240),
    policy("sla-incident-normal", ITSM.incident, "pr-normal", "cal-business", 60, 480),
    policy("sla-incident-low", ITSM.incident, "pr-low", "cal-business", 240, 1440),
    policy("sla-request-normal", ITSM.serviceRequest, "pr-normal", "cal-business", 240, 2400),
  ];
  // 행렬(영향도 × 긴급도): 1·1 긴급, 1·2와 2·1 높음, 3·3 낮음, 나머지 보통
  const levels = [1, 2, 3] as const;
  const priorityMatrix: DbMatrixCell[] = levels.flatMap((impact) =>
    levels.map((urgency) => ({
      projectId, impact, urgency,
      priorityId: impact === 1 && urgency === 1 ? "pr-urgent" : (impact === 1 && urgency === 2) || (impact === 2 && urgency === 1) ? "pr-high" : impact === 3 && urgency === 3 ? "pr-low" : "pr-normal",
    })),
  );

  // 티켓 6건. 기한은 화면에서 상태가 골고루 보이게 지금 기준으로 둔다(요청자 메일은 이미 나간 것으로).
  type Row = [string, string, string, string, string, string | null, string | null, DbServiceTicket["channel"], number, number, number | null, number, number | null, boolean];
  const rows: Row[] = [
    ["VPN 접속이 자꾸 끊깁니다", ITSM.incident, ITSM.received, "pr-high", "u-minji", null, "g-sd-l1", "portal", -20 * MIN, 10 * MIN, null, 3 * HOUR, null, false],
    ["회계 서버 응답 없음", ITSM.incident, ITSM.handling, "pr-urgent", "u-doyoon", "u-junho", "g-sd-infra", "agent", -3 * HOUR, -165 * MIN, -170 * MIN, -HOUR, null, true],
    ["모니터 추가 요청", ITSM.serviceRequest, ITSM.waiting, "pr-normal", "u-minji", "u-haneul", "g-sd-l1", "portal", -DAY, -20 * HOUR, -22 * HOUR, 2 * DAY, -2 * HOUR, false],
    ["메일 용량 증설", ITSM.serviceRequest, ITSM.resolved, "pr-normal", "u-doyoon", "u-haneul", "g-sd-l1", "portal", -3 * DAY, -2 * DAY, -70 * HOUR, DAY, null, false],
    ["프린터 용지 걸림", ITSM.incident, ITSM.received, "pr-low", "u-doyoon", null, null, "portal", -5 * MIN, 4 * HOUR, null, DAY, null, false],
    ["사내 위키 로그인 오류", ITSM.incident, ITSM.closed, "pr-normal", "u-minji", "u-junho", "g-sd-l1", "agent", -7 * DAY, -6 * DAY, -167 * HOUR, -5 * DAY, null, false],
  ];
  const closed = new Set<string>([ITSM.resolved, ITSM.closed]);
  const workPackages: DbWp[] = [];
  const journals: DbJournal[] = [];
  const serviceTickets: DbServiceTicket[] = [];
  rows.forEach(([subject, typeId, statusId, priorityId, requesterId, assigneeId, groupId, channel, created, respDue, responded, resDue, paused, breached], i) => {
    const number = firstNumber + i;
    const id = `wp-${number}`;
    const createdAt = iso(now + created);
    workPackages.push({
      id, number, projectId, typeId, statusId, priorityId, subject, descriptionHtml: "", authorId: requesterId, assigneeId,
      responsibleId: null, versionId: null, categoryId: null, parentId: null, startDate: null, dueDate: null, duration: null,
      ignoreNonWorkingDays: false, scheduleManually: true, workHours: null, remainingHours: null, doneRatio: closed.has(statusId) ? 100 : 0,
      lockVersion: 0, createdAt, updatedAt: createdAt,
    });
    // 작업마다 "만듦" 저널 1건(이력 화면이 비지 않게)
    journals.push({ id: `j-sd-${i + 1}`, wpId: id, version: 1, userId: requesterId, notesHtml: "", isInternal: false, changes: { _created: [null, true] }, cause: null, createdAt, updatedAt: createdAt });
    serviceTickets.push({
      workPackageId: id, requesterId, channel, impact: 2, urgency: 2, assignmentGroupId: groupId,
      slaPolicyId: slaPolicies.find((p) => p.typeId === typeId && p.priorityId === priorityId)?.id ?? null,
      firstResponseDueAt: iso(now + respDue), firstRespondedAt: responded === null ? null : iso(now + responded),
      resolveDueAt: iso(now + resDue), resolvedAt: closed.has(statusId) ? iso(now + created + DAY) : null,
      pausedSince: paused === null ? null : iso(now + paused), pausedTotalSeconds: 0,
      responseBreached: false, resolveBreached: breached, responseWarned: false, resolveWarned: false,
      requesterSeenAt: null, requesterMailedAt: t0, requesterMailedStatusId: statusId, createdAt, updatedAt: createdAt,
    });
  });

  /* ---------- ITSM 2단계 (V1005): catalog and one request awaiting approval ---------- */

  // 항목 양식의 필드: 서비스 요청 유형에서 쓰고 서비스 데스크에만 켠다. 포털 공통 양식에는 나오지 않고 항목 양식으로만 보인다.
  const customFields: DbCustomField[] = [
    {
      id: "cf-laptop", name: "노트북 기종", format: "list", isRequired: false, isForAll: false, isFilterable: true, isMultiValue: false, defaultValue: null,
      minLength: null, maxLength: null, regexp: null, position: 10, typeIds: [ITSM.serviceRequest], portalVisible: false,
      options: [{ id: "cfo-laptop-14", value: "14인치 표준" }, { id: "cfo-laptop-16", value: "16인치 개발용" }],
    },
  ];
  const projectCustomFields = [{ projectId, customFieldId: "cf-laptop" }];
  const catalogCategories: DbCatalogCategory[] = [
    { id: "cc-hardware", projectId, name: "하드웨어", position: 1 },
    { id: "cc-account", projectId, name: "계정·권한", position: 2 },
  ];
  const item = (id: string, categoryId: string, name: string, description: string, groupId: string, position: number,
    fields: DbCatalogItem["fields"], steps: DbCatalogItem["steps"]): DbCatalogItem => ({
    id, projectId, categoryId, name, descriptionHtml: `<p>${description}</p>`, typeId: ITSM.serviceRequest, defaultPriorityId: "pr-normal",
    assignmentGroupId: groupId, isActive: true, position, fields, steps,
  });
  const catalogItems: DbCatalogItem[] = [
    item("ci-laptop", "cc-hardware", "노트북 신청", "새 노트북이 필요할 때 신청합니다. 관리자와 그룹 책임자의 승인을 거칩니다.", "g-sd-l1", 1,
      [{ customFieldId: "cf-laptop", position: 1, isRequired: true }], [{ stepNo: 1, kind: "requester_manager" }, { stepNo: 2, kind: "group_lead" }]),
    item("ci-vpn", "cc-account", "VPN 계정", "원격 근무용 VPN 계정을 신청합니다. 관리자의 승인이 필요합니다.", "g-sd-infra", 2, [], [{ stepNo: 1, kind: "requester_manager" }]),
    item("ci-folder", "cc-account", "공유 폴더 권한", "부서 공유 폴더의 읽기·쓰기 권한을 신청합니다. 승인 없이 바로 접수됩니다.", "g-sd-l1", 3, [], []),
  ];

  // 승인 대기 요청 1건: 최민지의 노트북 신청. 1단계는 alex.kim(요청자 관리자)이 결정할 차례(이미 독촉까지 나가 승인 지연), 2단계는 박준호(그룹 책임자)가 기다림.
  const number = firstNumber + rows.length;
  const id = `wp-${number}`;
  const createdAt = iso(now - 2 * HOUR);
  workPackages.push({
    id, number, projectId, typeId: ITSM.serviceRequest, statusId: ITSM.awaitingApproval, priorityId: "pr-normal", subject: "노트북 신청",
    descriptionHtml: "<p>개발용 노트북이 필요합니다.</p>", authorId: "u-minji", assigneeId: null, responsibleId: null, versionId: null, categoryId: null,
    parentId: null, startDate: null, dueDate: null, duration: null, ignoreNonWorkingDays: false, scheduleManually: true, workHours: null,
    remainingHours: null, doneRatio: 0, customValues: { "cf-laptop": "cfo-laptop-16" }, lockVersion: 0, createdAt, updatedAt: createdAt,
  });
  journals.push({ id: "j-sd-approval", wpId: id, version: 1, userId: "u-minji", notesHtml: "", isInternal: false, changes: { _created: [null, true] }, cause: null, createdAt, updatedAt: createdAt });
  serviceTickets.push({
    workPackageId: id, requesterId: "u-minji", channel: "portal", impact: 2, urgency: 2, assignmentGroupId: "g-sd-l1", catalogItemId: "ci-laptop",
    slaPolicyId: "sla-request-normal", firstResponseDueAt: iso(now + 2 * HOUR), firstRespondedAt: null, resolveDueAt: iso(now + 38 * HOUR), resolvedAt: null,
    pausedSince: createdAt, pausedTotalSeconds: 0, responseBreached: false, resolveBreached: false, responseWarned: false, resolveWarned: false,
    requesterSeenAt: null, requesterMailedAt: t0, requesterMailedStatusId: ITSM.awaitingApproval, createdAt, updatedAt: createdAt,
  });
  const ticketApprovals: DbTicketApproval[] = [
    { id: "ta-1", wpId: id, stepNo: 1, kind: "requester_manager", approverId: "u-alex", decision: "pending", skipReason: null, decidedAt: null, comment: "", pendingSince: t0, remindedAt: t0, createdAt },
    { id: "ta-2", wpId: id, stepNo: 2, kind: "group_lead", approverId: "u-junho", decision: "waiting", skipReason: null, decidedAt: null, comment: "", pendingSince: null, remindedAt: null, createdAt },
  ];

  return {
    types, statuses, roles, workflows, serviceCalendars, project, groups, members, slaPolicies, priorityMatrix, workPackages, journals, serviceTickets,
    managerOf, customFields, projectCustomFields, catalogCategories, catalogItems, ticketApprovals,
  };
}
