// S05 프로젝트 개요: a dashboard of widgets — description, project status, work summary, members, versions, due soon,
// recently changed, mine. Which widgets show and in what order is kept per project (overviewWidgets); people who may
// edit the project change it with 위젯 편집, every change saved right away.
import { useRef, useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { AlertTriangle, ArrowDown, ArrowUp, Check, LayoutGrid, ListTodo, Settings2, Star, Users } from "lucide-react";
import { OVERVIEW_WIDGETS, isApiError, type OverviewWidget, type Project, type QueryFilter, type QuerySpec } from "@/api";
import { useProjectOverview, useSetProjectFavorite, useWpQuery } from "@/api/hooks";
import { useProjectSettingsMutations, useVersions } from "@/api/admin-hooks";
import { Badge, StatusPill, TypeLabel } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Principal } from "@/components/ui/avatar";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { formatDate, formatDateRange } from "@/lib/dates";
import { useLatestSent } from "@/lib/use-latest-sent";
import { encodeSpec } from "@/features/work-packages/view-state";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { RichText } from "@/components/domain/rich-text";

/** Wide widgets take two of the three columns on large screens. */
const WIDE: OverviewWidget[] = ["description", "workSummary", "versions", "dueSoon", "recent", "assignedToMe"];

function Widget({ title, children, className, action }: { title: string; children: ReactNode; className?: string; action?: ReactNode }) {
  return (
    <section aria-label={title} className={cn("flex min-w-0 flex-col rounded-lg border border-border bg-bg", className)}>
      <header className="flex items-center justify-between gap-2 border-b border-border-muted px-4 py-3">
        <h2 className="text-sm font-semibold text-fg">{title}</h2>
        {action}
      </header>
      <div className="p-4">{children}</div>
    </section>
  );
}

const tone = { on_track: "success", at_risk: "warning", off_track: "danger" } as const;
const open: QueryFilter = { field: "status", op: "open" };
const listSpec = (filters: QueryFilter[], sort: QuerySpec["sort"]): QuerySpec => ({
  v: 1,
  filters,
  columns: ["number", "type", "subject", "status", "assignee", "dueDate"],
  sort,
  groupBy: null,
  hierarchy: false,
  sums: false,
  includeSubprojects: false,
});
const LIST_SPECS: Partial<Record<OverviewWidget, QuerySpec>> = {
  dueSoon: listSpec([open, { field: "dueDate", op: "next_days", values: ["14"] }], [{ field: "dueDate", dir: "asc" }]),
  recent: listSpec([{ field: "updatedAt", op: "last_days", values: ["7"] }], [{ field: "updatedAt", dir: "desc" }]),
  assignedToMe: listSpec([{ field: "assignee", op: "eq", values: ["me"] }, open], [{ field: "dueDate", dir: "asc" }]),
};

/** A short work package list with a link to all of them in the project's list. */
function ListWidget({ project, kind }: { project: Project; kind: OverviewWidget }) {
  const { t } = useTranslation();
  const spec = LIST_SPECS[kind]!;
  const q = useWpQuery(spec, project.id, 1, 8);
  const title = t(`overview.widgets.${kind}`);
  return (
    <Widget
      title={title}
      action={
        q.data && q.data.total > 0 ? (
          <Link to={`/projects/${project.identifier}/work-packages?q=${encodeSpec(spec)}`} className="text-sm whitespace-nowrap text-accent hover:underline">
            {t("myPage.seeAll", { count: q.data.total })}
          </Link>
        ) : undefined
      }
    >
      {q.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : q.data.rows.length === 0 ? (
        <p className="text-sm text-fg-muted">{t(`overview.empty.${kind}` as "overview.empty.dueSoon")}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-border-muted">
          {q.data.rows.map(({ wp }) => (
            <li key={wp.id} className="flex flex-wrap items-center gap-x-2 gap-y-1 py-1.5 text-sm">
              <span className="text-xs text-fg-muted tabular-nums">#{wp.number}</span>
              <TypeLabel name={wp.type.name} color={wp.type.color} />
              <Link to={`/projects/${project.identifier}/work-packages/details/${wp.number}/overview`} className="min-w-0 flex-1 truncate hover:underline">
                {wp.subject}
              </Link>
              <StatusPill size="sm" name={wp.status.name} color={wp.status.color} />
              {wp.dueDate && <span className={cn("text-xs tabular-nums", wp._meta.overdue ? "font-semibold text-danger" : "text-fg-muted")}>{formatDate(wp.dueDate)}</span>}
            </li>
          ))}
        </ul>
      )}
    </Widget>
  );
}

function VersionsWidget({ project }: { project: Project }) {
  const { t } = useTranslation();
  const versions = useVersions(project.id);
  const shown = (versions.data ?? []).filter((v) => v.status !== "closed");
  return (
    <Widget title={t("overview.widgets.versions")}>
      {versions.isPending ? (
        <Skeleton className="h-16 w-full" />
      ) : shown.length === 0 ? (
        <p className="text-sm text-fg-muted">{t("overview.empty.versions")}</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {shown.map((v) => {
            const pct = v.workPackageCount ? Math.round((v.closedCount / v.workPackageCount) * 100) : 0;
            return (
              <li key={v.id} className="flex flex-col gap-1">
                <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
                  <span className="font-medium text-fg">{v.name}</span>
                  <span className="text-xs text-fg-muted tabular-nums">
                    {formatDateRange(v.startDate, v.endDate)} · {t("overview.versionDone", { closed: v.closedCount, total: v.workPackageCount })}
                  </span>
                </div>
                <div className="h-2 rounded-full bg-surface-selected" role="progressbar" aria-label={v.name} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
                  <span className="block h-2 rounded-full bg-success" style={{ width: `${pct}%` }} />
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Widget>
  );
}

function WidgetFor({ kind, project }: { kind: OverviewWidget; project: Project }) {
  const { t } = useTranslation();
  const overview = useProjectOverview(project.id);
  switch (kind) {
    case "description":
      return (
        <Widget title={t("overview.description")}>
          {project.descriptionHtml ? <RichText html={project.descriptionHtml} /> : <p className="text-sm text-fg-muted">{t("overview.noDescription")}</p>}
        </Widget>
      );
    case "status":
      return (
        <Widget title={t("overview.status")}>
          {project.status ? <Badge tone={tone[project.status]}>{t(`projects.status.${project.status}`)}</Badge> : <span className="text-sm text-fg-muted">{t("projects.status.none")}</span>}
          {project.statusExplanation && <p className="mt-2 text-sm text-fg">{project.statusExplanation}</p>}
        </Widget>
      );
    case "workSummary":
      return (
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
                  <span className={cn("block text-display font-bold tabular-nums group-hover:underline", overview.data.overdueCount ? "text-danger" : "text-fg")}>{overview.data.overdueCount}</span>
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
      );
    case "members":
      return (
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
      );
    case "versions":
      return <VersionsWidget project={project} />;
    default:
      return <ListWidget project={project} kind={kind} />;
  }
}

/** Choose and order the widgets; every change is saved right away. */
function Editor({ shown, onChange, saving }: { shown: OverviewWidget[]; onChange: (w: OverviewWidget[]) => void; saving: boolean }) {
  const { t } = useTranslation();
  const all = [...shown, ...OVERVIEW_WIDGETS.filter((w) => !shown.includes(w))];
  const move = (w: OverviewWidget, by: -1 | 1) => {
    const i = shown.indexOf(w);
    const next = [...shown];
    [next[i], next[i + by]] = [next[i + by], next[i]];
    onChange(next);
  };
  return (
    <section aria-label={t("overview.editWidgets")} className="mb-6 rounded-lg border border-border bg-surface p-4">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-sm text-fg-muted">{t("overview.editHint")}</p>
        <p className="text-xs text-fg-muted" aria-live="polite">
          {saving ? t("myPage.saving") : t("myPage.saved")}
        </p>
      </div>
      <ul className="flex flex-col gap-1">
        {all.map((w) => {
          const name = t(`overview.widgets.${w}`);
          const i = shown.indexOf(w);
          return (
            <li key={w} className="flex items-center gap-2 rounded-md px-2 py-1 hover:bg-surface-hover">
              <div className="flex-1">
                <Checkbox label={name} checked={i >= 0} onCheckedChange={(c) => onChange(c === true ? [...shown, w] : shown.filter((x) => x !== w))} />
              </div>
              <Button variant="ghost" size="sm" iconOnly icon={<ArrowUp />} aria-label={t("myPage.moveUp", { name })} disabled={i <= 0} onClick={() => move(w, -1)} />
              <Button variant="ghost" size="sm" iconOnly icon={<ArrowDown />} aria-label={t("myPage.moveDown", { name })} disabled={i < 0 || i >= shown.length - 1} onClick={() => move(w, 1)} />
            </li>
          );
        })}
      </ul>
    </section>
  );
}

export function ProjectOverviewPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const project = useCurrentProject();
  useDocumentTitle(project.name);
  const fav = useSetProjectFavorite();
  const m = useProjectSettingsMutations(project.id);
  const [editing, setEditing] = useState(false);
  const canEdit = can(project, "project.edit");
  // quick edits build on each other, and saves go out one after another
  const { latest, send } = useLatestSent<OverviewWidget[]>(project.overviewWidgets, (w) => w.join(","));
  const shown = latest.current;
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  const [pending, setPending] = useState(0);
  const save = (w: OverviewWidget[]) => {
    send(w);
    setPending((n) => n + 1);
    queue.current = queue.current
      .then(() => m.update.mutateAsync({ overviewWidgets: w }))
      .catch((e) => toast({ tone: "danger", title: isApiError(e) ? e.message : t("app.errorTitle") }))
      .finally(() => setPending((n) => n - 1));
  };

  return (
    <PageBody>
      <PageHeader
        title={project.name}
        breadcrumbs={[{ label: t("projects.title"), href: "/projects" }, ...(project.parent ? [{ label: project.parent.name, href: `/projects/${project.parent.identifier}` }] : []), { label: t("nav.overview") }]}
        actions={
          <>
            {canEdit && (
              <Button icon={editing ? <Check /> : <Settings2 />} aria-pressed={editing} onClick={() => setEditing((e) => !e)}>
                {editing ? t("myPage.done") : t("overview.editWidgets")}
              </Button>
            )}
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
      {editing && <Editor shown={shown} onChange={save} saving={pending > 0} />}
      {shown.length === 0 ? (
        <EmptyState icon={<LayoutGrid />} title={t("overview.noWidgets")}>
          {canEdit ? t("overview.noWidgetsBody") : null}
        </EmptyState>
      ) : (
        <div className="grid grid-flow-row-dense grid-cols-1 gap-4 lg:grid-cols-3">
          {shown.map((w) => (
            <div key={w} className={cn("min-w-0", WIDE.includes(w) && "lg:col-span-2")}>
              <WidgetFor kind={w} project={project} />
            </div>
          ))}
        </div>
      )}
    </PageBody>
  );
}
