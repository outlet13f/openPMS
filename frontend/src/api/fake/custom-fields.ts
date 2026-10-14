// Work package custom fields (S34, S25) in the fake data layer: which fields an item has, how values are
// checked and shown, and how lists filter and sort by them. backend/…/customfield/CustomFields.java mirrors it.
import type { CustomField, CustomValue, FilterOp } from "../types";
import { ApiError } from "../errors";
import { formatDate } from "@/lib/dates";
import { db, type DbWp } from "./db";
import { projectUserIds } from "./permissions";
import { clone } from "./dto";
import { fieldError } from "./util";

export const cfKey = (id: string) => `cf:${id}`;
export const cfIdOf = (key: string) => (key.startsWith("cf:") ? key.slice(3) : null);

const byPosition = (a: CustomField, b: CustomField) => a.position - b.position;

/** On in a project: for every project, or turned on there. */
export const enabledIn = (f: CustomField, projectId: string) => f.isForAll || db.projectCustomFields.some((x) => x.projectId === projectId && x.customFieldId === f.id);

/** The fields an item of this project and type has, in order. */
export const activeFields = (projectId: string, typeId: string) => db.customFields.filter((f) => f.typeIds.includes(typeId) && enabledIn(f, projectId)).sort(byPosition);

/** Fields on in any of these projects (column and filter choices for a list). */
export const fieldsInProjects = (projectIds: string[]) => db.customFields.filter((f) => projectIds.some((p) => enabledIn(f, p))).sort(byPosition).map(toField);

export const toField = (f: CustomField): CustomField => clone(f);

const empty = (v: unknown) => v === null || v === undefined || v === "" || (Array.isArray(v) && v.length === 0);

/** Display form: option and user names, formatted dates, 예/아니요. */
export function cfText(f: CustomField, v: CustomValue): string {
  if (empty(v)) return "";
  const many = Array.isArray(v) ? v : [v];
  switch (f.format) {
    case "list":
      return many.map((id) => f.options.find((o) => o.id === id)?.value ?? "").filter(Boolean).join(", ");
    case "user":
      return many.map((id) => db.users.find((u) => u.id === id)?.name ?? "").filter(Boolean).join(", ");
    case "date":
      return formatDate(String(v));
    case "bool":
      return v ? "예" : "아니요";
    default:
      return String(v);
  }
}

export function customValuesOf(w: DbWp): Record<string, { value: CustomValue; text: string }> {
  const out: Record<string, { value: CustomValue; text: string }> = {};
  for (const f of activeFields(w.projectId, w.typeId)) {
    const value = w.customValues?.[f.id] ?? null;
    out[f.id] = { value: clone(value), text: cfText(f, value) };
  }
  return out;
}

/**
 * A value as sent by a client → as stored, or a 422 on the field "cf:<id>". projectId is null when checking a
 * field's default value in administration (people are not checked then).
 */
export function checkValue(f: CustomField, raw: unknown, projectId: string | null): CustomValue {
  const err = (m: string) => fieldError(cfKey(f.id), `${f.name}: ${m}`);
  if (empty(raw)) {
    if (f.isRequired && projectId) throw err("값을 입력하세요.");
    return null;
  }
  switch (f.format) {
    case "string":
    case "text": {
      if (typeof raw !== "string") throw err("글자를 입력하세요.");
      const s = f.format === "string" ? raw.trim() : raw;
      const max = f.maxLength ?? (f.format === "string" ? 255 : 10000);
      if (f.minLength !== null && s.length < f.minLength) throw err(`${f.minLength}자 이상 입력하세요.`);
      if (s.length > max) throw err(`${max}자까지 입력할 수 있습니다.`);
      if (f.regexp && !new RegExp(f.regexp).test(s)) throw err("형식이 맞지 않습니다.");
      return s;
    }
    case "integer":
    case "float": {
      const n = typeof raw === "number" ? raw : typeof raw === "string" && raw.trim() !== "" ? Number(raw) : NaN;
      if (!Number.isFinite(n)) throw err("숫자를 입력하세요.");
      if (f.format === "integer" && !Number.isInteger(n)) throw err("정수를 입력하세요.");
      return n;
    }
    case "date":
      if (typeof raw !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(raw) || Number.isNaN(Date.parse(raw))) throw err("날짜를 YYYY-MM-DD 형식으로 입력하세요.");
      return raw;
    case "bool":
      if (typeof raw !== "boolean") throw err("예 또는 아니요를 고르세요.");
      return raw;
    case "link":
      if (typeof raw !== "string" || !/^https?:\/\/\S+$/i.test(raw.trim())) throw err("http:// 또는 https://로 시작하는 주소를 입력하세요.");
      return raw.trim();
    case "list":
    case "user": {
      const ids = Array.isArray(raw) ? raw : [raw];
      if (!f.isMultiValue && ids.length > 1) throw err("하나만 고를 수 있습니다.");
      if (ids.some((x) => typeof x !== "string")) throw err("선택지를 고르세요.");
      const unique = [...new Set(ids as string[])];
      if (f.format === "list" && unique.some((id) => !f.options.some((o) => o.id === id))) throw err("없는 선택지입니다.");
      if (f.format === "user") {
        const people = projectId ? projectUserIds(projectId) : db.users.filter((u) => u.status === "active").map((u) => u.id);
        if (unique.some((id) => !people.includes(id))) throw err("이 프로젝트의 멤버가 아닙니다.");
      }
      return f.isMultiValue ? unique : unique[0];
    }
  }
}

/**
 * A client's customValues against an item (with any type change already applied): the new stored map and the
 * journal changes. Nothing is written, so a 422 on the second field leaves the first untouched. Unknown or
 * inactive fields are a 422 (a stale form must not write where nobody can see it).
 */
export function applyValues(w: Pick<DbWp, "projectId" | "typeId" | "customValues">, input: Record<string, CustomValue>) {
  const changes: Record<string, [unknown, unknown]> = {};
  const values: Record<string, CustomValue> = { ...(w.customValues ?? {}) };
  const active = activeFields(w.projectId, w.typeId);
  for (const [id, raw] of Object.entries(input)) {
    const f = active.find((x) => x.id === id);
    if (!f) throw fieldError(cfKey(id), "이 작업에서 쓰지 않는 사용자 정의 필드입니다.");
    const next = checkValue(f, raw, w.projectId);
    const prev = values[id] ?? null;
    if (JSON.stringify(prev) === JSON.stringify(next)) continue;
    if (next === null) delete values[id];
    else values[id] = next;
    changes[cfKey(id)] = [prev, next];
  }
  return { values, changes };
}

/** New items: defaults for active fields not given, then the required check for all of them. */
export function initialValues(projectId: string, typeId: string, input: Record<string, CustomValue> | undefined, skipRequired?: Set<string>): Record<string, CustomValue> {
  const out: Record<string, CustomValue> = {};
  for (const f of activeFields(projectId, typeId)) {
    const raw = input && f.id in input ? input[f.id] : f.defaultValue;
    // portal: a required field the requester cannot see must not block the submission (defaults still apply)
    if (skipRequired?.has(f.id) && empty(raw)) continue;
    const v = checkValue(f, raw, projectId);
    if (v !== null) out[f.id] = v;
  }
  for (const id of Object.keys(input ?? {}))
    if (!activeFields(projectId, typeId).some((f) => f.id === id)) throw fieldError(cfKey(id), "이 작업에서 쓰지 않는 사용자 정의 필드입니다.");
  return out;
}

/* ---------- lists: filters and sorting ---------- */

const REF_OPS: FilterOp[] = ["eq", "neq", "any", "none"];
const TEXT_OPS: FilterOp[] = ["contains", "not_contains", "any", "none"];
const NUM_OPS: FilterOp[] = ["eq", "gte", "lte", "any", "none"];
const DATE_OPS: FilterOp[] = ["today", "this_week", "next_days", "last_days", "on", "between", "any", "none"];

export function opsFor(f: CustomField): FilterOp[] {
  switch (f.format) {
    case "list":
    case "user":
      return REF_OPS;
    case "integer":
    case "float":
      return NUM_OPS;
    case "date":
      return DATE_OPS;
    case "bool":
      return ["eq"];
    default:
      return TEXT_OPS;
  }
}

export function findField(key: string): CustomField {
  const id = cfIdOf(key);
  const f = id ? db.customFields.find((x) => x.id === id) : undefined;
  if (!f) throw new ApiError(422, "없는 사용자 정의 필드입니다.");
  return f;
}

/** The stored value of a field on an item, or null when the item does not have that field. */
export function valueOn(w: DbWp, f: CustomField): CustomValue {
  return activeFields(w.projectId, w.typeId).some((x) => x.id === f.id) ? (w.customValues?.[f.id] ?? null) : null;
}

/** Sort key: list by option order, user by name, others by value; empty sorts last. */
export function sortKey(w: DbWp, f: CustomField): string | number {
  const v = valueOn(w, f);
  if (empty(v)) return "￿";
  const first = Array.isArray(v) ? v[0] : v;
  switch (f.format) {
    case "list":
      return f.options.findIndex((o) => o.id === first);
    case "integer":
    case "float":
      return Number(first);
    case "bool":
      return first ? 0 : 1;
    default:
      return cfText(f, first);
  }
}
