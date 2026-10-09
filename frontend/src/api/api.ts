import type {
  Board,
  BoardKind,
  BoardSummary,
  FilterOptions,
  Id,
  Journal,
  Me,
  Member,
  NotificationGroup,
  Page,
  Project,
  ProjectInput,
  ProjectListParams,
  ProjectOverview,
  QueryResult,
  QuerySpec,
  RelationGroups,
  RelationType,
  Role,
  SavedQuery,
  SchedulePreview,
  SearchHit,
  StatusRef,
  UserRef,
  WorkPackage,
  WorkPackageForm,
  WorkPackageInput,
  WorkPackagePatch,
  WorkPackageRef,
  IsoDate,
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
    /** GET /principals */
    search(q: string, projectId?: Id): Promise<UserRef[]>;
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
    /** GET /queries */
    list(projectId?: Id): Promise<SavedQuery[]>;
    /** POST /queries */
    create(input: { projectId: Id | null; name: string; isPublic: boolean; spec: QuerySpec; view: "table" | "gantt" }): Promise<SavedQuery>;
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
}
