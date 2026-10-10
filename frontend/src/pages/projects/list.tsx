// S03 프로젝트 목록
import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { ArchiveRestore, FolderKanban, Globe, Lock, MoreHorizontal, Plus, Search, Star, Trash2 } from "lucide-react";
import type { Me, Project, ProjectListParams, ProjectStatus } from "@/api";
import { isApiError } from "@/api";
import { useProjects, useSetProjectFavorite } from "@/api/hooks";
import { useProjectLifecycle } from "@/api/admin-hooks";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badges";
import { Field, Input } from "@/components/ui/field";
import { Select } from "@/components/ui/select";
import { Banner, EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Pagination } from "@/components/ui/nav";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { relativeTime } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";

const FILTERS = ["active", "mine", "favorite", "archived"] as const;
const statusTone = { on_track: "success", at_risk: "warning", off_track: "danger" } as const;

function StatusBadge({ status }: { status: ProjectStatus | null }) {
  const { t } = useTranslation();
  if (!status) return <span className="text-xs text-fg-muted">{t("projects.status.none")}</span>;
  return <Badge tone={statusTone[status]}>{t(`projects.status.${status}`)}</Badge>;
}

function FavoriteButton({ project }: { project: Project }) {
  const { t } = useTranslation();
  const fav = useSetProjectFavorite();
  const on = fav.isPending ? !project.favorite : project.favorite;
  return (
    <Button
      variant="ghost"
      size="sm"
      iconOnly
      aria-pressed={on}
      aria-label={on ? t("projects.favoriteRemove", { name: project.name }) : t("projects.favoriteAdd", { name: project.name })}
      icon={<Star className={cn(on && "fill-warning-strong text-warning")} />}
      onClick={() => fav.mutate({ id: project.id, favorite: !project.favorite })}
    />
  );
}

/** Administrators: restore an archived project, or delete one (the identifier typed again). */
function ProjectActions({ project }: { project: Project }) {
  const { t } = useTranslation();
  const toast = useToast();
  const m = useProjectLifecycle();
  const [deleting, setDeleting] = useState(false);
  const [typed, setTyped] = useState("");
  const fail = (e: unknown) => toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle"), description: isApiError(e) && e.message !== e.title ? e.message : undefined });
  const fieldErr = isApiError(m.remove.error) ? m.remove.error.fieldErrors.find((f) => f.field === "confirmIdentifier")?.message : undefined;
  const otherErr = isApiError(m.remove.error) && !m.remove.error.fieldErrors.length ? m.remove.error : null;
  return (
    <>
      <Menu
        align="end"
        trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("projects.actionsFor", { name: project.name })} icon={<MoreHorizontal />} />}
        items={[
          ...(project.archivedAt
            ? [{ label: t("projects.unarchive"), icon: <ArchiveRestore />, onSelect: () => m.unarchive.mutate(project.id, { onSuccess: () => toast({ tone: "success", title: t("projects.unarchived", { name: project.name }) }), onError: fail }) }]
            : []),
          { label: t("projects.delete"), icon: <Trash2 />, danger: true, onSelect: () => (m.remove.reset(), setTyped(""), setDeleting(true)) },
        ]}
      />
      <Dialog
        open={deleting}
        onOpenChange={setDeleting}
        title={t("projects.deleteTitle", { name: project.name })}
        footer={
          <>
            <Button onClick={() => setDeleting(false)}>{t("app.cancel")}</Button>
            <Button
              variant="danger"
              icon={<Trash2 />}
              loading={m.remove.isPending}
              disabled={typed.trim() !== project.identifier}
              onClick={() =>
                m.remove.mutate(
                  { id: project.id, confirmIdentifier: typed },
                  { onSuccess: () => (setDeleting(false), toast({ tone: "success", title: t("projects.deleted", { name: project.name }) })) },
                )
              }
            >
              {t("projects.deleteConfirm")}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <Banner tone="danger">{t("projects.deleteBody")}</Banner>
          {otherErr && <Banner tone="warning" title={otherErr.title}>{otherErr.message !== otherErr.title ? otherErr.message : null}</Banner>}
          <Field label={t("projects.deleteType", { identifier: project.identifier })} error={fieldErr}>
            <Input autoComplete="off" spellCheck={false} value={typed} onChange={(e) => setTyped(e.target.value)} />
          </Field>
        </div>
      </Dialog>
    </>
  );
}

export function ProjectsPage({ me }: { me: Me }) {
  const { t } = useTranslation();
  useDocumentTitle(t("projects.title"));
  const [params, setParams] = useSearchParams();
  const filter = (FILTERS as readonly string[]).includes(params.get("filter") ?? "") ? (params.get("filter") as ProjectListParams["filter"]) : "active";
  const status = (params.get("status") as ProjectStatus | null) ?? undefined;
  const page = Number(params.get("page") ?? 1);
  const pageSize = Number(params.get("size") ?? 20);
  const [q, setQ] = useState(params.get("q") ?? "");
  useEffect(() => {
    const id = setTimeout(() => setParams((p) => (q ? p.set("q", q) : p.delete("q"), p.delete("page"), p), { replace: true }), 250);
    return () => clearTimeout(id);
  }, [q, setParams]);
  const list = useProjects({ filter, status, q: params.get("q") ?? undefined, page, pageSize });
  const set = (k: string, v: string | null) => setParams((p) => (v ? p.set(k, v) : p.delete(k), k !== "page" && p.delete("page"), p));

  return (
    <PageBody>
      <PageHeader
        title={t("projects.title")}
        actions={
          me.globalPermissions.includes("global.create_projects") && (
            <Button asChild variant="primary" icon={<Plus />}>
              <Link to="/projects/new">{t("projects.new")}</Link>
            </Button>
          )
        }
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div role="group" aria-label={t("projects.title")} className="inline-flex flex-wrap rounded-md border border-border bg-surface p-0.5">
          {FILTERS.map((f) => (
            <button
              key={f}
              type="button"
              aria-pressed={filter === f}
              onClick={() => set("filter", f === "active" ? null : f)}
              className={cn("h-7 rounded-sm px-3 text-sm text-fg-muted hover:text-fg", filter === f && "bg-bg font-medium text-fg shadow-card")}
            >
              {t(`projects.filter${f[0].toUpperCase()}${f.slice(1)}` as "projects.filterActive")}
            </button>
          ))}
        </div>
        <Select
          size="sm"
          className="w-36"
          aria-label={t("projects.columnStatus")}
          value={status ?? "all"}
          onValueChange={(v) => set("status", v === "all" ? null : v)}
          options={[
            { value: "all", label: t("projects.statusAll") },
            { value: "on_track", label: t("projects.status.on_track") },
            { value: "at_risk", label: t("projects.status.at_risk") },
            { value: "off_track", label: t("projects.status.off_track") },
          ]}
        />
        <label className="relative ml-auto w-full sm:w-64">
          <span className="sr-only">{t("projects.searchLabel")}</span>
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-fg-muted" />
          <Input size="sm" type="search" className="pl-8" placeholder={t("projects.searchLabel")} value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
      </div>

      {list.isPending ? (
        <div className="flex flex-col gap-2 rounded-md border border-border p-4">
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-6 w-full" />
          ))}
        </div>
      ) : list.isError ? (
        <ErrorState error={list.error} onRetry={() => list.refetch()} />
      ) : list.data.items.length === 0 ? (
        <div className="rounded-md border border-border">
          <EmptyState icon={<FolderKanban />} title={t("projects.empty")}>
            {t("projects.emptyBody")}
          </EmptyState>
        </div>
      ) : (
        <>
          {/* table ≥ md */}
          <div className="relative hidden overflow-x-auto rounded-md border border-border md:block">
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr className="h-row border-b border-border bg-surface text-left text-xs font-semibold text-fg-muted">
                  <th scope="col" className="w-10 px-2">
                    <span className="sr-only">{t("projects.filterFavorite")}</span>
                  </th>
                  <th scope="col" className="px-3">{t("projects.columnName")}</th>
                  <th scope="col" className="w-32 px-3">{t("projects.columnStatus")}</th>
                  <th scope="col" className="w-28 px-3">{t("projects.columnVisibility")}</th>
                  <th scope="col" className="w-36 px-3">{t("projects.columnActivity")}</th>
                  {me.isAdmin && (
                    <th scope="col" className="w-12 px-2">
                      <span className="sr-only">{t("app.more")}</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {list.data.items.map((p) => (
                  <tr key={p.id} className="h-row border-b border-border-muted last:border-b-0 hover:bg-surface">
                    <td className="px-2">
                      <FavoriteButton project={p} />
                    </td>
                    <td className="px-3 py-1.5">
                      <Link to={`/projects/${p.identifier}`} className="font-medium text-fg hover:underline">
                        {p.name}
                      </Link>
                      {p.parent && <span className="block text-xs text-fg-muted">{t("projects.subprojectOf", { parent: p.parent.name })}</span>}
                    </td>
                    <td className="px-3">
                      {p.archivedAt ? <Badge tone="warning">{t("projects.archived")}</Badge> : <StatusBadge status={p.status} />}
                    </td>
                    <td className="px-3 text-fg-muted">
                      <span className="inline-flex items-center gap-1.5 [&_svg]:size-3.5">
                        {p.isPublic ? <Globe aria-hidden="true" /> : <Lock aria-hidden="true" />}
                        {p.isPublic ? t("projects.public") : t("projects.private")}
                      </span>
                    </td>
                    <td className="px-3 text-fg-muted" title={p.latestActivityAt}>
                      {relativeTime(p.latestActivityAt)}
                    </td>
                    {me.isAdmin && (
                      <td className="px-2">
                        <ProjectActions project={p} />
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {/* cards < md */}
          <ul className="flex flex-col gap-2 md:hidden">
            {list.data.items.map((p) => (
              <li key={p.id} className="relative flex items-start gap-2 rounded-md border border-border p-3">
                <FavoriteButton project={p} />
                <div className="min-w-0 flex-1">
                  <Link to={`/projects/${p.identifier}`} className="font-medium text-fg hover:underline">
                    {p.name}
                  </Link>
                  {p.parent && <span className="block text-xs text-fg-muted">{t("projects.subprojectOf", { parent: p.parent.name })}</span>}
                  <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs text-fg-muted">
                    {p.archivedAt ? <Badge tone="warning">{t("projects.archived")}</Badge> : <StatusBadge status={p.status} />}
                    <span>{p.isPublic ? t("projects.public") : t("projects.private")}</span>
                    <span>{relativeTime(p.latestActivityAt)}</span>
                  </div>
                </div>
                {me.isAdmin && <ProjectActions project={p} />}
              </li>
            ))}
          </ul>
          <div className="mt-3">
            <Pagination page={page} pageSize={pageSize} total={list.data.total} onPage={(n) => set("page", String(n))} onPageSize={(s) => set("size", String(s))} />
          </div>
        </>
      )}
    </PageBody>
  );
}
