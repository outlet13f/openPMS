// The real client: one fetch per route in replica/architecture.md §4.2. Used when VITE_API=http,
// once /replica-backend has built the Spring Boot API. Screens never import this file directly.
import type { Api } from "./api";
import { ApiError } from "./errors";

const BASE = "/api/v1";

function csrfToken() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : undefined;
}

async function call<T>(method: string, path: string, body?: unknown, query?: Record<string, unknown>): Promise<T> {
  const url = new URL(BASE + path, window.location.origin);
  for (const [k, v] of Object.entries(query ?? {})) if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, String(v));
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const token = csrfToken();
  if (token && method !== "GET") headers["X-XSRF-TOKEN"] = token;
  const res = await fetch(url, { method, headers, credentials: "same-origin", body: body === undefined ? undefined : JSON.stringify(body) });
  if (res.status === 204) return undefined as T;
  const data = res.headers.get("content-type")?.includes("json") ? await res.json() : undefined;
  if (!res.ok) {
    throw new ApiError(res.status, data?.title ?? res.statusText, data?.detail, { fieldErrors: data?.errors, current: data?.current });
  }
  return data as T;
}

/** Multipart upload: the browser sets the boundary, so no Content-Type header here. */
async function upload<T>(path: string, file: File): Promise<T> {
  const form = new FormData();
  form.append("file", file);
  const headers: Record<string, string> = { Accept: "application/json" };
  const token = csrfToken();
  if (token) headers["X-XSRF-TOKEN"] = token;
  const res = await fetch(BASE + path, { method: "POST", headers, credentials: "same-origin", body: form });
  const data = res.headers.get("content-type")?.includes("json") ? await res.json() : undefined;
  if (!res.ok) throw new ApiError(res.status, data?.title ?? res.statusText, data?.detail, { fieldErrors: data?.errors });
  return data as T;
}

const get = <T>(p: string, q?: Record<string, unknown>) => call<T>("GET", p, undefined, q);
const post = <T>(p: string, b?: unknown) => call<T>("POST", p, b ?? {});
const patch = <T>(p: string, b: unknown) => call<T>("PATCH", p, b);
const put = <T>(p: string, b?: unknown) => call<T>("PUT", p, b ?? {});
const del = <T>(p: string, q?: Record<string, unknown>) => call<T>("DELETE", p, undefined, q);

export const httpApi: Api = {
  auth: {
    login: (email, password) => post("/auth/login", { email, password }),
    logout: () => post("/auth/logout"),
    me: async () => {
      try {
        return await get("/auth/me");
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
  },
  projects: {
    list: (params) => get("/projects", { ...params }),
    get: (id) => get(`/projects/${encodeURIComponent(id)}`),
    create: (input) => post("/projects", input),
    update: (id, p) => patch(`/projects/${id}`, p),
    setFavorite: (id, fav) => (fav ? put(`/projects/${id}/favorite`) : del(`/projects/${id}/favorite`)),
    overview: (id) => get(`/projects/${id}/overview`),
    setTypes: (id, typeIds) => put(`/projects/${id}/types`, { typeIds }),
    archive: (id) => post(`/projects/${id}/archive`),
  },
  categories: {
    list: (projectId) => get(`/projects/${projectId}/categories`),
    create: (projectId, input) => post(`/projects/${projectId}/categories`, input),
    update: (id, input) => patch(`/categories/${id}`, input),
    remove: (id) => del(`/categories/${id}`),
  },
  versions: {
    list: (projectId) => get(`/projects/${projectId}/versions`),
    create: (projectId, input) => post(`/projects/${projectId}/versions`, input),
    update: (id, input) => patch(`/versions/${id}`, input),
    remove: (id) => del(`/versions/${id}`),
  },
  members: {
    list: (projectId) => get(`/projects/${projectId}/members`),
    add: (projectId, principalIds, roleIds) => post(`/projects/${projectId}/members`, { principalIds, roleIds }),
    update: (memberId, roleIds) => patch(`/members/${memberId}`, { roleIds }),
    remove: (memberId) => del(`/members/${memberId}`),
    roles: () => get("/admin/roles", { scope: "project" }),
  },
  principals: {
    search: (q, projectId, includeGroups) => get("/principals", { q, projectId, kind: includeGroups ? "all" : "user" }),
  },
  workPackages: {
    form: (projectId, typeId) => get(`/projects/${projectId}/work-packages/form`, { typeId }),
    create: (projectId, input) => post(`/projects/${projectId}/work-packages`, input),
    get: (n) => get(`/work-packages/${n}`),
    update: (n, p) => patch(`/work-packages/${n}`, p),
    remove: (n, children) => del(`/work-packages/${n}`, { children }),
    allowedStatuses: (n) => get(`/work-packages/${n}/allowed-statuses`),
    autocomplete: (q, projectId) => get("/work-packages/autocomplete", { q, projectId }),
    activities: (n) => get(`/work-packages/${n}/activities`),
    comment: (n, bodyHtml) => post(`/work-packages/${n}/comments`, { bodyHtml }),
    editComment: (id, bodyHtml) => patch(`/comments/${id}`, { bodyHtml }),
    setWatching: (n, watching) => (watching ? post(`/work-packages/${n}/watchers`, { self: true }) : del(`/work-packages/${n}/watchers/me`)),
    attachments: (n) => get(`/work-packages/${n}/attachments`),
    upload: (n, file) => upload(`/work-packages/${n}/attachments`, file),
    removeAttachment: (id) => del(`/attachments/${id}`),
    relations: (n) => get(`/work-packages/${n}/relations`),
    addRelation: (n, input) => post(`/work-packages/${n}/relations`, input),
    removeRelation: (id) => del(`/relations/${id}`),
    schedulePreview: (n, p) => post(`/work-packages/${n}/schedule-preview`, p),
    query: (spec, opts) => post("/work-packages/query", { spec, ...opts }),
    filterOptions: (projectId) => get("/work-packages/filters", { projectId }),
  },
  queries: {
    list: (projectId, view) => get("/queries", { projectId, view }),
    create: (input) => post("/queries", input),
    update: (id, p) => patch(`/queries/${id}`, p),
    remove: (id) => del(`/queries/${id}`),
    setFavorite: (id, fav) => (fav ? put(`/queries/${id}/favorite`) : del(`/queries/${id}/favorite`)),
  },
  boards: {
    list: (projectId) => get(`/projects/${projectId}/boards`),
    create: (projectId, name, kind) => post(`/projects/${projectId}/boards`, { name, kind }),
    get: (id) => get(`/boards/${id}`),
    remove: (id) => del(`/boards/${id}`),
    move: (id, input) => post(`/boards/${id}/moves`, input),
  },
  notifications: {
    list: (filter) => get("/notifications", { filter }),
    unreadCount: async () => (await get<{ count: number }>("/notifications/unread-count")).count,
    markRead: (target) => post("/notifications/read", target),
  },
  calendar: {
    nonWorkingDays: (from, to) => get("/calendar/non-working-days", { from, to }),
  },
  search: {
    suggest: (q) => get("/search/suggest", { q }),
  },
  admin: {
    types: {
      list: () => get("/admin/types"),
      create: (input) => post("/admin/types", input),
      update: (id, p) => patch(`/admin/types/${id}`, p),
      remove: (id) => del(`/admin/types/${id}`),
      reorder: (ids) => put("/admin/types/order", { ids }),
    },
    statuses: {
      list: () => get("/admin/statuses"),
      create: (input) => post("/admin/statuses", input),
      update: (id, p) => patch(`/admin/statuses/${id}`, p),
      remove: (id) => del(`/admin/statuses/${id}`),
      reorder: (ids) => put("/admin/statuses/order", { ids }),
    },
    priorities: {
      list: () => get("/admin/priorities"),
      create: (input) => post("/admin/priorities", input),
      update: (id, p) => patch(`/admin/priorities/${id}`, p),
      remove: (id) => del(`/admin/priorities/${id}`),
      reorder: (ids) => put("/admin/priorities/order", { ids }),
    },
    workflows: {
      get: async (typeId, roleId) => (await get<{ transitions: [string, string][] }>("/admin/workflows", { typeId, roleId })).transitions,
      save: (typeId, roleId, transitions) => put("/admin/workflows", { typeId, roleId, appliesTo: "always", transitions }),
      copy: (from, to) => post("/admin/workflows/copy", { from, to }),
    },
    roles: {
      list: () => get("/admin/roles"),
      create: (input) => post("/admin/roles", input),
      update: (id, p) => patch(`/admin/roles/${id}`, p),
      remove: (id) => del(`/admin/roles/${id}`),
      permissions: () => get("/admin/permissions"),
    },
    users: {
      list: (params) => get("/admin/users", { ...params }),
      create: (input) => post("/admin/users", input),
      update: (id, p) => patch(`/admin/users/${id}`, p),
      remove: (id) => del(`/admin/users/${id}`),
      resendInvitation: (id) => post(`/admin/users/${id}/resend-invitation`),
    },
    groups: {
      list: () => get("/admin/groups"),
      create: (name) => post("/admin/groups", { name }),
      rename: (id, name) => patch(`/admin/groups/${id}`, { name }),
      remove: (id) => del(`/admin/groups/${id}`),
      setMembers: (id, userIds) => put(`/admin/groups/${id}/members`, { userIds }),
    },
    calendar: {
      get: () => get("/admin/settings/calendar"),
      save: (input) => put("/admin/settings/calendar", input),
      nonWorkingDays: (year) => get("/admin/non-working-days", { year }),
      addNonWorkingDay: (date, name) => post("/admin/non-working-days", { date, name }),
      removeNonWorkingDay: (id) => del(`/admin/non-working-days/${id}`),
    },
  },
};
