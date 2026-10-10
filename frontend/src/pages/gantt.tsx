// S13 Gantt 차트: saved views from the sidebar, filters, zoom, split detail panel. Without a project (/gantt) it spans
// every project the user can see, and a bar opens the item in its project, as the global list does.
import { useState } from "react";
import { Outlet, useMatch, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { CalendarSearch, Filter, GanttChart as GanttIcon } from "lucide-react";
import { useFilterOptions, useWpQuery } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Badge, Counter } from "@/components/ui/badges";
import { Segmented } from "@/components/ui/choice";
import { EmptyState } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageHeader, useDocumentTitle } from "@/app/page";
import { useCurrentProject, useOptionalProject } from "@/app/project";
import { FilterPanel } from "@/features/work-packages/filter-panel";
import { useListState } from "@/features/work-packages/view-state";
import { WorkPackageDetail, asDetailTab } from "@/features/work-packages/detail";
import { GanttChart, type Zoom } from "@/features/gantt/gantt-chart";

export function GanttPage() {
  const { t } = useTranslation();
  const project = useOptionalProject();
  const state = useListState(project?.id);
  const [zoom, setZoom] = useState<Zoom>("auto");
  const [filtersOpen, setFiltersOpen] = useState(false);
  const spec = state.spec ? { ...state.spec, timeline: { visible: true, zoom } } : undefined;
  const result = useWpQuery(spec, project?.id, 1, 200);
  const options = useFilterOptions(project?.id).data;
  const base = project ? `/projects/${project.identifier}/gantt` : "/gantt";
  const details = useMatch(`${base}/details/:number/*`);
  useDocumentTitle(state.view ? `${t("nav.gantt")} · ${state.view.name}` : t("nav.gantt"));
  const filterCount = (state.spec?.filters.length ?? 0) + (state.spec?.text ? 1 : 0);

  const scrollToToday = () => {
    const chart = document.querySelector<HTMLElement>("[data-gantt-scroll]");
    const line = chart?.querySelector<HTMLElement>("[data-gantt-today]");
    if (chart && line) chart.scrollTo({ left: Math.max(0, line.offsetLeft - chart.clientWidth / 2), behavior: "smooth" });
  };

  return (
    <div className="flex min-h-full">
      <div className="min-w-0 flex-1 px-content pb-10 md:px-8">
        <PageHeader
          breadcrumbs={project ? [{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("nav.gantt") }] : [{ label: t("nav.gantt") }]}
          title={
            <span className="inline-flex items-center gap-2">
              {state.view?.name ?? t("nav.gantt")}
              {state.dirty && <Badge tone="warning">{t("wp.unsavedView")}</Badge>}
            </span>
          }
          actions={
            <>
              <Segmented
                aria-label={t("gantt.zoom")}
                value={zoom}
                onValueChange={(v) => setZoom(v as Zoom)}
                options={[
                  { value: "auto", label: t("gantt.zoomAuto") },
                  { value: "day", label: t("gantt.zoomDay") },
                  { value: "week", label: t("gantt.zoomWeek") },
                  { value: "month", label: t("gantt.zoomMonth") },
                ]}
              />
              <Button icon={<CalendarSearch />} onClick={scrollToToday}>
                {t("gantt.today")}
              </Button>
              <Button aria-expanded={filtersOpen} icon={<Filter />} onClick={() => setFiltersOpen((o) => !o)}>
                {t("wp.filters")} {filterCount > 0 && <Counter>{filterCount}</Counter>}
              </Button>
            </>
          }
        />
        {filtersOpen && state.spec && <FilterPanel spec={state.spec} onSpec={state.setSpec} options={options} />}
        {!spec || result.isPending ? (
          <Skeleton className="h-96 w-full" />
        ) : result.isError ? (
          <ErrorState error={result.error} onRetry={() => result.refetch()} />
        ) : result.data.rows.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState icon={<GanttIcon />} title={t("wp.empty")}>
              {t("wp.emptyBody")}
            </EmptyState>
          </div>
        ) : (
          <>
            <GanttChart result={result.data} zoom={zoom} detailsHref={(wp) =>
                project && wp.project.id === project.id ? `${base}/details/${wp.number}/overview${state.search}` : `/projects/${wp.project.identifier}/work-packages/${wp.number}/activity`
              } />
            <p className="mt-2 text-xs text-fg-muted">{t("gantt.help")}</p>
          </>
        )}
      </div>
      {details && (
        <aside
          aria-label={t("wp.detailsPanel")}
          className="fixed inset-x-0 top-header bottom-0 z-(--pms-z-modal) flex flex-col bg-bg md:sticky md:inset-auto md:top-header md:z-auto md:h-[calc(100dvh-var(--pms-layout-header-h))] md:w-split md:shrink-0 md:border-l md:border-border-muted"
        >
          <Outlet />
        </aside>
      )}
    </div>
  );
}

export function GanttDetailRoute() {
  const { number, tab } = useParams();
  const project = useCurrentProject();
  const n = Number(number);
  const base = `/projects/${project.identifier}/gantt`;
  const current = asDetailTab(tab, "overview");
  return (
    <WorkPackageDetail
      key={n}
      number={n}
      mode="panel"
      tab={current}
      tabHref={(x) => `${base}/details/${n}/${x}`}
      closeHref={base}
      fullHref={`/projects/${project.identifier}/work-packages/${n}/${current === "overview" ? "activity" : current}`}
      afterDeleteHref={base}
    />
  );
}
