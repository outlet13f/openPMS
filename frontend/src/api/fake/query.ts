// Query engine (architecture.md §3.5) over the fake database.
import type { ColumnKey, QueryFilter, QueryResult, QueryRow, QuerySpec } from "../types";
import { ApiError } from "../errors";
import { addDays, isoWeekday, todayIso } from "@/lib/dates";
import { db, type DbProject, type DbUser, type DbWp } from "./db";
import { can } from "./permissions";
import { toWp } from "./dto";

function descendantsOf(projectId: string): string[] {
  const out = [projectId];
  for (let i = 0; i < out.length; i++) for (const p of db.projects) if (p.parentId === out[i]) out.push(p.id);
  return out;
}

function scopeProjects(user: DbUser, projectId: string | undefined, includeSubprojects: boolean): DbProject[] {
  const ids = projectId ? (includeSubprojects ? descendantsOf(projectId) : [projectId]) : db.projects.map((p) => p.id);
  return db.projects.filter((p) => ids.includes(p.id) && can(user, p, "wp.view") && (projectId ? true : !p.archivedAt));
}

function resolveUser(v: string, user: DbUser) {
  return v === "me" ? user.id : v;
}

function matches(w: DbWp, f: QueryFilter, user: DbUser): boolean {
  // A filter whose value has not been picked yet does not narrow the list.
  if ((f.op === "eq" || f.op === "neq") && !f.values?.length) return true;
  const today = todayIso(user.timeZone);
  const status = db.statuses.find((s) => s.id === w.statusId)!;
  const vals = (f.values ?? []).map((v) => resolveUser(v, user));
  const ref = (value: string | null) => {
    switch (f.op) {
      case "eq":
        return vals.includes(value ?? "none");
      case "neq":
        return !vals.includes(value ?? "none");
      case "any":
        return value !== null;
      case "none":
        return value === null;
      default:
        throw new ApiError(422, `연산자 ${f.op}는 ${f.field} 필드에 쓸 수 없습니다.`);
    }
  };
  const date = (value: string | null) => {
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
        const monday = addDays(today, 1 - isoWeekday(today));
        return !!value && value >= monday && value <= addDays(monday, 6);
      }
      case "next_days": {
        const n = Number(vals[0] ?? 7);
        return !!value && value >= today && value <= addDays(today, n);
      }
      default:
        throw new ApiError(422, `연산자 ${f.op}는 날짜 필드에 쓸 수 없습니다.`);
    }
  };
  switch (f.field) {
    case "status":
      if (f.op === "open") return !status.isClosed;
      if (f.op === "closed") return status.isClosed;
      return ref(w.statusId);
    case "type":
      return ref(w.typeId);
    case "priority":
      return ref(w.priorityId);
    case "assignee":
      return ref(w.assigneeId);
    case "responsible":
      return ref(w.responsibleId);
    case "author":
      return ref(w.authorId);
    case "version":
      return ref(w.versionId);
    case "isMilestone":
      return (db.types.find((t) => t.id === w.typeId)?.isMilestone ?? false) === (vals[0] === "true");
    case "subject": {
      const hay = w.subject.toLowerCase();
      const needle = (vals[0] ?? "").toLowerCase();
      return f.op === "not_contains" ? !hay.includes(needle) : hay.includes(needle);
    }
    case "dueDate":
      return date(w.dueDate);
    case "startDate":
      return date(w.startDate);
    case "updatedAt":
      return date(w.updatedAt.slice(0, 10));
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
    default:
      return w.number;
  }
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

export function runQuery(user: DbUser, spec: QuerySpec, opts: { projectId?: string; page?: number; pageSize?: number }): QueryResult {
  const page = Math.max(1, opts.page ?? 1);
  const pageSize = Math.min(1000, Math.max(1, opts.pageSize ?? 20));
  const projects = new Set(scopeProjects(user, opts.projectId, spec.includeSubprojects).map((p) => p.id));
  const text = spec.text?.trim().toLowerCase();

  const matching = db.workPackages.filter(
    (w) =>
      projects.has(w.projectId) &&
      spec.filters.every((f) => matches(w, f, user)) &&
      (!text || w.subject.toLowerCase().includes(text) || `#${w.number}` === text || String(w.number) === text.replace("#", "")),
  );
  const cmp = comparator(spec);
  const total = matching.length;

  let rows: QueryRow[];
  if (!spec.hierarchy) {
    rows = matching.sort(cmp).map((w) => ({ wp: toWp(w, user), depth: 0, hasChildren: false, context: false }));
    rows = rows.slice((page - 1) * pageSize, page * pageSize);
  } else {
    // Matching items plus their visible ancestors (as context rows), printed as a tree.
    const ids = new Set(matching.map((w) => w.id));
    const shown = new Map<string, DbWp>();
    for (const w of matching) {
      shown.set(w.id, w);
      let cur = w.parentId ? db.workPackages.find((x) => x.id === w.parentId) : undefined;
      while (cur && projects.has(cur.projectId)) {
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
    return { rows, total, page, pageSize, relations };
  }
  return { rows, total, page, pageSize };
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
