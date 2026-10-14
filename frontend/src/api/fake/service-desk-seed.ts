// 서비스 데스크 seed for the fake layer (no imports of the database: seed.ts builds it). The same rows as the server's
// V20__service_desk.sql (types, statuses, 요청자 role, workflows, calendars) and V1004__dev_service_desk.sql (the
// service-desk project, groups, members, policies, matrix and six tickets), with readable ids instead of uuids.
import type { Id, IsoDateTime } from "../types";
import type { DbJournal, DbRole, DbStatus, DbType, DbWorkflow, DbWp } from "./db";

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
  requesterMailedAt: IsoDateTime | null;
  requesterMailedStatusId: Id | null;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
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
  const groups = [
    { id: "g-sd-l1", name: "1선 지원", memberIds: ["u-junho", "u-haneul"] },
    { id: "g-sd-infra", name: "인프라", memberIds: ["u-alex"] },
  ];
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

  return { types, statuses, roles, workflows, serviceCalendars, project, groups, members, slaPolicies, priorityMatrix, workPackages, journals, serviceTickets };
}
