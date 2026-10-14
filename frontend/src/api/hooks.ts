// TanStack Query bindings. Screens use these hooks, never `api` directly, so caching and invalidation
// rules live in one place.
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { api } from "./index";
import type { TimeFilter, TimeGroupBy, TimeWeekCell, ApiTokenInput, QueryFilter, BoardKind, Id, NotificationFilter, NotificationSetting, Preferences, ProfileInput, QuerySpec, RelationType, ShareRole, WorkPackage, WorkPackageInput, WorkPackagePatch, ServiceDeskSettings, ServiceTicketPatch, PortalSubmit } from "./types";

export const qk = {
  me: ["me"] as const,
  projects: (params?: object) => ["projects", params ?? {}] as const,
  project: (id: string) => ["project", id] as const,
  overview: (id: string) => ["project", id, "overview"] as const,
  members: (id: string) => ["project", id, "members"] as const,
  roles: ["roles"] as const,
  wp: (n: number) => ["wp", n] as const,
  wpActivities: (n: number) => ["wp", n, "activities"] as const,
  wpRelations: (n: number) => ["wp", n, "relations"] as const,
  wpAttachments: (n: number) => ["wp", n, "attachments"] as const,
  wpWatchers: (n: number) => ["wp", n, "watchers"] as const,
  wpShares: (n: number) => ["wp", n, "shares"] as const,
  wpStatuses: (n: number) => ["wp", n, "allowed-statuses"] as const,
  wpForm: (projectId: string, typeId?: string) => ["wp-form", projectId, typeId ?? ""] as const,
  query: (spec: QuerySpec, projectId?: string, page = 1, pageSize = 20) => ["wp-query", projectId ?? "", page, pageSize, spec] as const,
  filterOptions: (projectId?: string) => ["filter-options", projectId ?? ""] as const,
  /** The view kind comes last so invalidating ["saved-queries", projectId] refreshes every kind. */
  savedQueries: (projectId?: string, view?: string) => ["saved-queries", projectId ?? "", view ?? "work_packages"] as const,
  boards: (projectId: string) => ["boards", projectId] as const,
  board: (id: string) => ["board", id] as const,
  notifications: (filter: string, narrow?: NotificationFilter) => ["notifications", "list", filter, narrow ?? {}] as const,
  notificationCounts: (narrow?: NotificationFilter) => ["notifications", "counts", narrow ?? {}] as const,
  /** Shares the ["notifications"] prefix so every inbox change also refreshes the badge. */
  unread: ["notifications", "count"] as const,
  calendar: (from: string, to: string) => ["calendar", from, to] as const,
};

/** After any work package write: refresh lists, boards, the item itself and the inbox badge. */
export function invalidateWorkPackages(qc: QueryClient, number?: number) {
  qc.invalidateQueries({ queryKey: ["wp-query"] });
  qc.invalidateQueries({ queryKey: ["board"] });
  qc.invalidateQueries({ queryKey: ["notifications"] });
  qc.invalidateQueries({ queryKey: ["project"] });
  if (number !== undefined) qc.invalidateQueries({ queryKey: ["wp", number] });
  else qc.invalidateQueries({ queryKey: ["wp"] });
}

/* ---------- session ---------- */

export const useMe = () => useQuery({ queryKey: qk.me, queryFn: () => api.auth.me(), staleTime: 60_000 });

export const useNotificationSettings = () => useQuery({ queryKey: ["me", "notification-settings"], queryFn: () => api.account.notificationSettings() });

export function useAccountMutations() {
  const qc = useQueryClient();
  const settings = () => qc.invalidateQueries({ queryKey: ["me", "notification-settings"] });
  return {
    preferences: useMutation({ mutationFn: (p: Partial<Preferences>) => api.account.updatePreferences(p), onSuccess: () => qc.invalidateQueries({ queryKey: qk.me }) }),
    profile: useMutation({ mutationFn: (input: ProfileInput) => api.account.updateProfile(input), onSuccess: (me) => qc.setQueryData(qk.me, me) }),
    password: useMutation({ mutationFn: ({ current, next }: { current: string; next: string }) => api.account.changePassword(current, next) }),
    passwordSetup: useMutation({ mutationFn: () => api.account.sendPasswordSetup() }),
    endOthers: useMutation({ mutationFn: () => api.account.endOtherSessions() }),
    saveDefault: useMutation({ mutationFn: (s: NotificationSetting) => api.account.saveDefaultNotificationSetting(s), onSuccess: settings }),
    saveProject: useMutation({
      mutationFn: ({ projectId, setting }: { projectId: Id; setting: NotificationSetting }) => api.account.saveProjectNotificationSetting(projectId, setting),
      onSuccess: settings,
    }),
    removeProject: useMutation({ mutationFn: (projectId: Id) => api.account.removeProjectNotificationSetting(projectId), onSuccess: settings }),
  };
}

/* ---------- projects ---------- */

/** What a new password needs (public; also on the signed-out pages). */
export const useDisplaySettings = (enabled = true) => useQuery({ queryKey: ["display-settings"], queryFn: () => api.calendar.display(), enabled, staleTime: 5 * 60_000 });
export const useSsoProviders = () => useQuery({ queryKey: ["sso-providers"], queryFn: () => api.auth.ssoProviders(), staleTime: 60_000 });
export const usePasswordRules = () => useQuery({ queryKey: ["password-policy"], queryFn: () => api.auth.passwordPolicy(), staleTime: 60_000 });

export const useProjects = (params: Parameters<typeof api.projects.list>[0]) =>
  useQuery({ queryKey: qk.projects(params), queryFn: () => api.projects.list(params), placeholderData: keepPreviousData });

export const useProject = (id: string | undefined) =>
  useQuery({ queryKey: qk.project(id ?? ""), queryFn: () => api.projects.get(id!), enabled: !!id, staleTime: 30_000 });

export const useProjectOverview = (id: string) => useQuery({ queryKey: qk.overview(id), queryFn: () => api.projects.overview(id) });

export function useSetProjectFavorite() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, favorite }: { id: Id; favorite: boolean }) => api.projects.setFavorite(id, favorite),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ["projects"] });
      qc.invalidateQueries({ queryKey: ["project"] });
    },
  });
}

export function useCreateProject() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: api.projects.create, onSuccess: () => qc.invalidateQueries({ queryKey: ["projects"] }) });
}

/* ---------- members ---------- */

export const useMembers = (projectId: string) => useQuery({ queryKey: qk.members(projectId), queryFn: () => api.members.list(projectId) });
export const useRoles = () => useQuery({ queryKey: qk.roles, queryFn: () => api.members.roles(), staleTime: 300_000 });

export function useMemberMutations(projectId: string) {
  const qc = useQueryClient();
  const done = () => qc.invalidateQueries({ queryKey: qk.members(projectId) });
  return {
    add: useMutation({ mutationFn: ({ userIds, roleIds }: { userIds: Id[]; roleIds: Id[] }) => api.members.add(projectId, userIds, roleIds), onSuccess: done }),
    update: useMutation({ mutationFn: ({ memberId, roleIds }: { memberId: Id; roleIds: Id[] }) => api.members.update(memberId, roleIds), onSuccess: done }),
    remove: useMutation({ mutationFn: (memberId: Id) => api.members.remove(memberId), onSuccess: done }),
  };
}

/* ---------- work packages ---------- */

export const useWorkPackage = (n: number | undefined) =>
  useQuery({ queryKey: qk.wp(n ?? 0), queryFn: () => api.workPackages.get(n!), enabled: !!n });

export const useActivities = (n: number) => useQuery({ queryKey: qk.wpActivities(n), queryFn: () => api.workPackages.activities(n) });
export const useRelations = (n: number, enabled = true) => useQuery({ queryKey: qk.wpRelations(n), queryFn: () => api.workPackages.relations(n), enabled });
export const useAllowedStatuses = (n: number, enabled: boolean) =>
  useQuery({ queryKey: qk.wpStatuses(n), queryFn: () => api.workPackages.allowedStatuses(n), enabled });

export const useWpForm = (projectId: string | undefined, typeId?: string) =>
  useQuery({ queryKey: qk.wpForm(projectId ?? "", typeId), queryFn: () => api.workPackages.form(projectId!, typeId), enabled: !!projectId, placeholderData: keepPreviousData });

/** Runs once the view's spec is known (saved views load first). */
export const useWpQuery = (spec: QuerySpec | undefined, projectId: string | undefined, page: number, pageSize: number) =>
  useQuery({
    queryKey: qk.query(spec ?? ({} as QuerySpec), projectId, page, pageSize),
    queryFn: () => api.workPackages.query(spec!, { projectId, page, pageSize }),
    enabled: !!spec,
    placeholderData: keepPreviousData,
  });

export const useFilterOptions = (projectId?: string) =>
  useQuery({ queryKey: qk.filterOptions(projectId), queryFn: () => api.workPackages.filterOptions(projectId), staleTime: 60_000 });

export function useUpdateWorkPackage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ number, patch }: { number: number; patch: WorkPackagePatch }) => api.workPackages.update(number, patch),
    onSuccess: (wp: WorkPackage) => {
      qc.setQueryData(qk.wp(wp.number), wp);
      invalidateWorkPackages(qc, wp.number);
    },
  });
}

export function useCreateWorkPackage(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: WorkPackageInput) => api.workPackages.create(projectId, input),
    onSuccess: (wp) => {
      qc.setQueryData(qk.wp(wp.number), wp);
      invalidateWorkPackages(qc);
    },
  });
}

export function useDeleteWorkPackage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ number, children }: { number: number; children: "delete" | "reparent" }) => api.workPackages.remove(number, children),
    onSuccess: () => invalidateWorkPackages(qc),
  });
}

export function useComment(n: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ bodyHtml, internal }: { bodyHtml: string; internal?: boolean }) => api.workPackages.comment(n, bodyHtml, internal),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.wpActivities(n) });
      qc.invalidateQueries({ queryKey: ["notifications"] });
    },
  });
}

export const useAttachments = (n: number) => useQuery({ queryKey: qk.wpAttachments(n), queryFn: () => api.workPackages.attachments(n) });

/** Upload and delete; both refresh the list, the item (attachment count) and its activity. */
export function useAttachmentMutations(n: number) {
  const qc = useQueryClient();
  const done = () => {
    qc.invalidateQueries({ queryKey: qk.wpAttachments(n) });
    qc.invalidateQueries({ queryKey: qk.wpActivities(n) });
    invalidateWorkPackages(qc, n);
  };
  return {
    upload: useMutation({ mutationFn: (file: File) => api.workPackages.upload(n, file), onSuccess: done }),
    remove: useMutation({ mutationFn: (id: Id) => api.workPackages.removeAttachment(id), onSuccess: done }),
  };
}

export function useEditComment(n: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ journalId, bodyHtml }: { journalId: Id; bodyHtml: string }) => api.workPackages.editComment(journalId, bodyHtml),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.wpActivities(n) }),
  });
}

/** The header's "watch" toggle. Refreshes the item and with it the watcher list (["wp", n, …]). */
export function useWatch(n: number) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (watching: boolean) => api.workPackages.setWatching(n, watching), onSuccess: () => qc.invalidateQueries({ queryKey: qk.wp(n) }) });
}

export const useShares = (n: number, enabled = true) => useQuery({ queryKey: qk.wpShares(n), queryFn: () => api.workPackages.shares(n), enabled });

export function useShareMutations(n: number) {
  const qc = useQueryClient();
  const done = () => qc.invalidateQueries({ queryKey: qk.wp(n) });
  return {
    add: useMutation({ mutationFn: ({ userId, role }: { userId: Id; role: ShareRole }) => api.workPackages.share(n, userId, role), onSuccess: done }),
    update: useMutation({ mutationFn: ({ id, role }: { id: Id; role: ShareRole }) => api.workPackages.updateShare(id, role), onSuccess: done }),
    remove: useMutation({ mutationFn: (id: Id) => api.workPackages.removeShare(id), onSuccess: done }),
  };
}

export const useWatchers = (n: number, enabled = true) => useQuery({ queryKey: qk.wpWatchers(n), queryFn: () => api.workPackages.watchers(n), enabled });

export function useWatcherMutations(n: number) {
  const qc = useQueryClient();
  const done = () => qc.invalidateQueries({ queryKey: qk.wp(n) });
  return {
    add: useMutation({ mutationFn: (userId: Id) => api.workPackages.addWatcher(n, userId), onSuccess: done }),
    remove: useMutation({ mutationFn: (userId: Id) => api.workPackages.removeWatcher(n, userId), onSuccess: done }),
  };
}

export function useRelationMutations(n: number) {
  const qc = useQueryClient();
  const done = () => {
    qc.invalidateQueries({ queryKey: qk.wpRelations(n) });
    invalidateWorkPackages(qc);
  };
  return {
    add: useMutation({ mutationFn: (input: { type: RelationType; toNumber: number; lag?: number }) => api.workPackages.addRelation(n, input), onSuccess: done }),
    remove: useMutation({ mutationFn: (id: Id) => api.workPackages.removeRelation(id), onSuccess: done }),
  };
}

/* ---------- saved views ---------- */

export const useSavedQueries = (projectId?: string, view?: "calendar" | "team_planner") =>
  useQuery({ queryKey: qk.savedQueries(projectId, view), queryFn: () => api.queries.list(projectId, view) });

export function useSavedQueryMutations(projectId?: string) {
  const qc = useQueryClient();
  const done = () => qc.invalidateQueries({ queryKey: ["saved-queries", projectId ?? ""] });
  return {
    create: useMutation({ mutationFn: api.queries.create, onSuccess: done }),
    update: useMutation({ mutationFn: ({ id, patch }: { id: Id; patch: Parameters<typeof api.queries.update>[1] }) => api.queries.update(id, patch), onSuccess: done }),
    remove: useMutation({ mutationFn: api.queries.remove, onSuccess: done }),
    favorite: useMutation({ mutationFn: ({ id, favorite }: { id: Id; favorite: boolean }) => api.queries.setFavorite(id, favorite), onSuccess: done }),
  };
}

/* ---------- boards ---------- */

export const useBoards = (projectId: string) => useQuery({ queryKey: qk.boards(projectId), queryFn: () => api.boards.list(projectId) });
export const useBoard = (id: string) => useQuery({ queryKey: qk.board(id), queryFn: () => api.boards.get(id) });
/** The board through filters that are not saved (null: off). */
export const useBoardLook = (id: string, filters: QueryFilter[] | null) =>
  useQuery({ queryKey: [...qk.board(id), "look", JSON.stringify(filters)], queryFn: () => api.boards.get(id, filters), enabled: filters !== null, placeholderData: keepPreviousData });

export function useBoardMutations(projectId: string) {
  const qc = useQueryClient();
  return {
    create: useMutation({
      mutationFn: ({ name, kind }: { name: string; kind: BoardKind }) => api.boards.create(projectId, name, kind),
      onSuccess: () => qc.invalidateQueries({ queryKey: qk.boards(projectId) }),
    }),
    remove: useMutation({ mutationFn: (id: Id) => api.boards.remove(id), onSuccess: () => qc.invalidateQueries({ queryKey: qk.boards(projectId) }) }),
  };
}

/* ---------- time tracking ---------- */

export const useTimeActivities = () => useQuery({ queryKey: ["time", "activities"], queryFn: () => api.time.activities(), staleTime: 5 * 60_000 });
export const useTimeWeek = (start?: string) =>
  useQuery({ queryKey: ["time", "week", start ?? ""], queryFn: () => api.time.week(start), placeholderData: keepPreviousData });
export const useTimeEntries = (params: TimeFilter & { workPackage?: number }, enabled = true) =>
  useQuery({ queryKey: ["time", "entries", params], queryFn: () => api.time.list(params), enabled });
export const useTimeReport = (params: TimeFilter & { groupBy: TimeGroupBy }, enabled = true) =>
  useQuery({ queryKey: ["time", "report", params], queryFn: () => api.time.report(params), placeholderData: keepPreviousData, enabled });

/** Every change refreshes the week, the lists and the report. */
export function useTimeMutations() {
  const qc = useQueryClient();
  const done = () => qc.invalidateQueries({ queryKey: ["time"] });
  return {
    saveWeek: useMutation({ mutationFn: (v: { start: string; cells: TimeWeekCell[] }) => api.time.saveWeek(v.start, v.cells), onSuccess: done }),
    create: useMutation({ mutationFn: api.time.create, onSuccess: done }),
    update: useMutation({ mutationFn: (v: { id: Id; patch: Parameters<typeof api.time.update>[1] }) => api.time.update(v.id, v.patch), onSuccess: done }),
    remove: useMutation({ mutationFn: api.time.remove, onSuccess: done }),
  };
}

/* ---------- notifications ---------- */

export const useNotifications = (filter: "unread" | "all", narrow?: NotificationFilter) =>
  useQuery({ queryKey: qk.notifications(filter, narrow), queryFn: () => api.notifications.list(filter, narrow), placeholderData: keepPreviousData });
export const useNotificationCounts = (narrow?: NotificationFilter) =>
  useQuery({ queryKey: qk.notificationCounts(narrow), queryFn: () => api.notifications.counts(narrow), placeholderData: keepPreviousData });

/** Header badge: polls every 60 s (architecture.md §5 "실시간"). */
export const useUnreadCount = (enabled: boolean) =>
  useQuery({ queryKey: qk.unread, queryFn: () => api.notifications.unreadCount(), refetchInterval: 60_000, enabled });

/**
 * keepList: refresh only the badge, so the item the user just opened does not vanish from the
 * "읽지 않음" list under their cursor (the list refreshes on the next visit or filter change).
 */
export function useMarkRead(opts: { keepList?: boolean } = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: api.notifications.markRead,
    onSuccess: () => qc.invalidateQueries({ queryKey: opts.keepList ? qk.unread : ["notifications"] }),
  });
}

/* ---------- calendar ---------- */

export const useNonWorkingDays = (from: string, to: string) =>
  useQuery({ queryKey: qk.calendar(from, to), queryFn: () => api.calendar.nonWorkingDays(from, to), staleTime: 300_000 });

/* ---------- 외부 연동 API 토큰 ---------- */

export const useApiTokens = () => useQuery({ queryKey: ["me", "api-tokens"], queryFn: () => api.account.apiTokens() });
export function useApiTokenMutations() {
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: ["me", "api-tokens"] });
  return {
    create: useMutation({ mutationFn: (input: ApiTokenInput) => api.account.createApiToken(input), onSuccess: refresh }),
    revoke: useMutation({ mutationFn: (id: Id) => api.account.revokeApiToken(id), onSuccess: refresh }),
  };
}

/* ---------- 서비스 데스크 (ITSM 1단계) ---------- */

export const useServiceCalendars = () => useQuery({ queryKey: ["service-calendars"], queryFn: () => api.serviceDesk.calendars(), staleTime: 300_000 });
export const useServiceDeskSettings = (project: string) =>
  useQuery({ queryKey: ["serviceDesk", project, "settings"], queryFn: () => api.serviceDesk.settings(project) });
export const useServiceDeskGroups = (project: string) =>
  useQuery({ queryKey: ["serviceDesk", project, "groups"], queryFn: () => api.serviceDesk.groups(project), staleTime: 300_000 });
export const useServiceDeskSummary = (project: string) =>
  useQuery({ queryKey: ["serviceDesk", project, "summary"], queryFn: () => api.serviceDesk.summary(project) });

/** Saving the SLA policies and matrix refreshes everything of that desk. */
export function useServiceDeskMutations(project: string) {
  const qc = useQueryClient();
  return {
    saveSettings: useMutation({
      mutationFn: (s: ServiceDeskSettings) => api.serviceDesk.saveSettings(project, s),
      onSuccess: () => qc.invalidateQueries({ queryKey: ["serviceDesk", project] }),
    }),
  };
}

/** Requester, impact, urgency, group (and the matrix priority) of one ticket: a work package write. */
export function useServiceTicketUpdate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ number, patch }: { number: number; patch: ServiceTicketPatch }) => api.serviceDesk.updateTicket(number, patch),
    onSuccess: (wp: WorkPackage) => {
      qc.setQueryData(qk.wp(wp.number), wp);
      invalidateWorkPackages(qc, wp.number);
      qc.invalidateQueries({ queryKey: ["serviceDesk"] });
    },
  });
}

/* the requester's portal */

export const usePortalDesks = () => useQuery({ queryKey: ["portal", "desks"], queryFn: () => api.portal.desks() });
export const usePortalTickets = (state: "open" | "closed" | "all") =>
  useQuery({ queryKey: ["portal", "tickets", state], queryFn: () => api.portal.tickets(state), placeholderData: keepPreviousData });
/** Opening a ticket clears its "새 소식", so the lists refresh after it loads. */
export const usePortalTicket = (number: number) => useQuery({ queryKey: ["portal", "ticket", number], queryFn: () => api.portal.ticket(number) });

export function usePortalMutations() {
  const qc = useQueryClient();
  const done = () => qc.invalidateQueries({ queryKey: ["portal"] });
  return {
    submit: useMutation({ mutationFn: (v: { identifier: string; input: PortalSubmit }) => api.portal.submit(v.identifier, v.input), onSuccess: done }),
    comment: useMutation({ mutationFn: (v: { number: number; bodyHtml: string }) => api.portal.comment(v.number, v.bodyHtml), onSuccess: done }),
    setStatus: useMutation({
      mutationFn: (v: { number: number; statusId: Id; lockVersion: number }) => api.portal.setStatus(v.number, v.statusId, v.lockVersion),
      onSuccess: done,
    }),
    attach: useMutation({ mutationFn: (v: { number: number; file: File }) => api.portal.attach(v.number, v.file), onSuccess: done }),
  };
}
