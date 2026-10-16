// Seed data for the fake data layer. Written for openPMS (a fictional internal team), not taken from the
// original app's demo content. Roles, types, statuses and priorities match replica/seed.sql.

import type { Db, DbJournal, DbWp } from "./db";
import { seedTime } from "./time-seed";
import { CICD, seedCicd } from "./cicd-seed";
import type { CustomField, MyPageWidget, QuerySpec } from "../types";

const P_MANAGER = [
  "wp.share",
  "project.view", "project.edit", "project.archive", "project.manage_modules", "project.manage_members",
  "project.view_members", "project.manage_versions", "project.manage_categories", "project.manage_types",
  "project.manage_custom_fields",
  "wp.view", "wp.create", "wp.edit", "wp.delete", "wp.move", "wp.attach", "wp.comment", "wp.edit_own_comments",
  "wp.edit_comments", "wp.view_internal_comments", "wp.add_internal_comments", "wp.manage_relations",
  "wp.manage_subtasks", "wp.assign_versions", "wp.view_watchers", "wp.add_watchers", "wp.delete_watchers", "wp.export",
  "query.save", "query.manage_public", "board.view", "board.manage", "time.log", "time.view", "time.manage",
  "cicd.view", "cicd.link", "cicd.manage",
];
const P_MEMBER = [
  "project.view", "project.view_members",
  "wp.view", "wp.create", "wp.edit", "wp.move", "wp.attach", "wp.comment", "wp.edit_own_comments",
  "wp.manage_relations", "wp.manage_subtasks", "wp.assign_versions", "wp.view_watchers", "wp.add_watchers", "wp.export",
  "query.save", "board.view", "board.manage", "time.log", "time.view",
  "cicd.view", "cicd.link",
];
const P_VIEWER = ["project.view", "project.view_members", "wp.view", "wp.comment", "wp.edit_own_comments", "wp.view_watchers", "wp.export", "query.save", "board.view", "cicd.view"];
const P_NON_MEMBER = ["project.view", "wp.view", "board.view"];

export const DEFAULT_COLUMNS: QuerySpec["columns"] = ["number", "subject", "type", "status", "assignee", "priority", "dueDate"];

const prefs = { commentsNewestFirst: false, warnOnUnsaved: true, keyboardShortcuts: true, immediateOnMention: true, digestEnabled: true, digestTimes: ["08:00"], remindersPausedUntil: null as string | null, myPageWidgets: ["assigned", "created"] as MyPageWidget[] };

export function buildSeed(schema: number): Db {
  const t0 = "2026-09-14T00:30:00Z";
  const iso = (s: string) => new Date(s).toISOString();

  const users = [
    { id: "u-seoyeon", email: "seoyeon.kim@openpms.local", name: "김서연", isAdmin: true },
    { id: "u-junho", email: "junho.park@openpms.local", name: "박준호", isAdmin: false },
    { id: "u-haneul", email: "haneul.lee@openpms.local", name: "이하늘", isAdmin: false },
    { id: "u-minji", email: "minji.choi@openpms.local", name: "최민지", isAdmin: false },
    { id: "u-doyoon", email: "doyoon.han@openpms.local", name: "한도윤", isAdmin: false },
    { id: "u-alex", email: "alex.kim@openpms.local", name: "Alex Kim", isAdmin: false },
    { id: "u-locked", email: "former.staff@openpms.local", name: "전 직원", isAdmin: false, status: "locked" as const },
  ].map((u) => ({ password: "openpms!", status: "active" as const, locale: "ko" as const, timeZone: "Asia/Seoul", prefs: { ...prefs, digestTimes: [...prefs.digestTimes], myPageWidgets: [...prefs.myPageWidgets] }, createdAt: t0, lastLoginAt: null as string | null, ...u }));

  const roles = [
    { id: "r-manager", name: "프로젝트 관리자", scope: "project" as const, builtin: null, permissions: P_MANAGER },
    { id: "r-member", name: "멤버", scope: "project" as const, builtin: null, permissions: P_MEMBER },
    { id: "r-viewer", name: "참관자", scope: "project" as const, builtin: null, permissions: P_VIEWER },
    { id: "r-nonmember", name: "비멤버", scope: "project" as const, builtin: "non_member" as const, permissions: P_NON_MEMBER },
    // V26: 개발 리더 = 멤버 + cicd.manage; CI/CD 연동 관리자는 관리 › CI/CD 연동만
    { id: CICD.devLeadRole, name: "개발 리더", scope: "project" as const, builtin: null, permissions: [...P_MEMBER, "cicd.manage"] },
    { id: CICD.cicdAdminRole, name: "CI/CD 연동 관리자", scope: "global" as const, builtin: null, permissions: ["global.manage_cicd"] },
    { id: "r-creator", name: "프로젝트 생성자", scope: "global" as const, builtin: null, permissions: ["global.create_projects"] },
  ];

  const types = [
    { id: "t-task", name: "작업", color: "#1f5fc4", position: 1, isMilestone: false, isDefault: true, isInRoadmap: true },
    { id: "t-milestone", name: "마일스톤", color: "#277a32", position: 2, isMilestone: true, isDefault: true, isInRoadmap: true },
    { id: "t-summary", name: "요약", color: "#b24d0c", position: 3, isMilestone: false, isDefault: true, isInRoadmap: true },
    { id: "t-feature", name: "기능", color: "#7041c4", position: 4, isMilestone: false, isDefault: false, isInRoadmap: true },
    { id: "t-bug", name: "버그", color: "#c02c39", position: 5, isMilestone: false, isDefault: false, isInRoadmap: false },
  ];
  const statuses = [
    { id: "s-new", name: "신규", color: "#0b7575", position: 1, isDefault: true, isClosed: false, isReadonly: false, defaultDoneRatio: 0, excludedFromTotals: false },
    { id: "s-progress", name: "진행 중", color: "#7041c4", position: 2, isDefault: false, isClosed: false, isReadonly: false, defaultDoneRatio: 50, excludedFromTotals: false },
    { id: "s-review", name: "검토 중", color: "#975800", position: 3, isDefault: false, isClosed: false, isReadonly: false, defaultDoneRatio: 80, excludedFromTotals: false },
    { id: "s-hold", name: "보류", color: "#5f6873", position: 4, isDefault: false, isClosed: false, isReadonly: false, defaultDoneRatio: 0, excludedFromTotals: false },
    { id: "s-done", name: "완료", color: "#277a32", position: 5, isDefault: false, isClosed: true, isReadonly: false, defaultDoneRatio: 100, excludedFromTotals: false },
    { id: "s-cancel", name: "취소", color: "#c02c39", position: 6, isDefault: false, isClosed: true, isReadonly: true, defaultDoneRatio: 0, excludedFromTotals: true },
  ];
  const priorities = [
    { id: "pr-low", name: "낮음", color: "#5f6873", position: 1, isDefault: false, isActive: true },
    { id: "pr-normal", name: "보통", color: "#1f5fc4", position: 2, isDefault: true, isActive: true },
    { id: "pr-high", name: "높음", color: "#975800", position: 3, isDefault: false, isActive: true },
    { id: "pr-urgent", name: "긴급", color: "#c02c39", position: 4, isDefault: false, isActive: true },
  ];
  const workflows = types.flatMap((t) =>
    ["r-manager", "r-member"].flatMap((roleId) =>
      statuses.flatMap((a) => statuses.filter((b) => b.id !== a.id).map((b) => ({ typeId: t.id, roleId, from: a.id, to: b.id }))),
    ),
  );
  // Members may not reopen a cancelled item; only managers can. Gives the workflow rule something to show.
  const wf = workflows.filter((w) => !(w.roleId === "r-member" && w.from === "s-cancel"));

  const allModules = ["work_packages", "gantt", "boards", "calendar", "team_planner", "time_tracking"] as const;
  type SeedModule = typeof allModules[number] | "cicd";
  const defaultTypeIds = ["t-task", "t-milestone", "t-summary"];
  const projects = [
    {
      id: "p-web", identifier: "web-platform", name: "웹 플랫폼 개편",
      descriptionHtml: "<p>고객 포털과 사내 관리 화면을 새 디자인 시스템으로 옮기고 3분기 안에 출시합니다.</p>",
      status: "on_track" as const, statusExplanation: "API 연동이 일정대로 진행 중입니다. 접근성 점검은 다음 주에 시작합니다.",
      isPublic: false, parentId: null, enabledModules: [...allModules, "cicd"] as SeedModule[], typeIds: ["t-task", "t-milestone", "t-summary", "t-feature", "t-bug"],
    },
    {
      id: "p-mobile", identifier: "mobile-app", name: "모바일 앱 2.0",
      descriptionHtml: "<p>푸시 알림과 오프라인 보기를 넣은 2.0 베타를 10월 말에 배포합니다.</p>",
      status: "at_risk" as const, statusExplanation: "iOS 로그인 오류 때문에 베타 일정이 늦어질 수 있습니다.",
      isPublic: false, parentId: null, enabledModules: ["work_packages", "gantt", "boards", "time_tracking", "cicd"] as SeedModule[], typeIds: ["t-task", "t-milestone", "t-summary", "t-bug"],
    },
    {
      id: "p-infra", identifier: "infra", name: "사내 인프라",
      descriptionHtml: "<p>사내 네트워크, 인증서, 백업을 관리합니다. 전 직원이 열람할 수 있습니다.</p>",
      status: "on_track" as const, statusExplanation: "",
      isPublic: true, parentId: null, enabledModules: ["work_packages", "gantt", "time_tracking"] as typeof allModules[number][], typeIds: defaultTypeIds,
    },
    {
      id: "p-backup", identifier: "infra-backup", name: "백업 체계 개선",
      descriptionHtml: "<p>백업 주기와 복원 절차를 정비합니다.</p>",
      status: null, statusExplanation: "",
      isPublic: false, parentId: "p-infra", enabledModules: ["work_packages", "gantt", "time_tracking"] as typeof allModules[number][], typeIds: defaultTypeIds,
    },
    {
      id: "p-legacy", identifier: "legacy-intranet", name: "구 인트라넷 정리",
      descriptionHtml: "<p>종료된 인트라넷의 자료를 이관했습니다.</p>",
      status: "off_track" as const, statusExplanation: "",
      isPublic: false, parentId: null, enabledModules: ["work_packages"] as typeof allModules[number][], typeIds: defaultTypeIds,
      archivedAt: iso("2026-06-30T09:00:00+09:00"),
    },
  ].map((p) => ({ isTemplate: false, archivedAt: null as string | null, createdAt: t0, updatedAt: t0, latestActivityAt: t0, ...p }));

  let mid = 0;
  const m = (projectId: string, userId: string, roleId: string) => ({ id: `m-${++mid}`, projectId, userId, groupId: null as string | null, roleIds: [roleId], createdAt: t0 });
  const members = [
    m("p-web", "u-seoyeon", "r-manager"), m("p-web", "u-junho", "r-member"), m("p-web", "u-haneul", "r-member"),
    m("p-web", "u-minji", "r-viewer"), m("p-web", "u-alex", "r-member"),
    m("p-mobile", "u-seoyeon", "r-manager"), m("p-mobile", "u-doyoon", "r-member"), m("p-mobile", "u-junho", "r-member"),
    m("p-infra", "u-haneul", "r-manager"), m("p-backup", "u-haneul", "r-manager"), m("p-backup", "u-seoyeon", "r-member"),
    m("p-legacy", "u-seoyeon", "r-manager"),
    // group membership: everyone in 인프라 담당 can read the mobile project
    { id: `m-${++mid}`, projectId: "p-mobile", userId: null as string | null, groupId: "g-infra" as string | null, roleIds: ["r-viewer"], createdAt: t0 },
  ];

  // V1010: 박준호는 웹 플랫폼에서 개발 리더 역할을 하나 더 가진다
  members.find((x) => x.projectId === "p-web" && x.userId === "u-junho")!.roleIds.push(CICD.devLeadRole);

  const versions = [
    { id: "v-q3", projectId: "p-web", name: "2026 3분기", description: "고객 포털 1차 출시", status: "open" as const, startDate: "2026-07-01", endDate: "2026-10-30" },
    { id: "v-q4", projectId: "p-web", name: "2026 4분기", description: "", status: "open" as const, startDate: "2026-11-02", endDate: "2026-12-31" },
    { id: "v-beta", projectId: "p-mobile", name: "2.0 베타", description: "", status: "open" as const, startDate: null, endDate: "2026-10-30" },
  ];

  // number, project, type, status, priority, subject, author, assignee, parent#, start, due, opts
  type Row = [number, string, string, string, string, string, string, string | null, number | null, string | null, string | null, Partial<DbWp>?];
  const rows: Row[] = [
    [1, "p-web", "t-summary", "s-progress", "pr-normal", "3분기 릴리스 준비", "u-seoyeon", "u-seoyeon", null, null, null, { scheduleManually: false, versionId: "v-q3" }],
    [2, "p-web", "t-task", "s-done", "pr-normal", "요구사항 정리", "u-seoyeon", "u-junho", 1, "2026-09-28", "2026-10-02", { versionId: "v-q3", workHours: 24, remainingHours: 0, doneRatio: 100 }],
    [3, "p-web", "t-task", "s-progress", "pr-high", "화면 설계 검토", "u-seoyeon", "u-haneul", 1, "2026-10-05", "2026-10-08", { versionId: "v-q3", categoryId: "c-design", workHours: 16, remainingHours: 6, doneRatio: 60 }],
    [4, "p-web", "t-task", "s-progress", "pr-normal", "결제 API 연동", "u-seoyeon", "u-junho", 1, "2026-10-05", "2026-10-16", { versionId: "v-q3", categoryId: "c-be", scheduleManually: false, workHours: 40, remainingHours: 28, doneRatio: 30 }],
    [5, "p-web", "t-task", "s-new", "pr-normal", "릴리스 노트 초안 작성", "u-seoyeon", "u-seoyeon", 1, "2026-10-19", "2026-10-21", { versionId: "v-q3", scheduleManually: false, workHours: 8 }],
    [6, "p-web", "t-milestone", "s-new", "pr-normal", "3분기 릴리스", "u-seoyeon", "u-seoyeon", 1, "2026-10-23", "2026-10-23", { versionId: "v-q3", scheduleManually: false }],
    [7, "p-web", "t-summary", "s-new", "pr-normal", "접근성 개선", "u-seoyeon", "u-alex", null, null, null, { scheduleManually: false, versionId: "v-q4" }],
    [8, "p-web", "t-bug", "s-review", "pr-high", "로그인 화면 글자 대비 부족", "u-haneul", "u-alex", 7, "2026-10-07", "2026-10-12", { categoryId: "c-fe" }],
    [9, "p-web", "t-feature", "s-new", "pr-normal", "키보드만으로 보드 카드 옮기기", "u-alex", "u-haneul", 7, "2026-10-13", "2026-10-22", {}],
    [10, "p-web", "t-task", "s-hold", "pr-low", "스크린리더 점검 일정 잡기", "u-alex", null, 7, null, null, {}],
    [11, "p-web", "t-bug", "s-new", "pr-urgent", "검색 결과가 날짜순으로 정렬되지 않음", "u-minji", "u-junho", null, "2026-10-06", "2026-10-08", {}],
    [12, "p-web", "t-feature", "s-new", "pr-normal", "알림 메일 하루 요약", "u-seoyeon", "u-alex", null, "2026-10-26", "2026-11-06", { versionId: "v-q4" }],
    [13, "p-web", "t-task", "s-done", "pr-normal", "배포 자동화 스크립트 정리", "u-junho", "u-junho", null, "2026-09-21", "2026-09-25", { doneRatio: 100 }],
    [14, "p-web", "t-task", "s-cancel", "pr-low", "구 로그 수집기 이전", "u-junho", null, null, null, null, {}],
    [15, "p-web", "t-feature", "s-new", "pr-low", "다크 모드 지원", "u-alex", null, null, null, null, {}],
    [16, "p-mobile", "t-summary", "s-progress", "pr-normal", "2.0 베타 준비", "u-seoyeon", "u-doyoon", null, null, null, { scheduleManually: false, versionId: "v-beta" }],
    [17, "p-mobile", "t-task", "s-progress", "pr-normal", "푸시 알림 연동", "u-doyoon", "u-doyoon", 16, "2026-10-01", "2026-10-15", { versionId: "v-beta" }],
    [18, "p-mobile", "t-bug", "s-new", "pr-urgent", "iOS에서 로그인 후 화면이 멈춤", "u-junho", "u-junho", 16, "2026-10-08", "2026-10-12", { versionId: "v-beta" }],
    [19, "p-mobile", "t-milestone", "s-new", "pr-normal", "베타 배포", "u-seoyeon", "u-seoyeon", 16, "2026-10-30", "2026-10-30", { versionId: "v-beta" }],
    [20, "p-infra", "t-task", "s-new", "pr-high", "VPN 인증서 갱신", "u-haneul", "u-haneul", null, "2026-10-14", "2026-10-14", {}],
    [21, "p-backup", "t-task", "s-new", "pr-normal", "백업 복원 리허설", "u-haneul", "u-haneul", null, "2026-10-20", "2026-10-22", {}],
  ];

  const byNumber = new Map<number, string>();
  const workPackages: DbWp[] = rows.map(([n, projectId, typeId, statusId, priorityId, subject, authorId, assigneeId, , start, due, opts]) => {
    const id = `wp-${n}`;
    byNumber.set(n, id);
    const created = iso(`2026-09-${String(14 + (n % 14)).padStart(2, "0")}T10:${String(n).padStart(2, "0")}:00+09:00`);
    return {
      id, number: n, projectId, typeId, statusId, priorityId, subject, descriptionHtml: "", authorId, assigneeId,
      responsibleId: null, versionId: null, categoryId: null, parentId: null, startDate: start, dueDate: due, duration: null,
      ignoreNonWorkingDays: false, scheduleManually: true, workHours: null, remainingHours: null, doneRatio: null,
      lockVersion: 0, createdAt: created, updatedAt: created, ...opts,
    };
  });
  // 공동 담당자: 결제 API 연동 is shared by 박준호 (24 h) and 이하늘 (16 h), as in the dev database
  workPackages.find((w) => w.number === 4)!.team = [{ userId: "u-junho", workHours: 24 }, { userId: "u-haneul", workHours: 16 }];
  rows.forEach(([n, , , , , , , , parent]) => {
    if (parent) workPackages.find((w) => w.number === n)!.parentId = byNumber.get(parent)!;
  });
  workPackages.find((w) => w.number === 3)!.descriptionHtml =
    "<p>1차 화면 설계안을 기획·개발과 함께 검토합니다.</p><ul><li>주문 내역 화면</li><li>결제 수단 관리</li><li>모바일 폭(390px) 배치</li></ul>";
  workPackages.find((w) => w.number === 11)!.descriptionHtml =
    "<p>검색 결과에서 정렬을 <strong>최신순</strong>으로 바꿔도 순서가 그대로입니다. 재현: 검색창에 \"정산\" 입력 → 정렬 변경.</p>";

  const cf = (id: string, name: string, format: CustomField["format"], position: number, typeIds: string[], more: Partial<CustomField> = {}): CustomField => ({
    id, name, format, isRequired: false, isForAll: false, isFilterable: true, isMultiValue: false, defaultValue: null, minLength: null, maxLength: null,
    regexp: null, position, options: [], typeIds, ...more,
  });
  const customFields: CustomField[] = [
    cf("cf-client", "고객사", "list", 1, ["t-task", "t-feature", "t-bug"], {
      options: [{ id: "cfo-internal", value: "사내" }, { id: "cfo-hanbit", value: "한빛은행" }, { id: "cfo-mirae", value: "미래상사" }, { id: "cfo-daon", value: "다온커머스" }],
    }),
    cf("cf-reviewer", "검토자", "user", 2, ["t-task", "t-feature", "t-bug"], { isForAll: true }),
    cf("cf-points", "스토리 포인트", "integer", 3, ["t-task", "t-feature"]),
    cf("cf-relnote", "릴리스 노트 필요", "bool", 4, ["t-feature", "t-bug"], { isForAll: true }),
    cf("cf-link", "참고 링크", "link", 5, ["t-task", "t-milestone", "t-summary", "t-feature", "t-bug"], { isForAll: true, isFilterable: false }),
  ];
  const values: Record<number, DbWp["customValues"]> = {
    3: { "cf-client": "cfo-hanbit", "cf-reviewer": "u-junho" },
    4: { "cf-client": "cfo-hanbit", "cf-points": 8 },
    8: { "cf-relnote": false, "cf-link": "https://example.com/a11y-report" },
    9: { "cf-points": 5, "cf-relnote": true },
    11: { "cf-client": "cfo-mirae" },
  };
  for (const [n, v] of Object.entries(values)) workPackages.find((w) => w.number === Number(n))!.customValues = v;

  const relations = [
    { id: "rel-1", fromId: "wp-2", toId: "wp-4", type: "precedes" as const, lag: 0, description: "" },
    { id: "rel-2", fromId: "wp-4", toId: "wp-5", type: "precedes" as const, lag: 0, description: "" },
    { id: "rel-3", fromId: "wp-5", toId: "wp-6", type: "precedes" as const, lag: 0, description: "" },
    { id: "rel-4", fromId: "wp-8", toId: "wp-3", type: "relates" as const, lag: 0, description: "" },
    { id: "rel-5", fromId: "wp-17", toId: "wp-19", type: "precedes" as const, lag: 0, description: "" },
  ];

  let jid = 0;
  const journals: DbJournal[] = workPackages.map((w) => ({
    id: `j-${++jid}`, wpId: w.id, version: 1, userId: w.authorId, notesHtml: "", isInternal: false,
    changes: { _created: [null, true] }, cause: null, createdAt: w.createdAt, updatedAt: w.createdAt,
  }));
  const addJ = (n: number, userId: string, when: string, changes: DbJournal["changes"], notesHtml = "") => {
    const wpId = byNumber.get(n)!;
    const version = journals.filter((j) => j.wpId === wpId).length + 1;
    const j = { id: `j-${++jid}`, wpId, version, userId, notesHtml, isInternal: false, changes, cause: null, createdAt: iso(when), updatedAt: iso(when) };
    journals.push(j);
    const wp = workPackages.find((w) => w.id === wpId)!;
    wp.lockVersion = version - 1;
    wp.updatedAt = j.createdAt;
    return j;
  };
  addJ(3, "u-haneul", "2026-10-05T10:20:00+09:00", { statusId: ["s-new", "s-progress"] });
  const mention = addJ(3, "u-haneul", "2026-10-08T16:40:00+09:00", {},
    '<p><span class="mention" data-user-id="u-seoyeon">@김서연</span> 1차 설계안을 공유 폴더에 올렸습니다. 금요일 전까지 의견 부탁드려요.</p>');
  const assigned = addJ(5, "u-junho", "2026-10-07T11:05:00+09:00", { assigneeId: [null, "u-seoyeon"] });
  addJ(4, "u-junho", "2026-10-06T09:30:00+09:00", { statusId: ["s-new", "s-progress"] });
  const reviewed = addJ(8, "u-alex", "2026-10-08T14:00:00+09:00", { statusId: ["s-progress", "s-review"] });
  addJ(8, "u-alex", "2026-10-08T14:02:00+09:00", {}, "<p>대비를 4.5:1 이상으로 올렸습니다. 확인 부탁드립니다.</p>");
  addJ(11, "u-minji", "2026-10-07T17:30:00+09:00", { priorityId: ["pr-high", "pr-urgent"] }, "<p>고객 문의가 계속 들어와서 긴급으로 올립니다.</p>");

  const notifications = [
    { id: "n-1", recipientId: "u-seoyeon", wpId: "wp-3", journalId: mention.id, actorId: "u-haneul", reason: "mentioned" as const, readAt: null, createdAt: mention.createdAt },
    { id: "n-2", recipientId: "u-seoyeon", wpId: "wp-5", journalId: assigned.id, actorId: "u-junho", reason: "assigned" as const, readAt: null, createdAt: assigned.createdAt },
    { id: "n-3", recipientId: "u-seoyeon", wpId: "wp-8", journalId: reviewed.id, actorId: "u-alex", reason: "watched" as const, readAt: iso("2026-10-08T15:00:00+09:00"), createdAt: reviewed.createdAt },
  ];

  const milestoneSpec: QuerySpec = { v: 1, filters: [{ field: "isMilestone", op: "eq", values: ["true"] }], columns: ["number", "subject", "status", "dueDate", "assignee"], sort: [{ field: "dueDate", dir: "asc" }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: true };
  const myBugsSpec: QuerySpec = { v: 1, filters: [{ field: "type", op: "eq", values: ["t-bug"] }, { field: "status", op: "open" }], columns: DEFAULT_COLUMNS, sort: [{ field: "priority", dir: "desc" }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: true };
  // calendars and team planners are saved queries too (queries.view); the visible window is added at query time
  const calendarSpec: QuerySpec = { v: 1, filters: [{ field: "status", op: "open" }], columns: DEFAULT_COLUMNS, sort: [{ field: "startDate", dir: "asc" }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false };
  const plannerSpec: QuerySpec = {
    ...calendarSpec,
    filters: [{ field: "assignee", op: "eq", values: ["u-seoyeon", "u-junho", "u-haneul", "u-alex"] }, { field: "status", op: "open" }],
  };

  return {
    schema,
    ...seedTime(),
    seq: { wp: 21, id: 1000 },
    sessionUserId: null,
    users,
    roles,
    projects,
    members,
    types,
    statuses,
    priorities,
    workflows: wf,
    versions,
    categories: [
      { id: "c-fe", projectId: "p-web", name: "프론트엔드", defaultAssigneeId: "u-haneul" },
      { id: "c-be", projectId: "p-web", name: "백엔드", defaultAssigneeId: "u-junho" },
      { id: "c-design", projectId: "p-web", name: "디자인", defaultAssigneeId: null },
    ],
    groups: [
      { id: "g-web", name: "웹 플랫폼 팀", memberIds: ["u-junho", "u-haneul", "u-alex"] },
      { id: "g-infra", name: "인프라 담당", memberIds: ["u-haneul"] },
    ],
    workPackages,
    relations,
    journals,
    ...(() => {
      const c = seedCicd();
      return { cicdConnections: c.connections, cicdRepositories: c.repositories, cicdCommits: c.commits, cicdMergeRequests: c.mergeRequests, cicdLinks: c.links };
    })(),
    watchers: [
      { wpId: "wp-8", userId: "u-seoyeon" },
      { wpId: "wp-3", userId: "u-junho" },
    ],
    queries: [
      { id: "q-milestones", projectId: "p-web", ownerId: "u-seoyeon", name: "마일스톤 일정", isPublic: true, view: "table", spec: milestoneSpec },
      { id: "q-mybugs", projectId: "p-web", ownerId: "u-seoyeon", name: "열린 버그", isPublic: false, view: "table", spec: myBugsSpec },
      { id: "q-cal-web", projectId: "p-web", ownerId: "u-seoyeon", name: "팀 일정", isPublic: true, view: "calendar", spec: calendarSpec, createdAt: t0 },
      { id: "q-tp-web", projectId: "p-web", ownerId: "u-seoyeon", name: "웹팀 주간 계획", isPublic: true, view: "team_planner", spec: plannerSpec, createdAt: t0 },
    ],
    queryFavorites: [{ userId: "u-seoyeon", queryId: "q-milestones" }],
    boards: [
      {
        id: "b-status", projectId: "p-web", name: "진행 현황", kind: "status", isPublic: true, createdAt: t0,
        columns: ["s-new", "s-progress", "s-review", "s-done"].map((s, i) => ({ id: `bc-s${i}`, position: i, name: null, valueId: s })),
        order: {},
      },
      {
        id: "b-people", projectId: "p-web", name: "담당자별", kind: "assignee", isPublic: true, createdAt: t0,
        columns: ["u-junho", "u-haneul", "u-alex", "u-seoyeon"].map((u, i) => ({ id: `bc-u${i}`, position: i, name: null, valueId: u })),
        order: {},
      },
    ],
    notifications,
    projectFavorites: [{ userId: "u-seoyeon", projectId: "p-web" }],
    attachments: [],
    shares: [],
    tokens: [],
    outbox: [],
    notificationSettings: [],
    customFields,
    projectCustomFields: [
      { projectId: "p-web", customFieldId: "cf-client" },
      { projectId: "p-web", customFieldId: "cf-points" },
    ],
    nonWorkingDays: [
      { id: "nwd-1", date: "2026-10-03", name: "개천절" },
      { id: "nwd-2", date: "2026-10-09", name: "한글날" },
      { id: "nwd-3", date: "2026-12-25", name: "성탄절" },
    ],
    settings: { workingDays: [1, 2, 3, 4, 5], hoursPerDay: 8, journalAggregationMinutes: 5 },
  };
}
