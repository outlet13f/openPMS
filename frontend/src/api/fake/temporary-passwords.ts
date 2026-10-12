// 임시 비밀번호 in the fake data layer; backend: auth/TemporaryPasswords.java (same rules, same messages).
import { nowIso } from "@/lib/dates";
import { DEFAULT_AUTH_SETTINGS } from "../password-policy";
import { db } from "./db";
import { revokeAll } from "./tokens";

const LOWER = "abcdefghijkmnopqrstuvwxyz";
const UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
const DIGITS = "23456789";
const SPECIAL = "!@#$%*-_+=?";

const random = (n: number) => crypto.getRandomValues(new Uint32Array(1))[0] % n;
const pick = (set: string) => set[random(set.length)];

/** At least 16 characters, one of each kind, shuffled (TemporaryPasswords.generate). */
export function generateTemporaryPassword(minLength: number) {
  const chars = [LOWER, UPPER, DIGITS, SPECIAL].map(pick);
  const all = LOWER + UPPER + DIGITS + SPECIAL;
  while (chars.length < Math.max(16, minLength)) chars.push(pick(all));
  for (let i = chars.length - 1; i > 0; i--) {
    const j = random(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join("");
}

/** A new one replaces the password. The demo has one session per browser: if it is theirs, it ends. */
export function newTemporaryPassword(userId: string, issuedBy: string) {
  const settings = db.settings.auth ?? DEFAULT_AUTH_SETTINGS;
  const password = generateTemporaryPassword(settings.minLength);
  const issuedAt = nowIso();
  const expiresAt = new Date(Date.now() + settings.invitationTtlHours * 3_600_000).toISOString();
  const u = db.users.find((x) => x.id === userId)!;
  u.password = password;
  db.temporaryPasswords = [...(db.temporaryPasswords ?? []).filter((x) => x.userId !== userId), { userId, password, expiresAt, issuedBy, issuedAt }];
  revokeAll(userId, "password_reset");
  revokeAll(userId, "password_change");
  if (db.sessionUserId === userId) db.sessionUserId = null;
  return { temporaryPassword: password, expiresAt };
}

export const temporaryPasswordOf = (userId: string) => (db.temporaryPasswords ?? []).find((x) => x.userId === userId);

/** Any path that sets a password, or the account went. */
export function clearTemporaryPassword(userId: string) {
  db.temporaryPasswords = (db.temporaryPasswords ?? []).filter((x) => x.userId !== userId);
}

/** Ran out: the row and the password itself go (TemporaryPasswords.expire). */
export function expireTemporaryPassword(userId: string) {
  clearTemporaryPassword(userId);
  const u = db.users.find((x) => x.id === userId);
  if (u) u.password = "";
}
