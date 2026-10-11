// 공동 담당자 in the demo data layer, the same rules as backend wp/Assignees.java: everyone who has an item, in order,
// each with their own hours; the first is the main assignee and assigneeId always names that person. Pure functions
// (no db import: seed.ts uses them too).
import type { AssigneesInput, Id } from "../types";

export interface TeamEntry {
  userId: Id;
  workHours: number | null;
}

export const MAX_ASSIGNEES = 20;

/** The list after an older-style change of the main assignee: nobody → nobody; on the list → to the front; new → takes the main place. */
export function withMain(team: TeamEntry[], main: Id | null): TeamEntry[] {
  if (!main) return [];
  const mine = team.find((e) => e.userId === main);
  const rest = team.filter((e, i) => e.userId !== main && !(i === 0 && !mine));
  return [mine ?? { userId: main, workHours: null }, ...rest];
}

/** One person handed over to another (a card dragged between two people's columns); the hours stay. */
export function swap(team: TeamEntry[], from: Id | null, to: Id): TeamEntry[] {
  if (!from || from === to || !team.some((e) => e.userId === from)) return withMain(team, to);
  return team.filter((e) => e.userId !== to).map((e) => (e.userId === from ? { userId: to, workHours: e.workHours } : e));
}

/** Everyone on the item; follows assigneeId when something set only that (copies, moves, a deleted person). */
export function teamOf(w: { assigneeId: Id | null; team?: TeamEntry[] }): TeamEntry[] {
  if (!w.team) return w.assigneeId ? [{ userId: w.assigneeId, workHours: null }] : [];
  if ((w.team[0]?.userId ?? null) === w.assigneeId) return w.team;
  return withMain(w.team, w.assigneeId);
}

export const sameTeam = (a: TeamEntry[], b: TeamEntry[]) =>
  a.length === b.length && a.every((e, i) => e.userId === b[i].userId && (e.workHours ?? null) === (b[i].workHours ?? null));

export const beyondMain = (t: TeamEntry[]) => t.length > 1 || t.some((e) => e.workHours !== null);

/** The request's list, checked like the server: members only, nobody twice, 0–9999 hours. Throws a message for the field. */
export function readTeam(input: AssigneesInput, isMember: (id: Id) => boolean): TeamEntry[] | string {
  if (input.length > MAX_ASSIGNEES) return `담당자는 ${MAX_ASSIGNEES}명까지 지정할 수 있습니다.`;
  const seen = new Set<Id>();
  const out: TeamEntry[] = [];
  for (const e of input) {
    if (seen.has(e.userId)) return "같은 사람을 두 번 지정할 수 없습니다.";
    seen.add(e.userId);
    if (!isMember(e.userId)) return "프로젝트 멤버만 지정할 수 있습니다.";
    const h = e.workHours ?? null;
    if (h !== null && (!Number.isFinite(h) || h < 0 || h > 9999)) return "예상 시간은 0–9999시간입니다.";
    out.push({ userId: e.userId, workHours: h === null ? null : Math.round(h * 100) / 100 });
  }
  return out;
}

/** How the list goes into a journal entry (as on the server). */
export const journalTeam = (t: TeamEntry[]) => t.map((e) => ({ userId: e.userId, workHours: e.workHours === null ? null : String(e.workHours) }));
