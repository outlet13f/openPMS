// Notification settings (S20) and my email preferences in the fake data layer.
// backend: notification/NotificationSettingsController.java + Journals.fanOut keep the same rules.
import type { Api } from "../api";
import { MY_PAGE_WIDGETS, type AlertDays, type NotificationSetting, type Preferences } from "../types";
import { db } from "./db";
import { findProject, sessionUser } from "./permissions";
import { clone, projectRef } from "./dto";
import { fieldError, run } from "./util";

/** Used when I have no row: the same as the database column defaults. */
export const DEFAULT_SETTING: NotificationSetting = {
  assignee: true, responsible: true, shared: true,
  workPackageCreated: false, commented: false, statusChanged: false, priorityChanged: false, datesChanged: false,
  startDateAlertDays: 1, dueDateAlertDays: 1, overdueAlert: false,
};

const KEYS = Object.keys(DEFAULT_SETTING) as (keyof NotificationSetting)[];
const pick = (row: NotificationSetting): NotificationSetting => Object.fromEntries(KEYS.map((k) => [k, row[k]])) as unknown as NotificationSetting;

/** A project's own row wins over my default row; no row at all means the defaults. */
export function effectiveSetting(userId: string, projectId: string): NotificationSetting {
  const own = db.notificationSettings.find((s) => s.userId === userId && s.projectId === projectId);
  const def = db.notificationSettings.find((s) => s.userId === userId && s.projectId === null);
  return pick(own ?? def ?? DEFAULT_SETTING);
}

const DAYS: (AlertDays | null)[] = [null, 0, 1, 3, 7];

function check(input: NotificationSetting): NotificationSetting {
  const out = { ...DEFAULT_SETTING };
  for (const k of KEYS) {
    const v = input[k];
    if (k === "startDateAlertDays" || k === "dueDateAlertDays") {
      if (v !== undefined && !DAYS.includes(v as AlertDays | null)) throw fieldError(k, "알림 시점은 당일, 1일·3일·7일 전 중에서 고르세요.");
      (out as Record<string, unknown>)[k] = v ?? null;
    } else {
      if (v !== undefined && typeof v !== "boolean") throw fieldError(k, "켜기 또는 끄기로 보내세요.");
      (out as Record<string, unknown>)[k] = v ?? DEFAULT_SETTING[k];
    }
  }
  return out;
}

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

function checkPreferences(patch: Partial<Preferences>) {
  if (patch.digestTimes !== undefined) {
    const t = patch.digestTimes;
    if (!Array.isArray(t) || t.length < 1 || t.length > 4) throw fieldError("digestTimes", "요약 메일 시각을 1~4개 정하세요.");
    if (t.some((x) => typeof x !== "string" || !TIME.test(x))) throw fieldError("digestTimes", "시각은 HH:MM 형식으로 입력하세요.");
    if (new Set(t).size !== t.length) throw fieldError("digestTimes", "같은 시각이 두 번 있습니다.");
  }
  if (patch.myPageWidgets !== undefined) {
    const w = patch.myPageWidgets;
    if (!Array.isArray(w) || w.some((x) => !MY_PAGE_WIDGETS.includes(x)) || new Set(w).size !== w.length) throw fieldError("myPageWidgets", "알 수 없는 위젯이 있습니다.");
  }
  if (patch.remindersPausedUntil !== undefined && patch.remindersPausedUntil !== null && !/^\d{4}-\d{2}-\d{2}$/.test(patch.remindersPausedUntil))
    throw fieldError("remindersPausedUntil", "날짜를 YYYY-MM-DD 형식으로 입력하세요.");
}

/** The settings half of /me; profile and password live with the other user code in index.ts. */
export const accountApi: Omit<Api["account"], "updateProfile" | "changePassword" | "sendPasswordSetup" | "endOtherSessions" | "apiTokens" | "createApiToken" | "revokeApiToken"> = {
  updatePreferences: (patch) =>
    run(() => {
      const user = sessionUser();
      checkPreferences(patch);
      const known: (keyof Preferences)[] = ["commentsNewestFirst", "warnOnUnsaved", "keyboardShortcuts", "immediateOnMention", "digestEnabled", "digestTimes", "remindersPausedUntil", "myPageWidgets"];
      for (const k of known) if (patch[k] !== undefined) (user.prefs as unknown as Record<string, unknown>)[k] = k === "digestTimes" ? [...(patch[k] as string[])].sort() : patch[k];
      return clone(user.prefs);
    }),
  notificationSettings: () =>
    run(() => {
      const user = sessionUser();
      const mine = db.notificationSettings.filter((s) => s.userId === user.id);
      const def = mine.find((s) => s.projectId === null);
      return clone({
        default: pick(def ?? DEFAULT_SETTING),
        projects: mine
          .filter((s) => s.projectId !== null)
          .map((s) => ({ ...pick(s), project: projectRef(db.projects.find((p) => p.id === s.projectId)!) }))
          .sort((a, b) => a.project.name.localeCompare(b.project.name, "ko")),
      });
    }),
  saveDefaultNotificationSetting: (setting) =>
    run(() => {
      const user = sessionUser();
      const next = check(setting);
      db.notificationSettings = [...db.notificationSettings.filter((s) => !(s.userId === user.id && s.projectId === null)), { userId: user.id, projectId: null, ...next }];
      return clone(next);
    }),
  saveProjectNotificationSetting: (projectId, setting) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, projectId);
      const next = check(setting);
      db.notificationSettings = [...db.notificationSettings.filter((s) => !(s.userId === user.id && s.projectId === p.id)), { userId: user.id, projectId: p.id, ...next }];
      return clone({ ...next, project: projectRef(p) });
    }),
  removeProjectNotificationSetting: (projectId) =>
    run(() => {
      const user = sessionUser();
      const p = findProject(user, projectId);
      db.notificationSettings = db.notificationSettings.filter((s) => !(s.userId === user.id && s.projectId === p.id));
    }),
};
