// 프로젝트 개요의 서비스 데스크 카드: open tickets per assignment group, deadlines of today missed, and the average
// first response of the last 30 days (wall-clock minutes).
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { Project } from "@/api";
import { useServiceDeskSummary } from "@/api/hooks";
import { Badge } from "@/components/ui/badges";
import { Skeleton } from "@/components/ui/spinner";

export function SummaryCard({ project }: { project: Project }) {
  const { t } = useTranslation();
  const q = useServiceDeskSummary(project.identifier);
  const s = q.data;
  return (
    <section aria-labelledby="sd-summary-h" className="mb-4 flex min-w-0 flex-col rounded-lg border border-border bg-bg">
      <header className="flex items-center justify-between gap-2 border-b border-border-muted px-4 py-3">
        <h2 id="sd-summary-h" className="text-sm font-semibold text-fg">
          {t("serviceDesk.summary.title")}
        </h2>
        <Link to={`/projects/${project.identifier}/work-packages`} className="text-sm text-accent hover:underline">
          {t("serviceDesk.summary.openList")}
        </Link>
      </header>
      <div className="p-4">
        {q.isPending ? (
          <Skeleton className="h-16 w-full" />
        ) : q.isError || !s ? (
          <p className="text-sm text-fg-muted">{t("app.errorTitle")}</p>
        ) : (
          <div className="grid grid-cols-1 gap-6 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)]">
            <div>
              <h3 className="mb-2 text-xs font-semibold text-fg-muted">{t("serviceDesk.summary.openByGroup")}</h3>
              {s.openByGroup.length === 0 ? (
                <p className="text-sm text-fg-muted">{t("serviceDesk.summary.noOpen")}</p>
              ) : (
                <ul className="flex flex-col gap-1 text-sm">
                  {s.openByGroup.map((g) => (
                    <li key={g.group?.id ?? "none"} className="flex items-baseline justify-between gap-3">
                      <span className={g.group ? "text-fg" : "text-fg-muted"}>{g.group?.name ?? t("serviceDesk.summary.noGroup")}</span>
                      <span className="font-medium text-fg tabular-nums">{g.count}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div>
              <h3 className="mb-2 text-xs font-semibold text-fg-muted">{t("serviceDesk.summary.breachedToday")}</h3>
              {s.breachedToday > 0 ? (
                <Badge tone="danger" className="h-7 px-2 text-base tabular-nums">
                  {s.breachedToday}
                </Badge>
              ) : (
                <p className="text-2xl text-fg tabular-nums">0</p>
              )}
            </div>
            <div>
              <h3 className="mb-2 text-xs font-semibold text-fg-muted">
                {t("serviceDesk.summary.avgFirstResponse")} <span className="font-normal">({t("serviceDesk.summary.avgHint")})</span>
              </h3>
              <p className="text-2xl text-fg tabular-nums">
                {s.avgFirstResponseMinutes === null ? t("serviceDesk.summary.none") : t("serviceDesk.summary.minutes", { n: s.avgFirstResponseMinutes })}
              </p>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
