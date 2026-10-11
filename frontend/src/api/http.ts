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

/** A file answer (export): the bytes and the name from Content-Disposition (filename*=UTF-8''…). */
async function download(path: string, body: unknown): Promise<{ blob: Blob; fileName: string }> {
  const headers: Record<string, string> = { "Content-Type": "application/json", Accept: "*/*" };
  const token = csrfToken();
  if (token) headers["X-XSRF-TOKEN"] = token;
  const res = await fetch(BASE + path, { method: "POST", headers, credentials: "same-origin", body: JSON.stringify(body) });
  if (!res.ok) {
    const data = res.headers.get("content-type")?.includes("json") ? await res.json() : undefined;
    throw new ApiError(res.status, data?.title ?? res.statusText, data?.detail, { fieldErrors: data?.errors });
  }
  const cd = res.headers.get("content-disposition") ?? "";
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  return { blob: await res.blob(), fileName: star ? decodeURIComponent(star[1]) : (plain?.[1] ?? "export") };
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
    passwordPolicy: () => get("/auth/password-policy"),
    requestPasswordReset: (email) => post("/auth/password-reset", { email }),
    confirmPasswordReset: (token, newPassword) => post("/auth/password-reset/confirm", { token, newPassword }),
    invitation: (token) => get(`/auth/invitations/${encodeURIComponent(token)}`),
    acceptInvitation: (token, input) => post(`/auth/invitations/${encodeURIComponent(token)}/accept`, input),
    ssoProviders: () => get("/auth/sso-providers"),
    ssoStartUrl: (slug, next) => `${BASE}/auth/oidc/start/${encodeURIComponent(slug)}${next ? `?next=${encodeURIComponent(next)}` : ""}`,
  },
  account: {
    updateProfile: (input) => patch("/me", input),
    changePassword: (currentPassword, newPassword) => put("/me/password", { currentPassword, newPassword }),
    sendPasswordSetup: () => post("/me/password/setup", {}),
    endOtherSessions: () => post("/me/sessions/end-others"),
    apiTokens: () => get("/me/api-tokens"),
    createApiToken: (input) => post("/me/api-tokens", input),
    revokeApiToken: (id) => del(`/me/api-tokens/${id}`),
    updatePreferences: (p) => put("/me/preferences", p),
    notificationSettings: () => get("/me/notification-settings"),
    saveDefaultNotificationSetting: (s) => put("/me/notification-settings/default", s),
    saveProjectNotificationSetting: (projectId, s) => put(`/me/notification-settings/projects/${projectId}`, s),
    removeProjectNotificationSetting: (projectId) => del(`/me/notification-settings/projects/${projectId}`),
  },
  projects: {
    list: (params) => get("/projects", { ...params }),
    get: (id) => get(`/projects/${encodeURIComponent(id)}`),
    create: (input) => post("/projects", input),
    copy: (id, input) => post(`/projects/${id}/copy`, input),
    update: (id, p) => patch(`/projects/${id}`, p),
    setFavorite: (id, fav) => (fav ? put(`/projects/${id}/favorite`) : del(`/projects/${id}/favorite`)),
    overview: (id) => get(`/projects/${id}/overview`),
    setTypes: (id, typeIds) => put(`/projects/${id}/types`, { typeIds }),
    customFields: (id) => get(`/projects/${id}/custom-fields`),
    setCustomFields: (id, customFieldIds) => put(`/projects/${id}/custom-fields`, { customFieldIds }),
    archive: (id) => post(`/projects/${id}/archive`),
    unarchive: (id) => post(`/projects/${id}/unarchive`),
    remove: (id, confirmIdentifier) => call("DELETE", `/projects/${id}`, { confirmIdentifier }),
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
    comment: (n, bodyHtml, internal) => post(`/work-packages/${n}/comments`, { bodyHtml, internal: !!internal }),
    editComment: (id, bodyHtml) => patch(`/comments/${id}`, { bodyHtml }),
    export: (input) => download("/work-packages/export", input),
    copy: (number, { targetProjectId, ...rest }) => post(`/work-packages/${number}/copy`, { ...rest, targetProject: targetProjectId }),
    move: (number, { targetProjectId, ...rest }) => post(`/work-packages/${number}/move`, { ...rest, targetProject: targetProjectId }),
    bulk: ({ targetProjectId, ...rest }) => post("/work-packages/bulk", { ...rest, targetProject: targetProjectId }),
    exportCount: (input) => post("/work-packages/export/count", input),
    shares: (n) => get(`/work-packages/${n}/shares`),
    share: (n, userId, role) => post(`/work-packages/${n}/shares`, { userId, role }),
    updateShare: (id, role) => patch(`/shares/${id}`, { role }),
    removeShare: (id) => del(`/shares/${id}`),
    watchers: (n) => get(`/work-packages/${n}/watchers`),
    addWatcher: (n, userId) => post(`/work-packages/${n}/watchers`, { userId }),
    removeWatcher: (n, userId) => del(`/work-packages/${n}/watchers/${userId}`),
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
    get: (id, filters) => get(`/boards/${id}`, filters ? { filters: JSON.stringify(filters) } : undefined),
    remove: (id) => del(`/boards/${id}`),
    move: (id, input) => post(`/boards/${id}/moves`, input),
    update: (id, p) => patch(`/boards/${id}`, p),
    addColumn: (id, input) => post(`/boards/${id}/columns`, input),
    updateColumn: (columnId, p) => patch(`/board-columns/${columnId}`, p),
    removeColumn: (columnId) => del(`/board-columns/${columnId}`),
    addCard: (id, columnId, number) => post(`/boards/${id}/cards`, { columnId, number }),
    removeCard: (id, number) => del(`/boards/${id}/cards/${number}`),
    createCard: (id, columnId, subject) => post(`/boards/${id}/cards/new`, { columnId, subject }),
  },
  notifications: {
    list: (filter) => get("/notifications", { filter }),
    unreadCount: async () => (await get<{ count: number }>("/notifications/unread-count")).count,
    markRead: (target) => post("/notifications/read", target),
  },
  calendar: {
    display: () => get("/settings/display"),
    nonWorkingDays: (from, to) => get("/calendar/non-working-days", { from, to }),
  },
  search: {
    suggest: (q) => get("/search/suggest", { q }),
    results: (q, scope, page) => get("/search", { q, scope, page: String(page) }),
  },
  admin: {
    customFields: {
      list: () => get("/admin/custom-fields"),
      create: (input) => post("/admin/custom-fields", input),
      update: (id, p) => patch(`/admin/custom-fields/${id}`, p),
      remove: (id) => del(`/admin/custom-fields/${id}`),
      reorder: (ids) => put("/admin/custom-fields/order", { ids }),
    },
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
    api: {
      settings: () => get("/admin/settings/api"),
      saveSettings: (input) => put("/admin/settings/api", input),
      tokens: () => get("/admin/api-tokens"),
      revokeToken: (id) => del(`/admin/api-tokens/${id}`),
    },
    users: {
      list: (params) => get("/admin/users", { ...params }),
      create: (input) => post("/admin/users", input),
      update: (id, p) => patch(`/admin/users/${id}`, p),
      remove: (id) => del(`/admin/users/${id}`),
      resendInvitation: (id) => post(`/admin/users/${id}/resend-invitation`),
      setGlobalRoles: (id, roleIds) => put(`/admin/users/${id}/global-roles`, { roleIds }),
    },
    groups: {
      list: () => get("/admin/groups"),
      create: (name) => post("/admin/groups", { name }),
      rename: (id, name) => patch(`/admin/groups/${id}`, { name }),
      remove: (id) => del(`/admin/groups/${id}`),
      setMembers: (id, userIds) => put(`/admin/groups/${id}/members`, { userIds }),
    },
    auth: {
      get: () => get("/admin/settings/auth"),
      save: (input) => put("/admin/settings/auth", input),
    },
    oidc: {
      list: () => get("/admin/oidc-providers"),
      create: (input) => post("/admin/oidc-providers", input),
      update: (id, input) => patch(`/admin/oidc-providers/${id}`, input),
      remove: (id) => del(`/admin/oidc-providers/${id}`),
    },
    progress: {
      get: () => get("/admin/settings/progress"),
      save: (input) => put("/admin/settings/progress", input),
    },
    calendar: {
      get: () => get("/admin/settings/calendar"),
      save: (input) => put("/admin/settings/calendar", input),
      nonWorkingDays: (year) => get("/admin/non-working-days", { year }),
      addNonWorkingDay: (date, name) => post("/admin/non-working-days", { date, name }),
      saveDisplay: (input) => put("/admin/settings/display", input),
      removeNonWorkingDay: (id) => del(`/admin/non-working-days/${id}`),
    },
  },
};
