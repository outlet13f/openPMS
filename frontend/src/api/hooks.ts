// TanStack Query bindings. Screens use these hooks, never `api` directly, so caching and invalidation
// rules live in one place.
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { api } from "./index";
import type { BoardKind, Id, QuerySpec, RelationType, WorkPackage, WorkPackageInput, WorkPackagePatch } from "./types";

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
  wpStatuses: (n: number) => ["wp", n, "allowed-statuses"] as const,
  wpForm: (projectId: string, typeId?: string) => ["wp-form", projectId, typeId ?? ""] as const,
  query: (spec: QuerySpec, projectId?: string, page = 1, pageSize = 20) => ["wp-query", projectId ?? "", page, pageSize, spec] as const,
  filterOptions: (projectId?: string) => ["filter-options", projectId ?? ""] as const,
  savedQueries: (projectId?: string) => ["saved-queries", projectId ?? ""] as const,
  boards: (projectId: string) => ["boards", projectId] as const,
  board: (id: string) => ["board", id] as const,
  notifications: (filter: string) => ["notifications", "list", filter] as const,
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

/* ---------- projects ---------- */

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
export const useRelations = (n: number) => useQuery({ queryKey: qk.wpRelations(n), queryFn: () => api.workPackages.relations(n) });
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
    mutationFn: (bodyHtml: string) => api.workPackages.comment(n, bodyHtml),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.wpActivities(n) });
      qc.invalidateQueries({ queryKey: ["notifications"] });
    },
  });
}

export function useWatch(n: number) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (watching: boolean) => api.workPackages.setWatching(n, watching), onSuccess: () => qc.invalidateQueries({ queryKey: qk.wp(n) }) });
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

export const useSavedQueries = (projectId?: string) =>
  useQuery({ queryKey: qk.savedQueries(projectId), queryFn: () => api.queries.list(projectId) });

export function useSavedQueryMutations(projectId?: string) {
  const qc = useQueryClient();
  const done = () => qc.invalidateQueries({ queryKey: qk.savedQueries(projectId) });
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

/* ---------- notifications ---------- */

export const useNotifications = (filter: "unread" | "all") => useQuery({ queryKey: qk.notifications(filter), queryFn: () => api.notifications.list(filter) });

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
