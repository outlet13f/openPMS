// The fake data layer: implements the Api interface in memory, following the domain rules in
// replica/architecture.md §3 (permissions, workflow, scheduling, journals, notifications).
// Swap for httpApi (src/api/http.ts) once the backend exists; no screen changes.

import type { Api } from "../api";
import type {
  Board,
  BoardColumn,
  Id,
  Me,
  Member,
  NotificationGroup,
  NotificationItem,
  RelationGroups,
  RelationType,
  SavedQuery,
  WorkPackage,
  WorkPackageInput,
  WorkPackagePatch,
} from "../types";
import { ApiError } from "../errors";
import { countWorkingDays, nowIso, todayIso } from "@/lib/dates";
import { db, newId, persist, type DbBoard, type DbJournal, type DbProject, type DbUser, type DbWp } from "./db";
import { allowedStatusIds, can, findProject, findWp, globalPermissions, memberRoleIds, projectUserIds, requirePerm, sessionUser, visibleProjects } from "./permissions";
import { clone, statusRef, toJournal, toProject, toWp, typeRef, priorityRef, userRef, wpRef } from "./dto";
import { propagate, resolveDates, wouldCycle, workCalendar } from "./scheduling";
import { defaultSpec, runQuery } from "./query";
import { DEFAULT_COLUMNS } from "./seed";
import { fieldError, run } from "./util";
import { adminApi, categoriesApi, projectExtras, versionsApi } from "./admin";

/* ---------- plumbing ---------- */

function normalizeOnce() {
  if (db.workPackages.some((w) => w.duration !== null)) return;
  const cal = workCalendar();
  for (const w of db.workPackages) if (w.startDate && w.dueDate) w.duration = countWorkingDays(w.startDate, w.dueDate, cal, w.ignoreNonWorkingDays);
  const leaves = db.workPackages.filter((w) => w.parentId && !db.workPackages.some((k) => k.parentId === w.id));
  propagate(leaves);
  persist();
}
normalizeOnce();


function toMe(u: DbUser): Me {
  return clone({
    id: u.id,
    name: u.name,
    email: u.email,
    status: u.status,
    isAdmin: u.isAdmin,
    locale: u.locale,
    timeZone: u.timeZone,
    globalPermissions: globalPermissions(u),
    preferences: u.prefs,
  });
}

const statusOf = (w: DbWp) => db.statuses.find((s) => s.id === w.statusId)!;
const projectOf = (w: DbWp) => db.projects.find((p) => p.id === w.projectId)!;
const isMember = (userId: string, projectId: string) => memberRoleIds(userId, projectId).length > 0;

/* ---------- journals and notifications (architecture.md §3.6) ---------- */

function writeJournal(wp: DbWp, actor: DbUser, changes: DbJournal["changes"], notesHtml = "", cause: DbJournal["cause"] = null, isInternal = false): DbJournal {
  const now = nowIso();
  const mine = db.journals.filter((j) => j.wpId === wp.id);
  const last = mine[mine.length - 1];
  const windowMs = db.settings.journalAggregationMinutes * 60_000;
  const canMerge =
    last && !notesHtml && !last.notesHtml && !cause && !last.cause && last.userId === actor.id && !last.changes._created &&
    Date.now() - new Date(last.createdAt).getTime() < windowMs;
  if (canMerge) {
    for (const [k, [from, to]] of Object.entries(changes)) {
      const prevFrom = last.changes[k]?.[0];
      last.changes[k] = [k in last.changes ? prevFrom : from, to];
      if (last.changes[k][0] === to) delete last.changes[k];
    }
    last.updatedAt = now;
    return last;
  }
  const j: DbJournal = { id: newId("j"), wpId: wp.id, version: mine.length + 1, userId: actor.id, notesHtml, isInternal, changes, cause, createdAt: now, updatedAt: now };
  db.journals.push(j);
  const p = projectOf(wp);
  p.latestActivityAt = now;
  return j;
}

const REASON_RANK = ["mentioned", "assigned", "responsible", "watched"] as const;

function fanOut(wp: DbWp, journal: DbJournal, actor: DbUser, mentioned: string[] = []) {
  const project = projectOf(wp);
  const recipients = new Map<string, (typeof REASON_RANK)[number]>();
  const offer = (userId: string | null, reason: (typeof REASON_RANK)[number]) => {
    if (!userId || userId === actor.id) return;
    const u = db.users.find((x) => x.id === userId && x.status === "active");
    if (!u || !can(u, project, "wp.view")) return; // never notify people who cannot see the item
    if (journal.isInternal && !can(u, project, "wp.view_internal_comments")) return;
    const cur = recipients.get(userId);
    if (!cur || REASON_RANK.indexOf(reason) < REASON_RANK.indexOf(cur)) recipients.set(userId, reason);
  };
  mentioned.forEach((id) => offer(id, "mentioned"));
  offer(wp.assigneeId, "assigned");
  offer(wp.responsibleId, "responsible");
  db.watchers.filter((x) => x.wpId === wp.id).forEach((x) => offer(x.userId, "watched"));
  for (const [recipientId, reason] of recipients) {
    const existing = db.notifications.find((n) => n.journalId === journal.id && n.recipientId === recipientId && !n.readAt);
    if (existing) {
      existing.createdAt = journal.updatedAt;
      continue;
    }
    db.notifications.push({ id: newId("n"), recipientId, wpId: wp.id, journalId: journal.id, actorId: actor.id, reason, readAt: null, createdAt: journal.updatedAt });
  }
}

/** A member row's principal (user or group) for the API. */
function principalOf(m: (typeof db.members)[number]): { id: string; name: string } {
  if (m.groupId) return { id: m.groupId, name: db.groups.find((g) => g.id === m.groupId)?.name ?? "삭제된 그룹" };
  return userRef(m.userId)!;
}

function toMember(m: (typeof db.members)[number]): Member {
  const u = m.userId ? db.users.find((x) => x.id === m.userId) : null;
  return clone({
    id: m.id,
    principal: { ...principalOf(m), kind: m.groupId ? ("group" as const) : ("user" as const), email: u?.email },
    roles: db.roles.filter((r) => m.roleIds.includes(r.id)).map((r) => ({ id: r.id, name: r.name })),
    createdAt: m.createdAt,
  });
}

const mentionsIn = (html: string) => Array.from(html.matchAll(/data-user-id="([^"]+)"/g), (m) => m[1]);

/* ---------- work package writes ---------- */

function applyPatch(user: DbUser, number: number, patch: WorkPackagePatch): WorkPackage {
  const { wp, project } = findWp(user, number);
  if (patch.lockVersion !== wp.lockVersion) {
    throw new ApiError(409, "다른 사람이 먼저 수정했습니다.", "최신 내용을 불러온 뒤 다시 저장하세요.", { current: toWp(wp, user) });
  }
  requirePerm(user, project, "wp.edit", "이 작업을 수정할 권한이 없습니다.");

  const given = Object.entries(patch).filter(([k, v]) => !["lockVersion", "touched"].includes(k) && v !== undefined).map(([k]) => k);
  if (statusOf(wp).isReadonly && given.some((k) => k !== "statusId")) {
    throw new ApiError(422, "읽기 전용 상태입니다.", `'${statusOf(wp).name}' 상태에서는 상태만 바꿀 수 있습니다.`);
  }

  const changes: DbJournal["changes"] = {};
  const next: Partial<DbWp> = {};
  const set = <K extends keyof DbWp>(k: K, v: DbWp[K]) => {
    if (wp[k] !== v) {
      changes[k] = [wp[k], v];
      next[k] = v;
    }
  };

  if (patch.statusId !== undefined && patch.statusId !== wp.statusId) {
    if (!allowedStatusIds(user, wp).includes(patch.statusId)) {
      const from = statusOf(wp).name;
      const to = db.statuses.find((s) => s.id === patch.statusId)?.name ?? "?";
      throw new ApiError(422, "허용되지 않는 상태 전환입니다.", `내 역할로는 '${from}'에서 '${to}'(으)로 바꿀 수 없습니다.`);
    }
    set("statusId", patch.statusId);
  }
  if (patch.subject !== undefined) {
    const s = patch.subject.trim();
    if (!s) throw fieldError("subject", "제목을 입력하세요.");
    if (s.length > 255) throw fieldError("subject", "제목은 255자 이하로 입력하세요.");
    set("subject", s);
  }
  if (patch.descriptionHtml !== undefined) set("descriptionHtml", patch.descriptionHtml);
  if (patch.typeId !== undefined) {
    if (!project.typeIds.includes(patch.typeId)) throw fieldError("type", "이 프로젝트에서 쓰지 않는 유형입니다.");
    set("typeId", patch.typeId);
  }
  if (patch.priorityId !== undefined) {
    if (!db.priorities.some((x) => x.id === patch.priorityId)) throw fieldError("priority", "알 수 없는 우선순위입니다.");
    set("priorityId", patch.priorityId);
  }
  for (const [key, field] of [["assigneeId", "assignee"], ["responsibleId", "responsible"]] as const) {
    const v = patch[key];
    if (v === undefined) continue;
    if (v !== null && !isMember(v, project.id)) throw fieldError(field, "프로젝트 멤버만 지정할 수 있습니다.");
    set(key, v);
  }
  if (patch.versionId !== undefined) {
    requirePerm(user, project, "wp.assign_versions");
    if (patch.versionId && db.versions.find((v) => v.id === patch.versionId)?.projectId !== project.id) throw fieldError("version", "이 프로젝트의 버전이 아닙니다.");
    set("versionId", patch.versionId);
  }
  if (patch.categoryId !== undefined) {
    if (patch.categoryId && db.categories.find((c) => c.id === patch.categoryId)?.projectId !== project.id) throw fieldError("category", "이 프로젝트의 카테고리가 아닙니다.");
    set("categoryId", patch.categoryId);
  }
  if (patch.parentNumber !== undefined) {
    requirePerm(user, project, "wp.manage_subtasks");
    const parent = patch.parentNumber === null ? null : db.workPackages.find((w) => w.number === patch.parentNumber);
    if (patch.parentNumber !== null && !parent) throw fieldError("parent", "상위 작업을 찾을 수 없습니다.");
    if (parent && (parent.id === wp.id || wouldCycle(wp.id, parent.id))) throw fieldError("parent", "자기 자신이나 하위 작업을 상위 작업으로 지정할 수 없습니다.");
    set("parentId", parent?.id ?? null);
  }
  if (patch.scheduleManually !== undefined) set("scheduleManually", patch.scheduleManually);
  if (patch.ignoreNonWorkingDays !== undefined) set("ignoreNonWorkingDays", patch.ignoreNonWorkingDays);
  const dateKeys = ["startDate", "dueDate", "duration"] as const;
  if (dateKeys.some((k) => patch[k] !== undefined)) {
    const hasKids = db.workPackages.some((k) => k.parentId === wp.id);
    if (hasKids && !(patch.scheduleManually ?? wp.scheduleManually)) {
      throw new ApiError(422, "날짜는 하위 작업에서 정해집니다.", "자동 일정인 상위 작업의 날짜는 하위 작업의 날짜를 바꿔서 조정하세요.");
    }
    const r = resolveDates({ ...wp, ...next }, { startDate: patch.startDate, dueDate: patch.dueDate, duration: patch.duration, touched: patch.touched });
    set("startDate", r.startDate);
    set("dueDate", r.dueDate);
    set("duration", r.duration);
  }
  if (patch.workHours !== undefined) set("workHours", patch.workHours);
  if (patch.doneRatio !== undefined) set("doneRatio", patch.doneRatio);
  if (patch.remainingHours !== undefined) set("remainingHours", patch.remainingHours);
  else if ((patch.workHours !== undefined || patch.doneRatio !== undefined) && (next.workHours ?? wp.workHours) !== null) {
    const work = (next.workHours ?? wp.workHours)!;
    const ratio = next.doneRatio ?? wp.doneRatio ?? 0;
    set("remainingHours", Math.round(work * (1 - ratio / 100) * 100) / 100);
  }

  if (!Object.keys(changes).length) return toWp(wp, user);

  const oldParent = wp.parentId;
  Object.assign(wp, next, { lockVersion: wp.lockVersion + 1, updatedAt: nowIso() });
  const journal = writeJournal(wp, user, changes);
  fanOut(wp, journal, user);

  // S7: dates, mode or hierarchy changes ripple to successors and automatic parents
  if ("startDate" in changes || "dueDate" in changes || "parentId" in changes || "scheduleManually" in changes) {
    const seeds = [wp];
    if (oldParent && oldParent !== wp.parentId) {
      const sibling = db.workPackages.find((k) => k.parentId === oldParent);
      if (sibling) seeds.push(sibling);
    }
    for (const c of propagate(seeds)) {
      c.wp.lockVersion += 1;
      c.wp.updatedAt = nowIso();
      writeJournal(c.wp, user, c.changes, "", c.cause);
    }
  }
  return toWp(wp, user);
}

/* ---------- default views (built into the app, not stored) ---------- */

function defaultViews(projectId: Id | null): SavedQuery[] {
  const v = (id: string, name: string, spec = defaultSpec()): SavedQuery => ({ id: `default:${id}`, name, kind: "default", view: "table", spec, favorite: false, projectId, canEdit: false });
  return [
    v("open", "모두 열림"),
    v("assigned", "내게 할당됨", defaultSpec({ filters: [{ field: "assignee", op: "eq", values: ["me"] }, { field: "status", op: "open" }], hierarchy: false })),
    v("created", "내가 만듦", defaultSpec({ filters: [{ field: "author", op: "eq", values: ["me"] }], hierarchy: false })),
    v("recent", "최근 업데이트", defaultSpec({ filters: [], hierarchy: false, sort: [{ field: "updatedAt", dir: "desc" }], columns: [...DEFAULT_COLUMNS.filter((c) => c !== "dueDate"), "updatedAt"] })),
    v("overdue", "기한 지남", defaultSpec({ filters: [{ field: "dueDate", op: "overdue" }], hierarchy: false, sort: [{ field: "dueDate", dir: "asc" }] })),
    v("all", "모든 작업", defaultSpec({ filters: [] })),
  ];
}

/* ---------- boards ---------- */

function boardColumns(user: DbUser, b: DbBoard, project: DbProject): BoardColumn[] {
  const wps = db.workPackages.filter((w) => w.projectId === project.id);
  const order = (w: DbWp) => b.order[w.id] ?? w.number;
  return b.columns
    .sort((x, y) => x.position - y.position)
    .map((c) => {
      let cards: DbWp[] = [];
      let title = c.name ?? "";
      let color: string | null = null;
      if (b.kind === "status" && c.valueId) {
        const s = db.statuses.find((x) => x.id === c.valueId);
        title = s?.name ?? "삭제된 상태";
        color = s?.color ?? null;
        cards = wps.filter((w) => w.statusId === c.valueId);
      } else if (b.kind === "assignee" && c.valueId) {
        title = userRef(c.valueId)?.name ?? "";
        cards = wps.filter((w) => w.assigneeId === c.valueId && !statusOf(w).isClosed);
      } else if (b.kind === "version" && c.valueId) {
        title = db.versions.find((v) => v.id === c.valueId)?.name ?? "";
        cards = wps.filter((w) => w.versionId === c.valueId);
      }
      return { id: c.id, position: c.position, title, color, valueId: c.valueId, cards: cards.sort((x, y) => order(x) - order(y)).map((w) => toWp(w, user)) };
    });
}

function toBoard(user: DbUser, b: DbBoard): Board {
  const project = db.projects.find((p) => p.id === b.projectId)!;
  return { id: b.id, name: b.name, kind: b.kind, isPublic: b.isPublic, createdAt: b.createdAt, projectId: b.projectId, columns: boardColumns(user, b, project), canManage: can(user, project, "board.manage") };
}

/* ---------- notifications ---------- */

const REASON_TEXT: Record<string, (actor: string) => string> = {
  mentioned: (a) => `${a}님이 코멘트에서 나를 언급했습니다`,
  assigned: (a) => `${a}님이 내가 담당한 작업을 바꿨습니다`,
  responsible: (a) => `${a}님이 내가 책임자인 작업을 바꿨습니다`,
  watched: (a) => `${a}님이 주시 중인 작업을 바꿨습니다`,
  date_alert_due: () => "완료일이 다가옵니다",
  date_alert_start: () => "시작일이 다가옵니다",
  date_alert_overdue: () => "기한이 지났습니다",
};

function notificationSummary(n: (typeof db.notifications)[number], user: DbUser): string {
  const actor = userRef(n.actorId)?.name ?? "누군가";
  const j = n.journalId ? db.journals.find((x) => x.id === n.journalId) : null;
  if (n.reason === "assigned" && j?.changes.assigneeId?.[1] === user.id) return `${actor}님이 나를 담당자로 지정했습니다`;
  let text = (REASON_TEXT[n.reason] ?? (() => "작업이 바뀌었습니다"))(actor);
  if (j && n.reason !== "mentioned") {
    const d = toJournal(j, user).details.filter((x) => x.field !== "_created")[0];
    if (d) text += ` · ${d.label}${d.to ? `: ${d.from ?? "없음"} → ${d.to}` : ""}`;
    else if (j.changes._created) text = `${actor}님이 작업을 만들었습니다`;
  }
  return text;
}

/* ---------- the API ---------- */

export const fakeApi: Api = {
  auth: {
    login: (email, password) =>
      run(() => {
        const u = db.users.find((x) => x.email.toLowerCase() === email.trim().toLowerCase());
        if (u?.status === "locked") throw new ApiError(423, "잠긴 계정입니다.", "관리자에게 계정 잠금 해제를 요청하세요.");
        if (!u || u.password !== password || u.status !== "active") throw new ApiError(401, "이메일 또는 비밀번호가 올바르지 않습니다.");
        db.sessionUserId = u.id;
        u.lastLoginAt = nowIso();
        return toMe(u);
      }),
    logout: () =>
      run(() => {
        db.sessionUserId = null;
      }),
    me: () =>
      run(() => {
        const u = db.users.find((x) => x.id === db.sessionUserId && x.status === "active");
        return u ? toMe(u) : null;
      }),
  },

  projects: {
    list: (params = {}) =>
      run(() => {
        const user = sessionUser();
        let list = visibleProjects(user);
        const f = params.filter ?? "active";
        if (f === "archived") list = list.filter((p) => p.archivedAt);
        else list = list.filter((p) => !p.archivedAt);
        if (f === "mine") list = list.filter((p) => isMember(user.id, p.id));
        if (f === "favorite") list = list.filter((p) => db.projectFavorites.some((x) => x.userId === user.id && x.projectId === p.id));
        if (params.status) list = list.filter((p) => p.status === params.status);
        if (params.q) list = list.filter((p) => p.name.toLowerCase().includes(params.q!.toLowerCase()) || p.identifier.includes(params.q!.toLowerCase()));
        list.sort((a, b) => a.name.localeCompare(b.name, "ko"));
        const page = params.page ?? 1;
        const pageSize = params.pageSize ?? 20;
        return { items: list.slice((page - 1) * pageSize, page * pageSize).map((p) => toProject(p, user)), total: list.length, page, pageSize };
      }),
    get: (id) => run(() => toProject(findProject(sessionUser(), id), sessionUser())),
    create: (input) =>
      run(() => {
        const user = sessionUser();
        if (!user.isAdmin && !globalPermissions(user).includes("global.create_projects")) throw new ApiError(403, "프로젝트를 만들 권한이 없습니다.");
        const name = input.name.trim();
        if (!name) throw fieldError("name", "프로젝트 이름을 입력하세요.");
        const identifier = input.identifier.trim();
        if (!/^[a-z][a-z0-9-]{0,99}$/.test(identifier)) throw fieldError("identifier", "영문 소문자로 시작하고 영문 소문자, 숫자, 하이픈만 쓸 수 있습니다.");
        if (db.projects.some((p) => p.identifier === identifier)) throw fieldError("identifier", "이미 사용 중인 식별자입니다.");
        const now = nowIso();
        const p: DbProject = {
          id: newId("p"), identifier, name, descriptionHtml: input.descriptionHtml ?? "", status: null, statusExplanation: "",
          isPublic: !!input.isPublic, isTemplate: false, archivedAt: null, parentId: input.parentId ?? null,
          enabledModules: ["work_packages", "gantt", "boards"], typeIds: db.types.filter((t) => t.isDefault).map((t) => t.id),
          createdAt: now, updatedAt: now, latestActivityAt: now,
        };
        db.projects.push(p);
        db.members.push({ id: newId("m"), projectId: p.id, userId: user.id, groupId: null, roleIds: ["r-manager"], createdAt: now });
        return toProject(p, user);
      }),
    update: (id, patch) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, id);
        requirePerm(user, p, patch.enabledModules ? "project.manage_modules" : "project.edit");
        Object.assign(p, patch, { updatedAt: nowIso() });
        return toProject(p, user);
      }),
    setFavorite: (id, favorite) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, id);
        db.projectFavorites = db.projectFavorites.filter((f) => !(f.userId === user.id && f.projectId === p.id));
        if (favorite) db.projectFavorites.push({ userId: user.id, projectId: p.id });
      }),
    overview: (id) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, id);
        const wps = db.workPackages.filter((w) => w.projectId === p.id);
        const tz = user.timeZone;
        return clone({
          project: toProject(p, user),
          members: db.members
            .filter((m) => m.projectId === p.id)
            .map((m) => ({ user: principalOf(m), roles: db.roles.filter((r) => m.roleIds.includes(r.id)).map((r) => r.name) })),
          countsByStatus: db.statuses.map((s) => ({ status: statusRef(s.id), count: wps.filter((w) => w.statusId === s.id).length })).filter((x) => x.count > 0),
          openCount: wps.filter((w) => !statusOf(w).isClosed).length,
          overdueCount: wps.filter((w) => w.dueDate && !statusOf(w).isClosed && w.dueDate < todayIso(tz)).length,
        });
      }),
    ...projectExtras,
  },

  categories: categoriesApi,
  versions: versionsApi,
  admin: adminApi,

  members: {
    list: (projectId) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, projectId);
        requirePerm(user, p, "project.view_members");
        return db.members
          .filter((m) => m.projectId === p.id)
          .map(toMember)
          .sort((a, b) => a.principal.name.localeCompare(b.principal.name, "ko"));
      }),
    add: (projectId, principalIds, roleIds) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, projectId);
        requirePerm(user, p, "project.manage_members");
        if (!roleIds.length) throw fieldError("roles", "역할을 하나 이상 고르세요.");
        const added = principalIds
          .filter((id) => !db.members.some((m) => m.projectId === p.id && (m.userId === id || m.groupId === id)))
          .map((id) => {
            const isGroup = db.groups.some((g) => g.id === id);
            if (!isGroup && !db.users.some((u) => u.id === id && u.status !== "deleted")) throw new ApiError(404, "사용자나 그룹을 찾을 수 없습니다.");
            return { id: newId("m"), projectId: p.id, userId: isGroup ? null : id, groupId: isGroup ? id : null, roleIds, createdAt: nowIso() };
          });
        db.members.push(...added);
        return added.map(toMember);
      }),
    update: (memberId, roleIds) =>
      run(() => {
        const user = sessionUser();
        const m = db.members.find((x) => x.id === memberId);
        if (!m) throw new ApiError(404, "멤버를 찾을 수 없습니다.");
        const p = findProject(user, m.projectId);
        requirePerm(user, p, "project.manage_members");
        if (!roleIds.length) throw fieldError("roles", "역할을 하나 이상 고르세요.");
        const managers = db.members.filter((x) => x.projectId === p.id && x.roleIds.includes("r-manager"));
        if (managers.length === 1 && managers[0].id === m.id && !roleIds.includes("r-manager")) throw new ApiError(422, "마지막 프로젝트 관리자입니다.", "다른 멤버를 관리자로 지정한 뒤 바꾸세요.");
        m.roleIds = roleIds;
        return toMember(m);
      }),
    remove: (memberId) =>
      run(() => {
        const user = sessionUser();
        const m = db.members.find((x) => x.id === memberId);
        if (!m) throw new ApiError(404, "멤버를 찾을 수 없습니다.");
        const p = findProject(user, m.projectId);
        requirePerm(user, p, "project.manage_members");
        const managers = db.members.filter((x) => x.projectId === p.id && x.roleIds.includes("r-manager"));
        if (managers.length === 1 && managers[0].id === m.id) throw new ApiError(422, "마지막 프로젝트 관리자는 뺄 수 없습니다.");
        db.members = db.members.filter((x) => x.id !== memberId);
      }),
    roles: () => run(() => clone(db.roles.filter((r) => r.scope === "project" && !r.builtin))),
  },

  principals: {
    search: (q, projectId, includeGroups) =>
      run(() => {
        const user = sessionUser();
        let users = db.users.filter((u) => u.status === "active");
        if (projectId) {
          const p = findProject(user, projectId);
          users = users.filter((u) => isMember(u.id, p.id));
        }
        const needle = q.trim().toLowerCase();
        const people = users.filter((u) => !needle || u.name.toLowerCase().includes(needle) || u.email.includes(needle)).slice(0, 20).map((u) => ({ id: u.id, name: u.name, kind: "user" as const }));
        const groups = includeGroups && !projectId ? db.groups.filter((g) => !needle || g.name.toLowerCase().includes(needle)).map((g) => ({ id: g.id, name: g.name, kind: "group" as const })) : [];
        return [...groups, ...people];
      }),
  },

  workPackages: {
    form: (projectId, typeId) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, projectId);
        requirePerm(user, p, "wp.create", "이 프로젝트에 작업을 만들 권한이 없습니다.");
        const types = db.types.filter((t) => p.typeIds.includes(t.id)).sort((a, b) => a.position - b.position);
        const type = types.find((t) => t.id === typeId) ?? types[0];
        const def = db.statuses.find((s) => s.isDefault)!;
        const roleIds = memberRoleIds(user.id, p.id);
        const next = user.isAdmin ? db.statuses.map((s) => s.id) : db.workflows.filter((w) => w.typeId === type.id && w.from === def.id && roleIds.includes(w.roleId)).map((w) => w.to);
        return clone({
          type: typeRef(type.id),
          types: types.map((t) => typeRef(t.id)),
          statuses: db.statuses.filter((s) => s.id === def.id || next.includes(s.id)).sort((a, b) => a.position - b.position).map((s) => statusRef(s.id)),
          priorities: db.priorities.filter((x) => x.isActive).sort((a, b) => a.position - b.position).map((x) => priorityRef(x.id)),
          assignees: projectUserIds(p.id).map((id) => userRef(id)!).sort((a, b) => a.name.localeCompare(b.name, "ko")),
          versions: db.versions.filter((v) => v.projectId === p.id && v.status === "open").map((v) => ({ id: v.id, name: v.name })),
          categories: db.categories.filter((c) => c.projectId === p.id).map((c) => ({ id: c.id, name: c.name, defaultAssigneeId: c.defaultAssigneeId })),
          defaults: { statusId: def.id, priorityId: db.priorities.find((x) => x.isDefault)!.id, descriptionHtml: "" },
          required: ["subject"],
        });
      }),
    create: (projectId, input: WorkPackageInput) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, projectId);
        requirePerm(user, p, "wp.create", "이 프로젝트에 작업을 만들 권한이 없습니다.");
        const subject = input.subject?.trim() ?? "";
        if (!subject) throw fieldError("subject", "제목을 입력하세요.");
        if (subject.length > 255) throw fieldError("subject", "제목은 255자 이하로 입력하세요.");
        if (!p.typeIds.includes(input.typeId)) throw fieldError("type", "이 프로젝트에서 쓰지 않는 유형입니다.");
        if (input.assigneeId && !isMember(input.assigneeId, p.id)) throw fieldError("assignee", "프로젝트 멤버만 지정할 수 있습니다.");
        if (input.statusId !== undefined && !db.statuses.some((x) => x.id === input.statusId)) throw fieldError("status", "알 수 없는 상태입니다.");
        if (input.priorityId !== undefined && !db.priorities.some((x) => x.id === input.priorityId)) throw fieldError("priority", "알 수 없는 우선순위입니다.");
        const category = input.categoryId ? db.categories.find((c) => c.id === input.categoryId) : null;
        if (input.categoryId && category?.projectId !== p.id) throw fieldError("category", "이 프로젝트의 카테고리가 아닙니다.");
        // a category's default assignee fills an empty assignee (settings S25)
        const assigneeId = input.assigneeId ?? (category?.defaultAssigneeId && isMember(category.defaultAssigneeId, p.id) ? category.defaultAssigneeId : null);
        let parentId: string | null = null;
        if (input.parentNumber) {
          requirePerm(user, p, "wp.manage_subtasks");
          const parent = db.workPackages.find((w) => w.number === input.parentNumber);
          if (!parent) throw fieldError("parent", "상위 작업을 찾을 수 없습니다.");
          parentId = parent.id;
        }
        const now = nowIso();
        db.seq.wp += 1;
        const base: DbWp = {
          id: newId("wp"), number: db.seq.wp, projectId: p.id, typeId: input.typeId,
          statusId: input.statusId ?? db.statuses.find((s) => s.isDefault)!.id,
          priorityId: input.priorityId ?? db.priorities.find((x) => x.isDefault)!.id,
          subject, descriptionHtml: input.descriptionHtml ?? "", authorId: user.id, assigneeId,
          responsibleId: input.responsibleId ?? null, versionId: input.versionId ?? null, categoryId: category?.id ?? null, parentId,
          startDate: null, dueDate: null, duration: null, ignoreNonWorkingDays: false, scheduleManually: true,
          workHours: null, remainingHours: null, doneRatio: null, lockVersion: 0, createdAt: now, updatedAt: now,
        };
        Object.assign(base, resolveDates(base, { startDate: input.startDate, dueDate: input.dueDate }));
        db.workPackages.push(base);
        const j = writeJournal(base, user, { _created: [null, true] });
        fanOut(base, j, user);
        for (const c of propagate([base])) {
          c.wp.lockVersion += 1;
          writeJournal(c.wp, user, c.changes, "", c.cause);
        }
        return toWp(base, user);
      }),
    get: (number) => run(() => toWp(findWp(sessionUser(), number).wp, sessionUser())),
    update: (number, patch) => run(() => applyPatch(sessionUser(), number, patch)),
    remove: (number, childrenMode) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        requirePerm(user, project, "wp.delete", "이 작업을 삭제할 권한이 없습니다.");
        const doomed = new Set([wp.id]);
        if (childrenMode === "delete") {
          for (let changed = true; changed; ) {
            changed = false;
            for (const w of db.workPackages) if (w.parentId && doomed.has(w.parentId) && !doomed.has(w.id)) (doomed.add(w.id), (changed = true));
          }
        } else db.workPackages.filter((w) => w.parentId === wp.id).forEach((w) => (w.parentId = wp.parentId));
        db.workPackages = db.workPackages.filter((w) => !doomed.has(w.id));
        db.relations = db.relations.filter((r) => !doomed.has(r.fromId) && !doomed.has(r.toId));
        db.journals = db.journals.filter((j) => !doomed.has(j.wpId));
        db.watchers = db.watchers.filter((x) => !doomed.has(x.wpId));
        db.notifications = db.notifications.filter((x) => !doomed.has(x.wpId));
      }),
    allowedStatuses: (number) =>
      run(() => {
        const user = sessionUser();
        const { wp } = findWp(user, number);
        const ids = allowedStatusIds(user, wp);
        return db.statuses.filter((s) => ids.includes(s.id)).sort((a, b) => a.position - b.position).map((s) => statusRef(s.id));
      }),
    autocomplete: (q, projectId) =>
      run(() => {
        const user = sessionUser();
        const needle = q.trim().replace(/^#/, "").toLowerCase();
        const projects = new Set(visibleProjects(user, "wp.view").map((p) => p.id));
        return db.workPackages
          .filter((w) => projects.has(w.projectId) && (!projectId || w.projectId === projectId || db.projects.find((p) => p.id === w.projectId)?.identifier === projectId))
          .filter((w) => !needle || String(w.number).startsWith(needle) || w.subject.toLowerCase().includes(needle))
          .slice(0, 10)
          .map(wpRef);
      }),
    activities: (number) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        const internalOk = can(user, project, "wp.view_internal_comments");
        return db.journals.filter((j) => j.wpId === wp.id && (!j.isInternal || internalOk)).sort((a, b) => a.version - b.version).map((j) => toJournal(j, user));
      }),
    comment: (number, bodyHtml) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        requirePerm(user, project, "wp.comment", "코멘트를 쓸 권한이 없습니다.");
        if (!bodyHtml.replace(/<[^>]*>/g, "").trim()) throw fieldError("comment", "코멘트를 입력하세요.");
        const j = writeJournal(wp, user, {}, bodyHtml);
        fanOut(wp, j, user, mentionsIn(bodyHtml));
        return toJournal(j, user);
      }),
    editComment: (journalId, bodyHtml) =>
      run(() => {
        const user = sessionUser();
        const j = db.journals.find((x) => x.id === journalId);
        if (!j) throw new ApiError(404, "코멘트를 찾을 수 없습니다.");
        const wp = db.workPackages.find((w) => w.id === j.wpId)!;
        findWp(user, wp.number);
        if (!toJournal(j, user).canEdit) throw new ApiError(403, "이 코멘트를 수정할 권한이 없습니다.");
        j.notesHtml = bodyHtml;
        j.updatedAt = nowIso();
        return toJournal(j, user);
      }),
    setWatching: (number, watching) =>
      run(() => {
        const user = sessionUser();
        const { wp } = findWp(user, number);
        db.watchers = db.watchers.filter((x) => !(x.wpId === wp.id && x.userId === user.id));
        if (watching) db.watchers.push({ wpId: wp.id, userId: user.id });
      }),
    relations: (number) =>
      run((): RelationGroups => {
        const user = sessionUser();
        const { wp } = findWp(user, number);
        const reverse: Record<string, RelationType> = { relates: "relates", precedes: "follows", duplicates: "duplicated", blocks: "blocked", includes: "partof", requires: "required" };
        const withDates = (w: DbWp) => ({ ...wpRef(w), startDate: w.startDate, dueDate: w.dueDate });
        const visible = (w: DbWp | undefined): w is DbWp => !!w && can(user, projectOf(w), "wp.view");
        const relations = db.relations
          .filter((r) => r.fromId === wp.id || r.toId === wp.id)
          .map((r) => {
            const outgoing = r.fromId === wp.id;
            const other = db.workPackages.find((w) => w.id === (outgoing ? r.toId : r.fromId));
            return visible(other) ? { id: r.id, type: (outgoing ? r.type : reverse[r.type]) as RelationType, other: withDates(other), lag: r.lag, description: r.description } : null;
          })
          .filter((x): x is NonNullable<typeof x> => !!x);
        const parent = wp.parentId ? db.workPackages.find((w) => w.id === wp.parentId) : undefined;
        return clone({ relations, parent: visible(parent) ? withDates(parent) : null, children: db.workPackages.filter((w) => w.parentId === wp.id && visible(w)).map(withDates) });
      }),
    addRelation: (number, input) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        const { wp: other, project: otherProject } = findWp(user, input.toNumber);
        requirePerm(user, project, "wp.manage_relations", "관계를 관리할 권한이 없습니다.");
        requirePerm(user, otherProject, "wp.manage_relations", "상대 작업의 관계를 관리할 권한이 없습니다.");
        if (other.id === wp.id) throw fieldError("toNumber", "자기 자신과는 관계를 맺을 수 없습니다.");
        const canonical: Record<RelationType, [DbWp, DbWp, "relates" | "precedes" | "duplicates" | "blocks" | "includes" | "requires"]> = {
          relates: [wp, other, "relates"], precedes: [wp, other, "precedes"], follows: [other, wp, "precedes"],
          duplicates: [wp, other, "duplicates"], duplicated: [other, wp, "duplicates"], blocks: [wp, other, "blocks"],
          blocked: [other, wp, "blocks"], includes: [wp, other, "includes"], partof: [other, wp, "includes"],
          requires: [wp, other, "requires"], required: [other, wp, "requires"],
        };
        const [from, to, type] = canonical[input.type];
        if (db.relations.some((r) => (r.fromId === wp.id && r.toId === other.id) || (r.fromId === other.id && r.toId === wp.id))) {
          throw new ApiError(409, "이미 관계가 있습니다.", "두 작업 사이에는 관계를 하나만 둘 수 있습니다.");
        }
        if (type === "precedes" && wouldCycle(from.id, to.id)) throw new ApiError(422, "순환 관계가 됩니다.", "상위·하위 작업 사이나 이미 이어진 선후행 작업끼리는 선후행 관계를 맺을 수 없습니다.");
        db.relations.push({ id: newId("rel"), fromId: from.id, toId: to.id, type, lag: Math.max(0, input.lag ?? 0), description: "" });
        if (type === "precedes") {
          for (const c of propagate([from])) {
            c.wp.lockVersion += 1;
            writeJournal(c.wp, user, c.changes, "", c.cause);
          }
        }
      }),
    removeRelation: (relationId) =>
      run(() => {
        const user = sessionUser();
        const r = db.relations.find((x) => x.id === relationId);
        if (!r) throw new ApiError(404, "관계를 찾을 수 없습니다.");
        const from = db.workPackages.find((w) => w.id === r.fromId)!;
        requirePerm(user, projectOf(from), "wp.manage_relations", "관계를 관리할 권한이 없습니다.");
        db.relations = db.relations.filter((x) => x.id !== relationId);
      }),
    schedulePreview: (number, patch) =>
      run(() => {
        const user = sessionUser();
        const { wp } = findWp(user, number);
        const draft = { ...wp, scheduleManually: patch.scheduleManually ?? wp.scheduleManually, ignoreNonWorkingDays: patch.ignoreNonWorkingDays ?? wp.ignoreNonWorkingDays };
        const r = resolveDates(draft, { startDate: patch.startDate, dueDate: patch.dueDate, duration: patch.duration, touched: patch.touched });
        const overrides = new Map<string, Partial<DbWp>>([[wp.id, { ...draft, ...r }]]);
        const affected = propagate([wp], { dryRun: true, overrides }).map((c) => {
          const o = overrides.get(c.wp.id)!;
          return { number: c.wp.number, subject: c.wp.subject, startDate: o.startDate ?? null, dueDate: o.dueDate ?? null };
        });
        return { ...r, affected };
      }),
    query: (spec, opts) =>
      run(() => {
        const user = sessionUser();
        const projectId = opts.projectId ? findProject(user, opts.projectId).id : undefined;
        return runQuery(user, spec, { ...opts, projectId });
      }),
    filterOptions: (projectId) =>
      run(() => {
        const user = sessionUser();
        const p = projectId ? findProject(user, projectId) : null;
        const userIds = p ? projectUserIds(p.id) : db.users.filter((u) => u.status === "active").map((u) => u.id);
        return clone({
          statuses: db.statuses.sort((a, b) => a.position - b.position).map((s) => statusRef(s.id)),
          types: db.types.filter((t) => !p || p.typeIds.includes(t.id)).sort((a, b) => a.position - b.position).map((t) => typeRef(t.id)),
          priorities: db.priorities.sort((a, b) => a.position - b.position).map((x) => priorityRef(x.id)),
          users: userIds.map((id) => userRef(id)!).sort((a, b) => a.name.localeCompare(b.name, "ko")),
          versions: db.versions.filter((v) => !p || v.projectId === p.id).map((v) => ({ id: v.id, name: v.name })),
          categories: db.categories.filter((c) => !p || c.projectId === p.id).map((c) => ({ id: c.id, name: c.name })),
        });
      }),
  },

  queries: {
    list: (projectId) =>
      run(() => {
        const user = sessionUser();
        const p = projectId ? findProject(user, projectId) : null;
        const saved = db.queries
          .filter((q) => (q.projectId ?? null) === (p?.id ?? null) && (q.isPublic || q.ownerId === user.id))
          .map((q): SavedQuery => ({
            id: q.id, name: q.name, kind: q.isPublic ? "public" : "private", view: q.view, spec: q.spec, projectId: q.projectId,
            favorite: db.queryFavorites.some((f) => f.userId === user.id && f.queryId === q.id),
            canEdit: q.ownerId === user.id || (!!p && q.isPublic && can(user, p, "query.manage_public")),
          }));
        return clone([...defaultViews(p?.id ?? null), ...saved]);
      }),
    create: (input) =>
      run(() => {
        const user = sessionUser();
        const p = input.projectId ? findProject(user, input.projectId) : null;
        if (p) requirePerm(user, p, input.isPublic ? "query.manage_public" : "query.save", input.isPublic ? "공개 보기를 저장할 권한이 없습니다." : "보기를 저장할 권한이 없습니다.");
        const name = input.name.trim();
        if (!name) throw fieldError("name", "보기 이름을 입력하세요.");
        const q = { id: newId("q"), projectId: p?.id ?? null, ownerId: user.id, name, isPublic: input.isPublic, view: input.view, spec: input.spec };
        db.queries.push(q);
        return clone({ ...q, kind: q.isPublic ? ("public" as const) : ("private" as const), favorite: false, canEdit: true });
      }),
    update: (id, patch) =>
      run(() => {
        const user = sessionUser();
        const q = db.queries.find((x) => x.id === id);
        if (!q || !(q.isPublic || q.ownerId === user.id)) throw new ApiError(404, "보기를 찾을 수 없습니다.");
        const p = q.projectId ? db.projects.find((x) => x.id === q.projectId) : null;
        if (q.ownerId !== user.id && !(p && can(user, p, "query.manage_public"))) throw new ApiError(403, "이 보기를 수정할 권한이 없습니다.");
        if (patch.name !== undefined && !patch.name.trim()) throw fieldError("name", "보기 이름을 입력하세요.");
        Object.assign(q, patch);
        return clone({ id: q.id, name: q.name, kind: q.isPublic ? ("public" as const) : ("private" as const), view: q.view, spec: q.spec, projectId: q.projectId, favorite: db.queryFavorites.some((f) => f.userId === user.id && f.queryId === q.id), canEdit: true });
      }),
    remove: (id) =>
      run(() => {
        const user = sessionUser();
        const q = db.queries.find((x) => x.id === id);
        if (!q) throw new ApiError(404, "보기를 찾을 수 없습니다.");
        const p = q.projectId ? db.projects.find((x) => x.id === q.projectId) : null;
        if (q.ownerId !== user.id && !(p && can(user, p, "query.manage_public"))) throw new ApiError(403, "이 보기를 삭제할 권한이 없습니다.");
        db.queries = db.queries.filter((x) => x.id !== id);
        db.queryFavorites = db.queryFavorites.filter((f) => f.queryId !== id);
      }),
    setFavorite: (id, favorite) =>
      run(() => {
        const user = sessionUser();
        db.queryFavorites = db.queryFavorites.filter((f) => !(f.userId === user.id && f.queryId === id));
        if (favorite) db.queryFavorites.push({ userId: user.id, queryId: id });
      }),
  },

  boards: {
    list: (projectId) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, projectId);
        requirePerm(user, p, "board.view", "보드를 볼 권한이 없습니다.");
        return db.boards.filter((b) => b.projectId === p.id).map((b) => ({ id: b.id, name: b.name, kind: b.kind, isPublic: b.isPublic, createdAt: b.createdAt }));
      }),
    create: (projectId, name, kind) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, projectId);
        requirePerm(user, p, "board.manage", "보드를 만들 권한이 없습니다.");
        if (!name.trim()) throw fieldError("name", "보드 이름을 입력하세요.");
        let values: (string | null)[] = [];
        if (kind === "status") values = db.statuses.filter((s) => !s.isReadonly).sort((a, b) => a.position - b.position).map((s) => s.id);
        else if (kind === "assignee") values = projectUserIds(p.id).slice(0, 6);
        else if (kind === "version") values = db.versions.filter((v) => v.projectId === p.id).map((v) => v.id);
        const b: DbBoard = {
          id: newId("b"), projectId: p.id, name: name.trim(), kind, isPublic: true, createdAt: nowIso(),
          columns: (values.length ? values : [null]).map((v, i) => ({ id: newId("bc"), position: i, name: v ? null : "할 일", valueId: v })),
          order: {},
        };
        db.boards.push(b);
        return toBoard(user, b);
      }),
    get: (id) =>
      run(() => {
        const user = sessionUser();
        const b = db.boards.find((x) => x.id === id);
        const p = b && db.projects.find((x) => x.id === b.projectId);
        if (!b || !p || !can(user, p, "board.view")) throw new ApiError(404, "보드를 찾을 수 없습니다.");
        return toBoard(user, b);
      }),
    remove: (id) =>
      run(() => {
        const user = sessionUser();
        const b = db.boards.find((x) => x.id === id);
        if (!b) throw new ApiError(404, "보드를 찾을 수 없습니다.");
        requirePerm(user, db.projects.find((x) => x.id === b.projectId)!, "board.manage");
        db.boards = db.boards.filter((x) => x.id !== id);
      }),
    move: (id, input) =>
      run(() => {
        const user = sessionUser();
        const b = db.boards.find((x) => x.id === id);
        if (!b) throw new ApiError(404, "보드를 찾을 수 없습니다.");
        const col = b.columns.find((c) => c.id === input.toColumnId);
        if (!col) throw new ApiError(404, "컬럼을 찾을 수 없습니다.");
        const { wp } = findWp(user, input.number);
        if (b.kind === "status" && col.valueId && col.valueId !== wp.statusId) applyPatch(user, wp.number, { lockVersion: input.lockVersion, statusId: col.valueId });
        if (b.kind === "assignee" && col.valueId && col.valueId !== wp.assigneeId) applyPatch(user, wp.number, { lockVersion: input.lockVersion, assigneeId: col.valueId });
        if (b.kind === "version" && col.valueId !== wp.versionId) applyPatch(user, wp.number, { lockVersion: input.lockVersion, versionId: col.valueId });
        // place the card before `beforeNumber` (fractional position between neighbours)
        const project = db.projects.find((p) => p.id === b.projectId)!;
        const cards = boardColumns(user, b, project).find((c) => c.id === col.id)!.cards.filter((c) => c.number !== wp.number);
        const pos = (n: number) => b.order[db.workPackages.find((w) => w.number === n)!.id] ?? n;
        const idx = input.beforeNumber === null ? cards.length : cards.findIndex((c) => c.number === input.beforeNumber);
        const before = idx > 0 ? pos(cards[idx - 1].number) : null;
        const after = idx >= 0 && idx < cards.length ? pos(cards[idx].number) : null;
        b.order[wp.id] = before === null && after === null ? 1 : before === null ? after! - 1 : after === null ? before + 1 : (before + after) / 2;
        return toBoard(user, b);
      }),
  },

  notifications: {
    list: (filter) =>
      run(() => {
        const user = sessionUser();
        const mine = db.notifications.filter((n) => n.recipientId === user.id && (filter === "all" || !n.readAt));
        const groups = new Map<string, NotificationGroup>();
        for (const n of mine.sort((a, b) => b.createdAt.localeCompare(a.createdAt))) {
          const wp = db.workPackages.find((w) => w.id === n.wpId);
          if (!wp || !can(user, projectOf(wp), "wp.view")) continue;
          const g = groups.get(wp.id) ?? { workPackage: wpRef(wp), items: [], unread: 0, latestAt: n.createdAt };
          const item: NotificationItem = { id: n.id, reason: n.reason, read: !!n.readAt, actor: userRef(n.actorId), summary: notificationSummary(n, user), createdAt: n.createdAt };
          g.items.push(item);
          if (!n.readAt) g.unread++;
          groups.set(wp.id, g);
        }
        return clone([...groups.values()]);
      }),
    unreadCount: () =>
      run(() => {
        const user = sessionUser();
        return db.notifications.filter((n) => n.recipientId === user.id && !n.readAt && db.workPackages.some((w) => w.id === n.wpId)).length;
      }),
    markRead: (target) =>
      run(() => {
        const user = sessionUser();
        const now = nowIso();
        const wpId = "workPackageNumber" in target ? db.workPackages.find((w) => w.number === target.workPackageNumber)?.id : undefined;
        for (const n of db.notifications) {
          if (n.recipientId !== user.id || n.readAt) continue;
          if ("all" in target || ("ids" in target && target.ids.includes(n.id)) || (wpId && n.wpId === wpId)) n.readAt = now;
        }
      }),
  },

  calendar: {
    nonWorkingDays: (from, to) =>
      run(() => {
        sessionUser();
        return clone({ workingDays: db.settings.workingDays, days: db.nonWorkingDays.filter((d) => d.date >= from && d.date <= to) });
      }),
  },

  search: {
    suggest: (q) =>
      run(() => {
        const user = sessionUser();
        const needle = q.trim().replace(/^#/, "").toLowerCase();
        if (!needle) return [];
        const projects = visibleProjects(user, "wp.view");
        const pids = new Set(projects.map((p) => p.id));
        const wps = db.workPackages
          .filter((w) => pids.has(w.projectId) && (String(w.number) === needle || w.subject.toLowerCase().includes(needle)))
          .slice(0, 5)
          .map((w) => {
            const p = projectOf(w);
            return { kind: "work_package" as const, title: `#${w.number} ${w.subject}`, subtitle: `${p.name} · ${statusOf(w).name}`, href: `/projects/${p.identifier}/work-packages/${w.number}/activity` };
          });
        const ps = visibleProjects(user)
          .filter((p) => !p.archivedAt && p.name.toLowerCase().includes(needle))
          .slice(0, 3)
          .map((p) => ({ kind: "project" as const, title: p.name, subtitle: p.identifier, href: `/projects/${p.identifier}` }));
        return [...wps, ...ps];
      }),
  },
};

export { resetFakeDb } from "./db";
export const fakeAccounts = () => db.users.map((u) => ({ email: u.email, name: u.name, password: u.password, status: u.status, isAdmin: u.isAdmin }));
