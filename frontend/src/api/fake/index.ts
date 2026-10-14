// The fake data layer: implements the Api interface in memory, following the domain rules in
// replica/architecture.md §3 (permissions, workflow, scheduling, journals, notifications).
// Swap for httpApi (src/api/http.ts) once the backend exists; no screen changes.

import type { Api } from "../api";
import type {
  IsoDate,
  ProjectInput,
  QueryResult,
  QuerySpec,
  CustomValue,
  QueryFilter,
  Board,
  BoardColumn,
  Id,
  MoveInput,
  Me,
  Member,
  NotificationGroup,
  NotificationCounts,
  NotificationFilter,
  NotificationReason,
  NotificationItem,
  RelationGroups,
  RelationType,
  SavedQuery,
  WorkPackage,
  WorkPackageInput,
  WorkPackagePatch,
  WorkPackageShare,
  SearchResult,
  SearchResults,
} from "../types";
import { ApiError } from "../errors";
import { addDays, countWorkingDays, diffDays, isWorkingDay, nowIso, todayIso } from "@/lib/dates";
import { db, newId, persist, type DbBoard, type DbJournal, type DbProject, type DbUser, type DbWp } from "./db";
import { allowedFor, allowedStatusIds, can, findProject, findWp, globalPermissions, memberRoleIds, projectUserIds, requirePerm, sessionUser, visibleProjects } from "./permissions";
import { clone, display, projectRef, statusRef, toJournal, toProject, toWp, typeRef, priorityRef, userRef, wpRef } from "./dto";
import { propagate, resolveDates, wouldCycle, workCalendar } from "./scheduling";
import { matches, defaultSpec, runQuery, listTakes, viewableProjectIds } from "./query";
import { DEFAULT_COLUMNS } from "./seed";
import { fieldError, run } from "./util";
import { activeFields, applyValues, cfText, fieldsInProjects, initialValues, toField } from "./custom-fields";
import { accountApi, effectiveSetting } from "./notification-settings";
import { exportCount, exportWorkPackages } from "./export";
import { deriveProgress, progressLocked } from "./progress";
import { DEFAULT_TYPE_FORM } from "../type-form";
import { DEFAULT_AUTH_SETTINGS, passwordProblem } from "../password-policy";
import { DEFAULT_DISPLAY, OVERVIEW_WIDGETS } from "../types";
import { issueToken, revokeAll, sendMail, tokenGone, validToken } from "./tokens";
import { clearTemporaryPassword, expireTemporaryPassword, temporaryPasswordOf } from "./temporary-passwords";
import { adminApi, categoriesApi, projectExtras, versionsApi } from "./admin";
import { followMove, timeApi } from "./time";
import { beyondMain, journalTeam, readTeam, sameTeam, swap, teamOf, withMain, type TeamEntry } from "./assignees";
import {
  appliesTo, cannotMoveTickets, connectWriters, hiddenFieldIds, matrixPriority, portalApi, serviceDeskApi, staysPrivate, ticketCommented, ticketCopied, ticketCreated,
  ticketDraft, ticketUpdated, type Acting,
} from "./service-desk";
import { ITSM } from "./service-desk-seed";

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


/**
 * 새 시작일 (server: ProjectCopy.Shift): working-day items keep their place counted in working days from the earliest date,
 * calendar-day items and versions move by the same number of calendar days.
 */
function scheduleShift(anchor: IsoDate | null, start: IsoDate | null | undefined) {
  if (!anchor || !start) return null;
  const cal = workCalendar();
  const firstWorking = (d: IsoDate) => {
    let x = d;
    for (let i = 0; i < 366 && !isWorkingDay(x, cal); i++) x = addDays(x, 1);
    return x;
  };
  const from = firstWorking(anchor);
  const base = firstWorking(start);
  const days = diffDays(anchor, start);
  return {
    calendar: (d: IsoDate | null) => (d ? addDays(d, days) : null),
    working: (d: IsoDate | null) => {
      if (!d) return null;
      let index = 0;
      for (let x = from; x < d; x = addDays(x, 1)) if (isWorkingDay(x, cal)) index++;
      let out = base;
      while (index > 0) {
        out = addDays(out, 1);
        if (isWorkingDay(out, cal)) index--;
      }
      return out;
    },
  };
}

/** S04, same rules as the server's ProjectCopy: modules, types, fields always; the chosen parts; nobody is notified. */
function copyTemplate(t: DbProject, p: DbProject, parts: Set<string>, user: DbUser, startDate?: IsoDate | null) {
  const now = nowIso();
  Object.assign(p, {
    enabledModules: [...t.enabledModules], typeIds: [...t.typeIds], descriptionHtml: p.descriptionHtml || t.descriptionHtml,
    overviewWidgets: t.overviewWidgets ? [...t.overviewWidgets] : undefined, internalComments: t.internalComments,
  });
  for (const f of db.projectCustomFields.filter((x) => x.projectId === t.id)) db.projectCustomFields.push({ projectId: p.id, customFieldId: f.customFieldId });
  if (parts.has("members"))
    for (const m of db.members.filter((x) => x.projectId === t.id)) {
      const same = db.members.find((x) => x.projectId === p.id && x.userId === m.userId && x.groupId === m.groupId);
      if (same) same.roleIds = [...new Set([...same.roleIds, ...m.roleIds])];
      else db.members.push({ ...m, id: newId("m"), projectId: p.id, roleIds: [...m.roleIds], createdAt: now });
    }
  const keep = (u: Id | null) => (u && isMember(u, p.id) ? u : null);
  const ids = new Map<Id, Id>();
  const earliest = (ds: (IsoDate | null)[]) => ds.filter((d): d is IsoDate => !!d).sort()[0] ?? null;
  const anchor =
    (parts.has("workPackages") ? earliest(db.workPackages.filter((w) => w.projectId === t.id).map((w) => w.startDate ?? w.dueDate)) : null) ??
    (parts.has("versions") ? earliest(db.versions.filter((v) => v.projectId === t.id).map((v) => v.startDate ?? v.endDate)) : null);
  const shift = scheduleShift(anchor, startDate);
  if (parts.has("versions"))
    for (const v of db.versions.filter((x) => x.projectId === t.id)) {
      const id = newId("v");
      ids.set(v.id, id);
      db.versions.push({ ...v, id, projectId: p.id, ...(shift ? { startDate: shift.calendar(v.startDate), endDate: shift.calendar(v.endDate) } : {}) });
    }
  if (parts.has("categories"))
    for (const c of db.categories.filter((x) => x.projectId === t.id)) {
      const id = newId("c");
      ids.set(c.id, id);
      db.categories.push({ ...c, id, projectId: p.id, defaultAssigneeId: keep(c.defaultAssigneeId) });
    }
  if (parts.has("workPackages")) {
    const sources = db.workPackages.filter((w) => w.projectId === t.id).sort((a, b) => a.number - b.number);
    const made = sources.map((w) => {
      db.seq.wp += 1;
      const c: DbWp = {
        ...structuredClone(w), id: newId("wp"), number: db.seq.wp, projectId: p.id, authorId: user.id, parentId: null,
        assigneeId: keep(w.assigneeId), responsibleId: keep(w.responsibleId), versionId: w.versionId ? (ids.get(w.versionId) ?? null) : null,
        categoryId: w.categoryId ? (ids.get(w.categoryId) ?? null) : null, lockVersion: 0, createdAt: now, updatedAt: now,
      };
      if (shift) {
        if (w.ignoreNonWorkingDays) (c.startDate = shift.calendar(w.startDate)), (c.dueDate = shift.calendar(w.dueDate));
        else {
          c.startDate = shift.working(w.startDate);
          c.dueDate = shift.working(w.dueDate);
        }
      }
      ids.set(w.id, c.id);
      db.workPackages.push(c);
      return c;
    });
    sources.forEach((w, i) => (made[i].parentId = w.parentId ? (ids.get(w.parentId) ?? null) : null));
    for (const r of db.relations.filter((x) => ids.has(x.fromId) && ids.has(x.toId) && sources.some((w) => w.id === x.fromId)))
      db.relations.push({ ...r, id: newId("rel"), fromId: ids.get(r.fromId)!, toId: ids.get(r.toId)! });
    for (const c of made) db.journals.push({ id: newId("j"), wpId: c.id, version: 1, userId: user.id, notesHtml: "", isInternal: false, changes: {}, cause: null, createdAt: now, updatedAt: now });
    // ITSM 1단계: copied tickets keep their requester and classification (the new project took the template's modules)
    sources.forEach((w, i) => ticketCopied(t, w.id, made[i]));
  }
  const remap = <T,>(v: T): T => {
    let s = JSON.stringify(v);
    for (const [a, b] of ids) s = s.split(a).join(b);
    return JSON.parse(s) as T;
  };
  if (parts.has("boards"))
    for (const b of db.boards.filter((x) => x.projectId === t.id && x.kind !== "subproject")) {
      const columns = b.columns
        .map((c) => ({ ...c, id: newId("col"), from: c.id, valueId: c.valueId && (b.kind === "version" || b.kind === "parent_child") ? (ids.get(c.valueId) ?? null) : b.kind === "assignee" ? keep(c.valueId) : c.valueId }))
        .filter((c) => c.name !== null || c.valueId !== null);
      const col = new Map(columns.map((c) => [c.from, c.id]));
      const cards = parts.has("workPackages");
      db.boards.push({
        ...b, id: newId("b"), projectId: p.id, createdAt: now, filters: remap(b.filters ?? []),
        columns: columns.map(({ from: _from, ...c }, i) => ({ ...c, position: i })),
        order: cards ? Object.fromEntries(Object.entries(b.order).filter(([w]) => ids.has(w)).map(([w, n]) => [ids.get(w)!, n])) : {},
        placed: cards && b.placed ? Object.fromEntries(Object.entries(b.placed).filter(([w, c]) => ids.has(w) && col.has(c)).map(([w, c]) => [ids.get(w)!, col.get(c)!])) : b.placed ? {} : undefined,
      });
    }
  if (parts.has("queries"))
    for (const q of db.queries.filter((x) => x.projectId === t.id && (x.isPublic || x.ownerId === user.id)))
      db.queries.push({ ...q, id: newId("q"), projectId: p.id, ownerId: user.id, spec: remap(q.spec), createdAt: now });
}

/** S04: a new project (blank, from a template, or a copy of source). */
function createProjectFor(user: DbUser, input: ProjectInput, source: DbProject | null = null): DbProject {
  if (!user.isAdmin && !globalPermissions(user).includes("global.create_projects")) throw new ApiError(403, "프로젝트를 만들 권한이 없습니다.");
  const name = input.name.trim();
  if (!name) throw fieldError("name", "프로젝트 이름을 입력하세요.");
  const identifier = input.identifier.trim();
  if (!/^[a-z][a-z0-9-]{0,99}$/.test(identifier)) throw fieldError("identifier", "영문 소문자로 시작하고 영문 소문자, 숫자, 하이픈만 쓸 수 있습니다.");
  if (db.projects.some((p) => p.identifier === identifier)) throw fieldError("identifier", "이미 사용 중인 식별자입니다.");
  if (input.parentId) {
    const parent = db.projects.find((x) => x.id === input.parentId);
    if (!parent || !can(user, parent, "project.edit")) throw fieldError("parent", "상위 프로젝트를 찾을 수 없습니다.");
  }
  const template = source ?? (input.templateId ? visibleProjects(user).find((p) => p.isTemplate && !p.archivedAt && (p.id === input.templateId || p.identifier === input.templateId)) : null);
  if (!source && input.templateId && !template) throw fieldError("template", "템플릿을 찾을 수 없습니다.");
  staysPrivate(!!input.isPublic, template?.enabledModules ?? [], "isPublic");
  const now = nowIso();
  const p: DbProject = {
    id: newId("p"), identifier, name, descriptionHtml: input.descriptionHtml ?? "", status: null, statusExplanation: "",
    isPublic: !!input.isPublic, isTemplate: false, archivedAt: null, parentId: input.parentId ?? null,
    enabledModules: ["work_packages", "gantt", "boards", "time_tracking"], typeIds: db.types.filter((t) => t.isDefault).map((t) => t.id),
    createdAt: now, updatedAt: now, latestActivityAt: now,
  };
  db.projects.push(p);
  db.members.push({ id: newId("m"), projectId: p.id, userId: user.id, groupId: null, roleIds: ["r-manager"], createdAt: now });
  if (template) copyTemplate(template, p, new Set(input.copy ?? []), user, input.startDate);
  return p;
}

/** The notification center's filters, as the server applies them. */
function inFilter(n: { reason: NotificationReason; wpId: string }, narrow?: NotificationFilter): boolean {
  if (narrow?.reasons?.length && !narrow.reasons.includes(n.reason)) return false;
  if (narrow?.project) {
    const wp = db.workPackages.find((w) => w.id === n.wpId);
    const p = db.projects.find((x) => x.identifier === narrow.project || x.id === narrow.project);
    if (!wp || !p || wp.projectId !== p.id) return false;
  }
  return true;
}

/** S06 기준선 (same rule as the server's Baselines): the first change after the point holds the value from then. */
const BASELINE_COLUMNS: Record<string, string> = {
  subject: "subject", typeId: "type", statusId: "status", priorityId: "priority", assigneeId: "assignee", responsibleId: "responsible",
  versionId: "version", startDate: "startDate", dueDate: "dueDate", duration: "duration", doneRatio: "doneRatio", workHours: "work",
  remainingHours: "remainingWork", projectId: "project",
};
function withBaseline(user: DbUser, projectId: string | undefined, spec: QuerySpec, result: QueryResult): QueryResult {
  const b = spec.baseline;
  if (!b) return result;
  const today = todayIso();
  const monthAgo = () => {
    const d = new Date(`${today}T00:00:00Z`);
    d.setUTCMonth(d.getUTCMonth() - 1);
    return d.toISOString().slice(0, 10);
  };
  const day = b.kind === "yesterday" ? addDays(today, -1) : b.kind === "lastWeek" ? addDays(today, -7) : b.kind === "lastMonth" ? monthAgo() : b.kind === "date" ? b.date : null;
  if (!day || day > today) return result;
  const at = new Date(`${day}T00:00:00+09:00`).toISOString();
  /** field → [value then, value now], changed-and-back left out */
  const history = (wpId: string) => {
    const out: Record<string, [unknown, unknown]> = {};
    for (const j of db.journals.filter((x) => x.wpId === wpId && x.createdAt >= at).sort((x, y) => x.version - y.version))
      for (const [field, [from, to]] of Object.entries(j.changes)) {
        if (!(field in out)) out[field] = [from, to];
        else out[field][1] = to;
      }
    for (const [field, [from, to]] of Object.entries(out)) if (String(from) === String(to)) delete out[field];
    return out;
  };
  const columns = (h: Record<string, [unknown, unknown]>, side: 0 | 1) => {
    const out: Record<string, string | null> = {};
    for (const [field, values] of Object.entries(h)) {
      const cf = field.startsWith("cf:") ? db.customFields.find((x) => `cf:${x.id}` === field) : undefined;
      if (cf) out[field] = cfText(cf, values[side] as CustomValue) || null;
      else if (BASELINE_COLUMNS[field]) out[BASELINE_COLUMNS[field]] = display(field, values[side]);
    }
    return out;
  };
  const rows = result.rows.map((r) => {
    if (r.wp.createdAt > at) return { ...r, baseline: { added: true, changed: {} } };
    const changed = columns(history(r.wp.id), 0);
    return { ...r, baseline: Object.keys(changed).length ? { added: false, changed } : null };
  });
  // matched then, not now: put the values from then back on a copy and ask the list again
  const viewable = viewableProjectIds(user);
  const baselineRemoved = db.workPackages
    .filter((w) => w.createdAt <= at && viewable.has(w.projectId) && !listTakes(user, spec, projectId, w))
    .flatMap((w) => {
      const h = history(w.id);
      if (!Object.keys(h).length) return [];
      const then: DbWp = { ...w, customValues: { ...(w.customValues ?? {}) } };
      for (const [field, [from]] of Object.entries(h)) {
        if (field.startsWith("cf:")) {
          if (from == null) delete then.customValues![field.slice(3)];
          else then.customValues![field.slice(3)] = from as CustomValue;
        } else if (field in then) (then as unknown as Record<string, unknown>)[field] = from;
      }
      if (!listTakes(user, spec, projectId, then)) return [];
      const p = db.projects.find((x) => x.id === w.projectId);
      return [{ id: w.id, number: w.number, subject: w.subject, project: p ? { id: p.id, identifier: p.identifier, name: p.name } : null, then: columns(h, 0), now: columns(h, 1) }];
    })
    .sort((a, b) => a.number - b.number)
    .slice(0, 200);
  return { ...result, rows, baselineAt: at, baselineRemoved };
}

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
    theme: u.theme ?? "system",
    hasPassword: !!u.password,
    signInWith: [],
  });
}

const statusOf = (w: DbWp) => db.statuses.find((s) => s.id === w.statusId)!;
const projectOf = (w: DbWp) => db.projects.find((p) => p.id === w.projectId)!;
const FAKE_MAX_BYTES = 1024 * 1024;
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

/** Strongest first: one notification per person, with the best reason they have. */
const REASON_RANK = ["mentioned", "assigned", "responsible", "watched", "created", "commented", "status_changed", "priority_changed", "dates_changed"] as const;

/**
 * Who hears about a journal (architecture.md §3.6), within each person's notification settings (S20):
 * mentions and watching always; assignee and responsible when switched on; and, for anyone in the project
 * who asked for them, new items, comments and status / priority / date changes.
 */
function fanOut(wp: DbWp, journal: DbJournal, actor: DbUser, mentioned: string[] = []) {
  const project = projectOf(wp);
  const recipients = new Map<string, (typeof REASON_RANK)[number]>();
  const offer = (userId: string | null, reason: (typeof REASON_RANK)[number]) => {
    if (!userId || userId === actor.id) return;
    const u = db.users.find((x) => x.id === userId && x.status === "active");
    if (!u || !can(u, project, "wp.view", wp)) return; // never notify people who cannot see the item
    if (journal.isInternal && !can(u, project, "wp.view_internal_comments")) return;
    const cur = recipients.get(userId);
    if (!cur || REASON_RANK.indexOf(reason) < REASON_RANK.indexOf(cur)) recipients.set(userId, reason);
  };
  const setting = (userId: string) => effectiveSetting(userId, project.id);
  mentioned.forEach((id) => offer(id, "mentioned"));
  for (const e of teamOf(wp)) if (setting(e.userId).assignee) offer(e.userId, "assigned");
  if (wp.responsibleId && setting(wp.responsibleId).responsible) offer(wp.responsibleId, "responsible");
  db.watchers.filter((x) => x.wpId === wp.id).forEach((x) => offer(x.userId, "watched"));
  const c = journal.changes;
  const events: [(typeof REASON_RANK)[number], keyof ReturnType<typeof setting>, boolean][] = [
    ["created", "workPackageCreated", "_created" in c],
    ["commented", "commented", !!journal.notesHtml],
    ["status_changed", "statusChanged", "statusId" in c],
    ["priority_changed", "priorityChanged", "priorityId" in c],
    ["dates_changed", "datesChanged", "startDate" in c || "dueDate" in c],
  ];
  if (events.some(([, , happened]) => happened))
    for (const userId of projectUserIds(project.id)) {
      const s = setting(userId);
      const hit = events.find(([, key, happened]) => happened && s[key]);
      if (hit) offer(userId, hit[0]);
    }
  for (const [recipientId, reason] of recipients) {
    const existing = db.notifications.find((n) => n.journalId === journal.id && n.recipientId === recipientId && !n.readAt);
    if (existing) {
      existing.createdAt = journal.updatedAt;
      continue;
    }
    db.notifications.push({ id: newId("n"), recipientId, wpId: wp.id, journalId: journal.id, actorId: actor.id, reason, readAt: null, createdAt: journal.updatedAt });
  }
}

const isDescendant = (id: string, ancestorId: string): boolean => {
  const parent = db.projects.find((x) => x.id === id)?.parentId;
  return !!parent && (parent === ancestorId || isDescendant(parent, ancestorId));
};

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
  requirePerm(user, project, "wp.edit", "이 작업을 수정할 권한이 없습니다.", wp);
  return toWp(patchWp(user, wp, project, patch, "member"), user);
}

/**
 * The saved row (wp itself when nothing changed). acting "portal": a requester moves their own ticket under the
 * 요청자 role's workflow (the portal decided who may); a conflict then carries no work package.
 */
function patchWp(user: DbUser, wp: DbWp, project: DbProject, patch: WorkPackagePatch, acting: Acting): DbWp {
  if (patch.lockVersion !== wp.lockVersion) {
    throw new ApiError(409, "다른 사람이 먼저 수정했습니다.", "최신 내용을 불러온 뒤 다시 저장하세요.", acting === "portal" ? {} : { current: toWp(wp, user) });
  }

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
    const allowed = acting === "portal" ? allowedFor([ITSM.requesterRole], user.id, wp, user.id) : allowedStatusIds(user, wp);
    if (!allowed.includes(patch.statusId)) {
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
  // 공동 담당자: the whole list, or the main assignee the older way
  let team: TeamEntry[] | null = null;
  const before = teamOf(wp);
  if (patch.assignees !== undefined) {
    const r = readTeam(patch.assignees, (id) => isMember(id, project.id));
    if (typeof r === "string") throw fieldError("assignees", r);
    team = r;
  } else if (patch.assigneeId !== undefined) {
    if (patch.assigneeId !== null && !isMember(patch.assigneeId, project.id)) throw fieldError("assignee", "프로젝트 멤버만 지정할 수 있습니다.");
    team = withMain(before, patch.assigneeId);
  }
  if (team && !sameTeam(before, team)) {
    set("assigneeId", team[0]?.userId ?? null);
    if (beyondMain(before) || beyondMain(team)) changes.assignees = [journalTeam(before), journalTeam(team)];
    next.team = team;
  }
  for (const [key, field] of [["responsibleId", "responsible"]] as const) {
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
  // progress (P1–P3): typed values first, then what follows from them, the status and the S32 settings
  if (patch.workHours !== undefined) {
    if (patch.workHours !== null && patch.workHours < 0) throw fieldError("work", "0 이상으로 입력하세요.");
    set("workHours", patch.workHours);
  }
  const locked = progressLocked({ statusId: next.statusId ?? wp.statusId });
  if (locked && (patch.doneRatio !== undefined || patch.remainingHours !== undefined))
    throw new ApiError(422, "완료 %는 상태를 따릅니다.", "진행률 설정(상태 기반, 또는 닫힘 상태면 100%) 때문에 완료 %와 남은 작업은 직접 바꿀 수 없습니다.");
  if (patch.doneRatio !== undefined) {
    if (patch.doneRatio !== null && (patch.doneRatio < 0 || patch.doneRatio > 100)) throw fieldError("doneRatio", "0에서 100 사이로 입력하세요.");
    set("doneRatio", patch.doneRatio);
  }
  if (patch.remainingHours !== undefined) {
    const work = next.workHours !== undefined ? next.workHours : wp.workHours;
    if (patch.remainingHours !== null && patch.remainingHours < 0) throw fieldError("remainingWork", "0 이상으로 입력하세요.");
    if (patch.remainingHours !== null && work !== null && patch.remainingHours > work) throw fieldError("remainingWork", "남은 작업은 작업량보다 클 수 없습니다.");
    set("remainingHours", patch.remainingHours);
  }
  const progress = deriveProgress(
    { ...wp, ...next },
    { remaining: patch.remainingHours !== undefined, other: patch.workHours !== undefined || patch.doneRatio !== undefined },
  );
  set("doneRatio", progress.doneRatio);
  set("remainingHours", progress.remainingHours);

  if (patch.customValues) {
    const r = applyValues({ ...wp, ...next }, patch.customValues);
    if (Object.keys(r.changes).length) {
      Object.assign(changes, r.changes);
      next.customValues = r.values;
    }
  }

  if (!Object.keys(changes).length) return wp;

  // 서비스 데스크: may refuse (ticket ↔ ordinary type) before anything is stored, then pauses, resolves and recomputes the SLA
  ticketUpdated(user, wp, { ...wp, ...next });
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
  return wp;
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

/** Direct subprojects of a project the person can see work in (a subproject board's columns). */
const subprojectsOf = (user: DbUser, projectId: Id) => db.projects.filter((p) => p.parentId === projectId && !p.archivedAt && can(user, p, "wp.view"));

function boardColumns(user: DbUser, b: DbBoard, project: DbProject, filters: QueryFilter[] = b.filters ?? []): BoardColumn[] {
  const inBoard = new Set([project.id, ...(b.kind === "subproject" ? subprojectsOf(user, project.id).map((p) => p.id) : [])]);
  const wps = db.workPackages.filter((w) => inBoard.has(w.projectId) && filters.every((f) => matches(w, f, user)));
  const order = (w: DbWp) => b.order[w.id] ?? w.number;
  const shown = new Set(subprojectsOf(user, project.id).map((p) => p.id));
  return b.columns
    .filter((c) => b.kind !== "subproject" || (c.valueId !== null && shown.has(c.valueId)))
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
        cards = wps.filter((w) => teamOf(w).some((e) => e.userId === c.valueId) && !statusOf(w).isClosed);
      } else if (b.kind === "version" && c.valueId) {
        title = db.versions.find((v) => v.id === c.valueId)?.name ?? "";
        cards = wps.filter((w) => w.versionId === c.valueId);
      } else if (b.kind === "subproject" && c.valueId) {
        title = db.projects.find((p) => p.id === c.valueId)?.name ?? "";
        cards = wps.filter((w) => w.projectId === c.valueId);
      } else if (b.kind === "parent_child" && c.valueId) {
        const parent = db.workPackages.find((w) => w.id === c.valueId);
        title = parent ? `#${parent.number} ${parent.subject}` : "";
        cards = wps.filter((w) => w.parentId === c.valueId);
      } else if (b.kind === "basic") {
        cards = wps.filter((w) => b.placed?.[w.id] === c.id);
      }
      return { id: c.id, position: c.position, title, color, valueId: c.valueId, cards: cards.sort((x, y) => order(x) - order(y)).map((w) => toWp(w, user)) };
    });
}

function toBoard(user: DbUser, b: DbBoard, look?: QueryFilter[] | null): Board {
  const project = db.projects.find((p) => p.id === b.projectId)!;
  const filters = look ?? b.filters ?? [];
  return { id: b.id, name: b.name, kind: b.kind, isPublic: b.isPublic, createdAt: b.createdAt, projectId: b.projectId, columns: boardColumns(user, b, project, filters), canManage: can(user, project, "board.manage"), filters: clone(filters) };
}

/** A card before `beforeNumber` (null: last) in a column, at a fractional position; basic boards also remember the list. */
function placeCard(user: DbUser, b: DbBoard, columnId: string, wp: DbWp, beforeNumber: number | null) {
  const project = db.projects.find((p) => p.id === b.projectId)!;
  const cards = boardColumns(user, b, project, []).find((c) => c.id === columnId)!.cards.filter((c) => c.number !== wp.number);
  const pos = (n: number) => b.order[db.workPackages.find((w) => w.number === n)!.id] ?? n;
  const idx = beforeNumber === null ? cards.length : cards.findIndex((c) => c.number === beforeNumber);
  const before = idx > 0 ? pos(cards[idx - 1].number) : null;
  const after = idx >= 0 && idx < cards.length ? pos(cards[idx].number) : null;
  b.order[wp.id] = before === null && after === null ? 1 : before === null ? after! - 1 : after === null ? before + 1 : (before + after) / 2;
  if (b.kind === "basic") b.placed = { ...(b.placed ?? {}), [wp.id]: columnId };
}

function checkBoardFilters(user: DbUser, filters: QueryFilter[]) {
  const sample = db.workPackages[0];
  try {
    if (sample) filters.forEach((f) => matches(sample, f, user));
  } catch {
    throw new ApiError(422, "필터를 확인하세요.");
  }
}

const toShare = (s: (typeof db.shares)[number]): WorkPackageShare =>
  clone({ id: s.id, user: userRef(s.userId)!, role: s.role, sharedBy: userRef(s.createdBy), createdAt: s.createdAt });

/* ---------- notifications ---------- */

const REASON_TEXT: Record<string, (actor: string) => string> = {
  mentioned: (a) => `${a}님이 코멘트에서 나를 언급했습니다`,
  assigned: (a) => `${a}님이 내가 담당한 작업을 바꿨습니다`,
  responsible: (a) => `${a}님이 내가 책임자인 작업을 바꿨습니다`,
  watched: (a) => `${a}님이 주시 중인 작업을 바꿨습니다`,
  shared: (a) => `${a}님이 나에게 작업을 공유했습니다`,
  commented: (a) => `${a}님이 코멘트를 남겼습니다`,
  status_changed: (a) => `${a}님이 상태를 바꿨습니다`,
  priority_changed: (a) => `${a}님이 우선순위를 바꿨습니다`,
  dates_changed: (a) => `${a}님이 날짜를 바꿨습니다`,
  date_alert_due: () => "완료일이 다가옵니다",
  date_alert_start: () => "시작일이 다가옵니다",
  date_alert_overdue: () => "기한이 지났습니다",
  sla_warning: () => "SLA 기한이 다가옵니다",
  sla_breached: () => "SLA 기한을 넘겼습니다",
};

function notificationSummary(n: (typeof db.notifications)[number], user: DbUser): string {
  const actor = userRef(n.actorId)?.name ?? "누군가";
  const j = n.journalId ? db.journals.find((x) => x.id === n.journalId) : null;
  if (n.reason === "assigned" && j?.changes.assigneeId?.[1] === user.id) return `${actor}님이 나를 담당자로 지정했습니다`;
  const listed = (v: unknown) => Array.isArray(v) && v.some((e: { userId?: string }) => e.userId === user.id);
  if (n.reason === "assigned" && j?.changes.assignees && listed(j.changes.assignees[1]) && !listed(j.changes.assignees[0])) return `${actor}님이 나를 공동 담당자로 지정했습니다`;
  let text = (REASON_TEXT[n.reason] ?? (() => "작업이 바뀌었습니다"))(actor);
  if (j && n.reason !== "mentioned") {
    const d = toJournal(j, user).details.filter((x) => x.field !== "_created")[0];
    if (d) text += ` · ${d.label}${d.to ? `: ${d.from ?? "없음"} → ${d.to}` : ""}`;
    else if (j.changes._created) text = `${actor}님이 작업을 만들었습니다`;
  }
  return text;
}

/** A board I may change (S15): 404 when I cannot see it, 403 without board.manage. */
function managedBoard(id: string) {
  const user = sessionUser();
  const b = db.boards.find((x) => x.id === id);
  const p = b && db.projects.find((x) => x.id === b.projectId);
  if (!b || !p || !can(user, p, "board.view")) throw new ApiError(404, "보드를 찾을 수 없습니다.");
  requirePerm(user, p, "board.manage", "보드를 바꿀 권한이 없습니다.");
  return { user, b };
}

/** POST /projects/{p}/work-packages (also what a copy goes through). */
function createWp(user: DbUser, projectId: string, input: WorkPackageInput): WorkPackage {
  const p = findProject(user, projectId);
  requirePerm(user, p, "wp.create", "이 프로젝트에 작업을 만들 권한이 없습니다.");
  return toWp(insertWp(user, p, input, "member"), user);
}

/** A new row; acting "portal": a requester's ticket (the portal checked who may and passes only portal fields). */
function insertWp(user: DbUser, p: DbProject, input: WorkPackageInput, acting: Acting): DbWp {
  const subject = input.subject?.trim() ?? "";
  if (!subject) throw fieldError("subject", "제목을 입력하세요.");
  if (subject.length > 255) throw fieldError("subject", "제목은 255자 이하로 입력하세요.");
  if (!p.typeIds.includes(input.typeId)) throw fieldError("type", "이 프로젝트에서 쓰지 않는 유형입니다.");
  // 서비스 데스크: a ticket starts as 접수 with the matrix priority and gets its service_tickets row
  const ticket = appliesTo(p, input.typeId) ? ticketDraft(user, input, acting === "portal" ? "portal" : "agent") : null;
  const team = input.assignees ? readTeam(input.assignees, (id) => isMember(id, p.id)) : null;
  if (typeof team === "string") throw fieldError("assignees", team);
  if (team?.length) input = { ...input, assigneeId: team[0].userId };
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
  // portal: a required field the requester cannot see must not block (or reveal itself in) the submission
  const customValues = initialValues(p.id, input.typeId, input.customValues, acting === "portal" ? hiddenFieldIds() : undefined);
  const now = nowIso();
  db.seq.wp += 1;
  const base: DbWp = {
    id: newId("wp"), number: db.seq.wp, projectId: p.id, typeId: input.typeId,
    statusId: input.statusId ?? (ticket ? ITSM.received : db.statuses.find((s) => s.isDefault)!.id),
    priorityId: input.priorityId ?? (ticket ? matrixPriority(p.id, ticket.impact, ticket.urgency) : null) ?? db.priorities.find((x) => x.isDefault)!.id,
    subject, descriptionHtml: input.descriptionHtml ?? "", authorId: user.id, assigneeId, ...(team?.length ? { team } : {}),
    responsibleId: input.responsibleId ?? null, versionId: input.versionId ?? null, categoryId: category?.id ?? null, parentId,
    startDate: null, dueDate: null, duration: null, ignoreNonWorkingDays: false, scheduleManually: true,
    workHours: null, remainingHours: null, doneRatio: null, customValues, lockVersion: 0, createdAt: now, updatedAt: now,
  };
  Object.assign(base, resolveDates(base, { startDate: input.startDate, dueDate: input.dueDate }));
  Object.assign(base, deriveProgress(base));
  db.workPackages.push(base);
  if (ticket) ticketCreated(base, ticket);
  const j = writeJournal(base, user, { _created: [null, true] });
  fanOut(base, j, user);
  for (const c of propagate([base])) {
    c.wp.lockVersion += 1;
    writeJournal(c.wp, user, c.changes, "", c.cause);
  }
  return base;
}

connectWriters({ insert: insertWp, patch: patchWp, update: applyPatch, journal: writeJournal, fanOut });

/** DELETE /work-packages/{n}: children deleted too, or moved up to the grandparent. */
function removeWp(user: DbUser, number: number, childrenMode: "delete" | "reparent") {
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
  db.attachments = db.attachments.filter((a) => !doomed.has(a.wpId));
  db.relations = db.relations.filter((r) => !doomed.has(r.fromId) && !doomed.has(r.toId));
  db.journals = db.journals.filter((j) => !doomed.has(j.wpId));
  db.watchers = db.watchers.filter((x) => !doomed.has(x.wpId));
  db.notifications = db.notifications.filter((x) => !doomed.has(x.wpId));
  db.serviceTickets = db.serviceTickets.filter((x) => !doomed.has(x.workPackageId));
}

/** A copy goes through the create rules: what still fits the target (members, its versions and categories, its active fields). */
function copyWp(user: DbUser, w: DbWp, target: DbProject, subject: string, parentNumber: number | null): WorkPackage {
  if (!target.typeIds.includes(w.typeId)) throw fieldError("targetProject", `#${w.number}의 유형 '${db.types.find((t) => t.id === w.typeId)?.name ?? "?"}'을(를) ${target.name}에서 쓰지 않습니다.`);
  const same = target.id === w.projectId;
  const parent = parentNumber ?? (same && w.parentId && can(user, target, "wp.manage_subtasks") ? (db.workPackages.find((x) => x.id === w.parentId)?.number ?? null) : null);
  const active = new Set(activeFields(target.id, w.typeId).map((f) => f.id));
  const made = createWp(user, target.id, {
    typeId: w.typeId,
    subject,
    descriptionHtml: w.descriptionHtml,
    statusId: w.statusId,
    priorityId: w.priorityId,
    // 공동 담당자: the members among them come along, with their hours
    assignees: teamOf(w).filter((e) => isMember(e.userId, target.id)),
    responsibleId: w.responsibleId && isMember(w.responsibleId, target.id) ? w.responsibleId : null,
    versionId: same && can(user, target, "wp.assign_versions") ? w.versionId : null,
    categoryId: same ? w.categoryId : null,
    parentNumber: parent,
    startDate: w.startDate,
    dueDate: w.dueDate,
    customValues: Object.fromEntries(Object.entries(w.customValues ?? {}).filter(([k]) => active.has(k))),
  });
  // ITSM 1단계: a copied ticket keeps the original's requester and classification and sends no new receipt
  const copy = db.workPackages.find((x) => x.id === made.id)!;
  ticketCopied(target, w.id, copy);
  return w.workHours === null ? toWp(copy, user) : applyPatch(user, made.number, { lockVersion: made.lockVersion, workHours: w.workHours });
}

function copyChildren(user: DbUser, sourceParentId: string, target: DbProject, newParentNumber: number) {
  for (const kid of db.workPackages.filter((x) => x.parentId === sourceParentId).sort((a, b) => a.number - b.number)) {
    const made = copyWp(user, kid, target, kid.subject, newParentNumber);
    copyChildren(user, kid.id, target, made.number);
  }
}

/** Keeps the number, takes the subtree along; the old project's version and category and non-members are cleared. */
function moveWp(user: DbUser, number: number, input: MoveInput): WorkPackage {
  const { wp: root, project } = findWp(user, number);
  if (input.lockVersion !== undefined && input.lockVersion !== root.lockVersion) throw new ApiError(409, "다른 사람이 먼저 수정했습니다.", "최신 내용을 불러온 뒤 다시 옮기세요.");
  if (!can(user, project, "wp.move", root)) throw new ApiError(403, "이 작업을 옮길 권한이 없습니다.");
  if (!input.targetProjectId) throw fieldError("targetProject", "옮길 프로젝트를 고르세요.");
  const target = findProject(user, input.targetProjectId);
  requirePerm(user, target, "wp.create", "대상 프로젝트에 작업을 만들 권한이 없습니다.");
  if (target.id === root.projectId) throw fieldError("targetProject", "지금 있는 프로젝트입니다.");
  const typeId = input.typeId ?? root.typeId;
  if (!target.typeIds.includes(typeId)) throw fieldError("type", "대상 프로젝트에서 쓰는 유형을 고르세요.");
  const moving = [root];
  for (let i = 0; i < moving.length; i++) moving.push(...db.workPackages.filter((x) => x.parentId === moving[i].id).sort((a, b) => a.number - b.number));
  cannotMoveTickets(moving);
  for (const w of moving.slice(1))
    if (!target.typeIds.includes(w.typeId)) throw fieldError("type", `하위 작업 #${w.number}의 유형 '${db.types.find((t) => t.id === w.typeId)?.name ?? "?"}'을(를) 대상 프로젝트에서 쓰지 않습니다.`);
  for (const w of moving) {
    const changes: DbJournal["changes"] = { projectId: [w.projectId, target.id] };
    w.projectId = target.id;
    if (w === root && typeId !== w.typeId) (changes.typeId = [w.typeId, typeId]), (w.typeId = typeId);
    if (w.versionId && db.versions.find((v) => v.id === w.versionId)?.projectId !== target.id) (changes.versionId = [w.versionId, null]), (w.versionId = null);
    if (w.categoryId) (changes.categoryId = [w.categoryId, null]), (w.categoryId = null);
    {
      const team = teamOf(w);
      const staying = team.filter((e) => isMember(e.userId, target.id));
      if (staying.length !== team.length) {
        const main = staying[0]?.userId ?? null;
        if (main !== w.assigneeId) changes.assigneeId = [w.assigneeId, main];
        if (beyondMain(team) || beyondMain(staying)) changes.assignees = [journalTeam(team), journalTeam(staying)];
        w.assigneeId = main;
        w.team = staying;
      }
    }
    if (w.responsibleId && !isMember(w.responsibleId, target.id)) (changes.responsibleId = [w.responsibleId, null]), (w.responsibleId = null);
    w.lockVersion += 1;
    w.updatedAt = nowIso();
    followMove(w.id, target.id); // its time entries move with it
    writeJournal(w, user, changes);
  }
  return toWp(root, user);
}

/* ---------- the API ---------- */

const authSettings = () => db.settings.auth ?? DEFAULT_AUTH_SETTINGS;
function checkNewPassword(password: string, field: string) {
  const problem = passwordProblem(authSettings(), password);
  if (problem) throw fieldError(field, problem);
}

export const fakeApi: Api = {
  account: {
    ...accountApi,
    updateProfile: (input) =>
      run(() => {
        const user = sessionUser();
        if (input.name !== undefined) {
          const name = input.name.trim();
          if (!name) throw fieldError("name", "이름을 입력하세요.");
          if (name.length > 100) throw fieldError("name", "이름은 100자 이하로 입력하세요.");
          user.name = name;
        }
        if (input.timeZone !== undefined) {
          const zones = Intl.supportedValuesOf("timeZone");
          if (input.timeZone !== "UTC" && !zones.includes(input.timeZone)) throw fieldError("timeZone", "알 수 없는 시간대입니다.");
          user.timeZone = input.timeZone;
        }
        if (input.theme !== undefined) {
          if (!["system", "light", "dark", "high_contrast"].includes(input.theme)) throw fieldError("theme", "테마를 고르세요.");
          user.theme = input.theme;
        }
        return toMe(user);
      }),
    changePassword: (currentPassword, newPassword) =>
      run(() => {
        const user = sessionUser();
        if (currentPassword !== user.password) throw fieldError("currentPassword", "현재 비밀번호가 맞지 않습니다.");
        checkNewPassword(newPassword, "newPassword");
        if (newPassword === currentPassword) throw fieldError("newPassword", "지금 비밀번호와 다른 비밀번호를 정하세요.");
        user.password = newPassword;
        clearTemporaryPassword(user.id);
      }),
    sendPasswordSetup: () =>
      run(() => {
        const u = sessionUser();
        if (u.password) throw new ApiError(422, "이미 비밀번호가 있습니다.", "비밀번호 바꾸기를 쓰세요.");
        revokeAll(u.id, "password_reset");
        sendMail(u.email, "[openPMS] 비밀번호 만들기", `/password-reset?token=${issueToken(u.id, "password_reset", 60, null)}`);
      }),
    apiTokens: () => run(() => (db.apiTokens ?? []).filter((x) => x.userId === sessionUser().id).map(({ userId: _u, ...t }) => clone(t))),
    createApiToken: (input) =>
      run(() => {
        const user = sessionUser();
        if (db.apiTokensEnabled === false) throw new ApiError(403, "관리자가 API 토큰을 꺼 두었습니다.");
        if (!input.name.trim() || input.name.trim().length > 60) throw fieldError("name", "토큰 이름을 1~60자로 입력하세요.");
        const now = nowIso();
        const t = { id: newId("tok"), userId: user.id, name: input.name.trim(), scope: input.scope, createdAt: now, lastUsedAt: null, expiresAt: input.expiresInDays ? new Date(Date.now() + input.expiresInDays * 86_400_000).toISOString() : null };
        db.apiTokens = [t, ...(db.apiTokens ?? [])];
        const { userId: _u, ...token } = t;
        return { token: clone(token), secret: `opms_demo_${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}` };
      }),
    revokeApiToken: (id) =>
      run(() => {
        const user = sessionUser();
        if (!(db.apiTokens ?? []).some((x) => x.id === id && x.userId === user.id)) throw new ApiError(404, "토큰을 찾을 수 없습니다.");
        db.apiTokens = (db.apiTokens ?? []).filter((x) => x.id !== id);
      }),
    // the demo has one session per browser: nothing else to end
    endOtherSessions: () => run(() => void sessionUser()),
  },
  auth: {
    login: (email, password) =>
      run(() => {
        const u = db.users.find((x) => x.email.toLowerCase() === email.trim().toLowerCase());
        if (u?.status === "locked") throw new ApiError(423, "잠긴 계정입니다.", "관리자에게 계정 잠금 해제를 요청하세요.");
        if (!u || !u.password || u.password !== password || u.status !== "active") throw new ApiError(401, "이메일 또는 비밀번호가 올바르지 않습니다.");
        const temporary = temporaryPasswordOf(u.id);
        if (temporary) {
          if (temporary.expiresAt <= nowIso()) {
            expireTemporaryPassword(u.id);
            throw new ApiError(401, "임시 비밀번호가 만료되었습니다.", "관리자에게 다시 발급을 요청하세요.", { current: { code: "temporary_password_expired" } });
          }
          revokeAll(u.id, "password_change");
          const changeToken = issueToken(u.id, "password_change", 10, null);
          throw new ApiError(403, "새 비밀번호를 정해야 합니다.", "임시 비밀번호로는 바로 쓸 수 없습니다. 새 비밀번호를 정하세요.", { current: { code: "password_change_required", changeToken } });
        }
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
    passwordPolicy: () =>
      run(() => {
        const { minLength, requiredClasses, minClasses, resetEnabled } = authSettings();
        return { minLength, requiredClasses, minClasses, resetEnabled };
      }),
    requestPasswordReset: (email) =>
      run(() => {
        if (!authSettings().resetEnabled) throw new ApiError(403, "비밀번호 재설정을 쓰지 않습니다. 관리자에게 문의하세요.");
        const u = db.users.find((x) => x.email.toLowerCase() === email.trim().toLowerCase());
        if (u?.status !== "active") return; // the same answer either way (no enumeration)
        revokeAll(u.id, "password_reset"); // only the newest link works
        const token = issueToken(u.id, "password_reset", 60, null);
        sendMail(u.email, "[openPMS] 비밀번호 재설정", `/password-reset?token=${token}`);
      }),
    confirmPasswordReset: (token, newPassword) =>
      run(() => {
        const t = validToken(token, "password_reset");
        checkNewPassword(newPassword, "newPassword");
        const u = db.users.find((x) => x.id === t.userId)!;
        u.password = newPassword;
        clearTemporaryPassword(u.id);
        revokeAll(u.id, "password_reset");
        if (db.sessionUserId === u.id) db.sessionUserId = null; // a reset signs the account out everywhere
      }),
    invitation: (token) =>
      run(() => {
        const t = validToken(token, "invitation");
        const u = db.users.find((x) => x.id === t.userId);
        if (u?.status !== "invited") throw tokenGone();
        return { email: u.email, name: u.name, invitedBy: db.users.find((x) => x.id === t.createdBy)?.name ?? null };
      }),
    acceptInvitation: (token, input) =>
      run(() => {
        const t = validToken(token, "invitation");
        const name = (input.name ?? "").trim();
        if (!name || name.length > 100) throw fieldError("name", "이름을 1~100자로 입력하세요.");
        checkNewPassword(input.password, "password");
        const u = db.users.find((x) => x.id === t.userId);
        if (u?.status !== "invited") throw tokenGone();
        Object.assign(u, { name, password: input.password, status: "active", lastLoginAt: nowIso() });
        clearTemporaryPassword(u.id);
        revokeAll(u.id, "invitation");
        db.sessionUserId = u.id;
        return toMe(u);
      }),
    changeTemporaryPassword: (token, newPassword) =>
      run(() => {
        const late = () => new ApiError(410, "시간이 지났습니다.", "임시 비밀번호로 다시 로그인하세요.");
        let t: ReturnType<typeof validToken>;
        try {
          t = validToken(token, "password_change");
        } catch {
          throw late();
        }
        const u = db.users.find((x) => x.id === t.userId);
        if (u?.status !== "active") throw late();
        const temporary = temporaryPasswordOf(u.id);
        if (!temporary || temporary.expiresAt <= nowIso()) throw late();
        checkNewPassword(newPassword, "newPassword");
        if (newPassword === u.password) throw fieldError("newPassword", "임시 비밀번호와 다른 비밀번호를 정하세요.");
        u.password = newPassword;
        u.lastLoginAt = nowIso();
        clearTemporaryPassword(u.id);
        revokeAll(u.id, "password_change");
        db.sessionUserId = u.id;
        return toMe(u);
      }),
    ssoProviders: () => run(() => (db.settings.oidcProviders ?? []).filter((p) => p.enabled).map((p) => ({ slug: p.slug, displayName: p.displayName }))),
    // there is no provider to go to in the browser-only data layer: the login page explains it
    ssoStartUrl: () => "/login?sso_error=fake",
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
        if (f === "templates") list = list.filter((p) => p.isTemplate);
        if (params.status) list = list.filter((p) => p.status === params.status);
        if (params.q) list = list.filter((p) => p.name.toLowerCase().includes(params.q!.toLowerCase()) || p.identifier.includes(params.q!.toLowerCase()));
        list.sort((a, b) => a.name.localeCompare(b.name, "ko"));
        const page = params.page ?? 1;
        const pageSize = params.pageSize ?? 20;
        return { items: list.slice((page - 1) * pageSize, page * pageSize).map((p) => toProject(p, user)), total: list.length, page, pageSize };
      }),
    get: (id) => run(() => toProject(findProject(sessionUser(), id), sessionUser())),
    create: (input) => run(() => toProject(createProjectFor(sessionUser(), input), sessionUser())),
    copy: (id, input) =>
      run(() => {
        const user = sessionUser();
        const source = findProject(user, id);
        requirePerm(user, source, "project.edit", "이 프로젝트를 복사할 권한이 없습니다.");
        return toProject(createProjectFor(user, { ...input, templateId: null }, source), user);
      }),
    update: (id, patch) =>
      run(() => {
        const user = sessionUser();
        const p = findProject(user, id);
        requirePerm(user, p, patch.enabledModules ? "project.manage_modules" : "project.edit");
        if (patch.overviewWidgets && patch.overviewWidgets.some((w) => !(OVERVIEW_WIDGETS as readonly string[]).includes(w))) throw fieldError("overviewWidgets", "알 수 없는 위젯이 있습니다.");
        if (patch.parentId !== undefined && patch.parentId !== null) {
          const parent = db.projects.find((x) => x.id === patch.parentId);
          if (!parent || !can(user, parent, "project.edit")) throw fieldError("parent", "상위 프로젝트를 찾을 수 없습니다.");
          for (let a: DbProject | undefined = parent; a; a = a.parentId ? db.projects.find((x) => x.id === a!.parentId) : undefined)
            if (a.id === p.id) throw fieldError("parent", "자기 자신이나 하위 프로젝트를 상위로 정할 수 없습니다.");
        }
        if (patch.isPublic !== undefined || patch.enabledModules !== undefined)
          staysPrivate(patch.isPublic ?? p.isPublic, patch.enabledModules ?? p.enabledModules, patch.isPublic !== undefined ? "isPublic" : "enabledModules");
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
          defaults: { statusId: def.id, priorityId: db.priorities.find((x) => x.isDefault)!.id, descriptionHtml: type.descriptionTemplateHtml ?? "" },
          required: ["subject"],
          customFields: activeFields(p.id, type.id).map(toField),
        });
      }),
    create: (projectId, input: WorkPackageInput) => run(() => createWp(sessionUser(), projectId, input)),
    get: (number) => run(() => toWp(findWp(sessionUser(), number).wp, sessionUser())),
    update: (number, patch) => run(() => applyPatch(sessionUser(), number, patch)),
    remove: (number, childrenMode) => run(() => removeWp(sessionUser(), number, childrenMode)),
    copy: (number, input) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        const target = input.targetProjectId ? findProject(user, input.targetProjectId) : project;
        const made = copyWp(user, wp, target, input.subject?.trim() || wp.subject, null);
        if (input.includeChildren) copyChildren(user, wp.id, target, made.number);
        return toWp(db.workPackages.find((w) => w.number === made.number)!, user);
      }),
    move: (number, input) => run(() => moveWp(sessionUser(), number, input)),
    bulk: (input) =>
      run(() => {
        const user = sessionUser();
        const numbers = [...new Set(input.numbers)];
        if (!numbers.length) throw new ApiError(422, "작업을 고르세요.");
        if (numbers.length > 200) throw new ApiError(422, "한 번에 200건까지 바꿀 수 있습니다.");
        if (input.action === "update" && !Object.keys(input.changes ?? {}).length) throw new ApiError(422, "바꿀 내용을 고르세요.");
        // all or nothing: the fake keeps no transaction, so it takes a copy to go back to
        const before = structuredClone(db);
        const failures: { field: string; message: string }[] = [];
        // a copy takes chosen parents before their chosen children, and hangs the children's copies under the parents' copies
        const copies = new Map<Id, number>();
        if (input.action === "copy") {
          const chosen = new Map(db.workPackages.filter((w) => numbers.includes(w.number)).map((w) => [w.id, w]));
          const depth = (w: DbWp) => {
            let d = 0;
            for (let p = w.parentId; p && chosen.has(p) && d < 100; p = chosen.get(p)!.parentId) d++;
            return d;
          };
          numbers.sort((a, b) => {
            const wa = db.workPackages.find((w) => w.number === a);
            const wb = db.workPackages.find((w) => w.number === b);
            return (wa ? depth(wa) : 0) - (wb ? depth(wb) : 0) || a - b;
          });
        }
        for (const n of numbers) {
          try {
            if (input.action === "update") {
              const { wp } = findWp(user, n);
              applyPatch(user, n, { ...input.changes, lockVersion: wp.lockVersion });
            } else if (input.action === "move") {
              const { wp } = findWp(user, n);
              const target = findProject(user, input.targetProjectId ?? "");
              if (wp.projectId !== target.id) moveWp(user, n, { targetProjectId: target.id, lockVersion: wp.lockVersion });
            } else if (input.action === "copy") {
              const { wp, project } = findWp(user, n);
              const target = input.targetProjectId ? findProject(user, input.targetProjectId) : project;
              const made = copyWp(user, wp, target, wp.subject, wp.parentId ? (copies.get(wp.parentId) ?? null) : null);
              copies.set(wp.id, made.number);
            } else if (db.workPackages.some((w) => w.number === n)) removeWp(user, n, "reparent");
          } catch (e) {
            if (!(e instanceof ApiError)) throw e;
            failures.push({ field: `#${n}`, message: e.fieldErrors[0]?.message ?? (e.message !== e.title ? `${e.title} ${e.message}` : e.title) });
          }
        }
        if (failures.length) {
          Object.assign(db, before);
          throw new ApiError(422, "아무것도 바꾸지 않았습니다.", `${failures.length}건에서 문제가 있어 전체를 되돌렸습니다.`, { fieldErrors: failures });
        }
        return { count: numbers.length };
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
    comment: (number, bodyHtml, internal = false) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        requirePerm(user, project, "wp.comment", "코멘트를 쓸 권한이 없습니다.", wp);
        if (internal) {
          if (!project.internalComments) throw new ApiError(422, "이 프로젝트는 내부 코멘트를 쓰지 않습니다.", "프로젝트 설정 · 작업 설정에서 켤 수 있습니다.");
          requirePerm(user, project, "wp.add_internal_comments", "내부 코멘트를 쓸 권한이 없습니다.");
        }
        if (!bodyHtml.replace(/<[^>]*>/g, "").trim()) throw fieldError("comment", "코멘트를 입력하세요.");
        const j = writeJournal(wp, user, {}, bodyHtml, null, internal);
        fanOut(wp, j, user, mentionsIn(bodyHtml));
        ticketCommented(user, wp, internal);
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
    attachments: (number) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        const canDelete = can(user, project, "wp.attach", wp);
        return db.attachments
          .filter((a) => a.wpId === wp.id)
          .map((a) => ({ id: a.id, fileName: a.fileName, contentType: a.contentType, byteSize: a.byteSize, author: userRef(a.authorId)!, createdAt: a.createdAt, url: a.dataUrl, canDelete }));
      }),
    upload: async (number, file) => {
      // the demo keeps bytes in localStorage, so it takes small files only (the real API takes 25MB)
      if (file.size > FAKE_MAX_BYTES) throw new ApiError(413, "파일이 너무 큽니다.", "데모에서는 파일 하나를 1MB까지 올릴 수 있습니다.");
      const dataUrl = await new Promise<string>((resolve, reject) => {
        const r = new FileReader();
        r.onload = () => resolve(String(r.result));
        r.onerror = () => reject(r.error);
        r.readAsDataURL(file);
      });
      return run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        requirePerm(user, project, "wp.attach", "파일을 첨부할 권한이 없습니다.", wp);
        if (!file.size) throw fieldError("file", "빈 파일은 올릴 수 없습니다.");
        const a = { id: newId("att"), wpId: wp.id, fileName: file.name.split(/[\\/]/).pop() || "file", contentType: file.type || "application/octet-stream", byteSize: file.size, authorId: user.id, createdAt: nowIso(), dataUrl };
        db.attachments.push(a);
        const j = writeJournal(wp, user, { [`attachment:${a.id}`]: [null, a.fileName] });
        fanOut(wp, j, user);
        return { id: a.id, fileName: a.fileName, contentType: a.contentType, byteSize: a.byteSize, author: userRef(user.id)!, createdAt: a.createdAt, url: dataUrl, canDelete: true };
      });
    },
    removeAttachment: (id) =>
      run(() => {
        const user = sessionUser();
        const a = db.attachments.find((x) => x.id === id);
        const wp = a && db.workPackages.find((w) => w.id === a.wpId);
        if (!a || !wp) throw new ApiError(404, "파일을 찾을 수 없습니다.");
        const { project } = findWp(user, wp.number);
        requirePerm(user, project, "wp.attach", "파일을 지울 권한이 없습니다.", wp);
        db.attachments = db.attachments.filter((x) => x.id !== id);
        writeJournal(wp, user, { [`attachment:${a.id}`]: [a.fileName, null] });
      }),
    shares: (number) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        requirePerm(user, project, "wp.share", "작업을 공유할 권한이 없습니다.");
        return db.shares
          .filter((s) => s.wpId === wp.id)
          .map(toShare)
          .sort((a, b) => a.user.name.localeCompare(b.user.name, "ko"));
      }),
    share: (number, userId, role) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        requirePerm(user, project, "wp.share", "작업을 공유할 권한이 없습니다.");
        if (!["view", "comment", "edit"].includes(role)) throw fieldError("role", "공유 권한을 고르세요.");
        if (userId === user.id) throw fieldError("userId", "자기 자신에게는 공유할 수 없습니다.");
        const target = db.users.find((u) => u.id === userId && u.status === "active");
        if (!target) throw fieldError("userId", "활성 사용자에게만 공유할 수 있습니다.");
        const existing = db.shares.find((s) => s.wpId === wp.id && s.userId === userId);
        if (existing) {
          existing.role = role;
          return toShare(existing);
        }
        const s = { id: newId("sh"), wpId: wp.id, userId, role, createdBy: user.id, createdAt: nowIso() };
        db.shares.push(s);
        // the one notification a share sends (reason "shared"), unless that person switched it off
        if (effectiveSetting(userId, project.id).shared) db.notifications.push({ id: newId("n"), recipientId: userId, wpId: wp.id, journalId: null, actorId: user.id, reason: "shared", readAt: null, createdAt: s.createdAt });
        return toShare(s);
      }),
    updateShare: (id, role) =>
      run(() => {
        const user = sessionUser();
        const s = db.shares.find((x) => x.id === id);
        const wp = s && db.workPackages.find((w) => w.id === s.wpId);
        if (!s || !wp) throw new ApiError(404, "공유를 찾을 수 없습니다.");
        const { project } = findWp(user, wp.number);
        requirePerm(user, project, "wp.share", "작업을 공유할 권한이 없습니다.");
        if (!["view", "comment", "edit"].includes(role)) throw fieldError("role", "공유 권한을 고르세요.");
        s.role = role;
        return toShare(s);
      }),
    removeShare: (id) =>
      run(() => {
        const user = sessionUser();
        const s = db.shares.find((x) => x.id === id);
        const wp = s && db.workPackages.find((w) => w.id === s.wpId);
        if (!s || !wp) throw new ApiError(404, "공유를 찾을 수 없습니다.");
        const { project } = findWp(user, wp.number);
        requirePerm(user, project, "wp.share", "작업을 공유할 권한이 없습니다.");
        db.shares = db.shares.filter((x) => x.id !== id);
      }),
    watchers: (number) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        requirePerm(user, project, "wp.view_watchers", "주시자를 볼 권한이 없습니다.");
        const canDelete = can(user, project, "wp.delete_watchers");
        return db.watchers
          .filter((x) => x.wpId === wp.id)
          .map((x) => ({ user: userRef(x.userId)!, canRemove: canDelete || x.userId === user.id }))
          .sort((a, b) => a.user.name.localeCompare(b.user.name, "ko"));
      }),
    addWatcher: (number, userId) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        if (userId !== user.id) requirePerm(user, project, "wp.add_watchers", "주시자를 추가할 권한이 없습니다.");
        const target = db.users.find((u) => u.id === userId && u.status === "active");
        if (!target || !can(target, project, "wp.view", wp)) throw fieldError("userId", "이 작업을 볼 수 없는 사람은 주시자로 추가할 수 없습니다.");
        if (!db.watchers.some((x) => x.wpId === wp.id && x.userId === userId)) db.watchers.push({ wpId: wp.id, userId });
      }),
    removeWatcher: (number, userId) =>
      run(() => {
        const user = sessionUser();
        const { wp, project } = findWp(user, number);
        if (userId !== user.id) requirePerm(user, project, "wp.delete_watchers", "주시자를 뺄 권한이 없습니다.");
        db.watchers = db.watchers.filter((x) => !(x.wpId === wp.id && x.userId === userId));
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
        const visible = (w: DbWp | undefined): w is DbWp => !!w && can(user, projectOf(w), "wp.view", w);
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
        return withBaseline(user, projectId, spec, runQuery(user, spec, { ...opts, projectId }));
      }),
    export: (input) => run(() => exportWorkPackages(sessionUser(), input)),
    exportCount: (input) => run(() => exportCount(sessionUser(), input)),
    filterOptions: (projectId) =>
      run(() => {
        const user = sessionUser();
        const p = projectId ? findProject(user, projectId) : null;
        const userIds = p ? projectUserIds(p.id) : db.users.filter((u) => u.status === "active").map((u) => u.id);
        const scope = visibleProjects(user, "wp.view").filter((x) => !p || x.id === p.id || isDescendant(x.id, p.id));
        return clone({
          statuses: db.statuses.sort((a, b) => a.position - b.position).map((s) => statusRef(s.id)),
          types: db.types.filter((t) => !p || p.typeIds.includes(t.id)).sort((a, b) => a.position - b.position).map((t) => typeRef(t.id)),
          priorities: db.priorities.sort((a, b) => a.position - b.position).map((x) => priorityRef(x.id)),
          users: userIds.map((id) => userRef(id)!).sort((a, b) => a.name.localeCompare(b.name, "ko")),
          versions: db.versions.filter((v) => !p || v.projectId === p.id).map((v) => ({ id: v.id, name: v.name })),
          categories: db.categories.filter((c) => !p || c.projectId === p.id).map((c) => ({ id: c.id, name: c.name })),
          groups: db.groups.map((g) => ({ id: g.id, name: g.name })),
          roles: db.roles.filter((r) => r.scope === "project" && !r.builtin).map((r) => ({ id: r.id, name: r.name })),
          projects: scope.map((x) => ({ id: x.id, name: x.name })),
          customFields: fieldsInProjects(scope.map((x) => x.id)),
          typeForms: Object.fromEntries(db.types.map((t) => [t.id, t.form ?? DEFAULT_TYPE_FORM])),
        });
      }),
  },

  queries: {
    list: (projectId, view) =>
      run(() => {
        const user = sessionUser();
        const p = projectId ? findProject(user, projectId) : null;
        const ofKind = (v: string) => (view ? v === view : v === "table" || v === "gantt");
        const saved = db.queries
          .filter((q) => (q.projectId ?? null) === (p?.id ?? null) && (q.isPublic || q.ownerId === user.id) && ofKind(q.view))
          .map((q): SavedQuery => ({
            id: q.id, name: q.name, kind: q.isPublic ? "public" : "private", view: q.view, spec: q.spec, projectId: q.projectId,
            favorite: db.queryFavorites.some((f) => f.userId === user.id && f.queryId === q.id),
            canEdit: q.ownerId === user.id || (!!p && q.isPublic && can(user, p, "query.manage_public")),
          }));
        return clone(view ? saved : [...defaultViews(p?.id ?? null), ...saved]);
      }),
    create: (input) =>
      run(() => {
        const user = sessionUser();
        const p = input.projectId ? findProject(user, input.projectId) : null;
        if (p) requirePerm(user, p, input.isPublic ? "query.manage_public" : "query.save", input.isPublic ? "공개 보기를 저장할 권한이 없습니다." : "보기를 저장할 권한이 없습니다.");
        const name = input.name.trim();
        if (!name) throw fieldError("name", "보기 이름을 입력하세요.");
        if ((input.view === "calendar" || input.view === "team_planner") && !p) throw new ApiError(422, "캘린더와 팀 플래너는 프로젝트 안에서만 만들 수 있습니다.");
        const q = { id: newId("q"), projectId: p?.id ?? null, ownerId: user.id, name, isPublic: input.isPublic, view: input.view, spec: input.spec, createdAt: nowIso() };
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
        else if (kind === "subproject") values = subprojectsOf(user, p.id).map((x) => x.id);
        else if (kind === "parent_child")
          values = db.workPackages.filter((w) => w.projectId === p.id && db.workPackages.some((k) => k.parentId === w.id)).sort((x, y) => x.number - y.number).slice(0, 6).map((w) => w.id);
        const b: DbBoard = {
          id: newId("b"), projectId: p.id, name: name.trim(), kind, isPublic: true, createdAt: nowIso(),
          columns: (values.length || kind !== "basic" ? values : [null]).map((v, i) => ({ id: newId("bc"), position: i, name: v ? null : "할 일", valueId: v })),
          order: {},
        };
        db.boards.push(b);
        return toBoard(user, b);
      }),
    get: (id, filters) =>
      run(() => {
        const user = sessionUser();
        const b = db.boards.find((x) => x.id === id);
        const p = b && db.projects.find((x) => x.id === b.projectId);
        if (!b || !p || !can(user, p, "board.view")) throw new ApiError(404, "보드를 찾을 수 없습니다.");
        if (filters) checkBoardFilters(user, filters);
        return toBoard(user, b, filters);
      }),
    remove: (id) =>
      run(() => {
        const user = sessionUser();
        const b = db.boards.find((x) => x.id === id);
        if (!b) throw new ApiError(404, "보드를 찾을 수 없습니다.");
        requirePerm(user, db.projects.find((x) => x.id === b.projectId)!, "board.manage");
        db.boards = db.boards.filter((x) => x.id !== id);
      }),
    update: (id, patch) =>
      run(() => {
        const { user, b } = managedBoard(id);
        if (patch.name !== undefined) {
          const name = patch.name.trim();
          if (!name) throw fieldError("name", "보드 이름을 입력하세요.");
          if (name.length > 100) throw fieldError("name", "보드 이름은 100자까지 입력할 수 있습니다.");
          b.name = name;
        }
        if (patch.isPublic !== undefined) b.isPublic = patch.isPublic;
        if (patch.filters !== undefined) {
          checkBoardFilters(user, patch.filters);
          b.filters = clone(patch.filters.map((f) => ({ field: f.field, op: f.op, values: f.values ?? [] })));
        }
        return toBoard(user, b);
      }),
    addColumn: (id, input) =>
      run(() => {
        const { user, b } = managedBoard(id);
        if (b.kind === "basic") {
          const name = "name" in input ? input.name.trim() : "";
          if (!name || name.length > 60) throw fieldError("name", "열 이름을 1~60자로 입력하세요.");
          if (b.columns.length >= 30) throw fieldError("name", "열은 30개까지 만들 수 있습니다.");
          b.columns.push({ id: newId("bc"), position: Math.max(-1, ...b.columns.map((c) => c.position)) + 1, name, valueId: null });
          return toBoard(user, b);
        }
        const valueId = "valueId" in input ? input.valueId : "";
        const ok =
          b.kind === "status" ? db.statuses.some((s) => s.id === valueId)
          : b.kind === "assignee" ? projectUserIds(b.projectId).includes(valueId)
          : b.kind === "version" ? db.versions.some((v) => v.id === valueId && v.projectId === b.projectId)
          : b.kind === "subproject" ? subprojectsOf(user, b.projectId).some((p) => p.id === valueId)
          : b.kind === "parent_child" ? db.workPackages.some((w) => w.id === valueId && w.projectId === b.projectId)
          : false;
        if (!ok) throw fieldError("valueId", "이 보드에 넣을 수 없는 값입니다.");
        if (b.columns.some((c) => c.valueId === valueId)) throw fieldError("valueId", "이미 있는 열입니다.");
        if (b.columns.length >= 30) throw fieldError("valueId", "열은 30개까지 만들 수 있습니다.");
        b.columns.push({ id: newId("bc"), position: Math.max(-1, ...b.columns.map((c) => c.position)) + 1, name: null, valueId });
        return toBoard(user, b);
      }),
    updateColumn: (columnId, patch) =>
      run(() => {
        const owner = db.boards.find((x) => x.columns.some((c) => c.id === columnId));
        if (!owner) throw new ApiError(404, "열을 찾을 수 없습니다.");
        const { user, b } = managedBoard(owner.id);
        const cols = b.columns.slice().sort((x, y) => x.position - y.position);
        const col = cols.find((c) => c.id === columnId)!;
        if (patch.name !== undefined) {
          if (col.valueId) throw fieldError("name", "값이 있는 열의 이름은 그 값의 이름을 따릅니다.");
          const name = patch.name.trim();
          if (!name || name.length > 60) throw fieldError("name", "열 이름을 1~60자로 입력하세요.");
          col.name = name;
        }
        if (patch.position !== undefined) {
          const rest = cols.filter((c) => c !== col);
          rest.splice(Math.max(0, Math.min(patch.position, rest.length)), 0, col);
          rest.forEach((c, i) => (c.position = i));
        }
        return toBoard(user, b);
      }),
    removeColumn: (columnId) =>
      run(() => {
        const owner = db.boards.find((x) => x.columns.some((c) => c.id === columnId));
        if (!owner) throw new ApiError(404, "열을 찾을 수 없습니다.");
        const { user, b } = managedBoard(owner.id);
        b.columns = b.columns.filter((c) => c.id !== columnId);
        return toBoard(user, b);
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
        if (b.kind === "assignee" && col.valueId) {
          // from one person's column to another's hands that person's place over
          const from = b.columns.find((c) => c.id === input.fromColumnId)?.valueId ?? null;
          const team = teamOf(wp);
          const nextTeam = from ? swap(team, from, col.valueId) : withMain(team, col.valueId);
          if (!sameTeam(team, nextTeam)) applyPatch(user, wp.number, { lockVersion: input.lockVersion, assignees: nextTeam });
        }
        if (b.kind === "version" && col.valueId !== wp.versionId) applyPatch(user, wp.number, { lockVersion: input.lockVersion, versionId: col.valueId });
        if (b.kind === "parent_child" && col.valueId && col.valueId !== wp.parentId)
          applyPatch(user, wp.number, { lockVersion: input.lockVersion, parentNumber: db.workPackages.find((w) => w.id === col.valueId)?.number ?? null });
        if (b.kind === "subproject" && col.valueId && col.valueId !== wp.projectId) moveWp(user, wp.number, { targetProjectId: col.valueId, lockVersion: input.lockVersion });
        if (b.kind === "basic") {
          requirePerm(user, db.projects.find((p) => p.id === b.projectId)!, "board.manage", "보드를 바꿀 권한이 없습니다.");
          if (!b.placed?.[wp.id]) throw new ApiError(404, "이 보드에 없는 카드입니다.");
        }
        placeCard(user, b, col.id, wp, input.beforeNumber);
        return toBoard(user, b);
      }),
    addCard: (id, columnId, number) =>
      run(() => {
        const { user, b } = managedBoard(id);
        if (b.kind !== "basic") throw new ApiError(422, "기본 보드에서만 작업을 직접 넣을 수 있습니다.");
        if (!b.columns.some((c) => c.id === columnId)) throw new ApiError(404, "컬럼을 찾을 수 없습니다.");
        const { wp } = findWp(user, number);
        if (wp.projectId !== b.projectId) throw fieldError("number", "이 프로젝트의 작업만 넣을 수 있습니다.");
        placeCard(user, b, columnId, wp, null);
        return toBoard(user, b);
      }),
    removeCard: (id, number) =>
      run(() => {
        const { user, b } = managedBoard(id);
        const { wp } = findWp(user, number);
        if (b.placed) delete b.placed[wp.id];
        delete b.order[wp.id];
        return toBoard(user, b);
      }),
    createCard: (id, columnId, subject) =>
      run(() => {
        const user = sessionUser();
        const b = db.boards.find((x) => x.id === id);
        const p = b && db.projects.find((x) => x.id === b.projectId);
        if (!b || !p || !can(user, p, "board.view")) throw new ApiError(404, "보드를 찾을 수 없습니다.");
        const col = b.columns.find((c) => c.id === columnId);
        if (!col) throw new ApiError(404, "컬럼을 찾을 수 없습니다.");
        if (b.kind === "basic") requirePerm(user, p, "board.manage", "보드를 바꿀 권한이 없습니다.");
        // the default type when the project uses it, else the project's first type
        // a subproject column makes the work package in that subproject
        const target = b.kind === "subproject" && col.valueId ? (db.projects.find((x) => x.id === col.valueId) ?? p) : p;
        const types = db.types.filter((t) => target.typeIds.includes(t.id)).sort((x, y) => Number(y.isDefault) - Number(x.isDefault) || x.position - y.position);
        const made = createWp(user, target.id, {
          subject,
          typeId: types[0]?.id ?? "",
          ...(b.kind === "status" && col.valueId ? { statusId: col.valueId } : {}),
          ...(b.kind === "assignee" && col.valueId ? { assigneeId: col.valueId } : {}),
          ...(b.kind === "version" && col.valueId ? { versionId: col.valueId } : {}),
          ...(b.kind === "parent_child" && col.valueId ? { parentNumber: db.workPackages.find((w) => w.id === col.valueId)?.number } : {}),
        });
        placeCard(user, b, col.id, db.workPackages.find((w) => w.number === made.number)!, null);
        return { board: toBoard(user, b), workPackage: made };
      }),
  },

  notifications: {
    list: (filter, narrow) =>
      run(() => {
        const user = sessionUser();
        const mine = db.notifications.filter((n) => n.recipientId === user.id && (filter === "all" || !n.readAt) && inFilter(n, narrow));
        const groups = new Map<string, NotificationGroup>();
        for (const n of mine.sort((a, b) => b.createdAt.localeCompare(a.createdAt))) {
          const wp = db.workPackages.find((w) => w.id === n.wpId);
          if (!wp || !can(user, projectOf(wp), "wp.view", wp)) continue;
          const g = groups.get(wp.id) ?? { workPackage: wpRef(wp), items: [], unread: 0, latestAt: n.createdAt };
          const item: NotificationItem = { id: n.id, reason: n.reason, read: !!n.readAt, actor: userRef(n.actorId), summary: notificationSummary(n, user), createdAt: n.createdAt };
          g.items.push(item);
          if (!n.readAt) g.unread++;
          groups.set(wp.id, g);
        }
        return clone([...groups.values()]);
      }),
    counts: (narrow) =>
      run(() => {
        const user = sessionUser();
        const out: NotificationCounts = { all: 0, byReason: {}, projects: [], allProjects: 0 };
        const projects = new Map<string, { identifier: string; name: string; unread: number }>();
        for (const n of db.notifications) {
          if (n.recipientId !== user.id || n.readAt) continue;
          const wp = db.workPackages.find((w) => w.id === n.wpId);
          const p = wp && projectOf(wp);
          if (!wp || !p || !can(user, p, "wp.view")) continue;
          if (inFilter(n, { project: narrow?.project })) {
            out.all++;
            out.byReason[n.reason] = (out.byReason[n.reason] ?? 0) + 1;
          }
          if (inFilter(n, { reasons: narrow?.reasons })) {
            out.allProjects++;
            const e = projects.get(p.id) ?? { identifier: p.identifier, name: p.name, unread: 0 };
            e.unread++;
            projects.set(p.id, e);
          }
        }
        out.projects = [...projects.values()].sort((a, b) => a.name.localeCompare(b.name, "ko"));
        return out;
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
          const byFilter = "reasons" in target || "project" in target;
          if ("all" in target || ("ids" in target && target.ids.includes(n.id)) || (wpId && n.wpId === wpId) || (byFilter && inFilter(n, target as NotificationFilter)))
            n.readAt = now;
        }
      }),
  },

  calendar: {
    display: () => run(() => (sessionUser(), clone(db.settings.display ?? DEFAULT_DISPLAY))),
    nonWorkingDays: (from, to) =>
      run(() => {
        sessionUser();
        return clone({ workingDays: db.settings.workingDays, days: db.nonWorkingDays.filter((d) => d.date >= from && d.date <= to) });
      }),
  },

  time: timeApi,
  serviceDesk: serviceDeskApi,
  portal: portalApi,
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
    results: (q, scope, page) =>
      run((): SearchResults => {
        const user = sessionUser();
        const pageSize = 20;
        const needle = q.trim().toLowerCase();
        const empty = { q, scope, counts: { workPackages: 0, projects: 0 }, items: [], total: 0, page: 1, pageSize };
        if (!needle) return empty;
        const numeric = needle.replace(/^#/, "");
        const rank = { number: 0, subject: 1, description: 2, comment: 3 } as const;
        const wps: (SearchResult & { kind: "work_package" })[] = [];
        for (const w of db.workPackages) {
          const project = projectOf(w);
          if (project.archivedAt || !can(user, project, "wp.view", w)) continue;
          let matchedIn: keyof typeof rank | null = null;
          let snippet: string | null = null;
          if (String(w.number) === numeric) matchedIn = "number";
          else if (w.subject.toLowerCase().includes(needle)) matchedIn = "subject";
          else if (plainText(w.descriptionHtml).toLowerCase().includes(needle)) (matchedIn = "description"), (snippet = snippetAround(plainText(w.descriptionHtml), needle));
          else {
            const hit = db.journals.find((j) => j.wpId === w.id && j.notesHtml && (!j.isInternal || can(user, project, "wp.view_internal_comments")) && plainText(j.notesHtml).toLowerCase().includes(needle));
            if (hit) (matchedIn = "comment"), (snippet = snippetAround(plainText(hit.notesHtml), needle));
          }
          if (matchedIn)
            wps.push({ kind: "work_package", number: w.number, subject: w.subject, type: typeRef(w.typeId), status: statusRef(w.statusId), project: projectRef(project), matchedIn, snippet, updatedAt: w.updatedAt });
        }
        wps.sort((a, b) => rank[a.matchedIn] - rank[b.matchedIn] || b.updatedAt.localeCompare(a.updatedAt));
        const projects: SearchResult[] = visibleProjects(user)
          .filter((p) => !p.archivedAt)
          .flatMap((p): SearchResult[] => {
            const desc = plainText(p.descriptionHtml);
            if (p.name.toLowerCase().includes(needle) || p.identifier.toLowerCase().includes(needle)) return [{ kind: "project", project: projectRef(p), snippet: null }];
            if (desc.toLowerCase().includes(needle)) return [{ kind: "project", project: projectRef(p), snippet: snippetAround(desc, needle) }];
            return [];
          })
          .sort((a, b) => (a.kind === "project" && b.kind === "project" ? a.project.name.localeCompare(b.project.name, "ko") : 0));
        const all = scope === "projects" ? projects : scope === "work_packages" ? wps : [...projects, ...wps];
        const p = Math.max(1, page);
        return clone({ q, scope, counts: { workPackages: wps.length, projects: projects.length }, items: all.slice((p - 1) * pageSize, p * pageSize), total: all.length, page: p, pageSize });
      }),
  },
};

const plainText = (html: string) => html.replace(/<[^>]*>/g, " ").replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim();

/** About 50 characters either side of the first match, with … where text was cut. */
function snippetAround(text: string, needle: string, radius = 50) {
  const i = text.toLowerCase().indexOf(needle);
  if (i < 0) return text.slice(0, radius * 2);
  const start = Math.max(0, i - radius);
  const end = Math.min(text.length, i + needle.length + radius);
  return `${start > 0 ? "…" : ""}${text.slice(start, end)}${end < text.length ? "…" : ""}`;
}

export { resetFakeDb } from "./db";
export const fakeOutbox = () => db.outbox.slice();
export const fakeAccounts = () => db.users.map((u) => ({ email: u.email, name: u.name, password: u.password, status: u.status, isAdmin: u.isAdmin }));
