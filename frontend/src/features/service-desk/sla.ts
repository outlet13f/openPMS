// SLA state and time left of a service desk ticket, for the 서비스 tab and the list columns.
//
// "임박" here is an approximation: two hours or less to a deadline. The exact rule (20 % of the resolve target left,
// in calendar time) belongs to the server's SLA notifications (sla_warning), which know the policy and its calendar;
// the screen only has the deadlines, so the two may disagree for very short or very long targets.
import type { Sla } from "@/api/types";

export type SlaState = "breached" | "paused" | "warning" | "ok" | "met" | "none";

const WARN_MS = 2 * 3600_000;

/** 위반 > 해결함 > 멈춤 > 임박 > 정상; no deadline at all (no policy) is "none". */
export function slaState(sla: Sla, now: number): SlaState {
  if (!sla.resolveDueAt && !sla.firstResponseDueAt) return "none";
  if (sla.responseBreached || sla.resolveBreached) return "breached";
  if (sla.resolvedAt) return "met";
  if (sla.paused) return "paused";
  const due = [sla.firstRespondedAt ? null : sla.firstResponseDueAt, sla.resolveDueAt].filter((x): x is string => !!x).map(Date.parse);
  if (due.some((d) => d <= now)) return "breached"; // past is past, even before the server's job marks it
  return due.some((d) => d - now <= WARN_MS) ? "warning" : "ok";
}

/** "1시간 20분 남음" / "35분 지남"; empty without a deadline. */
export function remainingLabel(dueAt: string | null, now: number): string {
  if (!dueAt) return "";
  const diff = Date.parse(dueAt) - now;
  const m = Math.round(Math.abs(diff) / 60_000);
  const text = m >= 60 ? `${Math.floor(m / 60)}시간${m % 60 ? ` ${m % 60}분` : ""}` : `${m}분`;
  return diff >= 0 ? `${text} 남음` : `${text} 지남`;
}
