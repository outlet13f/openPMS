// Administration (S27–S36) and project settings (S24–S26) for the fake data layer.
import type { Api } from "../api";
import { adminTimeActivities } from "./time";
import type { AdminCustomField, AdminOidcProvider, OidcProviderInput, AuthSettings, CustomField, CustomFieldInput, CustomValue, ProjectCustomFields, TypeForm, AdminGroup, AdminPriority, AdminRole, AdminStatus, AdminType, AdminUser, Category, TemporaryPassword, UserInput, Version } from "../types";
import { ApiError } from "../errors";
import { DATE_FORMATS, NOT_ON_PORTAL } from "../types";
import { nowIso } from "@/lib/dates";
import { db, newId, type DbCustomField, type DbUser } from "./db";
import { findProject, globalPermissions, memberRoleIds, requirePerm, sessionUser } from "./permissions";
import { clone, toProject, userRef } from "./dto";
import { rescheduleAll } from "./scheduling";
import { fieldError, HEX, run } from "./util";
import { checkValue, toField } from "./custom-fields";
import { deriveProgress, progressSettings } from "./progress";
import { BUILT_IN_ATTRIBUTES } from "../type-form";
import { DEFAULT_AUTH_SETTINGS, PASSWORD_CLASSES } from "../password-policy";
import { issueToken, revokeAll, sendMail } from "./tokens";
import { clearTemporaryPassword, newTemporaryPassword, temporaryPasswordOf } from "./temporary-passwords";

/* ---------- guards ---------- */

function requireAdmin(user: DbUser) {
  if (!user.isAdmin) throw new ApiError(403, "관리자만 할 수 있습니다.");
}
function requireUserManager(user: DbUser) {
  if (!user.isAdmin && !globalPermissions(user).includes("global.manage_users")) throw new ApiError(403, "사용자를 관리할 권한이 없습니다.");
}
const notFound = (what: string) => new ApiError(404, `${what}을(를) 찾을 수 없습니다.`);

/** Whose temporary password may be handled (backend admin/TemporaryPasswordController.target). */
function temporaryTarget(id: string) {
  const me = sessionUser();
  requireUserManager(me);
  const u = db.users.find((x) => x.id === id && x.status !== "deleted");
  if (!u) throw notFound("사용자");
  if (u.isAdmin && !me.isAdmin) throw new ApiError(403, "관리자 계정은 관리자만 다룰 수 있습니다.");
  if (u.id === me.id) throw new ApiError(422, "내 비밀번호는 계정 설정에서 바꾸세요.");
  if (u.status === "locked") throw new ApiError(422, "잠긴 계정입니다. 먼저 잠금을 푸세요.");
  return u;
}

/** A new account (users.create and createWithTemporaryPassword share the checks). */
function addUser(input: UserInput, status: DbUser["status"]) {
  const me = sessionUser();
  requireUserManager(me);
  checkName(input.name);
  const email = input.email.trim().toLowerCase();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw fieldError("email", "이메일 형식이 아닙니다.");
  if (db.users.some((u) => u.email.toLowerCase() === email)) throw fieldError("email", "이미 등록된 이메일입니다.");
  if (input.isAdmin && !me.isAdmin) throw new ApiError(403, "관리자만 관리자를 지정할 수 있습니다.");
  const u: DbUser = {
    id: newId("u"), email, name: input.name.trim(), password: "", status, isAdmin: input.isAdmin, locale: "ko", timeZone: "Asia/Seoul",
    prefs: { commentsNewestFirst: false, warnOnUnsaved: true, keyboardShortcuts: true, immediateOnMention: true, digestEnabled: true, digestTimes: ["08:00"], remindersPausedUntil: null, myPageWidgets: ["assigned", "created"] }, createdAt: nowIso(), lastLoginAt: null,
  };
  db.users.push(u);
  return u;
}

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
  clone({ ...t, form: t.form ?? null, descriptionTemplateHtml: t.descriptionTemplateHtml ?? "", workPackageCount: db.workPackages.filter((w) => w.typeId === t.id).length, projectCount: db.projects.filter((p) => p.typeIds.includes(t.id)).length });
const toStatus = (s: (typeof db.statuses)[number]): AdminStatus => clone({ ...s, slaPause: !!s.slaPause, workPackageCount: db.workPackages.filter((w) => w.statusId === s.id).length });
const toPriority = (p: (typeof db.priorities)[number]): AdminPriority => clone({ ...p, workPackageCount: db.workPackages.filter((w) => w.priorityId === p.id).length });
const toRole = (r: (typeof db.roles)[number]): AdminRole =>
  clone({ ...r, memberCount: r.scope === "project" ? db.members.filter((m) => m.roleIds.includes(r.id)).length : 0 });
const toUser = (u: DbUser): AdminUser =>
  clone({
    id: u.id, name: u.status === "deleted" ? "삭제된 사용자" : u.name, email: u.email, status: u.status, isAdmin: u.isAdmin, locale: u.locale, timeZone: u.timeZone,
    lastLoginAt: u.lastLoginAt, createdAt: u.createdAt, lockedUntil: null,
    temporaryPasswordUntil: u.status === "deleted" ? null : (temporaryPasswordOf(u.id)?.expiresAt ?? null),
    globalRoles: db.roles.filter((r) => r.scope === "global" && u.globalRoleIds?.includes(r.id)).map((r) => ({ id: r.id, name: r.name })).sort((a, b) => a.name.localeCompare(b.name, "ko")),
    projectCount: db.projects.filter((p) => memberRoleIds(u.id, p.id).length > 0).length,
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

const always = (w: { appliesTo?: string }) => (w.appliesTo ?? "always") === "always";

/** Every permission key the app knows, grouped for the role editor (architecture.md "권한 키"). */
const PERMISSIONS: { key: string; scope: "project" | "global"; group: string }[] = [
  ...["project.view", "project.edit", "project.archive", "project.manage_modules", "project.manage_members", "project.view_members", "project.manage_versions", "project.manage_categories", "project.manage_types", "project.manage_custom_fields"].map((key) => ({ key, scope: "project" as const, group: "project" })),
  ...["wp.view", "wp.create", "wp.edit", "wp.delete", "wp.move", "wp.attach", "wp.comment", "wp.edit_own_comments", "wp.edit_comments", "wp.view_internal_comments", "wp.add_internal_comments", "wp.manage_relations", "wp.manage_subtasks", "wp.assign_versions", "wp.view_watchers", "wp.add_watchers", "wp.delete_watchers", "wp.share", "wp.export"].map((key) => ({ key, scope: "project" as const, group: "wp" })),
  ...["query.save", "query.manage_public"].map((key) => ({ key, scope: "project" as const, group: "query" })),
  ...["board.view", "board.manage"].map((key) => ({ key, scope: "project" as const, group: "board" })),
  ...["time.log", "time.view", "time.manage"].map((key) => ({ key, scope: "project" as const, group: "time" })),
  ...["service.view", "service.handle", "service.manage"].map((key) => ({ key, scope: "project" as const, group: "service" })),
  ...["global.create_projects", "global.manage_users"].map((key) => ({ key, scope: "global" as const, group: "global" })),
];

/* ---------- project settings ---------- */

/* ---------- invitations (S01) ---------- */

/** Earlier links stop working; the new one lasts auth.invitation_ttl_hours (168). */
function invite(u: DbUser, by: string) {
  revokeAll(u.id, "invitation");
  sendMail(u.email, "[openPMS] 초대", `/invitations/${issueToken(u.id, "invitation", 168 * 60, by)}`);
}

/* ---------- type forms (S28) ---------- */

/** A form as sent → checked (messages as backend wp/TypeForms.parse). */
function checkForm(form: TypeForm): TypeForm {
  const groups = form.groups ?? [];
  const hidden = form.hidden ?? [];
  if (groups.length > 20) throw fieldError("form", "그룹은 20개까지 만들 수 있습니다.");
  const seen = new Set<string>();
  const names = new Set<string>();
  const known = (a: string) => {
    const cf = a.startsWith("cf:") ? a.slice(3) : null;
    if (!(BUILT_IN_ATTRIBUTES as readonly string[]).includes(a) && !(cf && db.customFields.some((f) => f.id === cf))) throw fieldError("form", `알 수 없는 항목입니다: ${a}`);
    if (seen.has(a)) throw fieldError("form", "같은 항목이 두 번 있습니다.");
    seen.add(a);
  };
  const out = groups.map((g) => {
    const name = (g.name ?? "").trim();
    if (!name) throw fieldError("form", "그룹 이름을 입력하세요.");
    if (name.length > 60) throw fieldError("form", "그룹 이름은 60자까지 입력할 수 있습니다.");
    if (names.has(name)) throw fieldError("form", `같은 이름의 그룹이 있습니다: ${name}`);
    names.add(name);
    g.attributes.forEach(known);
    return { name, attributes: [...g.attributes] };
  });
  hidden.forEach(known);
  return { groups: out, hidden: [...hidden] };
}

/* ---------- custom fields (S34, S25) ---------- */

const FORMATS: CustomField["format"][] = ["string", "text", "integer", "float", "list", "date", "bool", "user", "link"];
const toAdminField = (f: DbCustomField): AdminCustomField => ({
  ...toField(f), projectCount: db.projectCustomFields.filter((x) => x.customFieldId === f.id).length, portalVisible: !!f.portalVisible,
});

/**
 * Checks a definition (create, or an update merged onto the stored one) and returns the field to store.
 * New list options get ids; a list default may name an option by id or by its text (for options new in this request).
 */
function buildField(input: CustomFieldInput, existing?: CustomField): CustomField {
  checkName(input.name);
  if (db.customFields.some((x) => x.id !== existing?.id && x.name.trim() === input.name.trim())) throw fieldError("name", "같은 이름의 필드가 이미 있습니다.");
  if (!FORMATS.includes(input.format)) throw fieldError("format", "형식을 고르세요.");
  if (existing && existing.format !== input.format) throw fieldError("format", "만든 뒤에는 형식을 바꿀 수 없습니다.");
  const refs = input.format === "list" || input.format === "user";
  if (input.isMultiValue && !refs) throw fieldError("isMultiValue", "여러 값은 목록과 사용자 형식에서만 고를 수 있습니다.");
  if (!input.typeIds.length) throw fieldError("typeIds", "이 필드를 쓸 유형을 하나 이상 고르세요.");
  if (input.typeIds.some((id) => !db.types.some((t) => t.id === id))) throw fieldError("typeIds", "없는 유형입니다.");
  const lengths = input.format === "string" || input.format === "text";
  const min = lengths ? input.minLength : null;
  const max = lengths ? input.maxLength : null;
  if (min !== null && (!Number.isInteger(min) || min < 0)) throw fieldError("minLength", "최소 길이는 0 이상의 정수로 입력하세요.");
  if (max !== null && (!Number.isInteger(max) || max < 1)) throw fieldError("maxLength", "최대 길이는 1 이상의 정수로 입력하세요.");
  if (min !== null && max !== null && max < min) throw fieldError("maxLength", "최대 길이는 최소 길이보다 작을 수 없습니다.");
  const regexp = lengths && input.regexp?.trim() ? input.regexp.trim() : null;
  if (regexp) {
    try {
      new RegExp(regexp);
    } catch {
      throw fieldError("regexp", "정규식이 올바르지 않습니다.");
    }
  }
  let options: CustomField["options"] = [];
  if (input.format === "list") {
    const values = input.options.map((o) => o.value.trim());
    if (!values.length || values.some((v) => !v)) throw fieldError("options", "선택지를 하나 이상 입력하세요(빈 칸 없이).");
    if (values.some((v) => v.length > 255)) throw fieldError("options", "선택지는 255자까지 입력할 수 있습니다.");
    if (new Set(values).size !== values.length) throw fieldError("options", "같은 선택지가 두 번 있습니다.");
    options = input.options.map((o, i) => ({ id: o.id && existing?.options.some((x) => x.id === o.id) ? o.id : newId("cfo"), value: values[i] }));
  }
  const draft: CustomField = {
    id: existing?.id ?? newId("cf"), name: input.name.trim(), format: input.format, isRequired: input.isRequired, isForAll: input.isForAll,
    isFilterable: input.isFilterable, isMultiValue: refs && input.isMultiValue, defaultValue: null, minLength: min, maxLength: max, regexp,
    position: existing?.position ?? db.customFields.length + 1, options, typeIds: db.types.filter((t) => input.typeIds.includes(t.id)).map((t) => t.id),
  };
  // no default for people: who is a member differs per project
  let def: CustomValue = input.format === "user" ? null : input.defaultValue;
  if (input.format === "list" && def !== null) {
    const pick = (x: string) => options.find((o) => o.id === x || o.value === x)?.id ?? x;
    def = Array.isArray(def) ? def.map((x) => pick(String(x))) : pick(String(def));
  }
  try {
    draft.defaultValue = checkValue(draft, def, null);
  } catch {
    throw fieldError("defaultValue", "기본값이 이 필드의 형식·조건에 맞지 않습니다.");
  }
  return draft;
}

/** Stored values follow a definition change: removed options go, single and multi convert. */
function migrateValues(f: CustomField) {
  for (const w of db.workPackages) {
    const v = w.customValues?.[f.id];
    if (v === undefined || v === null) continue;
    let next: CustomValue = v;
    if (f.format === "list") {
      const ids = (Array.isArray(v) ? v : [v]).map(String).filter((id) => f.options.some((o) => o.id === id));
      next = f.isMultiValue ? (ids.length ? ids : null) : (ids[0] ?? null);
    } else if (f.format === "user") {
      const ids = (Array.isArray(v) ? v : [v]).map(String);
      next = f.isMultiValue ? ids : (ids[0] ?? null);
    }
    w.customValues = { ...w.customValues };
    if (next === null) delete w.customValues[f.id];
    else w.customValues[f.id] = next;
  }
}

/** Saved views forget a deleted field: its column, sort and filter. */
function forgetInQueries(id: string) {
  const key = `cf:${id}`;
  for (const q of db.queries) {
    q.spec = { ...q.spec, columns: q.spec.columns.filter((c) => c !== key), sort: q.spec.sort.filter((s) => s.field !== key), filters: q.spec.filters.filter((x) => x.field !== key) };
  }
}

/** User and version fields never go on the portal (like the server's CustomFieldController), so they are never marked for it. */
const portalCapable = (f: DbCustomField) => !NOT_ON_PORTAL.includes(f.format);

export const customFieldsAdmin: Api["admin"]["customFields"] = {
  list: () => run(() => (requireAdmin(sessionUser()), db.customFields.slice().sort((a, b) => a.position - b.position).map(toAdminField))),
  create: (input) =>
    run(() => {
      requireAdmin(sessionUser());
      const f: DbCustomField = { ...buildField(input), portalVisible: !!input.portalVisible };
      if (!portalCapable(f)) f.portalVisible = false;
      db.customFields.push(f);
      return toAdminField(f);
    }),
  update: (id, patch) =>
    run(() => {
      requireAdmin(sessionUser());
      const f = db.customFields.find((x) => x.id === id);
      if (!f) throw notFound("사용자 정의 필드");
      const next = buildField({ ...f, ...patch }, f);
      Object.assign(f, next);
      if (patch.portalVisible !== undefined) f.portalVisible = !!patch.portalVisible;
      if (!portalCapable(f)) f.portalVisible = false;
      migrateValues(f);
      return toAdminField(f);
    }),
  remove: (id) =>
    run(() => {
      requireAdmin(sessionUser());
      if (!db.customFields.some((x) => x.id === id)) throw notFound("사용자 정의 필드");
      db.customFields = db.customFields.filter((x) => x.id !== id);
      db.projectCustomFields = db.projectCustomFields.filter((x) => x.customFieldId !== id);
      for (const w of db.workPackages) if (w.customValues && id in w.customValues) delete w.customValues[id];
      forgetInQueries(id);
      db.customFields.sort((a, b) => a.position - b.position).forEach((x, i) => (x.position = i + 1));
    }),
  reorder: (ids) => run(() => (requireAdmin(sessionUser()), reorderBy(db.customFields, ids))),
};

function projectFields(projectId: string): ProjectCustomFields {
  return {
    fields: db.customFields.slice().sort((a, b) => a.position - b.position).map(toField),
    enabledIds: db.projectCustomFields.filter((x) => x.projectId === projectId).map((x) => x.customFieldId),
  };
}

export const projectExtras: Pick<Api["projects"], "setTypes" | "archive" | "unarchive" | "remove" | "customFields" | "setCustomFields"> = {
  customFields: (id) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, id);
      requirePerm(user, p, "project.manage_custom_fields", "사용자 정의 필드를 관리할 권한이 없습니다.");
      return projectFields(p.id);
    }),
  setCustomFields: (id, ids) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, id);
      requirePerm(user, p, "project.manage_custom_fields", "사용자 정의 필드를 관리할 권한이 없습니다.");
      if (ids.some((x) => !db.customFields.some((f) => f.id === x))) throw fieldError("customFieldIds", "없는 사용자 정의 필드입니다.");
      const on = db.customFields.filter((f) => !f.isForAll && ids.includes(f.id));
      db.projectCustomFields = [...db.projectCustomFields.filter((x) => x.projectId !== p.id), ...on.map((f) => ({ projectId: p.id, customFieldId: f.id }))];
      return projectFields(p.id);
    }),
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
  unarchive: (id) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, id); // first: an invisible project is a 404, not a 403
      if (!user.isAdmin) throw new ApiError(403, "관리자만 프로젝트를 복원할 수 있습니다.");
      const parent = db.projects.find((x) => x.id === p.parentId);
      if (parent?.archivedAt) throw new ApiError(409, "상위 프로젝트가 보관되어 있습니다.", `'${parent.name}'을(를) 먼저 복원하세요.`);
      p.archivedAt = null;
      p.updatedAt = nowIso();
    }),
  remove: (id, confirmIdentifier) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, id);
      if (!user.isAdmin) throw new ApiError(403, "관리자만 프로젝트를 삭제할 수 있습니다.");
      if ((confirmIdentifier ?? "").trim() !== p.identifier) throw fieldError("confirmIdentifier", `식별자 '${p.identifier}'를 그대로 입력하세요.`);
      const children = db.projects.filter((c) => c.parentId === p.id).map((c) => c.name).sort();
      if (children.length) throw new ApiError(409, "하위 프로젝트가 있습니다.", `먼저 하위 프로젝트를 옮기거나 삭제하세요: ${children.join(", ")}`);
      // as the database's cascades: everything of the project; items elsewhere lose a parent or a relation
      const wps = new Set(db.workPackages.filter((w) => w.projectId === p.id).map((w) => w.id));
      db.workPackages.forEach((w) => w.parentId && wps.has(w.parentId) && !wps.has(w.id) && (w.parentId = null));
      db.workPackages = db.workPackages.filter((w) => !wps.has(w.id));
      db.relations = db.relations.filter((r) => !wps.has(r.fromId) && !wps.has(r.toId));
      db.journals = db.journals.filter((j) => !wps.has(j.wpId));
      db.watchers = db.watchers.filter((x) => !wps.has(x.wpId));
      db.attachments = db.attachments.filter((x) => !wps.has(x.wpId));
      db.shares = db.shares.filter((x) => !wps.has(x.wpId));
      db.notifications = db.notifications.filter((x) => !wps.has(x.wpId));
      db.serviceTickets = db.serviceTickets.filter((x) => !wps.has(x.workPackageId));
      db.slaPolicies = db.slaPolicies.filter((x) => x.projectId !== p.id);
      db.priorityMatrix = db.priorityMatrix.filter((x) => x.projectId !== p.id);
      const versions = new Set(db.versions.filter((v) => v.projectId === p.id).map((v) => v.id));
      const categories = new Set(db.categories.filter((c) => c.projectId === p.id).map((c) => c.id));
      db.workPackages.forEach((w) => {
        if (w.versionId && versions.has(w.versionId)) w.versionId = null;
        if (w.categoryId && categories.has(w.categoryId)) w.categoryId = null;
      });
      db.versions = db.versions.filter((v) => v.projectId !== p.id);
      db.categories = db.categories.filter((c) => c.projectId !== p.id);
      db.members = db.members.filter((m) => m.projectId !== p.id);
      const queries = new Set(db.queries.filter((q) => q.projectId === p.id).map((q) => q.id));
      db.queries = db.queries.filter((q) => !queries.has(q.id));
      db.queryFavorites = db.queryFavorites.filter((f) => !queries.has(f.queryId));
      db.boards = db.boards.filter((x) => x.projectId !== p.id);
      db.projectFavorites = db.projectFavorites.filter((f) => f.projectId !== p.id);
      db.projectCustomFields = db.projectCustomFields.filter((f) => f.projectId !== p.id);
      db.notificationSettings = db.notificationSettings.filter((x) => x.projectId !== p.id);
      db.projects = db.projects.filter((x) => x.id !== p.id);
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

/* ---------- SSO providers ---------- */

type FakeOidc = NonNullable<typeof db.settings.oidcProviders>[number];
const toOidc = (p: FakeOidc): AdminOidcProvider => {
  const { clientSecret, ...rest } = p;
  return { ...clone(rest), secretSet: clientSecret !== "", redirectUri: `${window.location.origin}/api/v1/auth/oidc/callback/${p.slug}`, linkedUsers: 0 };
};

function applyOidc(p: FakeOidc, input: Partial<OidcProviderInput>, create: boolean) {
  const text = (k: keyof OidcProviderInput, max: number) => {
    const v = String(input[k] ?? "").trim();
    if (v.length > max) throw fieldError(k, `${max}자까지 입력할 수 있습니다.`);
    return v;
  };
  if (create || input.displayName !== undefined) {
    const v = text("displayName", 60);
    if (!v) throw fieldError("displayName", "로그인 버튼에 보일 이름을 60자까지 입력하세요.");
    p.displayName = v;
  }
  if (create || input.clientId !== undefined) {
    const v = text("clientId", 255);
    if (!v) throw fieldError("clientId", "클라이언트 ID를 입력하세요.");
    p.clientId = v;
  }
  const secret = text("clientSecret", 1000);
  if (secret) p.clientSecret = secret;
  else if (create) throw fieldError("clientSecret", "클라이언트 시크릿을 입력하세요.");
  if (create || input.scopes !== undefined) {
    const v = text("scopes", 500) || "openid email profile";
    if (!v.split(/[\s,]+/).includes("openid")) throw fieldError("scopes", "openid가 들어 있어야 합니다.");
    p.scopes = v;
  }
  if (create || input.claimEmail !== undefined) p.claimEmail = text("claimEmail", 100) || "email";
  if (create || input.claimName !== undefined) p.claimName = text("claimName", 100) || "name";
  if (input.autoCreate !== undefined) p.autoCreate = !!input.autoCreate;
  if (input.enabled !== undefined) p.enabled = input.enabled !== false;
  if (create || input.issuer !== undefined) {
    const v = text("issuer", 500);
    // the fake cannot fetch the discovery document: the address only has to look like one
    if (!/^https?:\/\/\S+$/.test(v)) throw fieldError("issuer", "https:// 또는 http://로 시작하는 주소를 입력하세요.");
    p.issuer = v;
  }
}

export const oidcAdmin: Api["admin"]["oidc"] = {
  list: () => run(() => (requireAdmin(sessionUser()), (db.settings.oidcProviders ?? []).map(toOidc))),
  create: (input) =>
    run(() => {
      requireAdmin(sessionUser());
      const list = (db.settings.oidcProviders ??= []);
      const slug = String(input.slug ?? "").trim();
      if (!/^[a-z0-9][a-z0-9-]{0,39}$/.test(slug)) throw fieldError("slug", "영문 소문자·숫자·하이픈으로 40자까지 입력하세요.");
      if (list.some((x) => x.slug === slug)) throw fieldError("slug", "이미 쓰는 식별자입니다.");
      const p: FakeOidc = { id: newId("oidc"), slug, displayName: "", issuer: "", clientId: "", clientSecret: "", scopes: "", claimEmail: "", claimName: "", autoCreate: false, enabled: true };
      applyOidc(p, input, true);
      list.push(p);
      return toOidc(p);
    }),
  update: (id, input) =>
    run(() => {
      requireAdmin(sessionUser());
      const p = (db.settings.oidcProviders ?? []).find((x) => x.id === id);
      if (!p) throw new ApiError(404, "SSO 공급자를 찾을 수 없습니다.");
      const draft = clone(p);
      applyOidc(draft, input, false);
      Object.assign(p, draft);
      return toOidc(p);
    }),
  remove: (id) =>
    run(() => {
      requireAdmin(sessionUser());
      const list = db.settings.oidcProviders ?? [];
      if (!list.some((x) => x.id === id)) throw new ApiError(404, "SSO 공급자를 찾을 수 없습니다.");
      db.settings.oidcProviders = list.filter((x) => x.id !== id);
    }),
};

export const adminApi: Api["admin"] = {
  customFields: customFieldsAdmin,
  oidc: oidcAdmin,
  auth: {
    get: () => run(() => (requireAdmin(sessionUser()), clone(db.settings.auth ?? DEFAULT_AUTH_SETTINGS))),
    save: (input) =>
      run(() => {
        requireAdmin(sessionUser());
        const num = (key: keyof AuthSettings, lo: number, hi: number) => {
          const v = input[key] as number;
          if (!Number.isInteger(v) || v < lo || v > hi) throw fieldError(key, `${lo}에서 ${hi} 사이로 입력하세요.`);
          return v;
        };
        const classes = [...new Set(input.requiredClasses ?? [])];
        for (const c of classes) if (!PASSWORD_CLASSES.includes(c)) throw fieldError("requiredClasses", `알 수 없는 문자 종류입니다: ${c}`);
        const minClasses = num("minClasses", 0, 4);
        if (minClasses < classes.length) throw fieldError("minClasses", `꼭 넣어야 하는 종류(${classes.length}가지)보다 적게 정할 수 없습니다.`);
        db.settings.auth = {
          minLength: num("minLength", 8, 128),
          requiredClasses: classes,
          minClasses,
          resetEnabled: input.resetEnabled !== false,
          lockoutAttempts: num("lockoutAttempts", 0, 50),
          lockoutMinutes: num("lockoutMinutes", 1, 1440),
          invitationTtlHours: num("invitationTtlHours", 1, 720),
          resetTtlMinutes: num("resetTtlMinutes", 10, 1440),
        };
        return clone(db.settings.auth);
      }),
  },
  progress: {
    get: () => run(() => (requireAdmin(sessionUser()), progressSettings())),
    save: (input) =>
      run(() => {
        requireAdmin(sessionUser());
        if (!["work", "status"].includes(input.mode)) throw fieldError("mode", "계산 방식을 고르세요.");
        if (!["weighted", "simple_average"].includes(input.totals)) throw fieldError("totals", "합계 방식을 고르세요.");
        db.settings = { ...db.settings, progressMode: input.mode, closedSets100: !!input.closedSets100, progressTotals: input.totals };
        // every item follows the new rules; no journal entries (a setting changed, not the work)
        let recalculated = 0;
        for (const w of db.workPackages) {
          const p = deriveProgress(w, { other: input.mode === "work" });
          if (p.doneRatio !== w.doneRatio || p.remainingHours !== w.remainingHours) {
            Object.assign(w, p);
            recalculated++;
          }
        }
        return { ...progressSettings(), recalculated };
      }),
  },
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
        if (patch.form !== undefined) patch = { ...patch, form: patch.form === null ? null : checkForm(patch.form) };
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
        db.slaPolicies = db.slaPolicies.filter((x) => x.typeId !== id);
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
        db.slaPolicies = db.slaPolicies.filter((x) => x.priorityId !== id);
        db.priorityMatrix = db.priorityMatrix.filter((x) => x.priorityId !== id);
        db.priorities.sort((a, b) => a.position - b.position).forEach((x, i) => (x.position = i + 1));
      }),
    reorder: (ids) => run(() => (requireAdmin(sessionUser()), reorderBy(db.priorities, ids))),
  },

  workflows: {
    get: (typeId, roleId) =>
      run(() => {
        requireAdmin(sessionUser());
        // the screen shows and saves the "always" rows; author / assignee rows (요청자) stay as they are, like the server
        return db.workflows.filter((w) => w.typeId === typeId && w.roleId === roleId && always(w)).map((w) => [w.from, w.to] as [string, string]);
      }),
    save: (typeId, roleId, transitions) =>
      run(() => {
        requireAdmin(sessionUser());
        if (!db.types.some((t) => t.id === typeId) || !db.roles.some((r) => r.id === roleId && r.scope === "project")) throw notFound("유형 또는 역할");
        const ids = new Set(db.statuses.map((s) => s.id));
        const clean = transitions.filter(([a, b]) => a !== b && ids.has(a) && ids.has(b));
        db.workflows = db.workflows.filter((w) => !(w.typeId === typeId && w.roleId === roleId && always(w)));
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
            db.workflows.push(...src.map((w) => ({ typeId, roleId, from: w.from, to: w.to, ...(w.appliesTo ? { appliesTo: w.appliesTo } : {}) })));
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

  // the demo keeps webhooks but sends nothing
  // the demo has no server, database or backups: the page shows what it can
  system: {
    info: () =>
      run(() => {
        requireAdmin(sessionUser());
        return {
          app: { version: "데모", builtAt: null, startedAt: nowIso(), java: "-", jobsEnabled: false },
          database: { version: "브라우저 저장소(데모)", sizeBytes: JSON.stringify(db).length },
          migrations: { current: null, applied: 0, pending: 0, recent: [] },
          backups: { beforeUpgrade: false, dir: "-", keep: 0, pgDump: null, running: false, files: [] },
          counts: { activeUsers: db.users.filter((u) => u.status === "active").length, projects: db.projects.length, workPackages: db.workPackages.length, attachmentBytes: 0 },
        };
      }),
    backup: () => run(() => (requireAdmin(sessionUser()), Promise.reject(new ApiError(503, "백업하지 못했습니다.", "데모에서는 백업하지 않습니다.")) as never)),
  },
  timeActivities: adminTimeActivities,
  webhooks: {
    list: () => run(() => (requireAdmin(sessionUser()), (db.webhooks ?? []).map(({ secret: _s, ...h }) => clone(h)))),
    create: (input) =>
      run(() => {
        const me = sessionUser();
        requireAdmin(me);
        if (!input.name.trim()) throw fieldError("name", "이름을 1~100자로 입력하세요.");
        if (!/^https?:\/\/[^/\s]+/.test(input.url)) throw fieldError("url", "http:// 또는 https:// 로 시작하는 주소를 입력하세요.");
        if (!input.events.length) throw fieldError("events", "보낼 이벤트를 하나 이상 고르세요.");
        const secret = `whsec_demo${Math.random().toString(36).slice(2)}`;
        const h = { ...input, name: input.name.trim(), id: newId("wh"), secret, secretHint: `…${secret.slice(-4)}`, createdAt: nowIso() };
        db.webhooks = [...(db.webhooks ?? []), h];
        return clone(h);
      }),
    update: (id, patch) =>
      run(() => {
        requireAdmin(sessionUser());
        const h = (db.webhooks ?? []).find((x) => x.id === id);
        if (!h) throw notFound("웹훅");
        Object.assign(h, patch);
        const { secret: _s, ...out } = h;
        return clone(out);
      }),
    rotateSecret: (id) =>
      run(() => {
        requireAdmin(sessionUser());
        const h = (db.webhooks ?? []).find((x) => x.id === id);
        if (!h) throw notFound("웹훅");
        h.secret = `whsec_demo${Math.random().toString(36).slice(2)}`;
        h.secretHint = `…${h.secret.slice(-4)}`;
        return clone(h);
      }),
    remove: (id) => run(() => (requireAdmin(sessionUser()), (db.webhooks = (db.webhooks ?? []).filter((x) => x.id !== id)), undefined)),
    deliveries: () => run(() => (requireAdmin(sessionUser()), [])),
    test: () =>
      run(() => {
        requireAdmin(sessionUser());
        return { id: newId("wd"), event: "ping" as const, status: "failed" as const, attempts: 1, responseStatus: null, error: "데모에서는 보내지 않습니다", createdAt: nowIso(), deliveredAt: null, nextAttemptAt: null };
      }),
    redeliver: () =>
      run(() => {
        requireAdmin(sessionUser());
        throw notFound("전송 기록");
      }),
  },
  api: {
    settings: () => run(() => (requireAdmin(sessionUser()), { tokensEnabled: db.apiTokensEnabled !== false })),
    saveSettings: (input) => run(() => (requireAdmin(sessionUser()), (db.apiTokensEnabled = input.tokensEnabled), { tokensEnabled: input.tokensEnabled })),
    tokens: () =>
      run(() => {
        requireAdmin(sessionUser());
        return (db.apiTokens ?? []).map((t) => clone({ ...t, userName: db.users.find((u) => u.id === t.userId)?.name ?? "" }));
      }),
    revokeToken: (id) =>
      run(() => {
        requireAdmin(sessionUser());
        if (!(db.apiTokens ?? []).some((x) => x.id === id)) throw notFound("토큰");
        db.apiTokens = (db.apiTokens ?? []).filter((x) => x.id !== id);
      }),
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
        const u = addUser(input, "invited");
        invite(u, sessionUser().id);
        return toUser(u);
      }),
    createWithTemporaryPassword: (input) =>
      run(() => {
        const u = addUser(input, "active");
        const issued = newTemporaryPassword(u.id, sessionUser().id);
        return { user: toUser(u), ...issued };
      }),
    issueTemporaryPassword: (id) =>
      run(() => {
        const u = temporaryTarget(id);
        if (u.status === "invited") {
          u.status = "active";
          revokeAll(u.id, "invitation");
        }
        return newTemporaryPassword(u.id, sessionUser().id);
      }),
    temporaryPassword: (id) =>
      run((): TemporaryPassword => {
        temporaryTarget(id);
        const t = temporaryPasswordOf(id);
        if (!t || t.expiresAt <= nowIso()) throw new ApiError(404, "유효한 임시 비밀번호가 없습니다.");
        const by = t.issuedBy ? db.users.find((x) => x.id === t.issuedBy) : undefined;
        return { temporaryPassword: t.password, expiresAt: t.expiresAt, issuedBy: by ? { id: by.id, name: by.name } : null, issuedAt: t.issuedAt };
      }),
    update: (id, patch) =>
      run(() => {
        const me = sessionUser();
        requireUserManager(me);
        const u = db.users.find((x) => x.id === id && x.status !== "deleted");
        if (!u) throw notFound("사용자");
        if (u.isAdmin && !me.isAdmin) throw new ApiError(403, "관리자 계정은 관리자만 다룰 수 있습니다.");
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
        if (u.isAdmin && !me.isAdmin) throw new ApiError(403, "관리자 계정은 관리자만 다룰 수 있습니다.");
        if (u.id === me.id) throw new ApiError(422, "내 계정은 삭제할 수 없습니다.");
        if (u.isAdmin && db.users.filter((x) => x.isAdmin && x.status === "active").length <= 1) throw new ApiError(422, "마지막 관리자입니다.");
        // anonymise (architecture.md §2): keep journals, drop personal data and access
        Object.assign(u, { status: "deleted", name: "삭제된 사용자", email: `deleted+${u.id}@invalid`, password: "", isAdmin: false, globalRoleIds: [] });
        clearTemporaryPassword(u.id);
        db.members = db.members.filter((m) => m.userId !== id);
        db.watchers = db.watchers.filter((w) => w.userId !== id);
        db.shares = db.shares.filter((x) => x.userId !== id);
        db.notifications = db.notifications.filter((n) => n.recipientId !== id);
        db.groups.forEach((g) => (g.memberIds = g.memberIds.filter((m) => m !== id)));
        db.workPackages.forEach((w) => {
          // 공동 담당자: off every list; the next person becomes the main assignee
          if (w.team) w.team = w.team.filter((e) => e.userId !== id);
          if (w.assigneeId === id) w.assigneeId = w.team?.[0]?.userId ?? null;
          if (w.responsibleId === id) w.responsibleId = null;
        });
      }),
    resendInvitation: (id) =>
      run(() => {
        requireUserManager(sessionUser());
        const u = db.users.find((x) => x.id === id);
        if (!u) throw notFound("사용자");
        if (u.status !== "invited") throw new ApiError(422, "초대 대기 중인 사용자만 다시 보낼 수 있습니다.");
        invite(u, sessionUser().id);
      }),
    setGlobalRoles: (id, roleIds) =>
      run(() => {
        const me = sessionUser();
        requireUserManager(me);
        requireAdmin(me);
        const u = db.users.find((x) => x.id === id && x.status !== "deleted");
        if (!u) throw notFound("사용자");
        if (roleIds.some((r) => db.roles.find((x) => x.id === r)?.scope !== "global")) throw fieldError("roleIds", "전역 역할만 줄 수 있습니다.");
        u.globalRoleIds = [...new Set(roleIds)];
        return toUser(u);
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
        db.members = db.members.filter((m) => m.groupId !== id);
        db.serviceTickets.forEach((t) => t.assignmentGroupId === id && (t.assignmentGroupId = null));
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
    saveDisplay: (input) =>
      run(() => {
        requireAdmin(sessionUser());
        if (!DATE_FORMATS.includes(input.dateFormat)) throw fieldError("dateFormat", "날짜 형식을 고르세요.");
        if (!["default", "24h", "12h"].includes(input.timeFormat)) throw fieldError("timeFormat", "시간 형식을 고르세요.");
        if (!["default", "1", "6", "7"].includes(input.weekStart)) throw fieldError("weekStart", "주 시작 요일을 고르세요.");
        db.settings.display = { dateFormat: input.dateFormat, timeFormat: input.timeFormat, weekStart: input.weekStart };
        return clone(db.settings.display);
      }),
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

