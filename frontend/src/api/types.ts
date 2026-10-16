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

export type Theme = "system" | "light" | "dark" | "high_contrast";

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
export type ModuleKey = "work_packages" | "gantt" | "boards" | "calendar" | "team_planner" | "time_tracking" | "cicd";

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
  /** 내부 코멘트 on: roles with wp.add_internal_comments may write comments only wp.view_internal_comments roles read */
  internalComments: boolean;
  /** S05 개요: the widgets shown, in order */
  overviewWidgets: OverviewWidget[];
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

/** 외부 연동 API 토큰 (계정 설정): the secret is only shown when made */
export interface ApiToken {
  id: Id;
  name: string;
  scope: "read" | "write";
  createdAt: IsoDateTime;
  lastUsedAt: IsoDateTime | null;
  expiresAt: IsoDateTime | null;
}
export interface ApiTokenInput {
  name: string;
  scope: "read" | "write";
  /** 30, 90 or 365; null: no expiry */
  expiresInDays: 30 | 90 | 365 | null;
}
export interface AdminApiToken extends ApiToken {
  userId: Id;
  userName: string;
}

/** 웹훅 (관리 · API와 웹훅) */
export const WEBHOOK_EVENTS = ["work_package.created", "work_package.updated", "work_package.deleted", "work_package.commented"] as const;
export type WebhookEvent = (typeof WEBHOOK_EVENTS)[number];
/** 관리 · 시스템 정보 */
export interface BackupFile {
  name: string;
  kind: "before-upgrade" | "manual";
  bytes: number;
  createdAt: IsoDateTime;
}
export interface SystemInfo {
  app: { version: string; builtAt: IsoDateTime | null; startedAt: IsoDateTime; java: string; jobsEnabled: boolean };
  database: { version: string; sizeBytes: number };
  migrations: { current: string | null; applied: number; pending: number; recent: { version: string; description: string; installedAt: IsoDateTime | null; success: boolean }[] };
  /** pgDump: its version line, null when it cannot be run (development) */
  backups: { beforeUpgrade: boolean; dir: string; keep: number; pgDump: string | null; running: boolean; files: BackupFile[] };
  counts: { activeUsers: number; projects: number; workPackages: number; attachmentBytes: number };
}

/** 시간 기록 (내 공수, 작업의 시간 탭, 공수 보고서) */
export interface TimeActivity {
  id: Id;
  name: string;
  position: number;
  isDefault: boolean;
  active: boolean;
}
export interface TimeEntry {
  id: Id;
  user: UserRef;
  project: ProjectRef;
  workPackage: WorkPackageRef;
  activity: { id: Id; name: string } | null;
  spentOn: IsoDate;
  hours: number;
  comment: string;
  canEdit: boolean;
}
export interface TimeEntryInput {
  workPackageNumber: number;
  spentOn: IsoDate;
  hours: number;
  /** absent: the default activity */
  activityId?: Id | null;
  comment?: string;
  /** someone else's time (time.manage) */
  userId?: Id;
}
export interface TimeWeek {
  /** the first day, as 관리 · 날짜 형식 starts weeks */
  start: IsoDate;
  days: { date: IsoDate; working: boolean; holiday: string | null }[];
  /** 관리 · 근무 시간: a day past this is flagged, not refused (24 is the hard limit) */
  hoursPerDay: number;
  /** work packages I logged time on this week */
  rows: { workPackage: WorkPackageRef; canLog: boolean; cells: { date: IsoDate; hours: number; entries: number }[] }[];
}
/** one cell of the sheet: my hours on a work package that day (0 clears it) */
export interface TimeWeekCell {
  workPackageNumber: number;
  date: IsoDate;
  hours: number;
}
export const TIME_GROUPS = ["user", "workPackage", "activity", "project", "day"] as const;
export type TimeGroupBy = (typeof TIME_GROUPS)[number];
export interface TimeFilter {
  from?: IsoDate;
  to?: IsoDate;
  /** identifier or id */
  project?: string;
  /** "me", a user id, or all when absent */
  user?: string;
}
export interface TimeReport {
  from: IsoDate;
  to: IsoDate;
  groupBy: TimeGroupBy;
  rows: { key: string; label: string; hours: number }[];
  total: number;
}

export interface Webhook {
  id: Id;
  name: string;
  url: string;
  events: WebhookEvent[];
  /** empty: every project */
  projectIds: Id[];
  enabled: boolean;
  /** the last characters of the signing secret */
  secretHint: string;
  createdAt: IsoDateTime;
  /** only in the answer that made the webhook or a new secret */
  secret?: string | null;
}
export interface WebhookInput {
  name: string;
  url: string;
  events: WebhookEvent[];
  projectIds: Id[];
  enabled: boolean;
}
export interface WebhookDelivery {
  id: Id;
  event: WebhookEvent | "ping";
  status: "pending" | "succeeded" | "failed";
  attempts: number;
  responseStatus: number | null;
  error: string | null;
  createdAt: IsoDateTime;
  deliveredAt: IsoDateTime | null;
  nextAttemptAt: IsoDateTime | null;
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
  /** with a template: the day the copied schedule begins (work packages keep their place in working days; versions move by calendar days) */
  startDate?: IsoDate | null;
}

export const TEMPLATE_PARTS = ["members", "versions", "categories", "workPackages", "boards", "queries"] as const;
export type TemplatePart = (typeof TEMPLATE_PARTS)[number];

export const OVERVIEW_WIDGETS = ["description", "workSummary", "status", "members", "versions", "dueSoon", "recent", "assignedToMe"] as const;
export type OverviewWidget = (typeof OVERVIEW_WIDGETS)[number];
export const DEFAULT_OVERVIEW_WIDGETS: OverviewWidget[] = ["description", "workSummary", "status", "members"];

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
  /** the main assignee: the first of assignees */
  assignee: UserRef | null;
  /** 공동 담당자: everyone who has the item, in order, each with their own estimate in hours */
  assignees: Assignee[];
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
      /** write an internal comment here (the project has them on, wp.add_internal_comments) */
      commentInternal: boolean;
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

/** 공동 담당자: one of the people who have a work package */
export interface Assignee {
  user: UserRef;
  workHours: number | null;
}

/** How a list of assignees is sent: everyone, in order (the first is the main assignee); [] = nobody */
export type AssigneesInput = { userId: Id; workHours?: number | null }[];

export interface WorkPackageInput {
  typeId: Id;
  subject: string;
  descriptionHtml?: string;
  statusId?: Id;
  priorityId?: Id;
  /** the main assignee only (someone on the list moves to the front; someone new takes the main place; null = nobody) */
  assigneeId?: Id | null;
  /** everyone, in order, with their hours; instead of assigneeId */
  assignees?: AssigneesInput;
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
  /** Gantt views: when visible, the result also carries the precedes relations between its rows; labels: what each bar shows beside it. */
  timeline?: { visible: boolean; zoom: "day" | "week" | "month" | "auto"; labels?: GanttLabels };
  /** S11 강조 표시: none; inline = priority and due date cells tinted; status/priority/type = whole rows in that attribute's colour */
  highlight?: Highlight;
  /** S06 기준선: mark what changed since the start of that day */
  baseline?: Baseline | null;
}

export type Baseline = { kind: "yesterday" | "lastWeek" | "lastMonth" } | { kind: "date"; date: IsoDate };
export interface RowBaseline {
  /** made after the baseline point */
  added: boolean;
  /** column → the value it had then (null: empty) */
  changed: Record<string, string | null>;
}
/** S06 기준선: an item the list matched at the baseline point but no longer does */
export interface BaselineRemoved {
  id: Id;
  number: number;
  subject: string;
  project: { id: Id; identifier: string; name: string } | null;
  /** the changed columns: value then and today (null: empty) */
  then: Record<string, string | null>;
  now: Record<string, string | null>;
}

export type Highlight = "none" | "inline" | "status" | "priority" | "type";
export const HIGHLIGHTS: Highlight[] = ["none", "inline", "status", "priority", "type"];

export const GANTT_LABELS = ["none", "subject", "assignee", "status", "dates", "type", "priority", "doneRatio"] as const;
export type GanttLabel = (typeof GANTT_LABELS)[number];
/** S13 라벨: left of the bar, right of it, and at the far right of the chart */
export interface GanttLabels {
  left: GanttLabel;
  right: GanttLabel;
  farRight: GanttLabel;
}
export const DEFAULT_GANTT_LABELS: GanttLabels = { left: "none", right: "subject", farRight: "none" };

/** S12 내보내기: every item matching the spec (not just the page), in its order; at most EXPORT_LIMIT. */
export type ExportFormat = "csv" | "xlsx" | "pdf";
export type PdfType = "table" | "report" | "gantt";
/** PDF: the server makes it, up to this many items */
export const PDF_LIMIT = 1000;
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
  /** with format pdf: a table of the columns, a report of each item, or a Gantt chart */
  pdfType?: PdfType;
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
  /** copy: to targetProjectId (or the same project), keeping the hierarchy among the chosen items */
  action: "update" | "delete" | "move" | "copy";
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
  /** with spec.baseline: what changed since then (null: nothing) */
  baseline?: RowBaseline | null;
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
  /** with spec.baseline: the point compared against */
  baselineAt?: IsoDateTime | null;
  /** with spec.baseline: items that matched then but not now (lowest numbers first, at most 200) */
  baselineRemoved?: BaselineRemoved[] | null;
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

/** The notification center's filters: reasons (empty: all) and a project (identifier, empty: all). */
export interface NotificationFilter {
  reasons?: NotificationReason[];
  project?: string | null;
}
/** Unread counts over every notification: all and per reason within the chosen project, per project within the chosen reasons. */
export interface NotificationCounts {
  all: number;
  byReason: Partial<Record<NotificationReason, number>>;
  projects: { identifier: string; name: string; unread: number }[];
  allProjects: number;
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
  /** S31 global roles this person holds (create projects, manage users, …) */
  globalRoles: { id: Id; name: string }[];
  /** 임시 비밀번호: until when it runs (past = ran out, shown until the nightly cleanup); the password only via temporaryPassword(id) */
  temporaryPasswordUntil: IsoDateTime | null;
}
/** 임시 비밀번호 as an administrator sees it: made, issued again, or looked up (issuedBy/issuedAt only when looked up). */
export interface TemporaryPassword {
  temporaryPassword: string;
  expiresAt: IsoDateTime;
  issuedBy?: { id: Id; name: string } | null;
  issuedAt?: IsoDateTime;
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
/* ---------- CI/CD 연동 (GitLab·GitHub·Jenkins·Argo CD) ---------- */

export type CicdKind = "gitlab" | "github" | "jenkins" | "argocd";
/** Never holds a secret: only the last four characters (tokenHint, secretHint). */
export interface CicdConnection {
  id: Id;
  kind: CicdKind;
  name: string;
  baseUrl: string;
  webhookUrl: string;
  tokenHint: string | null;
  secretHint: string | null;
  active: boolean;
  repositoryCount: number;
  createdAt: IsoDateTime;
  /** the allowed projects the caller may see (a delegated CI/CD 연동 관리자 sees only their own projects by name) */
  projects: { id: Id; name: string }[];
  /** the other allowed projects, counted only */
  hiddenProjectCount: number;
  /** Jenkins·Argo CD: the address links must be on; null for GitLab·GitHub */
  toolUrl: string | null;
  /** Jenkins·Argo CD: null = the default rule (7–40 hex digits) */
  imageTagPattern: string | null;
}
export interface CicdConnectionInput {
  kind: CicdKind;
  name: string;
  baseUrl: string;
  /** optional; without it only the webhook feeds the connection (Jenkins·Argo CD take none: 422) */
  apiToken?: string;
  /** Jenkins·Argo CD only; baseUrl is the tool's address */
  imageTagPattern?: string;
}
/** apiToken "" clears the token; a changed baseUrl clears it too unless a new one is given */
export interface CicdConnectionPatch {
  name?: string;
  baseUrl?: string;
  apiToken?: string;
  /** Jenkins·Argo CD only; "" = back to the default rule */
  imageTagPattern?: string;
  active?: boolean;
}
export interface CicdRepository {
  id: Id;
  connectionName: string;
  kind: CicdKind;
  fullPath: string;
  webUrl: string;
  lastPolledAt: IsoDateTime | null;
  /** set = detached: the history stays, nothing new comes in */
  detachedAt: IsoDateTime | null;
}
/** the project's choice list of active connections */
export interface CicdConnectionChoice {
  id: Id;
  kind: CicdKind;
  name: string;
}
/** attachedProject is present (true) only when the repository is already attached somewhere (never which project) */
export interface CicdRemoteRepo {
  externalId: string;
  fullPath: string;
  webUrl: string;
  attachedProject?: boolean;
}
export type CicdBuildStatus = "running" | "success" | "failed" | "canceled";
/** several workflows of one commit or branch, summed up: any failure shows (count = workflows) */
export interface CicdBuild {
  status: CicdBuildStatus;
  webUrl: string;
  at: IsoDateTime;
  source: "gitlab" | "github" | "jenkins";
  count: number;
}
export type CicdLinkSource = "message" | "branch" | "title" | "manual";
export interface CicdMergeRequest {
  linkId: Id;
  repository: string;
  iid: number;
  title: string;
  state: "open" | "draft" | "merged" | "closed";
  sourceBranch: string;
  targetBranch: string;
  author: string;
  user: UserRef | null;
  mergedAt: IsoDateTime | null;
  updatedAt: IsoDateTime;
  webUrl: string;
  source: CicdLinkSource;
  /** the build of the source branch; null = none received */
  build: CicdBuild | null;
  /** the repository is detached */
  detached: boolean;
}
export interface CicdCommit {
  linkId: Id;
  repository: string;
  shortSha: string;
  title: string;
  author: string;
  user: UserRef | null;
  committedAt: IsoDateTime;
  webUrl: string;
  source: CicdLinkSource;
  build: CicdBuild | null;
  /** the names of the environments it is in, the furthest one first */
  environments: string[];
  detached: boolean;
}
export type CicdEnvState = "in" | "partial" | "none" | "checking" | "failed" | "before_first";
/** how far the work went in one environment */
export interface CicdEnvironmentStatus {
  id: Id;
  name: string;
  production: boolean;
  state: CicdEnvState;
  included: number;
  total: number;
  /** some of the included commits were added by a check that saw only part of the history */
  partialCheck: boolean;
  deployedAt: IsoDateTime | null;
  /** "" when nothing has been deployed there */
  webUrl: string;
  /** the project's name, only when the linked commits come from repositories of several projects */
  project: string | null;
}
/** the work package's 개발 tab; canLink = cicd.link. The builds are on each merge request and commit (there is no top-level list). */
export interface DevInfo {
  mergeRequests: CicdMergeRequest[];
  commits: CicdCommit[];
  canLink: boolean;
  environments: CicdEnvironmentStatus[];
}
/** 프로젝트 › CI/CD › 환경; hidden = it has deployment history, so it was hidden instead of deleted */
export interface CicdEnvironment {
  id: Id;
  name: string;
  position: number;
  production: boolean;
  aliases: string[];
  hidden: boolean;
  deploymentCount: number;
}
/** deployments no environment took, grouped by name (lower case) */
export interface CicdUnassigned {
  name: string;
  count: number;
  lastAt: IsoDateTime | null;
}
export interface CicdEnvironmentsView {
  environments: CicdEnvironment[];
  unassigned: CicdUnassigned[];
  canManage: boolean;
}
export interface CicdEnvironmentInput {
  name: string;
  production?: boolean;
  aliases?: string[];
}
export interface CicdEnvironmentPatch {
  name?: string;
  production?: boolean;
  aliases?: string[];
  hidden?: boolean;
}
/** only the part for the connection's kind is filled (GitLab·GitHub: neither) */
export interface CicdTemplates {
  kind: CicdKind;
  jenkinsfile: string | null;
  argocd: string | null;
}
