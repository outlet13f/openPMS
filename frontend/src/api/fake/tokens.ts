// One-time links (invitation, password reset) in the fake data layer, and an outbox standing in for mail.
// backend: auth/Tokens.java (the DB keeps only a hash there; the demo keeps the token, it never leaves the browser).
import { nowIso } from "@/lib/dates";
import { ApiError } from "../errors";
import { db, newId, type DbToken } from "./db";

const gone = () => new ApiError(410, "링크가 만료됐거나 이미 사용됐습니다.", "새 링크를 요청하세요.");

export function issueToken(userId: string, purpose: DbToken["purpose"], minutes: number, createdBy: string | null) {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  const token = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  db.tokens.push({ token, userId, purpose, expiresAt: new Date(Date.now() + minutes * 60_000).toISOString(), usedAt: null, createdBy });
  return token;
}

export function validToken(token: string, purpose: DbToken["purpose"]) {
  const t = db.tokens.find((x) => x.token === token && x.purpose === purpose);
  if (!t || t.usedAt || t.expiresAt < nowIso()) throw gone();
  return t;
}

/** Ends every open link of this kind for the user (a new one replaces them; a used one closes the rest). */
export function revokeAll(userId: string, purpose: DbToken["purpose"]) {
  const now = nowIso();
  db.tokens.forEach((t) => t.userId === userId && t.purpose === purpose && !t.usedAt && (t.usedAt = now));
}

/** Demo stand-in for mail: what the real backend would send, with the link to open. */
export function sendMail(to: string, subject: string, link: string) {
  db.outbox.unshift({ id: newId("m"), to, subject, link, sentAt: nowIso() });
  db.outbox.splice(20);
}

export { gone as tokenGone };
