// SLA panel of the 서비스 tab: the first-response and resolve deadlines, each with time left, a state badge and a bar of
// the time used so far (wall clock from the ticket's creation; the server counts calendar time, so the bar is a guide).
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { Sla } from "@/api/types";
import { Badge } from "@/components/ui/badges";
import { cn } from "@/lib/cn";
import { formatDateTime } from "@/lib/dates";
import { remainingLabel, type SlaState } from "./sla";

export const SLA_TONE: Record<Exclude<SlaState, "none">, "danger" | "warning" | "neutral" | "success"> = {
  breached: "danger",
  warning: "warning",
  paused: "neutral",
  ok: "success",
  met: "success",
};

const WARN_MS = 2 * 3600_000;

/** Re-renders every minute so the time left stays true while the tab is open. */
export function useNow(intervalMs = 60_000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}

type Line = { kind: "firstResponse" | "resolve"; due: string | null; doneAt: string | null; breached: boolean; paused: boolean };

function lineState(l: Line, now: number): SlaState {
  if (!l.due) return "none";
  if (l.breached) return "breached";
  if (l.doneAt) return "met";
  if (l.paused) return "paused";
  const d = Date.parse(l.due);
  if (d <= now) return "breached";
  return d - now <= WARN_MS ? "warning" : "ok";
}

function SlaLine({ line, createdAt, now }: { line: Line; createdAt: string; now: number }) {
  const { t } = useTranslation();
  const name = t(`serviceDesk.sla.${line.kind}`);
  const state = lineState(line, now);
  if (!line.due || state === "none") return null;
  const start = Date.parse(createdAt);
  const end = Date.parse(line.due);
  const at = line.doneAt ? Date.parse(line.doneAt) : now;
  const pct = end > start ? Math.max(0, Math.min(100, Math.round(((at - start) / (end - start)) * 100))) : 100;
  const label =
    state === "met" ? t(line.kind === "firstResponse" ? "serviceDesk.sla.responded" : "serviceDesk.sla.resolved")
    : state === "paused" ? t("serviceDesk.sla.paused")
    : t(`serviceDesk.state.${state}`);
  return (
    <li className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 text-sm">
        <span className="font-medium text-fg">{name}</span>
        <Badge tone={SLA_TONE[state]}>{label}</Badge>
      </div>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 text-xs text-fg-muted tabular-nums">
        <span>
          {t("serviceDesk.sla.due")} {formatDateTime(line.due)}
        </span>
        {!line.doneAt && state !== "paused" && <span className={cn(state === "breached" && "font-semibold text-danger")}>{remainingLabel(line.due, now)}</span>}
        {line.doneAt && <span>{formatDateTime(line.doneAt)}</span>}
      </div>
      <div
        className="h-2 rounded-full bg-surface-selected"
        role="progressbar"
        aria-label={t("serviceDesk.sla.elapsed", { name })}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct}
      >
        <span
          className={cn("block h-2 rounded-full", state === "breached" ? "bg-danger" : state === "warning" ? "bg-warning" : state === "paused" ? "bg-fg-muted" : "bg-success")}
          style={{ width: `${pct}%` }}
        />
      </div>
    </li>
  );
}

export function SlaPanel({ sla, createdAt }: { sla: Sla; createdAt: string }) {
  const { t } = useTranslation();
  const now = useNow();
  const none = !sla.firstResponseDueAt && !sla.resolveDueAt;
  return (
    <section aria-labelledby="sla-panel-h" className="flex flex-col gap-3 rounded-md border border-border p-3">
      <h3 id="sla-panel-h" className="text-sm font-semibold text-fg">
        {t("serviceDesk.sla.title")}
      </h3>
      {none ? (
        <p className="text-sm text-fg-muted">{t("serviceDesk.sla.none")}</p>
      ) : (
        <ul className="flex flex-col gap-4">
          <SlaLine
            now={now}
            createdAt={createdAt}
            line={{ kind: "firstResponse", due: sla.firstResponseDueAt, doneAt: sla.firstRespondedAt, breached: sla.responseBreached, paused: false }}
          />
          <SlaLine
            now={now}
            createdAt={createdAt}
            line={{ kind: "resolve", due: sla.resolveDueAt, doneAt: sla.resolvedAt, breached: sla.resolveBreached, paused: sla.paused }}
          />
        </ul>
      )}
    </section>
  );
}
