// In-memory stand-in for the openPMS database while /replica-backend has not run.
// Table shapes follow replica/schema.sql closely so the move to the real API is mechanical.
// State survives reloads through localStorage (per browser, per user) and can be reset from the login page.

import type { BoardKind, Id, IsoDate, IsoDateTime, ModuleKey, NotificationReason, Preferences, ProjectStatus, QuerySpec } from "../types";
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
export interface DbMember { id: Id; projectId: Id; userId: Id; roleIds: Id[]; createdAt: IsoDateTime }
export interface DbType { id: Id; name: string; color: string; position: number; isMilestone: boolean; isDefault: boolean }
export interface DbStatus { id: Id; name: string; color: string; position: number; isDefault: boolean; isClosed: boolean; isReadonly: boolean; defaultDoneRatio: number }
export interface DbPriority { id: Id; name: string; color: string; position: number; isDefault: boolean }
export interface DbWorkflow { typeId: Id; roleId: Id; from: Id; to: Id }
export interface DbVersion { id: Id; projectId: Id; name: string; status: "open" | "locked" | "closed"; startDate: IsoDate | null; endDate: IsoDate | null }
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
  parentId: Id | null;
  startDate: IsoDate | null;
  dueDate: IsoDate | null;
  duration: number | null;
  ignoreNonWorkingDays: boolean;
  scheduleManually: boolean;
  workHours: number | null;
  remainingHours: number | null;
  doneRatio: number | null;
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
export interface DbQuery { id: Id; projectId: Id | null; ownerId: Id; name: string; isPublic: boolean; view: "table" | "gantt"; spec: QuerySpec }
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
  workPackages: DbWp[];
  relations: DbRelation[];
  journals: DbJournal[];
  watchers: { wpId: Id; userId: Id }[];
  queries: DbQuery[];
  queryFavorites: { userId: Id; queryId: Id }[];
  boards: DbBoard[];
  notifications: DbNotification[];
  projectFavorites: { userId: Id; projectId: Id }[];
  nonWorkingDays: { date: IsoDate; name: string }[];
  settings: { workingDays: number[]; journalAggregationMinutes: number };
}

const KEY = "openpms.fakedb";
const SCHEMA = 3;

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
