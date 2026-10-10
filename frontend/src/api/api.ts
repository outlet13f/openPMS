import type { ExportCount, Invitation,
  Attachment,
  ProgressSettings,
  ExportInput,
  SearchResults,
  SearchScope,
  ProfileInput,
  NotificationSetting,
  NotificationSettings,
  Preferences,
  ProjectNotificationSetting,
  ShareRole,
  WorkPackageShare,
  AdminCustomField,
  CustomFieldInput,
  ProjectCustomFields,
  Watcher,
  AdminGroup,
  AdminPriority,
  AdminRole,
  AdminStatus,
  AdminType,
  AdminUser,
  Board,
  BoardKind,
  BoardSummary,
  CalendarSettings,
  Category,
  FilterOptions,
  Id,
  IsoDate,
  Journal,
  Me,
  Member,
  NotificationGroup,
  Page,
  PriorityInput,
  Project,
  ProjectInput,
  ProjectListParams,
  ProjectOverview,
  QueryResult,
  QuerySpec,
  RelationGroups,
  RelationType,
  Role,
  RoleInput,
  SavedQuery,
  SchedulePreview,
  SearchHit,
  StatusInput,
  StatusRef,
  Transition,
  TypeInput,
  UserInput,
  UserRef,
  Version,
  VersionInput,
  ViewKind,
  WorkPackage,
  WorkPackageForm,
  WorkPackageInput,
  WorkPackagePatch,
  WorkPackageRef,
} from "./types";

/**
 * The openPMS API as the screens see it. One method per route in replica/architecture.md §4.2
 * (the comment on each method names the route). `fake/` implements it in memory, `http.ts` over fetch.
 */
export interface Api {
  auth: {
    /** POST /auth/login */
    login(email: string, password: string): Promise<Me>;
    /** POST /auth/logout */
    logout(): Promise<void>;
    /** GET /auth/me — null when not signed in (401). */
    me(): Promise<Me | null>;
    /** POST /auth/password-reset — sends a link if the address has an active account; answers the same either way. */
    requestPasswordReset(email: string): Promise<void>;
    /** POST /auth/password-reset/confirm — 410 when the link is expired or used. Signs the account out everywhere. */
    confirmPasswordReset(token: string, newPassword: string): Promise<void>;
    /** GET /auth/invitations/{token} — 410 when expired, used or unknown. */
    invitation(token: string): Promise<Invitation>;
    /** POST /auth/invitations/{token}/accept — activates the account and signs it in. */
    acceptInvitation(token: string, input: { name: string; password: string }): Promise<Me>;
  };

  /** My own settings (architecture.md §4.2 "/me"). */
  account: {
    /** PATCH /me — name, time zone, theme */
    updateProfile(input: ProfileInput): Promise<Me>;
    /** PUT /me/password — 422 on a wrong current password or a weak new one; my other sessions end */
    changePassword(currentPassword: string, newPassword: string): Promise<void>;
    /** POST /me/sessions/end-others — sign out everywhere but here */
    endOtherSessions(): Promise<void>;
    /** PUT /me/preferences — only the given keys change */
    updatePreferences(patch: Partial<Preferences>): Promise<Preferences>;
    /** GET /me/notification-settings */
    notificationSettings(): Promise<NotificationSettings>;
    /** PUT /me/notification-settings/default */
    saveDefaultNotificationSetting(setting: NotificationSetting): Promise<NotificationSetting>;
    /** PUT /me/notification-settings/projects/{project} — creates or replaces that project's setting */
    saveProjectNotificationSetting(projectId: Id, setting: NotificationSetting): Promise<ProjectNotificationSetting>;
    /** DELETE /me/notification-settings/projects/{project} — the project follows my default again */
    removeProjectNotificationSetting(projectId: Id): Promise<void>;
  };

  projects: {
    /** GET /projects */
    list(params?: ProjectListParams): Promise<Page<Project>>;
    /** GET /projects/{project} */
    get(idOrIdentifier: string): Promise<Project>;
    /** POST /projects */
    create(input: ProjectInput): Promise<Project>;
    /** PATCH /projects/{project} */
    update(id: Id, patch: Partial<Pick<Project, "name" | "descriptionHtml" | "status" | "statusExplanation" | "isPublic" | "enabledModules">>): Promise<Project>;
    /** PUT|DELETE /projects/{project}/favorite */
    setFavorite(id: Id, favorite: boolean): Promise<void>;
    /** GET /projects/{project}/overview */
    overview(id: Id): Promise<ProjectOverview>;
    /** PUT /projects/{project}/types */
    setTypes(id: Id, typeIds: Id[]): Promise<Project>;
    /** GET /projects/{project}/custom-fields (project.manage_custom_fields) */
    customFields(id: Id): Promise<ProjectCustomFields>;
    /** PUT /projects/{project}/custom-fields — the fields turned on here (isForAll ones need not be listed) */
    setCustomFields(id: Id, customFieldIds: Id[]): Promise<ProjectCustomFields>;
    /** POST /projects/{project}/archive */
    archive(id: Id): Promise<void>;
    /** POST /projects/{project}/unarchive (administrators) — 409 while the parent is archived */
    unarchive(id: Id): Promise<void>;
    /** DELETE /projects/{project} {confirmIdentifier} (administrators) — everything in it goes; 409 with subprojects */
    remove(id: Id, confirmIdentifier: string): Promise<void>;
  };

  categories: {
    /** GET /projects/{project}/categories */
    list(projectId: Id): Promise<Category[]>;
    /** POST /projects/{project}/categories */
    create(projectId: Id, input: { name: string; defaultAssigneeId: Id | null }): Promise<Category>;
    /** PATCH /categories/{id} */
    update(id: Id, input: { name?: string; defaultAssigneeId?: Id | null }): Promise<Category>;
    /** DELETE /categories/{id} */
    remove(id: Id): Promise<void>;
  };

  versions: {
    /** GET /projects/{project}/versions */
    list(projectId: Id): Promise<Version[]>;
    /** POST /projects/{project}/versions */
    create(projectId: Id, input: VersionInput): Promise<Version>;
    /** PATCH /versions/{id} */
    update(id: Id, input: Partial<VersionInput>): Promise<Version>;
    /** DELETE /versions/{id} */
    remove(id: Id): Promise<void>;
  };

  members: {
    /** GET /projects/{project}/members */
    list(projectId: Id): Promise<Member[]>;
    /** POST /projects/{project}/members */
    add(projectId: Id, principalIds: Id[], roleIds: Id[]): Promise<Member[]>;
    /** PATCH /members/{id} */
    update(memberId: Id, roleIds: Id[]): Promise<Member>;
    /** DELETE /members/{id} */
    remove(memberId: Id): Promise<void>;
    /** GET /admin/roles (project roles only, readable by members managers) */
    roles(): Promise<Role[]>;
  };

  principals: {
    /** GET /principals — with includeGroups (and no project) groups come first, for adding members */
    search(q: string, projectId?: Id, includeGroups?: boolean): Promise<(UserRef & { kind: "user" | "group" })[]>;
  };

  workPackages: {
    /** GET /projects/{project}/work-packages/form */
    form(projectId: Id, typeId?: Id): Promise<WorkPackageForm>;
    /** POST /projects/{project}/work-packages */
    create(projectId: Id, input: WorkPackageInput): Promise<WorkPackage>;
    /** GET /work-packages/{number} */
    get(number: number): Promise<WorkPackage>;
    /** PATCH /work-packages/{number} */
    update(number: number, patch: WorkPackagePatch): Promise<WorkPackage>;
    /** DELETE /work-packages/{number} */
    remove(number: number, children: "delete" | "reparent"): Promise<void>;
    /** GET /work-packages/{number}/allowed-statuses */
    allowedStatuses(number: number): Promise<StatusRef[]>;
    /** GET /work-packages/autocomplete */
    autocomplete(q: string, projectId?: Id): Promise<WorkPackageRef[]>;
    /** GET /work-packages/{number}/activities */
    activities(number: number): Promise<Journal[]>;
    /** POST /work-packages/{number}/comments */
    comment(number: number, bodyHtml: string): Promise<Journal>;
    /** PATCH /comments/{journalId} */
    editComment(journalId: Id, bodyHtml: string): Promise<Journal>;
    /** POST|DELETE /work-packages/{number}/watchers */
    setWatching(number: number, watching: boolean): Promise<void>;
    /** GET /work-packages/{number}/shares (wp.share) */
    shares(number: number): Promise<WorkPackageShare[]>;
    /** POST /work-packages/{number}/shares {userId, role} — shares, or changes the role of an existing share */
    share(number: number, userId: Id, role: ShareRole): Promise<WorkPackageShare>;
    /** PATCH /shares/{id} {role} */
    updateShare(id: Id, role: ShareRole): Promise<WorkPackageShare>;
    /** DELETE /shares/{id} */
    removeShare(id: Id): Promise<void>;
    /** GET /work-packages/{number}/watchers (wp.view_watchers) */
    watchers(number: number): Promise<Watcher[]>;
    /** POST /work-packages/{number}/watchers {userId} (wp.add_watchers; 422 when that person cannot see the item) */
    addWatcher(number: number, userId: Id): Promise<void>;
    /** DELETE /work-packages/{number}/watchers/{userId} (yourself always, others with wp.delete_watchers) */
    removeWatcher(number: number, userId: Id): Promise<void>;
    /** GET /work-packages/{number}/attachments */
    attachments(number: number): Promise<Attachment[]>;
    /** POST /work-packages/{number}/attachments (multipart, field "file") — 413 when too large */
    upload(number: number, file: File): Promise<Attachment>;
    /** DELETE /attachments/{id} */
    removeAttachment(id: Id): Promise<void>;
    /** GET /work-packages/{number}/relations */
    relations(number: number): Promise<RelationGroups>;
    /** POST /work-packages/{number}/relations */
    addRelation(number: number, input: { type: RelationType; toNumber: number; lag?: number }): Promise<void>;
    /** DELETE /relations/{id} */
    removeRelation(relationId: Id): Promise<void>;
    /** POST /work-packages/{number}/schedule-preview */
    schedulePreview(number: number, patch: Omit<WorkPackagePatch, "lockVersion">): Promise<SchedulePreview>;
    /** POST /work-packages/query */
    query(spec: QuerySpec, opts: { projectId?: Id; page?: number; pageSize?: number }): Promise<QueryResult>;
    /** GET /work-packages/filters */
    filterOptions(projectId?: Id): Promise<FilterOptions>;
    /** POST /work-packages/export (wp.export) — the file; 422 above EXPORT_LIMIT items */
    export(input: ExportInput): Promise<{ blob: Blob; fileName: string }>;
    /** POST /work-packages/export/count — the rows the file would have and what it leaves out */
    exportCount(input: Pick<ExportInput, "spec" | "projectId">): Promise<ExportCount>;
  };

  queries: {
    /** GET /queries — without view: built-in + table/Gantt views; with view: only calendars or team planners */
    list(projectId?: Id, view?: "calendar" | "team_planner"): Promise<SavedQuery[]>;
    /** POST /queries */
    create(input: { projectId: Id | null; name: string; isPublic: boolean; spec: QuerySpec; view: ViewKind }): Promise<SavedQuery>;
    /** PATCH /queries/{id} */
    update(id: Id, patch: { name?: string; spec?: QuerySpec; isPublic?: boolean }): Promise<SavedQuery>;
    /** DELETE /queries/{id} */
    remove(id: Id): Promise<void>;
    /** PUT|DELETE /queries/{id}/favorite */
    setFavorite(id: Id, favorite: boolean): Promise<void>;
  };

  boards: {
    /** GET /projects/{project}/boards */
    list(projectId: Id): Promise<BoardSummary[]>;
    /** POST /projects/{project}/boards */
    create(projectId: Id, name: string, kind: BoardKind): Promise<Board>;
    /** GET /boards/{id} */
    get(id: Id): Promise<Board>;
    /** DELETE /boards/{id} */
    remove(id: Id): Promise<void>;
    /** POST /boards/{id}/moves */
    move(id: Id, input: { number: number; toColumnId: Id; beforeNumber: number | null; lockVersion: number }): Promise<Board>;
  };

  notifications: {
    /** GET /notifications */
    list(filter: "unread" | "all"): Promise<NotificationGroup[]>;
    /** GET /notifications/unread-count */
    unreadCount(): Promise<number>;
    /** POST /notifications/read */
    markRead(target: { ids: Id[] } | { workPackageNumber: number } | { all: true }): Promise<void>;
  };

  calendar: {
    /** GET /calendar/non-working-days */
    nonWorkingDays(from: IsoDate, to: IsoDate): Promise<{ workingDays: number[]; days: { date: IsoDate; name: string }[] }>;
  };

  search: {
    /** GET /search/suggest */
    suggest(q: string): Promise<SearchHit[]>;
    /** GET /search?q&scope&page — S22: work packages by number, title, description or comment; projects by name or description */
    results(q: string, scope: SearchScope, page: number): Promise<SearchResults>;
  };

  /** Everything under /admin (architecture.md §4.2 "관리"): administrators, users/groups also global.manage_users. */
  admin: {
    customFields: {
      /** GET /admin/custom-fields */
      list(): Promise<AdminCustomField[]>;
      /** POST /admin/custom-fields */
      create(input: CustomFieldInput): Promise<AdminCustomField>;
      /** PATCH /admin/custom-fields/{id} — the format cannot change */
      update(id: Id, patch: Partial<CustomFieldInput>): Promise<AdminCustomField>;
      /** DELETE /admin/custom-fields/{id} — also clears its values from every work package */
      remove(id: Id): Promise<void>;
      /** PUT /admin/custom-fields/order */
      reorder(ids: Id[]): Promise<void>;
    };
    types: {
      /** GET /admin/types */
      list(): Promise<AdminType[]>;
      /** POST /admin/types */
      create(input: TypeInput): Promise<AdminType>;
      /** PATCH /admin/types/{id} */
      update(id: Id, patch: Partial<TypeInput>): Promise<AdminType>;
      /** DELETE /admin/types/{id} — 409 while work packages use it */
      remove(id: Id): Promise<void>;
      /** PUT /admin/types/order (added to the contract, like statuses and priorities) */
      reorder(ids: Id[]): Promise<void>;
    };
    statuses: {
      /** GET /admin/statuses */
      list(): Promise<AdminStatus[]>;
      /** POST /admin/statuses */
      create(input: StatusInput): Promise<AdminStatus>;
      /** PATCH /admin/statuses/{id} */
      update(id: Id, patch: Partial<StatusInput>): Promise<AdminStatus>;
      /** DELETE /admin/statuses/{id} */
      remove(id: Id): Promise<void>;
      /** PUT /admin/statuses/order */
      reorder(ids: Id[]): Promise<void>;
    };
    priorities: {
      /** GET /admin/priorities */
      list(): Promise<AdminPriority[]>;
      /** POST /admin/priorities */
      create(input: PriorityInput): Promise<AdminPriority>;
      /** PATCH /admin/priorities/{id} */
      update(id: Id, patch: Partial<PriorityInput>): Promise<AdminPriority>;
      /** DELETE /admin/priorities/{id} */
      remove(id: Id): Promise<void>;
      /** PUT /admin/priorities/order */
      reorder(ids: Id[]): Promise<void>;
    };
    workflows: {
      /** GET /admin/workflows?typeId&roleId */
      get(typeId: Id, roleId: Id): Promise<Transition[]>;
      /** PUT /admin/workflows — replaces the whole matrix for (type, role) */
      save(typeId: Id, roleId: Id, transitions: Transition[]): Promise<void>;
      /** POST /admin/workflows/copy */
      copy(from: { typeId: Id; roleId: Id }, to: { typeIds: Id[]; roleIds: Id[] }): Promise<void>;
    };
    roles: {
      /** GET /admin/roles */
      list(): Promise<AdminRole[]>;
      /** POST /admin/roles */
      create(input: RoleInput): Promise<AdminRole>;
      /** PATCH /admin/roles/{id} */
      update(id: Id, patch: Partial<Omit<RoleInput, "scope">>): Promise<AdminRole>;
      /** DELETE /admin/roles/{id} — 409 while members use it */
      remove(id: Id): Promise<void>;
      /** GET /admin/permissions */
      permissions(): Promise<{ key: string; scope: "project" | "global"; group: string }[]>;
    };
    users: {
      /** GET /admin/users */
      list(params?: { q?: string; status?: AdminUser["status"] }): Promise<AdminUser[]>;
      /** POST /admin/users — creates an invited user and sends the invitation */
      create(input: UserInput): Promise<AdminUser>;
      /** PATCH /admin/users/{id} (lock/unlock, admin flag, name) */
      update(id: Id, patch: { name?: string; status?: "active" | "locked"; isAdmin?: boolean }): Promise<AdminUser>;
      /** DELETE /admin/users/{id} — anonymises the account */
      remove(id: Id): Promise<void>;
      /** POST /admin/users/{id}/resend-invitation */
      resendInvitation(id: Id): Promise<void>;
    };
    groups: {
      /** GET /admin/groups */
      list(): Promise<AdminGroup[]>;
      /** POST /admin/groups */
      create(name: string): Promise<AdminGroup>;
      /** PATCH /admin/groups/{id} */
      rename(id: Id, name: string): Promise<AdminGroup>;
      /** DELETE /admin/groups/{id} */
      remove(id: Id): Promise<void>;
      /** PUT /admin/groups/{id}/members */
      setMembers(id: Id, userIds: Id[]): Promise<AdminGroup>;
    };
    progress: {
      /** GET /admin/settings/progress */
      get(): Promise<ProgressSettings>;
      /** PUT /admin/settings/progress — recalculates every work package; returns how many changed */
      save(input: ProgressSettings): Promise<ProgressSettings & { recalculated: number }>;
    };
    calendar: {
      /** GET /admin/settings/calendar */
      get(): Promise<CalendarSettings>;
      /** PUT /admin/settings/calendar — schedules reschedule-all when working days change */
      save(input: CalendarSettings): Promise<CalendarSettings>;
      /** GET /admin/non-working-days?year */
      nonWorkingDays(year: number): Promise<{ id: Id; date: IsoDate; name: string }[]>;
      /** POST /admin/non-working-days */
      addNonWorkingDay(date: IsoDate, name: string): Promise<void>;
      /** DELETE /admin/non-working-days/{id} */
      removeNonWorkingDay(id: Id): Promise<void>;
    };
  };
}
