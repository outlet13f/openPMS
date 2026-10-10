// Query hooks for administration (S27–S36) and project settings (S24–S26).
import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { api } from "./index";
import type { CalendarSettings, CustomFieldInput, Id, ProgressSettings, PriorityInput, RoleInput, StatusInput, Transition, TypeInput, UserInput, VersionInput } from "./types";

export const ak = {
  customFields: ["admin", "custom-fields"] as const,
  projectCustomFields: (projectId: string) => ["project", projectId, "custom-fields"] as const,
  types: ["admin", "types"] as const,
  statuses: ["admin", "statuses"] as const,
  priorities: ["admin", "priorities"] as const,
  roles: ["admin", "roles"] as const,
  permissions: ["admin", "permissions"] as const,
  workflow: (typeId: string, roleId: string) => ["admin", "workflow", typeId, roleId] as const,
  users: (params: object) => ["admin", "users", params] as const,
  groups: ["admin", "groups"] as const,
  calendar: ["admin", "calendar"] as const,
  nonWorkingDays: (year: number) => ["admin", "non-working-days", year] as const,
  categories: (projectId: string) => ["project", projectId, "categories"] as const,
  versions: (projectId: string) => ["project", projectId, "versions"] as const,
};

/** Admin changes reach into every screen (type colours, status names, forms, schedules). */
function refreshEverything(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: ["admin"] });
  qc.invalidateQueries({ queryKey: ["wp-query"] });
  qc.invalidateQueries({ queryKey: ["wp"] });
  qc.invalidateQueries({ queryKey: ["wp-form"] });
  qc.invalidateQueries({ queryKey: ["filter-options"] });
  qc.invalidateQueries({ queryKey: ["board"] });
  qc.invalidateQueries({ queryKey: ["project"] });
  qc.invalidateQueries({ queryKey: ["roles"] });
  qc.invalidateQueries({ queryKey: ["calendar"] });
}

function useMut<A, R>(fn: (a: A) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => refreshEverything(qc) });
}

/* ---------- progress tracking (S32) ---------- */

export const useProgressSettings = () => useQuery({ queryKey: ["admin", "progress"], queryFn: () => api.admin.progress.get() });
export const useSaveProgressSettings = () => useMut((input: ProgressSettings) => api.admin.progress.save(input));

/* ---------- custom fields (S34, S25) ---------- */

export const useAdminCustomFields = () => useQuery({ queryKey: ak.customFields, queryFn: () => api.admin.customFields.list() });
export function useCustomFieldMutations() {
  return {
    create: useMut((input: CustomFieldInput) => api.admin.customFields.create(input)),
    update: useMut(({ id, patch }: { id: Id; patch: Partial<CustomFieldInput> }) => api.admin.customFields.update(id, patch)),
    remove: useMut((id: Id) => api.admin.customFields.remove(id)),
    reorder: useMut((ids: Id[]) => api.admin.customFields.reorder(ids)),
  };
}
export const useProjectCustomFields = (projectId: Id, enabled = true) =>
  useQuery({ queryKey: ak.projectCustomFields(projectId), queryFn: () => api.projects.customFields(projectId), enabled });
export const useSetProjectCustomFields = (projectId: Id) => useMut((ids: Id[]) => api.projects.setCustomFields(projectId, ids));

/* ---------- types / statuses / priorities ---------- */

export const useAdminTypes = () => useQuery({ queryKey: ak.types, queryFn: () => api.admin.types.list() });
export function useTypeMutations() {
  return {
    create: useMut((input: TypeInput) => api.admin.types.create(input)),
    update: useMut(({ id, patch }: { id: Id; patch: Partial<TypeInput> }) => api.admin.types.update(id, patch)),
    remove: useMut((id: Id) => api.admin.types.remove(id)),
    reorder: useMut((ids: Id[]) => api.admin.types.reorder(ids)),
  };
}

export const useAdminStatuses = () => useQuery({ queryKey: ak.statuses, queryFn: () => api.admin.statuses.list() });
export function useStatusMutations() {
  return {
    create: useMut((input: StatusInput) => api.admin.statuses.create(input)),
    update: useMut(({ id, patch }: { id: Id; patch: Partial<StatusInput> }) => api.admin.statuses.update(id, patch)),
    remove: useMut((id: Id) => api.admin.statuses.remove(id)),
    reorder: useMut((ids: Id[]) => api.admin.statuses.reorder(ids)),
  };
}

export const useAdminPriorities = () => useQuery({ queryKey: ak.priorities, queryFn: () => api.admin.priorities.list() });
export function usePriorityMutations() {
  return {
    create: useMut((input: PriorityInput) => api.admin.priorities.create(input)),
    update: useMut(({ id, patch }: { id: Id; patch: Partial<PriorityInput> }) => api.admin.priorities.update(id, patch)),
    remove: useMut((id: Id) => api.admin.priorities.remove(id)),
    reorder: useMut((ids: Id[]) => api.admin.priorities.reorder(ids)),
  };
}

/* ---------- workflows / roles ---------- */

export const useWorkflow = (typeId: string | undefined, roleId: string | undefined) =>
  useQuery({ queryKey: ak.workflow(typeId ?? "", roleId ?? ""), queryFn: () => api.admin.workflows.get(typeId!, roleId!), enabled: !!typeId && !!roleId });
export function useWorkflowMutations() {
  return {
    save: useMut(({ typeId, roleId, transitions }: { typeId: Id; roleId: Id; transitions: Transition[] }) => api.admin.workflows.save(typeId, roleId, transitions)),
    copy: useMut(({ from, to }: { from: { typeId: Id; roleId: Id }; to: { typeIds: Id[]; roleIds: Id[] } }) => api.admin.workflows.copy(from, to)),
  };
}

export const useAdminRoles = () => useQuery({ queryKey: ak.roles, queryFn: () => api.admin.roles.list() });
export const usePermissionDefs = () => useQuery({ queryKey: ak.permissions, queryFn: () => api.admin.roles.permissions(), staleTime: Infinity });
export function useRoleMutations() {
  return {
    create: useMut((input: RoleInput) => api.admin.roles.create(input)),
    update: useMut(({ id, patch }: { id: Id; patch: Partial<Omit<RoleInput, "scope">> }) => api.admin.roles.update(id, patch)),
    remove: useMut((id: Id) => api.admin.roles.remove(id)),
  };
}

/* ---------- users / groups ---------- */

export const useAdminUsers = (params: { q?: string; status?: "invited" | "active" | "locked" | "deleted" }) =>
  useQuery({ queryKey: ak.users(params), queryFn: () => api.admin.users.list(params) });
export function useUserMutations() {
  return {
    create: useMut((input: UserInput) => api.admin.users.create(input)),
    update: useMut(({ id, patch }: { id: Id; patch: Parameters<typeof api.admin.users.update>[1] }) => api.admin.users.update(id, patch)),
    remove: useMut((id: Id) => api.admin.users.remove(id)),
    resend: useMut((id: Id) => api.admin.users.resendInvitation(id)),
  };
}

export const useAdminGroups = () => useQuery({ queryKey: ak.groups, queryFn: () => api.admin.groups.list() });
export function useGroupMutations() {
  return {
    create: useMut((name: string) => api.admin.groups.create(name)),
    rename: useMut(({ id, name }: { id: Id; name: string }) => api.admin.groups.rename(id, name)),
    remove: useMut((id: Id) => api.admin.groups.remove(id)),
    setMembers: useMut(({ id, userIds }: { id: Id; userIds: Id[] }) => api.admin.groups.setMembers(id, userIds)),
  };
}

/* ---------- calendar ---------- */

export const useCalendarSettings = () => useQuery({ queryKey: ak.calendar, queryFn: () => api.admin.calendar.get() });
export const useAdminNonWorkingDays = (year: number) => useQuery({ queryKey: ak.nonWorkingDays(year), queryFn: () => api.admin.calendar.nonWorkingDays(year) });
export function useCalendarMutations() {
  return {
    save: useMut((input: CalendarSettings) => api.admin.calendar.save(input)),
    add: useMut(({ date, name }: { date: string; name: string }) => api.admin.calendar.addNonWorkingDay(date, name)),
    remove: useMut((id: Id) => api.admin.calendar.removeNonWorkingDay(id)),
  };
}

/* ---------- project settings ---------- */

export const useCategories = (projectId: string) => useQuery({ queryKey: ak.categories(projectId), queryFn: () => api.categories.list(projectId) });
export function useCategoryMutations(projectId: string) {
  return {
    create: useMut((input: { name: string; defaultAssigneeId: Id | null }) => api.categories.create(projectId, input)),
    update: useMut(({ id, input }: { id: Id; input: { name?: string; defaultAssigneeId?: Id | null } }) => api.categories.update(id, input)),
    remove: useMut((id: Id) => api.categories.remove(id)),
  };
}

export const useVersions = (projectId: string) => useQuery({ queryKey: ak.versions(projectId), queryFn: () => api.versions.list(projectId) });
export function useVersionMutations(projectId: string) {
  return {
    create: useMut((input: VersionInput) => api.versions.create(projectId, input)),
    update: useMut(({ id, input }: { id: Id; input: Partial<VersionInput> }) => api.versions.update(id, input)),
    remove: useMut((id: Id) => api.versions.remove(id)),
  };
}

/** S03 (administrators): back from the archive, or delete for good. */
export function useProjectLifecycle() {
  const qc = useQueryClient();
  const done = () => (refreshEverything(qc), qc.invalidateQueries({ queryKey: ["projects"] }));
  return {
    unarchive: useMutation({ mutationFn: (id: Id) => api.projects.unarchive(id), onSuccess: done }),
    remove: useMutation({ mutationFn: ({ id, confirmIdentifier }: { id: Id; confirmIdentifier: string }) => api.projects.remove(id, confirmIdentifier), onSuccess: done }),
  };
}

export function useProjectSettingsMutations(projectId: string) {
  return {
    update: useMut((patch: Parameters<typeof api.projects.update>[1]) => api.projects.update(projectId, patch)),
    setTypes: useMut((typeIds: Id[]) => api.projects.setTypes(projectId, typeIds)),
    archive: useMut(() => api.projects.archive(projectId)),
  };
}
