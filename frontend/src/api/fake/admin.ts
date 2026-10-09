// Administration (S27–S36) and project settings (S24–S26) for the fake data layer.
import type { Api } from "../api";
import type { AdminGroup, AdminPriority, AdminRole, AdminStatus, AdminType, AdminUser, Category, Version } from "../types";
import { ApiError } from "../errors";
import { nowIso } from "@/lib/dates";
import { db, newId, type DbUser } from "./db";
import { findProject, globalPermissions, requirePerm, sessionUser } from "./permissions";
import { clone, toProject, userRef } from "./dto";
import { rescheduleAll } from "./scheduling";
import { fieldError, HEX, run } from "./util";

/* ---------- guards ---------- */

function requireAdmin(user: DbUser) {
  if (!user.isAdmin) throw new ApiError(403, "관리자만 할 수 있습니다.");
}
function requireUserManager(user: DbUser) {
  if (!user.isAdmin && !globalPermissions(user).includes("global.manage_users")) throw new ApiError(403, "사용자를 관리할 권한이 없습니다.");
}
const notFound = (what: string) => new ApiError(404, `${what}을(를) 찾을 수 없습니다.`);

function checkName(name: string | undefined, field = "name", max = 100) {
  if (name === undefined) return;
  const n = name.trim();
  if (!n) throw fieldError(field, "이름을 입력하세요.");
  if (n.length > max) throw fieldError(field, `이름은 ${max}자 이하로 입력하세요.`);
}
function checkColor(color: string | undefined) {
  if (color !== undefined && !HEX.test(color)) throw fieldError("color", "색은 #RRGGBB 형식으로 입력하세요.");
}
function unique<T extends { id: string; name: string }>(list: T[], name: string | undefined, selfId?: string) {
  if (name !== undefined && list.some((x) => x.id !== selfId && x.name.trim() === name.trim())) throw fieldError("name", "같은 이름이 이미 있습니다.");
}
function reorderBy<T extends { id: string; position: number }>(list: T[], ids: string[]) {
  if (ids.length !== list.length || !list.every((x) => ids.includes(x.id))) throw new ApiError(422, "순서 목록이 맞지 않습니다.");
  ids.forEach((id, i) => (list.find((x) => x.id === id)!.position = i + 1));
}

/** Writes the journals for the items a calendar change moved. */
function applyCalendarChange(actor: DbUser) {
  const now = nowIso();
  for (const c of rescheduleAll()) {
    c.wp.lockVersion += 1;
    c.wp.updatedAt = now;
    const version = db.journals.filter((j) => j.wpId === c.wp.id).length + 1;
    db.journals.push({ id: newId("j"), wpId: c.wp.id, version, userId: actor.id, notesHtml: "", isInternal: false, changes: c.changes, cause: c.cause, createdAt: now, updatedAt: now });
  }
}

/* ---------- DTOs ---------- */

const toType = (t: (typeof db.types)[number]): AdminType =>
  clone({ ...t, workPackageCount: db.workPackages.filter((w) => w.typeId === t.id).length, projectCount: db.projects.filter((p) => p.typeIds.includes(t.id)).length });
const toStatus = (s: (typeof db.statuses)[number]): AdminStatus => clone({ ...s, workPackageCount: db.workPackages.filter((w) => w.statusId === s.id).length });
const toPriority = (p: (typeof db.priorities)[number]): AdminPriority => clone({ ...p, workPackageCount: db.workPackages.filter((w) => w.priorityId === p.id).length });
const toRole = (r: (typeof db.roles)[number]): AdminRole =>
  clone({ ...r, memberCount: r.scope === "project" ? db.members.filter((m) => m.roleIds.includes(r.id)).length : 0 });
const toUser = (u: DbUser): AdminUser =>
  clone({
    id: u.id, name: u.status === "deleted" ? "삭제된 사용자" : u.name, email: u.email, status: u.status, isAdmin: u.isAdmin, locale: u.locale, timeZone: u.timeZone,
    lastLoginAt: u.lastLoginAt, createdAt: u.createdAt, projectCount: new Set(db.members.filter((m) => m.userId === u.id).map((m) => m.projectId)).size,
  });
const toGroup = (g: (typeof db.groups)[number]): AdminGroup => clone({ id: g.id, name: g.name, members: g.memberIds.map((id) => userRef(id)!).filter(Boolean) });
const toCategory = (c: (typeof db.categories)[number]): Category =>
  clone({ id: c.id, name: c.name, defaultAssignee: userRef(c.defaultAssigneeId), workPackageCount: db.workPackages.filter((w) => w.categoryId === c.id).length });
const toVersion = (v: (typeof db.versions)[number]): Version => {
  const wps = db.workPackages.filter((w) => w.versionId === v.id);
  return clone({
    id: v.id, name: v.name, description: v.description, status: v.status, startDate: v.startDate, endDate: v.endDate,
    workPackageCount: wps.length, closedCount: wps.filter((w) => db.statuses.find((s) => s.id === w.statusId)?.isClosed).length,
  });
};

/** Every permission key the app knows, grouped for the role editor (architecture.md "권한 키"). */
const PERMISSIONS: { key: string; scope: "project" | "global"; group: string }[] = [
  ...["project.view", "project.edit", "project.archive", "project.manage_modules", "project.manage_members", "project.view_members", "project.manage_versions", "project.manage_categories", "project.manage_types", "project.manage_custom_fields"].map((key) => ({ key, scope: "project" as const, group: "project" })),
  ...["wp.view", "wp.create", "wp.edit", "wp.delete", "wp.move", "wp.attach", "wp.comment", "wp.edit_own_comments", "wp.edit_comments", "wp.view_internal_comments", "wp.add_internal_comments", "wp.manage_relations", "wp.manage_subtasks", "wp.assign_versions", "wp.view_watchers", "wp.add_watchers", "wp.delete_watchers", "wp.export"].map((key) => ({ key, scope: "project" as const, group: "wp" })),
  ...["query.save", "query.manage_public"].map((key) => ({ key, scope: "project" as const, group: "query" })),
  ...["board.view", "board.manage"].map((key) => ({ key, scope: "project" as const, group: "board" })),
  ...["global.create_projects", "global.manage_users"].map((key) => ({ key, scope: "global" as const, group: "global" })),
];

/* ---------- project settings ---------- */

export const projectExtras: Pick<Api["projects"], "setTypes" | "archive"> = {
  setTypes: (id, typeIds) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, id);
      requirePerm(user, p, "project.manage_types", "프로젝트 유형을 바꿀 권한이 없습니다.");
      if (!typeIds.length) throw fieldError("types", "유형을 하나 이상 고르세요.");
      const inUse = db.types.filter((t) => !typeIds.includes(t.id) && db.workPackages.some((w) => w.projectId === p.id && w.typeId === t.id));
      if (inUse.length) throw new ApiError(422, "쓰고 있는 유형은 끌 수 없습니다.", `${inUse.map((t) => t.name).join(", ")} 유형의 작업이 이 프로젝트에 있습니다.`);
      p.typeIds = db.types.filter((t) => typeIds.includes(t.id)).map((t) => t.id);
      p.updatedAt = nowIso();
      return toProject(p, user);
    }),
  archive: (id) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, id);
      requirePerm(user, p, "project.archive", "프로젝트를 보관할 권한이 없습니다.");
      p.archivedAt = nowIso();
    }),
};

export const categoriesApi: Api["categories"] = {
  list: (projectId) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, projectId);
      requirePerm(user, p, "wp.view");
      return db.categories.filter((c) => c.projectId === p.id).sort((a, b) => a.name.localeCompare(b.name, "ko")).map(toCategory);
    }),
  create: (projectId, input) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, projectId);
      requirePerm(user, p, "project.manage_categories", "카테고리를 관리할 권한이 없습니다.");
      checkName(input.name);
      unique(db.categories.filter((c) => c.projectId === p.id), input.name);
      const c = { id: newId("c"), projectId: p.id, name: input.name.trim(), defaultAssigneeId: input.defaultAssigneeId };
      db.categories.push(c);
      return toCategory(c);
    }),
  update: (id, input) =>
    run(() => {
      const user = sessionUser();
      const c = db.categories.find((x) => x.id === id);
      if (!c) throw notFound("카테고리");
      requirePerm(user, findProject(user, c.projectId), "project.manage_categories", "카테고리를 관리할 권한이 없습니다.");
      checkName(input.name);
      unique(db.categories.filter((x) => x.projectId === c.projectId), input.name, c.id);
      if (input.name !== undefined) c.name = input.name.trim();
      if (input.defaultAssigneeId !== undefined) c.defaultAssigneeId = input.defaultAssigneeId;
      return toCategory(c);
    }),
  remove: (id) =>
    run(() => {
      const user = sessionUser();
      const c = db.categories.find((x) => x.id === id);
      if (!c) throw notFound("카테고리");
      requirePerm(user, findProject(user, c.projectId), "project.manage_categories", "카테고리를 관리할 권한이 없습니다.");
      db.workPackages.filter((w) => w.categoryId === id).forEach((w) => (w.categoryId = null));
      db.categories = db.categories.filter((x) => x.id !== id);
    }),
};

export const versionsApi: Api["versions"] = {
  list: (projectId) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, projectId);
      requirePerm(user, p, "wp.view");
      return db.versions.filter((v) => v.projectId === p.id).sort((a, b) => (a.endDate ?? "9999").localeCompare(b.endDate ?? "9999")).map(toVersion);
    }),
  create: (projectId, input) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, projectId);
      requirePerm(user, p, "project.manage_versions", "버전을 관리할 권한이 없습니다.");
      checkName(input.name);
      unique(db.versions.filter((v) => v.projectId === p.id), input.name);
      if (input.startDate && input.endDate && input.endDate < input.startDate) throw fieldError("endDate", "종료일은 시작일 이후여야 합니다.");
      const v = { id: newId("v"), projectId: p.id, name: input.name.trim(), description: input.description ?? "", status: input.status ?? "open", startDate: input.startDate ?? null, endDate: input.endDate ?? null };
      db.versions.push(v);
      return toVersion(v);
    }),
  update: (id, input) =>
    run(() => {
      const user = sessionUser();
      const v = db.versions.find((x) => x.id === id);
      if (!v) throw notFound("버전");
      requirePerm(user, findProject(user, v.projectId), "project.manage_versions", "버전을 관리할 권한이 없습니다.");
      checkName(input.name);
      unique(db.versions.filter((x) => x.projectId === v.projectId), input.name, v.id);
      const start = input.startDate !== undefined ? input.startDate : v.startDate;
      const end = input.endDate !== undefined ? input.endDate : v.endDate;
      if (start && end && end < start) throw fieldError("endDate", "종료일은 시작일 이후여야 합니다.");
      Object.assign(v, { ...input, name: input.name?.trim() ?? v.name });
      return toVersion(v);
    }),
  remove: (id) =>
    run(() => {
      const user = sessionUser();
      const v = db.versions.find((x) => x.id === id);
      if (!v) throw notFound("버전");
      requirePerm(user, findProject(user, v.projectId), "project.manage_versions", "버전을 관리할 권한이 없습니다.");
      db.workPackages.filter((w) => w.versionId === id).forEach((w) => (w.versionId = null));
      db.versions = db.versions.filter((x) => x.id !== id);
    }),
};

/* ---------- administration ---------- */

export const adminApi: Api["admin"] = {
  types: {
    list: () => run(() => (requireAdmin(sessionUser()), db.types.slice().sort((a, b) => a.position - b.position).map(toType))),
    create: (input) =>
      run(() => {
        requireAdmin(sessionUser());
        checkName(input.name);
        checkColor(input.color);
        unique(db.types, input.name);
        const t = { id: newId("t"), position: db.types.length + 1, ...input, name: input.name.trim() };
        db.types.push(t);
        return toType(t);
      }),
    update: (id, patch) =>
      run(() => {
        requireAdmin(sessionUser());
        const t = db.types.find((x) => x.id === id);
        if (!t) throw notFound("유형");
        checkName(patch.name);
        checkColor(patch.color);
        unique(db.types, patch.name, id);
        if (patch.isMilestone !== undefined && patch.isMilestone !== t.isMilestone && db.workPackages.some((w) => w.typeId === id))
          throw new ApiError(422, "쓰고 있는 유형은 마일스톤 여부를 바꿀 수 없습니다.", "작업의 날짜 규칙이 달라지기 때문입니다.");
        Object.assign(t, { ...patch, name: patch.name?.trim() ?? t.name });
        return toType(t);
      }),
    remove: (id) =>
      run(() => {
        requireAdmin(sessionUser());
        const n = db.workPackages.filter((w) => w.typeId === id).length;
        if (n) throw new ApiError(409, "쓰고 있는 유형입니다.", `이 유형의 작업 ${n}개를 다른 유형으로 바꾼 뒤 삭제하세요.`);
        db.types = db.types.filter((t) => t.id !== id);
        db.workflows = db.workflows.filter((w) => w.typeId !== id);
        db.projects.forEach((p) => (p.typeIds = p.typeIds.filter((x) => x !== id)));
        db.types.sort((a, b) => a.position - b.position).forEach((t, i) => (t.position = i + 1));
      }),
    reorder: (ids) => run(() => (requireAdmin(sessionUser()), reorderBy(db.types, ids))),
  },

  statuses: {
    list: () => run(() => (requireAdmin(sessionUser()), db.statuses.slice().sort((a, b) => a.position - b.position).map(toStatus))),
    create: (input) =>
      run(() => {
        requireAdmin(sessionUser());
        checkName(input.name);
        checkColor(input.color);
        unique(db.statuses, input.name);
        if (input.defaultDoneRatio < 0 || input.defaultDoneRatio > 100) throw fieldError("defaultDoneRatio", "0에서 100 사이로 입력하세요.");
        if (input.isDefault) db.statuses.forEach((s) => (s.isDefault = false));
        const s = { id: newId("s"), position: db.statuses.length + 1, ...input, name: input.name.trim() };
        db.statuses.push(s);
        return toStatus(s);
      }),
    update: (id, patch) =>
      run(() => {
        requireAdmin(sessionUser());
        const s = db.statuses.find((x) => x.id === id);
        if (!s) throw notFound("상태");
        checkName(patch.name);
        checkColor(patch.color);
        unique(db.statuses, patch.name, id);
        if (patch.defaultDoneRatio !== undefined && (patch.defaultDoneRatio < 0 || patch.defaultDoneRatio > 100)) throw fieldError("defaultDoneRatio", "0에서 100 사이로 입력하세요.");
        if (patch.isDefault === false && s.isDefault) throw new ApiError(422, "기본 상태는 하나 있어야 합니다.", "다른 상태를 기본으로 지정하면 이 상태의 기본 표시가 풀립니다.");
        if (patch.isDefault) db.statuses.forEach((x) => (x.isDefault = false));
        Object.assign(s, { ...patch, name: patch.name?.trim() ?? s.name });
        return toStatus(s);
      }),
    remove: (id) =>
      run(() => {
        requireAdmin(sessionUser());
        const s = db.statuses.find((x) => x.id === id);
        if (!s) throw notFound("상태");
        if (s.isDefault) throw new ApiError(409, "기본 상태는 삭제할 수 없습니다.", "다른 상태를 기본으로 지정한 뒤 삭제하세요.");
        const n = db.workPackages.filter((w) => w.statusId === id).length;
        if (n) throw new ApiError(409, "쓰고 있는 상태입니다.", `이 상태의 작업 ${n}개를 옮긴 뒤 삭제하세요.`);
        db.statuses = db.statuses.filter((x) => x.id !== id);
        db.workflows = db.workflows.filter((w) => w.from !== id && w.to !== id);
        db.boards.forEach((b) => (b.columns = b.columns.filter((c) => !(b.kind === "status" && c.valueId === id))));
        db.statuses.sort((a, b) => a.position - b.position).forEach((x, i) => (x.position = i + 1));
      }),
    reorder: (ids) => run(() => (requireAdmin(sessionUser()), reorderBy(db.statuses, ids))),
  },

  priorities: {
    list: () => run(() => (requireAdmin(sessionUser()), db.priorities.slice().sort((a, b) => a.position - b.position).map(toPriority))),
    create: (input) =>
      run(() => {
        requireAdmin(sessionUser());
        checkName(input.name);
        checkColor(input.color);
        unique(db.priorities, input.name);
        if (input.isDefault) db.priorities.forEach((x) => (x.isDefault = false));
        const p = { id: newId("pr"), position: db.priorities.length + 1, ...input, name: input.name.trim() };
        db.priorities.push(p);
        return toPriority(p);
      }),
    update: (id, patch) =>
      run(() => {
        requireAdmin(sessionUser());
        const p = db.priorities.find((x) => x.id === id);
        if (!p) throw notFound("우선순위");
        checkName(patch.name);
        checkColor(patch.color);
        unique(db.priorities, patch.name, id);
        if (patch.isDefault === false && p.isDefault) throw new ApiError(422, "기본 우선순위는 하나 있어야 합니다.");
        if (patch.isActive === false && (patch.isDefault ?? p.isDefault)) throw new ApiError(422, "기본 우선순위는 숨길 수 없습니다.");
        if (patch.isDefault) db.priorities.forEach((x) => (x.isDefault = false));
        Object.assign(p, { ...patch, name: patch.name?.trim() ?? p.name });
        return toPriority(p);
      }),
    remove: (id) =>
      run(() => {
        requireAdmin(sessionUser());
        const p = db.priorities.find((x) => x.id === id);
        if (!p) throw notFound("우선순위");
        if (p.isDefault) throw new ApiError(409, "기본 우선순위는 삭제할 수 없습니다.");
        const n = db.workPackages.filter((w) => w.priorityId === id).length;
        if (n) throw new ApiError(409, "쓰고 있는 우선순위입니다.", `이 우선순위의 작업 ${n}개를 바꾼 뒤 삭제하세요. 숨기기를 쓰면 새 작업에서만 빠집니다.`);
        db.priorities = db.priorities.filter((x) => x.id !== id);
        db.priorities.sort((a, b) => a.position - b.position).forEach((x, i) => (x.position = i + 1));
      }),
    reorder: (ids) => run(() => (requireAdmin(sessionUser()), reorderBy(db.priorities, ids))),
  },

  workflows: {
    get: (typeId, roleId) =>
      run(() => {
        requireAdmin(sessionUser());
        return db.workflows.filter((w) => w.typeId === typeId && w.roleId === roleId).map((w) => [w.from, w.to] as [string, string]);
      }),
    save: (typeId, roleId, transitions) =>
      run(() => {
        requireAdmin(sessionUser());
        if (!db.types.some((t) => t.id === typeId) || !db.roles.some((r) => r.id === roleId && r.scope === "project")) throw notFound("유형 또는 역할");
        const ids = new Set(db.statuses.map((s) => s.id));
        const clean = transitions.filter(([a, b]) => a !== b && ids.has(a) && ids.has(b));
        db.workflows = db.workflows.filter((w) => !(w.typeId === typeId && w.roleId === roleId));
        const seen = new Set<string>();
        for (const [from, to] of clean) {
          if (seen.has(`${from}>${to}`)) continue;
          seen.add(`${from}>${to}`);
          db.workflows.push({ typeId, roleId, from, to });
        }
      }),
    copy: (from, to) =>
      run(() => {
        requireAdmin(sessionUser());
        const src = db.workflows.filter((w) => w.typeId === from.typeId && w.roleId === from.roleId);
        for (const typeId of to.typeIds)
          for (const roleId of to.roleIds) {
            if (typeId === from.typeId && roleId === from.roleId) continue;
            db.workflows = db.workflows.filter((w) => !(w.typeId === typeId && w.roleId === roleId));
            db.workflows.push(...src.map((w) => ({ typeId, roleId, from: w.from, to: w.to })));
          }
      }),
  },

  roles: {
    list: () => run(() => (requireAdmin(sessionUser()), db.roles.map(toRole))),
    create: (input) =>
      run(() => {
        requireAdmin(sessionUser());
        checkName(input.name);
        unique(db.roles, input.name);
        const known = PERMISSIONS.filter((p) => p.scope === input.scope).map((p) => p.key);
        const r = { id: newId("r"), name: input.name.trim(), scope: input.scope, builtin: null, permissions: input.permissions.filter((k) => known.includes(k)) };
        db.roles.push(r);
        return toRole(r);
      }),
    update: (id, patch) =>
      run(() => {
        requireAdmin(sessionUser());
        const r = db.roles.find((x) => x.id === id);
        if (!r) throw notFound("역할");
        checkName(patch.name);
        unique(db.roles, patch.name, id);
        if (patch.name !== undefined) r.name = patch.name.trim();
        if (patch.permissions) {
          const known = PERMISSIONS.filter((p) => p.scope === r.scope).map((p) => p.key);
          r.permissions = patch.permissions.filter((k) => known.includes(k));
        }
        return toRole(r);
      }),
    remove: (id) =>
      run(() => {
        requireAdmin(sessionUser());
        const r = db.roles.find((x) => x.id === id);
        if (!r) throw notFound("역할");
        if (r.builtin) throw new ApiError(409, "내장 역할은 삭제할 수 없습니다.", "권한만 바꿀 수 있습니다.");
        const n = db.members.filter((m) => m.roleIds.includes(id)).length;
        if (n) throw new ApiError(409, "쓰고 있는 역할입니다.", `이 역할을 가진 멤버십 ${n}개를 다른 역할로 바꾼 뒤 삭제하세요.`);
        db.roles = db.roles.filter((x) => x.id !== id);
        db.workflows = db.workflows.filter((w) => w.roleId !== id);
      }),
    permissions: () => run(() => (requireAdmin(sessionUser()), clone(PERMISSIONS))),
  },

  users: {
    list: (params = {}) =>
      run(() => {
        requireUserManager(sessionUser());
        const q = params.q?.trim().toLowerCase();
        return db.users
          .filter((u) => u.status !== "deleted" || params.status === "deleted")
          .filter((u) => !params.status || u.status === params.status)
          .filter((u) => !q || u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q))
          .sort((a, b) => a.name.localeCompare(b.name, "ko"))
          .map(toUser);
      }),
    create: (input) =>
      run(() => {
        requireUserManager(sessionUser());
        checkName(input.name);
        const email = input.email.trim().toLowerCase();
        if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw fieldError("email", "이메일 형식이 아닙니다.");
        if (db.users.some((u) => u.email.toLowerCase() === email)) throw fieldError("email", "이미 등록된 이메일입니다.");
        const u: DbUser = {
          id: newId("u"), email, name: input.name.trim(), password: "", status: "invited", isAdmin: input.isAdmin, locale: "ko", timeZone: "Asia/Seoul",
          prefs: { commentsNewestFirst: false, warnOnUnsaved: true, keyboardShortcuts: true }, createdAt: nowIso(), lastLoginAt: null,
        };
        db.users.push(u);
        return toUser(u);
      }),
    update: (id, patch) =>
      run(() => {
        const me = sessionUser();
        requireUserManager(me);
        const u = db.users.find((x) => x.id === id && x.status !== "deleted");
        if (!u) throw notFound("사용자");
        checkName(patch.name);
        if (patch.isAdmin !== undefined) {
          requireAdmin(me);
          if (!patch.isAdmin && u.id === me.id) throw new ApiError(422, "내 관리자 권한은 내가 해제할 수 없습니다.");
          if (!patch.isAdmin && db.users.filter((x) => x.isAdmin && x.status === "active").length <= 1) throw new ApiError(422, "마지막 관리자입니다.");
          u.isAdmin = patch.isAdmin;
        }
        if (patch.status) {
          if (u.id === me.id) throw new ApiError(422, "내 계정은 잠글 수 없습니다.");
          if (u.status === "invited" && patch.status === "active") throw new ApiError(422, "초대를 수락하기 전에는 활성화할 수 없습니다.");
          u.status = patch.status;
        }
        if (patch.name !== undefined) u.name = patch.name.trim();
        return toUser(u);
      }),
    remove: (id) =>
      run(() => {
        const me = sessionUser();
        requireUserManager(me);
        const u = db.users.find((x) => x.id === id && x.status !== "deleted");
        if (!u) throw notFound("사용자");
        if (u.id === me.id) throw new ApiError(422, "내 계정은 삭제할 수 없습니다.");
        if (u.isAdmin && db.users.filter((x) => x.isAdmin && x.status === "active").length <= 1) throw new ApiError(422, "마지막 관리자입니다.");
        // anonymise (architecture.md §2): keep journals, drop personal data and access
        Object.assign(u, { status: "deleted", name: "삭제된 사용자", email: `deleted+${u.id}@invalid`, password: "", isAdmin: false });
        db.members = db.members.filter((m) => m.userId !== id);
        db.watchers = db.watchers.filter((w) => w.userId !== id);
        db.notifications = db.notifications.filter((n) => n.recipientId !== id);
        db.groups.forEach((g) => (g.memberIds = g.memberIds.filter((m) => m !== id)));
        db.workPackages.forEach((w) => {
          if (w.assigneeId === id) w.assigneeId = null;
          if (w.responsibleId === id) w.responsibleId = null;
        });
      }),
    resendInvitation: (id) =>
      run(() => {
        requireUserManager(sessionUser());
        const u = db.users.find((x) => x.id === id);
        if (!u) throw notFound("사용자");
        if (u.status !== "invited") throw new ApiError(422, "초대 대기 중인 사용자만 다시 보낼 수 있습니다.");
      }),
  },

  groups: {
    list: () => run(() => (requireUserManager(sessionUser()), db.groups.slice().sort((a, b) => a.name.localeCompare(b.name, "ko")).map(toGroup))),
    create: (name) =>
      run(() => {
        requireUserManager(sessionUser());
        checkName(name);
        unique(db.groups, name);
        const g = { id: newId("g"), name: name.trim(), memberIds: [] as string[] };
        db.groups.push(g);
        return toGroup(g);
      }),
    rename: (id, name) =>
      run(() => {
        requireUserManager(sessionUser());
        const g = db.groups.find((x) => x.id === id);
        if (!g) throw notFound("그룹");
        checkName(name);
        unique(db.groups, name, id);
        g.name = name.trim();
        return toGroup(g);
      }),
    remove: (id) =>
      run(() => {
        requireUserManager(sessionUser());
        db.groups = db.groups.filter((x) => x.id !== id);
      }),
    setMembers: (id, userIds) =>
      run(() => {
        requireUserManager(sessionUser());
        const g = db.groups.find((x) => x.id === id);
        if (!g) throw notFound("그룹");
        g.memberIds = userIds.filter((u) => db.users.some((x) => x.id === u && x.status !== "deleted"));
        return toGroup(g);
      }),
  },

  calendar: {
    get: () => run(() => (requireAdmin(sessionUser()), clone({ workingDays: db.settings.workingDays, hoursPerDay: db.settings.hoursPerDay }))),
    save: (input) =>
      run(() => {
        const me = sessionUser();
        requireAdmin(me);
        const days = [...new Set(input.workingDays)].filter((d) => d >= 1 && d <= 7).sort();
        if (!days.length) throw fieldError("workingDays", "근무 요일을 하루 이상 고르세요.");
        if (!(input.hoursPerDay >= 1 && input.hoursPerDay <= 24)) throw fieldError("hoursPerDay", "1에서 24 사이로 입력하세요.");
        const changed = days.join() !== db.settings.workingDays.join();
        db.settings.workingDays = days;
        db.settings.hoursPerDay = input.hoursPerDay;
        if (changed) applyCalendarChange(me);
        return clone({ workingDays: days, hoursPerDay: input.hoursPerDay });
      }),
    nonWorkingDays: (year) => run(() => (requireAdmin(sessionUser()), clone(db.nonWorkingDays.filter((d) => d.date.startsWith(`${year}-`)).sort((a, b) => a.date.localeCompare(b.date))))),
    addNonWorkingDay: (date, name) =>
      run(() => {
        const me = sessionUser();
        requireAdmin(me);
        if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw fieldError("date", "날짜를 고르세요.");
        checkName(name);
        if (db.nonWorkingDays.some((d) => d.date === date)) throw fieldError("date", "이미 등록된 휴무일입니다.");
        db.nonWorkingDays.push({ id: newId("nwd"), date, name: name.trim() });
        applyCalendarChange(me);
      }),
    removeNonWorkingDay: (id) =>
      run(() => {
        const me = sessionUser();
        requireAdmin(me);
        db.nonWorkingDays = db.nonWorkingDays.filter((d) => d.id !== id);
        applyCalendarChange(me);
      }),
  },
};

