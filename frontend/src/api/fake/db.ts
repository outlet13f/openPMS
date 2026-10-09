// In-memory stand-in for the openPMS database while /replica-backend has not run.
// Table shapes follow replica/schema.sql closely so the move to the real API is mechanical.
// State survives reloads through localStorage (per browser, per user) and can be reset from the login page.

import type { BoardKind, CustomField, CustomValue, Id, NotificationSetting, ShareRole, IsoDate, IsoDateTime, ModuleKey, NotificationReason, Preferences, ProjectStatus, QuerySpec, ViewKind } from "../types";
import { buildSeed } from "./seed";

export interface DbUser {
  id: Id;
  email: string;
  name: string;
  password: string;
  status: "invited" | "active" | "locked" | "deleted";
  isAdmin: boolean;
  locale: "ko" | "en";
  timeZone: string;
  prefs: Preferences;
  createdAt: IsoDateTime;
  lastLoginAt: IsoDateTime | null;
}
export interface DbRole { id: Id; name: string; scope: "project" | "global"; builtin: "non_member" | null; permissions: string[] }
export interface DbProject {
  id: Id;
  identifier: string;
  name: string;
  descriptionHtml: string;
  status: ProjectStatus | null;
  statusExplanation: string;
  isPublic: boolean;
  isTemplate: boolean;
  archivedAt: IsoDateTime | null;
  parentId: Id | null;
  enabledModules: ModuleKey[];
  typeIds: Id[];
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  latestActivityAt: IsoDateTime;
}
/** A project member is a user or a group (exactly one of userId / groupId, like members_one_principal). */
export interface DbMember { id: Id; projectId: Id; userId: Id | null; groupId: Id | null; roleIds: Id[]; createdAt: IsoDateTime }
export interface DbType { id: Id; name: string; color: string; position: number; isMilestone: boolean; isDefault: boolean; isInRoadmap: boolean }
export interface DbStatus { id: Id; name: string; color: string; position: number; isDefault: boolean; isClosed: boolean; isReadonly: boolean; defaultDoneRatio: number; excludedFromTotals: boolean }
export interface DbPriority { id: Id; name: string; color: string; position: number; isDefault: boolean; isActive: boolean }
export interface DbWorkflow { typeId: Id; roleId: Id; from: Id; to: Id }
export interface DbVersion { id: Id; projectId: Id; name: string; description: string; status: "open" | "locked" | "closed"; startDate: IsoDate | null; endDate: IsoDate | null }
export interface DbCategory { id: Id; projectId: Id; name: string; defaultAssigneeId: Id | null }
export interface DbGroup { id: Id; name: string; memberIds: Id[] }
export interface DbWp {
  id: Id;
  number: number;
  projectId: Id;
  typeId: Id;
  statusId: Id;
  priorityId: Id;
  subject: string;
  descriptionHtml: string;
  authorId: Id;
  assigneeId: Id | null;
  responsibleId: Id | null;
  versionId: Id | null;
  categoryId: Id | null;
  parentId: Id | null;
  startDate: IsoDate | null;
  dueDate: IsoDate | null;
  duration: number | null;
  ignoreNonWorkingDays: boolean;
  scheduleManually: boolean;
  workHours: number | null;
  remainingHours: number | null;
  doneRatio: number | null;
  /** {customFieldId: value}; values of fields an item no longer has (type or project changed) are kept, hidden */
  customValues?: Record<Id, CustomValue>;
  lockVersion: number;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}
/** Canonical direction only, like the real schema. */
export interface DbRelation { id: Id; fromId: Id; toId: Id; type: "relates" | "precedes" | "duplicates" | "blocks" | "includes" | "requires"; lag: number; description: string }
export interface DbJournal {
  id: Id;
  wpId: Id;
  version: number;
  userId: Id;
  notesHtml: string;
  isInternal: boolean;
  changes: Record<string, [unknown, unknown]>;
  cause: { kind: "predecessor_moved" | "child_moved" | "calendar_changed"; workPackageNumber?: number } | null;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}
export interface DbQuery { id: Id; projectId: Id | null; ownerId: Id; name: string; isPublic: boolean; view: ViewKind; spec: QuerySpec; createdAt?: IsoDateTime }
export interface DbBoard {
  id: Id;
  projectId: Id;
  name: string;
  kind: BoardKind;
  isPublic: boolean;
  createdAt: IsoDateTime;
  columns: { id: Id; position: number; name: string | null; valueId: Id | null }[];
  /** Card order per work package id (fractional positions, like board_cards.position). */
  order: Record<Id, number>;
}
export interface DbNotification { id: Id; recipientId: Id; wpId: Id; journalId: Id | null; actorId: Id | null; reason: NotificationReason; readAt: IsoDateTime | null; createdAt: IsoDateTime }

/** Demo only: bytes live in the browser as a data URL, so the fake layer keeps files small. */
export interface DbAttachment { id: Id; wpId: Id; fileName: string; contentType: string; byteSize: number; authorId: Id; createdAt: IsoDateTime; dataUrl: string }

export interface Db {
  schema: number;
  seq: { wp: number; id: number };
  sessionUserId: Id | null;
  users: DbUser[];
  roles: DbRole[];
  projects: DbProject[];
  members: DbMember[];
  types: DbType[];
  statuses: DbStatus[];
  priorities: DbPriority[];
  workflows: DbWorkflow[];
  versions: DbVersion[];
  categories: DbCategory[];
  groups: DbGroup[];
  workPackages: DbWp[];
  relations: DbRelation[];
  journals: DbJournal[];
  watchers: { wpId: Id; userId: Id }[];
  attachments: DbAttachment[];
  notificationSettings: ({ userId: Id; projectId: Id | null } & NotificationSetting)[];
  shares: { id: Id; wpId: Id; userId: Id; role: ShareRole; createdBy: Id | null; createdAt: IsoDateTime }[];
  customFields: CustomField[];
  projectCustomFields: { projectId: Id; customFieldId: Id }[];
  queries: DbQuery[];
  queryFavorites: { userId: Id; queryId: Id }[];
  boards: DbBoard[];
  notifications: DbNotification[];
  projectFavorites: { userId: Id; projectId: Id }[];
  nonWorkingDays: { id: Id; date: IsoDate; name: string }[];
  settings: { workingDays: number[]; hoursPerDay: number; journalAggregationMinutes: number };
}

const KEY = "openpms.fakedb";
const SCHEMA = 11;

function load(): Db {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Db;
      if (parsed.schema === SCHEMA) return parsed;
    }
  } catch {
    /* storage unavailable or corrupt: fall back to a fresh seed */
  }
  return buildSeed(SCHEMA);
}

export const db: Db = load();

export function persist() {
  try {
    localStorage.setItem(KEY, JSON.stringify(db));
  } catch {
    /* private mode or quota: keep working in memory */
  }
}

export function resetFakeDb() {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* ignore */
  }
  Object.assign(db, buildSeed(SCHEMA));
}

export function newId(prefix: string) {
  db.seq.id += 1;
  return `${prefix}-${db.seq.id.toString(36)}`;
}
