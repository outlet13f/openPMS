import type {
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
    /** POST /projects/{project}/archive */
    archive(id: Id): Promise<void>;
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
  };

  /** Everything under /admin (architecture.md §4.2 "관리"): administrators, users/groups also global.manage_users. */
  admin: {
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
