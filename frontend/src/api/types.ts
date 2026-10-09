// DTOs for the openPMS REST API (replica/architecture.md §4). The fake data layer and the HTTP client
// both return exactly these shapes, so screens never know which one is running.

export type Id = string;
export type IsoDate = string; // "2026-10-09" (calendar date, no time zone)
export type IsoDateTime = string; // "2026-10-09T05:36:00Z"

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

/* ---------- people ---------- */

export interface UserRef {
  id: Id;
  name: string;
}

export interface User extends UserRef {
  email: string;
  status: "invited" | "active" | "locked" | "deleted";
  isAdmin: boolean;
  locale: "ko" | "en";
  timeZone: string;
}

export interface Preferences {
  commentsNewestFirst: boolean;
  warnOnUnsaved: boolean;
  keyboardShortcuts: boolean;
}

export interface Me extends User {
  globalPermissions: string[];
  preferences: Preferences;
}

export interface Role {
  id: Id;
  name: string;
  scope: "project" | "global";
  builtin: "non_member" | null;
  permissions: string[];
}

/* ---------- projects ---------- */

export type ProjectStatus = "on_track" | "at_risk" | "off_track";
export type ModuleKey = "work_packages" | "gantt" | "boards" | "calendar" | "team_planner";

export interface ProjectRef {
  id: Id;
  identifier: string;
  name: string;
}

export interface Project extends ProjectRef {
  descriptionHtml: string;
  status: ProjectStatus | null;
  statusExplanation: string;
  isPublic: boolean;
  isTemplate: boolean;
  archivedAt: IsoDateTime | null;
  parent: ProjectRef | null;
  enabledModules: ModuleKey[];
  favorite: boolean;
  /** Permission keys the current user has in this project (architecture.md "권한 키"). */
  myPermissions: string[];
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  latestActivityAt: IsoDateTime;
}

export interface ProjectListParams {
  filter?: "active" | "mine" | "favorite" | "archived";
  status?: ProjectStatus;
  q?: string;
  page?: number;
  pageSize?: number;
}

export interface ProjectInput {
  name: string;
  identifier: string;
  descriptionHtml?: string;
  parentId?: Id | null;
  isPublic?: boolean;
  templateId?: Id | null;
}

export interface ProjectOverview {
  project: Project;
  members: { user: UserRef; roles: string[] }[];
  countsByStatus: { status: StatusRef; count: number }[];
  openCount: number;
  overdueCount: number;
}

export interface Member {
  id: Id;
  principal: UserRef & { kind: "user" | "group"; email?: string };
  roles: { id: Id; name: string }[];
  createdAt: IsoDateTime;
}

/* ---------- work package configuration ---------- */

export interface TypeRef {
  id: Id;
  name: string;
  color: string;
  isMilestone: boolean;
}

export interface StatusRef {
  id: Id;
  name: string;
  color: string;
  isClosed: boolean;
  isReadonly: boolean;
}

export interface PriorityRef {
  id: Id;
  name: string;
  color: string;
}

export interface Status extends StatusRef {
  position: number;
  isDefault: boolean;
  defaultDoneRatio: number;
}

export interface WpType extends TypeRef {
  position: number;
  isDefault: boolean;
}

export interface Priority extends PriorityRef {
  position: number;
  isDefault: boolean;
}

export interface VersionRef {
  id: Id;
  name: string;
}

/* ---------- work packages ---------- */

export type WpField =
  | "subject"
  | "description"
  | "type"
  | "status"
  | "priority"
  | "assignee"
  | "responsible"
  | "version"
  | "category"
  | "parent"
  | "dates"
  | "work"
  | "remainingWork"
  | "doneRatio";

export interface WorkPackageRef {
  id: Id;
  number: number;
  subject: string;
  type: TypeRef;
  status: StatusRef;
  project: ProjectRef;
}

export interface WorkPackage {
  id: Id;
  number: number;
  project: ProjectRef;
  type: TypeRef;
  status: StatusRef;
  priority: PriorityRef;
  subject: string;
  descriptionHtml: string;
  author: UserRef;
  assignee: UserRef | null;
  responsible: UserRef | null;
  version: VersionRef | null;
  category: { id: Id; name: string } | null;
  parent: WorkPackageRef | null;
  childCount: number;
  startDate: IsoDate | null;
  dueDate: IsoDate | null;
  duration: number | null;
  ignoreNonWorkingDays: boolean;
  scheduleManually: boolean;
  workHours: number | null;
  remainingHours: number | null;
  doneRatio: number | null;
  watching: boolean;
  watcherCount: number;
  relationCount: number;
  lockVersion: number;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  _meta: {
    allowedActions: {
      edit: boolean;
      delete: boolean;
      move: boolean;
      comment: boolean;
      manageRelations: boolean;
      manageSubtasks: boolean;
      watch: boolean;
    };
    editableFields: WpField[];
    /** Fields whose value is computed (e.g. parent dates in automatic mode). */
    derived: WpField[];
    overdue: boolean;
  };
}

export interface WorkPackageInput {
  typeId: Id;
  subject: string;
  descriptionHtml?: string;
  statusId?: Id;
  priorityId?: Id;
  assigneeId?: Id | null;
  responsibleId?: Id | null;
  versionId?: Id | null;
  categoryId?: Id | null;
  parentNumber?: number | null;
  startDate?: IsoDate | null;
  dueDate?: IsoDate | null;
}

export interface WorkPackagePatch extends Partial<Omit<WorkPackageInput, "typeId">> {
  lockVersion: number;
  typeId?: Id;
  duration?: number | null;
  scheduleManually?: boolean;
  ignoreNonWorkingDays?: boolean;
  workHours?: number | null;
  remainingHours?: number | null;
  doneRatio?: number | null;
  /** The fields the user actually touched, so the scheduler knows which two of start/due/duration to keep. */
  touched?: ("startDate" | "dueDate" | "duration")[];
}

export interface WorkPackageForm {
  type: TypeRef;
  types: TypeRef[];
  statuses: StatusRef[];
  priorities: PriorityRef[];
  assignees: UserRef[];
  versions: VersionRef[];
  categories: { id: Id; name: string; defaultAssigneeId: Id | null }[];
  defaults: { statusId: Id; priorityId: Id; descriptionHtml: string };
  required: WpField[];
}

export interface JournalDetail {
  field: string;
  label: string;
  from: string | null;
  to: string | null;
}

export interface Journal {
  id: Id;
  version: number;
  user: UserRef;
  notesHtml: string;
  isInternal: boolean;
  details: JournalDetail[];
  cause: { kind: "predecessor_moved" | "child_moved" | "calendar_changed"; workPackageNumber?: number } | null;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  canEdit: boolean;
}

export type RelationType =
  | "relates"
  | "precedes"
  | "follows"
  | "duplicates"
  | "duplicated"
  | "blocks"
  | "blocked"
  | "includes"
  | "partof"
  | "requires"
  | "required";

export interface Relation {
  id: Id;
  /** Seen from the work package that was asked for (follows is the reverse of precedes, …). */
  type: RelationType;
  other: WorkPackageRef & { startDate: IsoDate | null; dueDate: IsoDate | null };
  lag: number;
  description: string;
}

export interface RelationGroups {
  relations: Relation[];
  parent: (WorkPackageRef & { startDate: IsoDate | null; dueDate: IsoDate | null }) | null;
  children: (WorkPackageRef & { startDate: IsoDate | null; dueDate: IsoDate | null })[];
}

export interface SchedulePreview {
  startDate: IsoDate | null;
  dueDate: IsoDate | null;
  duration: number | null;
  affected: { number: number; subject: string; startDate: IsoDate | null; dueDate: IsoDate | null }[];
}

/* ---------- queries (list engine) ---------- */

export type FilterOp =
  | "eq"
  | "neq"
  | "any"
  | "none"
  | "open"
  | "closed"
  | "contains"
  | "not_contains"
  | "overdue"
  | "today"
  | "this_week"
  | "next_days";

export type FilterField =
  | "status"
  | "type"
  | "priority"
  | "assignee"
  | "responsible"
  | "author"
  | "version"
  | "subject"
  | "dueDate"
  | "startDate"
  | "updatedAt"
  | "isMilestone";

export interface QueryFilter {
  field: FilterField;
  op: FilterOp;
  values?: string[];
}

export type ColumnKey =
  | "number"
  | "subject"
  | "type"
  | "status"
  | "priority"
  | "assignee"
  | "responsible"
  | "author"
  | "version"
  | "startDate"
  | "dueDate"
  | "duration"
  | "doneRatio"
  | "updatedAt"
  | "project";

export interface QuerySpec {
  v: 1;
  filters: QueryFilter[];
  columns: ColumnKey[];
  sort: { field: ColumnKey; dir: "asc" | "desc" }[];
  groupBy: ColumnKey | null;
  hierarchy: boolean;
  sums: boolean;
  includeSubprojects: boolean;
  /** Free text over subject and #number (the "텍스트로 필터링" box). */
  text?: string;
  /** Gantt views: when visible, the result also carries the precedes relations between its rows. */
  timeline?: { visible: boolean; zoom: "day" | "week" | "month" | "auto" };
}

export interface QueryRow {
  wp: WorkPackage;
  depth: number;
  hasChildren: boolean;
  /** Ancestor shown for context only: it does not match the filters. */
  context: boolean;
}

export interface QueryResult {
  rows: QueryRow[];
  total: number;
  page: number;
  pageSize: number;
  /** Only with spec.timeline.visible: predecessor → successor links among the returned rows. */
  relations?: { fromNumber: number; toNumber: number; lag: number }[];
}

export interface SavedQuery {
  id: Id;
  name: string;
  /** "default" views are built in and cannot be edited. */
  kind: "default" | "public" | "private";
  view: "table" | "gantt";
  spec: QuerySpec;
  favorite: boolean;
  projectId: Id | null;
  canEdit: boolean;
}

export interface FilterOptions {
  statuses: StatusRef[];
  types: TypeRef[];
  priorities: PriorityRef[];
  users: UserRef[];
  versions: VersionRef[];
  categories: { id: Id; name: string }[];
}

/* ---------- boards ---------- */

export type BoardKind = "basic" | "status" | "assignee" | "version" | "subproject" | "parent_child";

export interface BoardSummary {
  id: Id;
  name: string;
  kind: BoardKind;
  isPublic: boolean;
  createdAt: IsoDateTime;
}

export interface BoardColumn {
  id: Id;
  position: number;
  title: string;
  color: string | null;
  /** Status, user, version, project or parent id the column stands for (null for basic boards). */
  valueId: Id | null;
  cards: WorkPackage[];
}

export interface Board extends BoardSummary {
  projectId: Id;
  columns: BoardColumn[];
  canManage: boolean;
}

/* ---------- notifications ---------- */

export type NotificationReason =
  | "mentioned"
  | "assigned"
  | "responsible"
  | "watched"
  | "created"
  | "commented"
  | "status_changed"
  | "dates_changed"
  | "date_alert_start"
  | "date_alert_due"
  | "date_alert_overdue";

export interface NotificationItem {
  id: Id;
  reason: NotificationReason;
  read: boolean;
  actor: UserRef | null;
  summary: string;
  createdAt: IsoDateTime;
}

export interface NotificationGroup {
  workPackage: WorkPackageRef;
  items: NotificationItem[];
  unread: number;
  latestAt: IsoDateTime;
}

/* ---------- search ---------- */

export interface SearchHit {
  kind: "work_package" | "project";
  title: string;
  subtitle: string;
  href: string;
}

/* ---------- administration (S27–S36) and project settings (S24–S26) ---------- */

export interface AdminType extends WpType {
  isInRoadmap: boolean;
  workPackageCount: number;
  projectCount: number;
}
export interface TypeInput {
  name: string;
  color: string;
  isMilestone: boolean;
  isDefault: boolean;
  isInRoadmap: boolean;
}

export interface AdminStatus extends Status {
  excludedFromTotals: boolean;
  workPackageCount: number;
}
export interface StatusInput {
  name: string;
  color: string;
  isDefault: boolean;
  isClosed: boolean;
  isReadonly: boolean;
  defaultDoneRatio: number;
  excludedFromTotals: boolean;
}

export interface AdminPriority extends Priority {
  isActive: boolean;
  workPackageCount: number;
}
export interface PriorityInput {
  name: string;
  color: string;
  isDefault: boolean;
  isActive: boolean;
}

/** One allowed transition: [from status id, to status id]. */
export type Transition = [Id, Id];

export interface AdminRole extends Role {
  memberCount: number;
}
export interface RoleInput {
  name: string;
  scope: "project" | "global";
  permissions: string[];
}

export interface AdminUser extends User {
  lastLoginAt: IsoDateTime | null;
  projectCount: number;
  createdAt: IsoDateTime;
}
export interface UserInput {
  name: string;
  email: string;
  isAdmin: boolean;
}

export interface AdminGroup {
  id: Id;
  name: string;
  members: UserRef[];
}

export interface CalendarSettings {
  workingDays: number[];
  hoursPerDay: number;
}

export interface Category {
  id: Id;
  name: string;
  defaultAssignee: UserRef | null;
  workPackageCount: number;
}

export interface Version {
  id: Id;
  name: string;
  description: string;
  status: "open" | "locked" | "closed";
  startDate: IsoDate | null;
  endDate: IsoDate | null;
  workPackageCount: number;
  closedCount: number;
}
export interface VersionInput {
  name: string;
  description?: string;
  status?: Version["status"];
  startDate?: IsoDate | null;
  endDate?: IsoDate | null;
}
