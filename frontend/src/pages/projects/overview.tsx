// S05 프로젝트 개요
import type { ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { AlertTriangle, ListTodo, Star, Users } from "lucide-react";
import { useProjectOverview, useSetProjectFavorite } from "@/api/hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Principal } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { useCurrentProject } from "@/app/project";
import { RichText } from "@/components/domain/rich-text";

function Widget({ title, children, className }: { title: string; children: ReactNode; className?: string }) {
  return (
    <section aria-label={title} className={cn("rounded-lg border border-border bg-bg", className)}>
      <h2 className="border-b border-border-muted px-4 py-3 text-sm font-semibold text-fg">{title}</h2>
      <div className="p-4">{children}</div>
    </section>
  );
}

const tone = { on_track: "success", at_risk: "warning", off_track: "danger" } as const;

export function ProjectOverviewPage() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  useDocumentTitle(project.name);
  const overview = useProjectOverview(project.id);
  const fav = useSetProjectFavorite();

  return (
    <PageBody>
      <PageHeader
        title={project.name}
        breadcrumbs={[{ label: t("projects.title"), href: "/projects" }, ...(project.parent ? [{ label: project.parent.name, href: `/projects/${project.parent.identifier}` }] : []), { label: t("nav.overview") }]}
        actions={
          <>
            <Button
              aria-pressed={project.favorite}
              icon={<Star className={cn(project.favorite && "fill-warning-strong text-warning")} />}
              onClick={() => fav.mutate({ id: project.id, favorite: !project.favorite })}
            >
              {t("projects.favorite")}
            </Button>
            <Button asChild variant="primary" icon={<ListTodo />}>
              <Link to={`/projects/${project.identifier}/work-packages`}>{t("nav.workPackages")}</Link>
            </Button>
          </>
        }
      />
      {project.archivedAt && (
        <p className="mb-3">
          <Badge tone="warning">{t("projects.archived")}</Badge>
        </p>
      )}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="flex flex-col gap-4 lg:col-span-2">
          <Widget title={t("overview.description")}>
            {project.descriptionHtml ? <RichText html={project.descriptionHtml} /> : <p className="text-sm text-fg-muted">{t("overview.noDescription")}</p>}
          </Widget>
          <Widget title={t("overview.workSummary")}>
            {overview.isPending ? (
              <Skeleton className="h-16 w-full" />
            ) : overview.isError ? (
              <ErrorState error={overview.error} onRetry={() => overview.refetch()} />
            ) : (
              <div className="flex flex-col gap-4">
                <div className="flex flex-wrap gap-6">
                  <Link to={`/projects/${project.identifier}/work-packages?view=default%3Aopen`} className="group">
                    <span className="block text-display font-bold text-fg tabular-nums group-hover:underline">{overview.data.openCount}</span>
                    <span className="text-sm text-fg-muted">{t("overview.open")}</span>
                  </Link>
                  <Link to={`/projects/${project.identifier}/work-packages?view=default%3Aoverdue`} className="group">
                    <span className={cn("block text-display font-bold tabular-nums group-hover:underline", overview.data.overdueCount ? "text-danger" : "text-fg")}>
                      {overview.data.overdueCount}
                    </span>
                    <span className="inline-flex items-center gap-1 text-sm text-fg-muted">
                      {overview.data.overdueCount > 0 && <AlertTriangle className="size-3.5 text-danger" aria-hidden="true" />}
                      {t("overview.overdue")}
                    </span>
                  </Link>
                </div>
                <div>
                  <h3 className="mb-2 text-xs font-semibold text-fg-muted">{t("overview.byStatus")}</h3>
                  <ul className="flex flex-col gap-1.5">
                    {overview.data.countsByStatus.map(({ status, count }) => {
                      const max = Math.max(...overview.data.countsByStatus.map((c) => c.count));
                      return (
                        <li key={status.id} className="grid grid-cols-[80px_1fr_32px] items-center gap-2 text-sm">
                          <span className="truncate text-fg">{status.name}</span>
                          <span className="h-2 rounded-full bg-surface-selected" aria-hidden="true">
                            <span className="block h-2 rounded-full" style={{ width: `${(count / max) * 100}%`, backgroundColor: status.color }} />
                          </span>
                          <span className="text-right text-fg-muted tabular-nums">{count}</span>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              </div>
            )}
          </Widget>
        </div>
        <div className="flex flex-col gap-4">
          <Widget title={t("overview.status")}>
            {project.status ? <Badge tone={tone[project.status]}>{t(`projects.status.${project.status}`)}</Badge> : <span className="text-sm text-fg-muted">{t("projects.status.none")}</span>}
            {project.statusExplanation && <p className="mt-2 text-sm text-fg">{project.statusExplanation}</p>}
          </Widget>
          <Widget title={t("overview.members")}>
            {overview.isPending ? (
              <Skeleton className="h-12 w-full" />
            ) : overview.data ? (
              <ul className="flex flex-col gap-2">
                {overview.data.members.slice(0, 8).map((m) => (
                  <li key={m.user.id} className="flex items-center justify-between gap-2">
                    <Principal id={m.user.id} name={m.user.name} />
                    <span className="truncate text-xs text-fg-muted">{m.roles.join(", ")}</span>
                  </li>
                ))}
              </ul>
            ) : null}
            {project.myPermissions.includes("project.view_members") && (
              <Link to={`/projects/${project.identifier}/members`} className="mt-3 inline-flex items-center gap-1 text-sm text-accent hover:underline">
                <Users className="size-4" aria-hidden="true" />
                {t("overview.allMembers")}
              </Link>
            )}
          </Widget>
        </div>
      </div>
    </PageBody>
  );
}
