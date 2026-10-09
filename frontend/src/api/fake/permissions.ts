// Permission rules from architecture.md §3.1, applied to the fake database.
import { ApiError } from "../errors";
import { db, type DbProject, type DbUser, type DbWp } from "./db";

const MODULE_OF: Record<string, DbProject["enabledModules"][number]> = {
  "board.view": "boards",
  "board.manage": "boards",
};

const ALL_PROJECT_PERMS = Array.from(new Set(db.roles.filter((r) => r.scope === "project").flatMap((r) => r.permissions)));

export function sessionUser(): DbUser {
  const u = db.users.find((x) => x.id === db.sessionUserId && x.status === "active");
  if (!u) throw new ApiError(401, "로그인이 필요합니다.");
  return u;
}

/** Roles from direct membership plus membership through any of the user's groups (architecture.md §3.1). */
export function memberRoleIds(userId: string, projectId: string): string[] {
  const groups = new Set(db.groups.filter((g) => g.memberIds.includes(userId)).map((g) => g.id));
  return db.members.filter((m) => m.projectId === projectId && (m.userId === userId || (m.groupId !== null && groups.has(m.groupId)))).flatMap((m) => m.roleIds);
}

/** Active people who are members of the project, directly or through a group (assignee candidates). */
export function projectUserIds(projectId: string): string[] {
  const ids = new Set<string>();
  for (const m of db.members.filter((x) => x.projectId === projectId)) {
    if (m.userId) ids.add(m.userId);
    if (m.groupId) db.groups.find((g) => g.id === m.groupId)?.memberIds.forEach((u) => ids.add(u));
  }
  return [...ids].filter((id) => db.users.some((u) => u.id === id && u.status === "active"));
}

export function projectPermissions(user: DbUser, project: DbProject): Set<string> {
  let perms: string[];
  if (user.isAdmin) perms = ALL_PROJECT_PERMS;
  else {
    const roleIds = memberRoleIds(user.id, project.id);
    if (roleIds.length) perms = db.roles.filter((r) => roleIds.includes(r.id)).flatMap((r) => r.permissions);
    else if (project.isPublic) perms = db.roles.find((r) => r.builtin === "non_member")?.permissions ?? [];
    else perms = [];
  }
  let set = new Set(perms);
  if (project.archivedAt) set = new Set([...set].filter((p) => p.endsWith(".view") || p === "project.view_members"));
  for (const [perm, mod] of Object.entries(MODULE_OF)) if (!project.enabledModules.includes(mod)) set.delete(perm);
  return set;
}

export function can(user: DbUser, project: DbProject, perm: string) {
  return projectPermissions(user, project).has(perm);
}

export function globalPermissions(user: DbUser): string[] {
  if (user.isAdmin) return ["global.create_projects", "global.manage_users"];
  return [];
}

export function visibleProjects(user: DbUser, perm = "project.view"): DbProject[] {
  return db.projects.filter((p) => can(user, p, perm));
}

/** Projects the user can see, by id or identifier. Invisible and missing are the same 404 (no enumeration). */
export function findProject(user: DbUser, idOrIdentifier: string): DbProject {
  const p = db.projects.find((x) => x.id === idOrIdentifier || x.identifier === idOrIdentifier);
  if (!p || !can(user, p, "project.view")) throw new ApiError(404, "프로젝트를 찾을 수 없습니다.");
  return p;
}

export function findWp(user: DbUser, number: number): { wp: DbWp; project: DbProject } {
  const wp = db.workPackages.find((w) => w.number === number);
  const project = wp && db.projects.find((p) => p.id === wp.projectId);
  if (!wp || !project || !can(user, project, "wp.view")) throw new ApiError(404, "작업을 찾을 수 없습니다.");
  return { wp, project };
}

export function requirePerm(user: DbUser, project: DbProject, perm: string, message = "이 작업을 할 권한이 없습니다.") {
  if (!can(user, project, perm)) throw new ApiError(403, message);
}

/** Workflow check (architecture.md §3.2): the statuses this user may move this work package to. */
export function allowedStatusIds(user: DbUser, wp: DbWp): string[] {
  if (user.isAdmin) return db.statuses.filter((s) => s.id !== wp.statusId).map((s) => s.id);
  const roleIds = memberRoleIds(user.id, wp.projectId);
  return Array.from(
    new Set(db.workflows.filter((w) => w.typeId === wp.typeId && w.from === wp.statusId && roleIds.includes(w.roleId)).map((w) => w.to)),
  );
}
