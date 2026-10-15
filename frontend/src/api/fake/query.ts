// Query engine (architecture.md §3.5) over the fake database.
import { GROUPABLE_COLUMNS, type ColumnKey, type CustomField, type QueryFilter, type QueryGroup, type QueryResult, type QueryRow, type QuerySpec } from "../types";
import { ApiError } from "../errors";
import { addDays, isoWeekday, todayIso, weekStart } from "@/lib/dates";
import { db, type DbProject, type DbUser, type DbWp } from "./db";
import { can, memberRoleIds } from "./permissions";
import { toWp } from "./dto";
import { cfText, findField, opsFor, sortKey, valueOn } from "./custom-fields";
import { progressSettings } from "./progress";
import { teamOf } from "./assignees";

function descendantsOf(projectId: string): string[] {
  const out = [projectId];
  for (let i = 0; i < out.length; i++) for (const p of db.projects) if (p.parentId === out[i]) out.push(p.id);
  return out;
}

function scopeProjects(user: DbUser, projectId: string | undefined, includeSubprojects: boolean): DbProject[] {
  return candidateProjects(projectId, includeSubprojects).filter((p) => can(user, p, "wp.view"));
}

/** Projects a list covers, before permissions: one (plus subprojects) or every unarchived one. */
function candidateProjects(projectId: string | undefined, includeSubprojects: boolean): DbProject[] {
  const ids = projectId ? (includeSubprojects ? descendantsOf(projectId) : [projectId]) : db.projects.map((p) => p.id);
  return db.projects.filter((p) => ids.includes(p.id) && (projectId ? true : !p.archivedAt));
}

function resolveUser(v: string, user: DbUser) {
  return v === "me" ? user.id : v;
}

const plain = (html: string) => html.replace(/<[^>]*>/g, " ").toLowerCase();

/** Which operators each field accepts (architecture.md §3.5); anything else is a 422, like the real API. */
const OPS: Record<QueryFilter["field"], readonly QueryFilter["op"][]> = (() => {
  const ref = ["eq", "neq", "any", "none"] as const;
  const date = ["overdue", "today", "this_week", "next_days", "last_days", "on", "between", "any", "none"] as const;
  const num = ["eq", "gte", "lte", "any", "none"] as const;
  const text = ["contains", "not_contains"] as const;
  return {
    status: ["open", "closed", "eq", "neq"], type: ref, priority: ref, assignee: ref, responsible: ref, author: ref, version: ref,
    category: ref, project: ref, watcher: ["eq", "neq"], assigneeGroup: ["eq", "neq"], assigneeRole: ["eq", "neq"],
    number: ["eq", "neq"], parent: ["eq", "any", "none"], subject: text, description: text, comment: text,
    startDate: date, dueDate: date, createdAt: date, updatedAt: date, dates: ["overlaps", "between", "any", "none"],
    duration: num, doneRatio: num, work: num, remainingWork: num,
    isMilestone: ["eq"], scheduleManually: ["eq"], blocked: ["eq"],
  };
})();

const NEEDS_VALUE = new Set<QueryFilter["op"]>(["eq", "neq", "gte", "lte", "on", "between", "overlaps", "contains", "not_contains"]);

export function matches(w: DbWp, f: QueryFilter, user: DbUser): boolean {
  const custom = f.field.startsWith("cf:") ? findField(f.field) : null;
  const allowed = custom ? opsFor(custom) : OPS[f.field as keyof typeof OPS];
  if (!allowed?.includes(f.op)) throw new ApiError(422, `연산자 ${f.op}는 ${custom?.name ?? f.field} 필드에 쓸 수 없습니다.`);
  // A filter whose value has not been picked yet does not narrow the list.
  const vals = (f.values ?? []).map((v) => resolveUser(v, user));
  if (NEEDS_VALUE.has(f.op) && !vals.some((v) => v !== "")) return true;
  const today = todayIso(user.timeZone);
  const status = db.statuses.find((s) => s.id === w.statusId)!;

  const ref = (value: string | null) => {
    switch (f.op) {
      case "eq":
        return vals.includes(value ?? "none");
      case "neq":
        return !vals.includes(value ?? "none");
      case "any":
        return value !== null;
      default:
        return value === null; // none
    }
  };
  /** eq/neq over a set of values the item has (watchers, groups, roles). */
  const some = (have: string[]) => (f.op === "eq" ? have.some((x) => vals.includes(x)) : !have.some((x) => vals.includes(x)));
  const date = (value: string | null) => {
    const n = Number(vals[0] ?? 7) || 7;
    switch (f.op) {
      case "any":
        return value !== null;
      case "none":
        return value === null;
      case "overdue":
        return !!value && value < today && !status.isClosed;
      case "today":
        return value === today;
      case "this_week": {
        // the week as the calendars show it (관리 · 날짜 형식 · 주 시작 요일)
        const first = addDays(today, -((isoWeekday(today) % 7) - weekStart() + 7) % 7);
        return !!value && value >= first && value <= addDays(first, 6);
      }
      case "next_days":
        return !!value && value >= today && value <= addDays(today, n);
      case "last_days":
        return !!value && value <= today && value >= addDays(today, -n);
      case "on":
        return value === vals[0];
      default: // between: either end may be open
        return !!value && (!vals[0] || value >= vals[0]) && (!vals[1] || value <= vals[1]);
    }
  };
  const num = (value: number | null) => {
    const n = Number(vals[0]);
    switch (f.op) {
      case "any":
        return value !== null;
      case "none":
        return value === null;
      case "eq":
        return Number.isNaN(n) || value === n;
      case "gte":
        return Number.isNaN(n) || (value !== null && value >= n);
      default: // lte
        return Number.isNaN(n) || (value !== null && value <= n);
    }
  };
  const needle = (vals[0] ?? "").toLowerCase();
  const text = (hay: string) => (f.op === "not_contains" ? !hay.includes(needle) : hay.includes(needle));
  const bool = (value: boolean) => value === (vals[0] === "true");

  if (custom) {
    // an item without the field (other type or project) counts as empty
    const v = valueOn(w, custom);
    const isEmpty = v === null || (Array.isArray(v) && !v.length);
    switch (custom.format) {
      case "list":
      case "user": {
        const have = isEmpty ? [] : (Array.isArray(v) ? v : [v]).map(String);
        if (f.op === "any") return have.length > 0;
        if (f.op === "none") return have.length === 0;
        return some(have);
      }
      case "integer":
      case "float":
        return num(isEmpty ? null : Number(v));
      case "date":
        return date(isEmpty ? null : String(v));
      case "bool":
        return !isEmpty && bool(Boolean(v));
      default:
        if (f.op === "any") return !isEmpty;
        if (f.op === "none") return isEmpty;
        return text(cfText(custom, v).toLowerCase());
    }
  }

  switch (f.field) {
    case "status":
      if (f.op === "open") return !status.isClosed;
      if (f.op === "closed") return status.isClosed;
      return ref(w.statusId);
    case "type":
      return ref(w.typeId);
    case "priority":
      return ref(w.priorityId);
    case "assignee": {
      // 공동 담당자: anyone who has the item
      const team = teamOf(w).map((e) => e.userId);
      if (f.op === "any") return team.length > 0;
      if (f.op === "none") return team.length === 0;
      const hit = team.some((x) => vals.includes(x)) || (vals.includes("none") && team.length === 0);
      return f.op === "eq" ? hit : !hit;
    }
    case "responsible":
      return ref(w.responsibleId);
    case "author":
      return ref(w.authorId);
    case "version":
      return ref(w.versionId);
    case "category":
      return ref(w.categoryId);
    case "project":
      return ref(w.projectId);
    case "watcher":
      return some(db.watchers.filter((x) => x.wpId === w.id).map((x) => x.userId));
    case "assigneeGroup":
      return some(db.groups.filter((g) => teamOf(w).some((e) => g.memberIds.includes(e.userId))).map((g) => g.id));
    case "assigneeRole":
      return some(teamOf(w).flatMap((e) => memberRoleIds(e.userId, w.projectId)));
    case "number": {
      const nums = vals.flatMap((v) => v.split(/[\s,]+/)).map((v) => Number(v.replace("#", ""))).filter((x) => Number.isInteger(x) && x > 0);
      return f.op === "eq" ? nums.includes(w.number) : !nums.includes(w.number);
    }
    case "parent": {
      if (f.op === "any") return w.parentId !== null;
      if (f.op === "none") return w.parentId === null;
      const parent = db.workPackages.find((x) => x.number === Number(vals[0].replace("#", "")));
      return !!parent && w.parentId === parent.id;
    }
    case "subject":
      return text(w.subject.toLowerCase());
    case "description":
      return text(plain(w.descriptionHtml));
    case "comment":
      return text(db.journals.filter((j) => j.wpId === w.id && j.notesHtml).map((j) => plain(j.notesHtml)).join("\n"));
    case "isMilestone":
      return bool(db.types.find((t) => t.id === w.typeId)?.isMilestone ?? false);
    case "scheduleManually":
      return bool(w.scheduleManually);
    case "blocked":
      return bool(
        db.relations.some((r) => {
          if (r.type !== "blocks" || r.toId !== w.id) return false;
          const blocker = db.workPackages.find((x) => x.id === r.fromId);
          return !!blocker && !db.statuses.find((st) => st.id === blocker.statusId)?.isClosed;
        }),
      );
    case "dueDate":
      return date(w.dueDate);
    case "startDate":
      return date(w.startDate);
    case "createdAt":
      return date(w.createdAt.slice(0, 10));
    case "updatedAt":
      return date(w.updatedAt.slice(0, 10));
    case "duration":
      return num(w.duration);
    case "doneRatio":
      return num(w.doneRatio);
    case "work":
      return num(w.workHours);
    case "remainingWork":
      return num(w.remainingHours);
    case "dates": {
      const s = w.startDate ?? w.dueDate;
      const e = w.dueDate ?? w.startDate;
      if (f.op === "any") return !!s;
      if (f.op === "none") return !s;
      if (f.op === "overlaps" && vals.length < 2) throw new ApiError(422, "날짜 범위 필터는 시작일과 종료일이 필요합니다.");
      // overlaps / between: the item's period touches the window (between allows an open end)
      return !!s && !!e && (!vals[1] || s <= vals[1]) && (!vals[0] || e >= vals[0]);
    }
    default:
      return true;
  }
}

function sortValue(w: DbWp, field: ColumnKey): string | number {
  const name = (id: string | null) => (id ? db.users.find((u) => u.id === id)?.name ?? "" : "￿");
  switch (field) {
    case "number":
      return w.number;
    case "subject":
      return w.subject;
    case "type":
      return db.types.find((t) => t.id === w.typeId)?.position ?? 0;
    case "status":
      return db.statuses.find((t) => t.id === w.statusId)?.position ?? 0;
    case "priority":
      return db.priorities.find((t) => t.id === w.priorityId)?.position ?? 0;
    case "assignee":
      return name(w.assigneeId);
    case "responsible":
      return name(w.responsibleId);
    case "author":
      return name(w.authorId);
    case "startDate":
      return w.startDate ?? "9999";
    case "dueDate":
      return w.dueDate ?? "9999";
    case "updatedAt":
      return w.updatedAt;
    case "project":
      return db.projects.find((p) => p.id === w.projectId)?.name ?? "";
    case "version":
      return w.versionId ? (db.versions.find((v) => v.id === w.versionId)?.name ?? "") : "￿";
    case "work":
      return w.workHours ?? Number.MAX_VALUE;
    case "remainingWork":
      return w.remainingHours ?? Number.MAX_VALUE;
    default:
      if (field.startsWith("cf:")) {
        const f = db.customFields.find((x) => `cf:${x.id}` === field);
        if (f) return sortKey(w, f);
      }
      return w.number;
  }
}

const compareValues = (va: string | number, vb: string | number) => (typeof va === "number" && typeof vb === "number" ? va - vb : String(va).localeCompare(String(vb), "ko"));

/* ---------- groups and sums (same rules as the server's QueryEngine) ---------- */

type Group = { key: string; label: string; color: string | null };
const NONE: Group = { key: "", label: "", color: null };

function groupField(groupBy: string): CustomField | null {
  const f = groupBy.startsWith("cf:") ? db.customFields.find((x) => `cf:${x.id}` === groupBy) : undefined;
  return f && ["list", "user", "bool"].includes(f.format) ? f : null;
}

function groupOf(w: DbWp, by: ColumnKey, cf: CustomField | null): Group {
  if (cf) {
    const v = valueOn(w, cf);
    const label = cfText(cf, v);
    if (!label) return NONE;
    return { key: Array.isArray(v) ? [...v].map(String).sort().join(",") : String(v), label, color: null };
  }
  const principal = (id: string | null) => (id ? (db.users.find((u) => u.id === id)?.name ?? db.groups.find((g) => g.id === id)?.name ?? "") : null);
  const pick = <T extends { id: string; name: string; color?: string }>(list: T[], id: string | null): Group =>
    id ? ((x) => (x ? { key: id, label: x.name, color: x.color ?? null } : NONE))(list.find((x) => x.id === id)) : NONE;
  switch (by) {
    case "type": return pick(db.types, w.typeId);
    case "status": return pick(db.statuses, w.statusId);
    case "priority": return pick(db.priorities, w.priorityId);
    case "version": return pick(db.versions, w.versionId);
    case "project": return pick(db.projects, w.projectId);
    case "assignee": case "responsible": case "author": {
      const id = by === "assignee" ? w.assigneeId : by === "responsible" ? w.responsibleId : w.authorId;
      return id ? { key: id, label: principal(id) ?? "", color: null } : NONE;
    }
    default: return NONE;
  }
}

function summable(columns: ColumnKey[]): ColumnKey[] {
  return [...new Set(columns)].filter((c) => {
    if (c === "work" || c === "remainingWork" || c === "doneRatio") return true;
    const f = c.startsWith("cf:") ? db.customFields.find((x) => `cf:${x.id}` === c) : undefined;
    return !!f && (f.format === "integer" || f.format === "float");
  });
}

/** Work adds up every listed item; % complete follows the progress settings, excluded statuses left out (as ∑ totals). */
function accumulate(w: DbWp, cols: ColumnKey[], acc: Record<string, number>) {
  if (w.workHours !== null) acc.work = (acc.work ?? 0) + w.workHours;
  if (w.remainingHours !== null) acc.remainingWork = (acc.remainingWork ?? 0) + w.remainingHours;
  if (cols.includes("doneRatio") && !db.statuses.find((s) => s.id === w.statusId)?.excludedFromTotals) {
    if (w.workHours !== null) {
      acc["%work"] = (acc["%work"] ?? 0) + w.workHours;
      acc["%remaining"] = (acc["%remaining"] ?? 0) + (w.remainingHours ?? w.workHours); // nothing recorded: not started
    }
    if (w.doneRatio !== null) {
      acc["%sum"] = (acc["%sum"] ?? 0) + w.doneRatio;
      acc["%count"] = (acc["%count"] ?? 0) + 1;
    }
  }
  for (const c of cols) {
    const f = c.startsWith("cf:") ? db.customFields.find((x) => `cf:${x.id}` === c) : undefined;
    if (!f) continue;
    const v = valueOn(w, f);
    const n = typeof v === "number" ? v : typeof v === "string" && v !== "" ? Number(v) : NaN;
    if (!Number.isNaN(n)) acc[c] = (acc[c] ?? 0) + n;
  }
}

function finish(acc: Record<string, number>, cols: ColumnKey[]): Record<string, number | null> {
  const out: Record<string, number | null> = {};
  for (const c of cols) {
    if (c === "doneRatio") {
      const work = acc["%work"];
      if (progressSettings().totals === "weighted") out[c] = !work ? null : Math.max(0, Math.round(((work - (acc["%remaining"] ?? 0)) * 100) / work));
      else out[c] = acc["%count"] ? Math.round(acc["%sum"] / acc["%count"]) : null;
    } else out[c] = acc[c] !== undefined ? Math.round(acc[c] * 100) / 100 : null;
  }
  return out;
}

function comparator(spec: QuerySpec) {
  const sort = spec.sort.length ? spec.sort : [{ field: "number" as ColumnKey, dir: "asc" as const }];
  return (a: DbWp, b: DbWp) => {
    for (const s of sort) {
      const va = sortValue(a, s.field);
      const vb = sortValue(b, s.field);
      const c = typeof va === "number" && typeof vb === "number" ? va - vb : String(va).localeCompare(String(vb), "ko");
      if (c !== 0) return s.dir === "asc" ? c : -c;
    }
    return a.number - b.number;
  };
}

/** Does the list (scope, filters, quick search) take this item? Shares are left out (기준선 only). */
export function listTakes(user: DbUser, spec: QuerySpec, projectId: string | undefined, w: DbWp): boolean {
  const projects = new Set(scopeProjects(user, projectId, spec.includeSubprojects).map((p) => p.id));
  const text = spec.text?.trim().toLowerCase();
  return (
    projects.has(w.projectId) &&
    spec.filters.every((f) => matches(w, f, user)) &&
    (!text || w.subject.toLowerCase().includes(text) || `#${w.number}` === text || String(w.number) === text.replace("#", ""))
  );
}

/** Projects where I may see work packages (every one, not only the list's). */
export function viewableProjectIds(user: DbUser): Set<string> {
  return new Set(scopeProjects(user, undefined, true).map((p) => p.id));
}

export function runQuery(user: DbUser, spec: QuerySpec, opts: { projectId?: string; page?: number; pageSize?: number }): QueryResult {
  const page = Math.max(1, opts.page ?? 1);
  const pageSize = Math.min(1000, Math.max(1, opts.pageSize ?? 20));
  const projects = new Set(scopeProjects(user, opts.projectId, spec.includeSubprojects).map((p) => p.id));
  // items shared with me count too, in the projects this list covers
  const candidates = new Set(candidateProjects(opts.projectId, spec.includeSubprojects).map((p) => p.id));
  const shared = new Set(db.shares.filter((s) => s.userId === user.id).map((s) => s.wpId));
  const inScope = (w: DbWp) => projects.has(w.projectId) || (shared.has(w.id) && candidates.has(w.projectId));
  const text = spec.text?.trim().toLowerCase();

  const matching = db.workPackages.filter(
    (w) =>
      inScope(w) &&
      spec.filters.every((f) => matches(w, f, user)) &&
      (!text || w.subject.toLowerCase().includes(text) || `#${w.number}` === text || String(w.number) === text.replace("#", "")),
  );
  const sortCmp = comparator(spec);
  const total = matching.length;
  // grouped: the group decides the order first ("none" last), then the sort
  const groupCf = spec.groupBy ? groupField(spec.groupBy) : null;
  const groupBy = spec.groupBy && (GROUPABLE_COLUMNS.includes(spec.groupBy) || groupCf) ? spec.groupBy : null;
  const groupOfRow = new Map<string, Group>();
  let cmp = sortCmp;
  if (groupBy) {
    for (const w of matching) groupOfRow.set(w.id, groupOf(w, groupBy, groupCf));
    const desc = spec.sort[0]?.field === groupBy && spec.sort[0].dir === "desc";
    const key = (w: DbWp) => (groupCf ? sortKey(w, groupCf) : sortValue(w, groupBy));
    cmp = (a, b) => {
      const ga = groupOfRow.get(a.id)!, gb = groupOfRow.get(b.id)!;
      if (ga === NONE || gb === NONE) return ga === gb ? sortCmp(a, b) : ga === NONE ? 1 : -1;
      let c = compareValues(key(a), key(b));
      if (c === 0) c = ga.key.localeCompare(gb.key);
      if (c !== 0) return desc ? -c : c;
      return sortCmp(a, b);
    };
  }
  const sumCols = spec.sums ? summable(spec.columns) : [];
  let groups: QueryGroup[] | undefined;
  let sums: Record<string, number | null> | undefined;
  if (groupBy) {
    const byKey = new Map<string, { g: Group; count: number; acc: Record<string, number> }>();
    for (const w of [...matching].sort(cmp)) {
      const g = groupOfRow.get(w.id)!;
      const e = byKey.get(g.key) ?? byKey.set(g.key, { g, count: 0, acc: {} }).get(g.key)!;
      e.count++;
      if (spec.sums) accumulate(w, sumCols, e.acc);
    }
    groups = [...byKey.values()].map((e) => ({ key: e.g.key, label: e.g.label, color: e.g.color, count: e.count, sums: spec.sums ? finish(e.acc, sumCols) : null }));
  }
  if (spec.sums) {
    const acc: Record<string, number> = {};
    for (const w of matching) accumulate(w, sumCols, acc);
    sums = finish(acc, sumCols);
  }
  const extra = { groups: groups ?? null, sums: sums ?? null };

  let rows: QueryRow[];
  // a grouped list is flat (hierarchy is off while grouping)
  if (!spec.hierarchy || groupBy) {
    rows = matching.sort(cmp).map((w) => ({ wp: toWp(w, user), depth: 0, hasChildren: false, context: false, groupKey: groupBy ? groupOfRow.get(w.id)!.key : null }));
    rows = rows.slice((page - 1) * pageSize, page * pageSize);
  } else {
    // Matching items plus their visible ancestors (as context rows), printed as a tree.
    const ids = new Set(matching.map((w) => w.id));
    const shown = new Map<string, DbWp>();
    for (const w of matching) {
      shown.set(w.id, w);
      let cur = w.parentId ? db.workPackages.find((x) => x.id === w.parentId) : undefined;
      while (cur && inScope(cur)) {
        shown.set(cur.id, cur);
        cur = cur.parentId ? db.workPackages.find((x) => x.id === cur!.parentId) : undefined;
      }
    }
    const all = [...shown.values()];
    const roots = all.filter((w) => !w.parentId || !shown.has(w.parentId)).sort(cmp);
    const out: QueryRow[] = [];
    const walk = (w: DbWp, depth: number) => {
      const kids = all.filter((x) => x.parentId === w.id).sort(cmp);
      out.push({ wp: toWp(w, user), depth, hasChildren: kids.length > 0, context: !ids.has(w.id) });
      kids.forEach((k) => walk(k, depth + 1));
    };
    roots.forEach((r) => walk(r, 0));
    // Paginate by matching rows. Each page repeats the ancestors its first rows need, so a page never
    // starts with an orphaned child.
    const byId = new Map(out.map((r) => [r.wp.id, r]));
    const pageRows = out.filter((r) => !r.context).slice((page - 1) * pageSize, page * pageSize);
    const added = new Set<string>();
    rows = [];
    for (const r of pageRows) {
      const chain: QueryRow[] = [];
      let parentId = shown.get(r.wp.id)?.parentId ?? null;
      while (parentId && byId.has(parentId) && !added.has(parentId)) {
        chain.unshift(byId.get(parentId)!);
        parentId = shown.get(parentId)?.parentId ?? null;
      }
      for (const c of chain) {
        rows.push(c);
        added.add(c.wp.id);
      }
      rows.push(r);
      added.add(r.wp.id);
    }
  }
  if (spec.timeline?.visible) {
    const shown = new Map(rows.map((r) => [r.wp.id, r.wp.number]));
    const relations = db.relations
      .filter((r) => r.type === "precedes" && shown.has(r.fromId) && shown.has(r.toId))
      .map((r) => ({ fromNumber: shown.get(r.fromId)!, toNumber: shown.get(r.toId)!, lag: r.lag }));
    return { rows, total, page, pageSize, relations, ...extra };
  }
  return { rows, total, page, pageSize, ...extra };
}

export function defaultSpec(overrides: Partial<QuerySpec> = {}): QuerySpec {
  return {
    v: 1,
    filters: [{ field: "status", op: "open" }],
    columns: ["number", "subject", "type", "status", "assignee", "priority", "dueDate"],
    sort: [{ field: "number", dir: "asc" }],
    groupBy: null,
    hierarchy: true,
    sums: false,
    includeSubprojects: true,
    ...overrides,
  };
}
