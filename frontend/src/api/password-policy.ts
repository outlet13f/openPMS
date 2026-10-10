// 관리 · 로그인과 비밀번호: what a new password needs. The server checks the same (backend auth/PasswordPolicy.java); the pages
// check first so a typo does not cost a round trip, and the fake data layer uses it as its server.
import type { AuthSettings, PasswordClass, PasswordRules } from "./types";

export const PASSWORD_CLASSES: readonly PasswordClass[] = ["lower", "upper", "digit", "special"];

const NAMES: Record<PasswordClass, string> = { lower: "영문 소문자", upper: "영문 대문자", digit: "숫자", special: "특수 문자" };

export const DEFAULT_AUTH_SETTINGS: AuthSettings = {
  minLength: 10,
  requiredClasses: [],
  minClasses: 0,
  resetEnabled: true,
  lockoutAttempts: 5,
  lockoutMinutes: 15,
  invitationTtlHours: 168,
  resetTtlMinutes: 60,
};

function has(p: string, kind: PasswordClass) {
  if (kind === "lower") return /[a-z]/.test(p);
  if (kind === "upper") return /[A-Z]/.test(p);
  if (kind === "digit") return /[0-9]/.test(p);
  return /[^a-zA-Z0-9\s]/.test(p);
}

/** What the password still needs (the server's words), or null. */
export function passwordProblem(r: PasswordRules, password: string): string | null {
  const p = password ?? "";
  if (p.length < r.minLength) return `비밀번호는 ${r.minLength}자 이상이어야 합니다.`;
  if (p.length > 128) return "비밀번호는 128자 이하로 정하세요.";
  const missing = r.requiredClasses.filter((k) => !has(p, k)).map((k) => NAMES[k]);
  if (missing.length) return `비밀번호에 ${missing.join(", ")}을(를) 넣으세요.`;
  if (PASSWORD_CLASSES.filter((k) => has(p, k)).length < r.minClasses) return `영문 소문자·대문자·숫자·특수 문자 중 ${r.minClasses}가지 이상을 섞으세요.`;
  return null;
}

/** The rules in one line, for a field's hint: "10자 이상 · 숫자 포함 · 3가지 이상 섞기". */
export function describeRules(r: PasswordRules): string {
  return [
    `${r.minLength}자 이상`,
    ...(r.requiredClasses.length ? [`${r.requiredClasses.map((k) => NAMES[k]).join("·")} 포함`] : []),
    ...(r.minClasses > 0 ? [`영문 소문자·대문자·숫자·특수 문자 중 ${r.minClasses}가지 이상`] : []),
  ].join(" · ");
}
