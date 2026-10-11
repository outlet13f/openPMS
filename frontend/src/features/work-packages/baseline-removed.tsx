// S06 기준선: items the list matched at the baseline point but no longer does, under the list with what changed (then → now).
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { BaselineRemoved } from "@/api";

export function BaselineRemovedSection({ items, columnLabel }: { items: BaselineRemoved[]; columnLabel: (column: string) => string }) {
  const { t } = useTranslation();
  if (items.length === 0) return null;
  return (
    <section aria-labelledby="baseline-removed-title" className="mt-4 rounded-md border border-border">
      <div className="border-b border-border bg-surface-sunken px-3 py-2">
        <h2 id="baseline-removed-title" className="text-sm font-semibold">
          {t("baseline.removedTitle", { count: items.length })}
        </h2>
        <p className="text-xs text-fg-muted">
          {t("baseline.removedHint")}
          {items.length >= 200 && ` ${t("baseline.removedMore", { count: items.length })}`}
        </p>
      </div>
      <ul className="divide-y divide-border">
        {items.map((r) => (
          <li key={r.id} className="flex flex-col gap-1 px-3 py-2 text-sm sm:flex-row sm:items-baseline sm:gap-3">
            <span className="flex min-w-0 items-baseline gap-2 sm:w-2/5">
              {r.project ? (
                <Link to={`/projects/${r.project.identifier}/work-packages/${r.number}/activity`} className="text-accent tabular-nums hover:underline">
                  #{r.number}
                </Link>
              ) : (
                <span className="tabular-nums">#{r.number}</span>
              )}
              <span className="truncate line-through" title={r.subject}>
                {r.subject}
              </span>
            </span>
            <span className="flex min-w-0 flex-wrap gap-x-4 gap-y-0.5 text-xs text-fg-muted">
              {Object.keys(r.then).map((c) => (
                <span key={c}>
                  <span className="font-medium text-fg">{columnLabel(c)}</span>{" "}
                  {t("baseline.change", { then: r.then[c] ?? t("baseline.empty"), now: r.now[c] ?? t("baseline.empty") })}
                </span>
              ))}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
