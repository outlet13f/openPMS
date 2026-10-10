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
  /** S20 이메일: mentions mailed right away (else they wait for the digest) */
  immediateOnMention: boolean;
  /** S20 이메일: the daily digest of unread notifications */
  digestEnabled: boolean;
  /** "HH:MM" in my time zone, 1–4 of them */
  digestTimes: string[];
  /** no date alerts up to and including this day */
  remindersPausedUntil: IsoDate | null;
  /** S21 내 페이지: the widgets shown, in order */
  myPageWidgets: MyPageWidget[];
}

export const MY_PAGE_WIDGETS = ["assigned", "created", "watched", "responsible", "dueSoon", "recent"] as const;
export type MyPageWidget = (typeof MY_PAGE_WIDGETS)[number];

/* ---------- notification settings (S20) ---------- */

/** Days before the date (0 = on the day); null = off. */
export type AlertDays = 0 | 1 | 3 | 7;

/**
 * What I am notified about. Mentions and watched items always notify, so they have no switch.
 * The non-participating events (created … datesChanged) cover every item I can see in the project.
 */
export interface NotificationSetting {
  assignee: boolean;
  responsible: boolean;
  shared: boolean;
  workPackageCreated: boolean;
  commented: boolean;
  statusChanged: boolean;
  priorityChanged: boolean;
  datesChanged: boolean;
  startDateAlertDays: AlertDays | null;
  dueDateAlertDays: AlertDays | null;
  overdueAlert: boolean;
}

/** A project's own setting, used instead of my default for that project. */
export interface ProjectNotificationSetting extends NotificationSetting {
  project: ProjectRef;
}

export interface NotificationSettings {
  default: NotificationSetting;
  projects: ProjectNotificationSetting[];
}

export type Theme = "system" | "light" | "dark";

export interface Me extends User {
  globalPermissions: string[];
  preferences: Preferences;
  /** S37: system follows the operating system */
  theme: Theme;
  /** false for someone who has only signed in through company SSO */
  hasPassword: boolean;
  /** names of the SSO providers linked to me */
  signInWith: string[];
}

/** S37 프로필: what I may change about myself (email is changed by an administrator). */
export interface ProfileInput {
  name?: string;
  timeZone?: string;
  theme?: Theme;
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
  /** templates: projects marked as templates (S04) */
  filter?: "active" | "mine" | "favorite" | "archived" | "templates";
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
  /** S04: start from this template */
  templateId?: Id | null;
  /** with a template: what to take over besides modules, types and project attributes */
  copy?: TemplatePart[];
}

export const TEMPLATE_PARTS = ["members", "versions", "categories", "workPackages", "boards", "queries"] as const;
export type TemplatePart = (typeof TEMPLATE_PARTS)[number];

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
  attachmentCount: number;
  /**
   * Custom fields active for this item (its project and type), in field order. Empty fields are present with
   * value null. `text` is the display form (option and user names, formatted dates).
   */
  customValues: Record<Id, { value: CustomValue; text: string }>;
  /** P4/P5: this item and its descendants (statuses excluded from totals left out); null without children */
  totals: { workHours: number | null; remainingHours: number | null; doneRatio: number | null } | null;
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
      /** see the watcher list (wp.view_watchers) */
      viewWatchers: boolean;
      /** add other people as watchers (wp.add_watchers) */
      addWatchers: boolean;
      /** upload and delete files (wp.attach) */
      attach: boolean;
      /** share this item with people outside the project (wp.share) */
      share: boolean;
    };
    /** set when the item reached me through a share rather than (only) project membership */
    sharedWithMe: ShareRole | null;
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
  /** Only the fields to set; null clears one. */
  customValues?: Record<Id, CustomValue>;
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
  /** Custom fields active for this project and type */
  customFields: CustomField[];
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

/** A file on a work package (S08 파일 탭). `url` downloads it (images open inline). */
export interface Attachment {
  id: Id;
  fileName: string;
  contentType: string;
  byteSize: number;
  author: UserRef;
  createdAt: IsoDateTime;
  url: string;
  canDelete: boolean;
}

/**
 * Work package sharing (S07/S08 공유): one item for one person, also outside the project.
 * view = see it; comment = + comment; edit = + edit fields and files.
 */
export type ShareRole = "view" | "comment" | "edit";

export interface WorkPackageShare {
  id: Id;
  user: UserRef;
  role: ShareRole;
  sharedBy: UserRef | null;
  createdAt: IsoDateTime;
}

/** Someone watching a work package (S08 주시자 탭). Anyone can remove themselves; others need wp.delete_watchers. */
export interface Watcher {
  user: UserRef;
  canRemove: boolean;
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
  | "next_days"
  /** dates: within the last N days (values[0], default 7) */
  | "last_days"
  /** dates: exactly values[0] */
  | "on"
  /** dates: values[0]..values[1], either end may be empty */
  | "between"
  /** numbers: at least / at most values[0] */
  | "gte"
  | "lte"
  /** dates only: start..due (a single date counts as one day) overlaps values[0]..values[1] */
  | "overlaps";

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
  | "isMilestone"
  | "number"
  | "description"
  /** any comment on the work package */
  | "comment"
  /** the assignee is in one of these groups */
  | "assigneeGroup"
  /** the assignee has one of these roles in the work package's project */
  | "assigneeRole"
  | "watcher"
  | "category"
  | "project"
  /** values[0] = parent number */
  | "parent"
  | "createdAt"
  | "duration"
  | "doneRatio"
  | "work"
  | "remainingWork"
  | "scheduleManually"
  /** an open work package blocks this one */
  | "blocked"
  /** The work package's whole date range; used by the calendar and team planner for the visible window. */
  | "dates"
  /** a custom field, by id */
  | `cf:${string}`;

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
  | "project"
  | "work"
  | "remainingWork"
  | `cf:${string}`;

/** Columns a list can be grouped by (and list, user and yes/no custom fields). */
export const GROUPABLE_COLUMNS: ColumnKey[] = ["type", "status", "priority", "assignee", "responsible", "author", "version", "project"];

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

/** S12 내보내기: every item matching the spec (not just the page), in its order; at most EXPORT_LIMIT. */
export type ExportFormat = "csv" | "xlsx";
export const EXPORT_LIMIT = 5000;

export interface ExportInput {
  spec: QuerySpec;
  projectId?: Id;
  format: ExportFormat;
  columns: ColumnKey[];
  /** an extra last column with the description as plain text */
  includeDescription: boolean;
  /** names the file: "<title>-<yyyymmdd>.<format>" */
  title: string;
}

/** S12: what an export would contain, asked before downloading. */
/** S08: copy a work package (the create rules apply in the target). */
export interface CopyInput {
  /** default: the same project */
  targetProjectId?: Id;
  /** default: the same subject */
  subject?: string;
  /** copy the subtree too, under the new item */
  includeChildren: boolean;
}

/** S08: move to another project; the number stays, the subtree comes along. */
export interface MoveInput {
  targetProjectId: Id;
  /** when the current type is not used there */
  typeId?: Id;
  lockVersion: number;
}

/** S06: one change for many items, all or nothing (422 lists "#n" with each reason). */
export interface BulkInput {
  numbers: number[];
  action: "update" | "delete" | "move";
  changes?: Partial<Pick<WorkPackagePatch, "statusId" | "priorityId" | "assigneeId" | "responsibleId" | "versionId" | "categoryId" | "startDate" | "dueDate">>;
  targetProjectId?: Id;
}

export interface ExportCount {
  /** items matching the list */
  total: number;
  /** rows the file would have (0 when total > limit) */
  count: number;
  /** matching items left out: in projects where I may not export, or seen only through a share */
  excluded: number;
  limit: number;
}

export interface QueryRow {
  wp: WorkPackage;
  depth: number;
  hasChildren: boolean;
  /** Ancestor shown for context only: it does not match the filters. */
  context: boolean;
  /** the row's QueryGroup.key when the list is grouped */
  groupKey?: string | null;
}

/** A group of a grouped list, over every matching item (not only this page). key "" = no value. */
export interface QueryGroup {
  key: string;
  label: string;
  color: string | null;
  count: number;
  /** per summable visible column, when sums are on */
  sums: Record<string, number | null> | null;
}

export interface QueryResult {
  rows: QueryRow[];
  total: number;
  page: number;
  pageSize: number;
  /** Only with spec.timeline.visible: predecessor → successor links among the returned rows. */
  relations?: { fromNumber: number; toNumber: number; lag: number }[];
  /** only when grouped, in display order */
  groups?: QueryGroup[] | null;
  /** only with spec.sums: totals of the summable visible columns over every matching item */
  sums?: Record<string, number | null> | null;
}

/** queries.view: list and Gantt share the work package views; calendars and team planners are their own lists. */
export type ViewKind = "table" | "gantt" | "calendar" | "team_planner";

export interface SavedQuery {
  id: Id;
  name: string;
  /** "default" views are built in and cannot be edited. */
  kind: "default" | "public" | "private";
  view: ViewKind;
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
  groups: { id: Id; name: string }[];
  roles: { id: Id; name: string }[];
  /** the project and its subprojects (or every visible project for the global list) */
  projects: { id: Id; name: string }[];
  /** custom fields active somewhere in that scope: column and filter choices */
  customFields: CustomField[];
  /** S28: each type's form, resolved (the default where none is set) */
  typeForms: Record<Id, TypeForm>;
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
  /** the saved filters (list format), or the ones asked for when looking without saving */
  filters: QueryFilter[];
}

/** POST /boards/{id}/cards/new: the board as it is now and the new work package. */
export interface BoardCardCreated {
  board: Board;
  workPackage: WorkPackage;
}

/* ---------- notifications ---------- */

export type NotificationReason =
  | "mentioned"
  | "assigned"
  | "responsible"
  | "watched"
  | "shared"
  | "created"
  | "commented"
  | "status_changed"
  | "priority_changed"
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

export type SearchScope = "all" | "work_packages" | "projects";

/** S22: one result. `snippet` is plain text around the match (description or comment), or null. */
export type SearchResult =
  | {
      kind: "work_package";
      number: number;
      subject: string;
      type: TypeRef;
      status: StatusRef;
      project: ProjectRef;
      matchedIn: "number" | "subject" | "description" | "comment";
      snippet: string | null;
      updatedAt: IsoDateTime;
    }
  | { kind: "project"; project: ProjectRef; snippet: string | null };

export interface SearchResults {
  q: string;
  scope: SearchScope;
  counts: { workPackages: number; projects: number };
  items: SearchResult[];
  total: number;
  page: number;
  pageSize: number;
}

/* ---------- custom fields (S34 관리, S25 프로젝트 설정) ---------- */

export type CustomFieldFormat = "string" | "text" | "integer" | "float" | "list" | "date" | "bool" | "user" | "link";

/** As stored: list → option id(s), user → user id(s), date → ISO date, integer/float → number, bool, text. */
export type CustomValue = string | number | boolean | string[] | null;

export interface CustomFieldOption {
  id: Id;
  value: string;
}

/** A work package custom field. Active in a project when isForAll or turned on there, and only for its types. */
export interface CustomField {
  id: Id;
  name: string;
  format: CustomFieldFormat;
  isRequired: boolean;
  isForAll: boolean;
  isFilterable: boolean;
  /** list and user only */
  isMultiValue: boolean;
  defaultValue: CustomValue;
  /** string and text only */
  minLength: number | null;
  maxLength: number | null;
  regexp: string | null;
  position: number;
  /** list only, in order */
  options: CustomFieldOption[];
  typeIds: Id[];
}

export interface AdminCustomField extends CustomField {
  /** projects that turned it on (not counting isForAll) */
  projectCount: number;
}

/** Options without an id are new; existing ones keep theirs; missing ones are removed (and cleared from items). */
export interface CustomFieldInput {
  name: string;
  format: CustomFieldFormat;
  isRequired: boolean;
  isForAll: boolean;
  isFilterable: boolean;
  isMultiValue: boolean;
  defaultValue: CustomValue;
  minLength: number | null;
  maxLength: number | null;
  regexp: string | null;
  options: { id?: Id; value: string }[];
  typeIds: Id[];
}

export interface ProjectCustomFields {
  /** every work package custom field, in order */
  fields: CustomField[];
  /** fields turned on for this project (isForAll ones are always on) */
  enabledIds: Id[];
}

/* ---------- administration (S27–S36) and project settings (S24–S26) ---------- */

export interface AdminType extends WpType {
  /** null = the default layout */
  form: TypeForm | null;
  descriptionTemplateHtml: string;
  isInRoadmap: boolean;
  workPackageCount: number;
  projectCount: number;
}
export type PasswordClass = "lower" | "upper" | "digit" | "special";

/** What a new password needs (public: the signed-out pages read it too). */
export interface PasswordRules {
  minLength: number;
  /** kinds that must each appear */
  requiredClasses: PasswordClass[];
  /** how many of the four kinds must appear, 0 = no rule */
  minClasses: number;
  /** "비밀번호를 잊으셨나요?" works */
  resetEnabled: boolean;
}

/** 관리 · 로그인과 비밀번호 */
export interface AuthSettings extends PasswordRules {
  /** failed sign-ins before a lockout, 0 = never */
  lockoutAttempts: number;
  lockoutMinutes: number;
  invitationTtlHours: number;
  resetTtlMinutes: number;
}

/** S01: a "sign in with" button for a company OpenID provider. */
export interface SsoProvider {
  slug: string;
  displayName: string;
}

/** 관리 · 로그인과 비밀번호 · SSO: a provider. The client secret is write-only (secretSet tells whether one is stored). */
export interface AdminOidcProvider extends SsoProvider {
  id: Id;
  issuer: string;
  clientId: string;
  secretSet: boolean;
  scopes: string;
  claimEmail: string;
  claimName: string;
  /** people without an account get one on their first sign-in */
  autoCreate: boolean;
  enabled: boolean;
  /** what the provider must allow as the redirect URI */
  redirectUri: string;
  linkedUsers: number;
}

export interface OidcProviderInput {
  slug?: string;
  displayName: string;
  issuer: string;
  clientId: string;
  /** blank on edit: keep the stored secret */
  clientSecret: string;
  scopes: string;
  claimEmail: string;
  claimName: string;
  autoCreate: boolean;
  enabled: boolean;
}

/** S01: what an invitation link shows before it is accepted. */
export interface Invitation {
  email: string;
  name: string;
  invitedBy: string | null;
}

export interface TypeInput {
  name: string;
  color: string;
  isMilestone: boolean;
  isDefault: boolean;
  isInRoadmap: boolean;
  /** S28: null = back to the default layout */
  form?: TypeForm | null;
  /** S28: the description new items of this type start with (sanitised HTML) */
  descriptionTemplateHtml?: string;
}

/** S28 양식 구성: one group of the detail's attributes (built-in keys or "cf:<id>"), in order. */
export interface AttributeGroup {
  name: string;
  attributes: string[];
}

/** A type's form. Custom fields in no group and not hidden show in an automatic last group ("추가 정보"). */
export interface TypeForm {
  groups: AttributeGroup[];
  hidden: string[];
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
  /** Locked for a while after too many failed sign-ins; the status stays active. */
  lockedUntil: IsoDateTime | null;
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

/**
 * S32 진행률 추적 (architecture.md §3.4). work: % is typed, or follows from work and remaining work;
 * status: % is the status's default and cannot be typed. closedSets100: a closed status means 100 % and 0 h left.
 * totals: how a parent's total % is made — weighted by work, or the plain average of the items' %.
 */
export interface ProgressSettings {
  mode: "work" | "status";
  closedSets100: boolean;
  totals: "weighted" | "simple_average";
}

export interface CalendarSettings {
  workingDays: number[];
  hoursPerDay: number;
}

/** 관리 · 달력과 날짜 · 날짜 형식. "default" follows the language (Korean: 2026.10.21, 24-hour clock, weeks from Sunday). */
export type DateFormat = "default" | "YYYY.MM.DD" | "YYYY-MM-DD" | "YYYY/MM/DD" | "YYYY년 M월 D일" | "DD.MM.YYYY" | "MM/DD/YYYY";
export const DATE_FORMATS: DateFormat[] = ["default", "YYYY.MM.DD", "YYYY-MM-DD", "YYYY/MM/DD", "YYYY년 M월 D일", "DD.MM.YYYY", "MM/DD/YYYY"];
export type TimeFormat = "default" | "24h" | "12h";
/** ISO weekday the week starts on: Monday "1", Saturday "6", Sunday "7" */
export type WeekStart = "default" | "1" | "6" | "7";
export interface DisplaySettings {
  dateFormat: DateFormat;
  timeFormat: TimeFormat;
  weekStart: WeekStart;
}
export const DEFAULT_DISPLAY: DisplaySettings = { dateFormat: "default", timeFormat: "default", weekStart: "7" };

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
