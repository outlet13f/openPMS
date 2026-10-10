// S06 작업 목록 (project or global) with the split panel outlet for S07/S09.
import { useState } from "react";
import { Outlet, useMatch, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { Columns3, Download, Filter, FolderTree, ListTodo, MoreHorizontal, Pencil, Plus, RotateCcw, Save, Star, Trash2 } from "lucide-react";
import type { ColumnKey, Project, QuerySpec, SavedQuery } from "@/api";
import { isApiError } from "@/api";
import { useFilterOptions, useSavedQueryMutations, useWpForm, useWpQuery } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Badge, Counter, TypeLabel } from "@/components/ui/badges";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Pagination } from "@/components/ui/nav";
import { Dialog, Menu, type MenuEntry } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { ErrorState, PageHeader, useDocumentTitle } from "@/app/page";
import { useOptionalProject, can } from "@/app/project";
import { WorkPackageTable } from "@/features/work-packages/table";
import { cfColumn, useColumnLabel } from "@/features/work-packages/custom-fields";
import { FilterPanel } from "@/features/work-packages/filter-panel";
import { ExportDialog } from "@/features/work-packages/export-dialog";
import { useListState } from "@/features/work-packages/view-state";

const ALL_COLUMNS: ColumnKey[] = ["number", "subject", "type", "status", "priority", "assignee", "responsible", "author", "version", "startDate", "dueDate", "duration", "doneRatio", "updatedAt", "project"];

function SaveViewDialog({ open, onOpenChange, spec, project, onSaved }: { open: boolean; onOpenChange: (o: boolean) => void; spec: QuerySpec; project: Project | null; onSaved: (id: string) => void }) {
  const { t } = useTranslation();
  const { create } = useSavedQueryMutations(project?.id);
  const [name, setName] = useState("");
  const [isPublic, setIsPublic] = useState(false);
  const err = isApiError(create.error) ? create.error.fieldErrors[0]?.message ?? create.error.message : undefined;
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="sm"
      title={t("wp.saveViewAs")}
      footer={
        <>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            loading={create.isPending}
            onClick={async () => {
              const q = await create.mutateAsync({ projectId: project?.id ?? null, name, isPublic, spec, view: "table" }).catch(() => null);
              if (q) onSaved(q.id);
            }}
          >
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("wp.viewName")} required error={err}>
          <Input data-autofocus value={name} maxLength={255} onChange={(e) => setName(e.target.value)} />
        </Field>
        {project && can(project, "query.manage_public") && <Checkbox label={t("wp.viewPublic")} checked={isPublic} onCheckedChange={(v) => setIsPublic(v === true)} />}
      </div>
    </Dialog>
  );
}

function RenameViewDialog({ view, projectId, onClose }: { view: SavedQuery; projectId?: string; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const { update } = useSavedQueryMutations(projectId);
  const [name, setName] = useState(view.name);
  const err = isApiError(update.error) ? update.error.fieldErrors[0]?.message ?? update.error.message : undefined;
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={t("wp.renameView")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            loading={update.isPending}
            onClick={async () => {
              const q = await update.mutateAsync({ id: view.id, patch: { name } }).catch(() => null);
              if (q) {
                toast({ tone: "success", title: t("wp.viewRenamed") });
                onClose();
              }
            }}
          >
            {t("app.save")}
          </Button>
        </>
      }
    >
      <Field label={t("wp.viewName")} required error={err}>
        <Input data-autofocus value={name} maxLength={255} onChange={(e) => setName(e.target.value)} />
      </Field>
    </Dialog>
  );
}

export function WorkPackagesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const toast = useToast();
  const project = useOptionalProject();
  const base = project ? `/projects/${project.identifier}/work-packages` : "/work-packages";
  const state = useListState(project?.id);
  const { spec, view, dirty, page, pageSize, search } = state;
  const result = useWpQuery(spec, project?.id, page, pageSize);
  const options = useFilterOptions(project?.id).data;
  const columnLabel = useColumnLabel(options);
  // built-in columns, then the custom fields on in this scope
  const allColumns: ColumnKey[] = [...ALL_COLUMNS, ...(options?.customFields ?? []).map((f) => cfColumn(f.id))];
  const form = useWpForm(project && can(project, "wp.create") ? project.id : undefined);
  const mutations = useSavedQueryMutations(project?.id);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [saveAs, setSaveAs] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [exporting, setExporting] = useState(false);
  const details = useMatch(`${base}/details/:number/*`);
  const creating = useMatch(`${base}/new`);
  const panelOpen = !!(details || creating);
  const selected = details ? Number(details.params.number) : undefined;
  useDocumentTitle(view ? `${view.name} · ${project?.name ?? t("wp.listTitle")}` : undefined);

  if (state.views.isError) return <ErrorState error={state.views.error} onRetry={() => state.views.refetch()} />;
  const canCreate = !!project && can(project, "wp.create");
  const types = project && canCreate ? (form.data?.types ?? []) : [];
  const filterCount = (spec?.filters.length ?? 0) + (spec?.text ? 1 : 0);

  const viewMenu: MenuEntry[] = spec
    ? [
        ...(dirty && view && view.canEdit
          ? [{ label: t("wp.saveView"), icon: <Save />, onSelect: () => mutations.update.mutate({ id: view.id, patch: { spec } }, { onSuccess: () => (toast({ tone: "success", title: t("wp.viewSaved") }), state.resetToView(view.id)) }) }]
          : []),
        ...(project && can(project, "query.save") ? [{ label: t("wp.saveViewAs"), icon: <Save />, onSelect: () => setSaveAs(true) }] : []),
        ...(dirty ? [{ label: t("wp.resetView"), icon: <RotateCcw />, onSelect: () => state.resetToView(view?.id) }] : []),
        ...(!project || can(project, "wp.export") ? [{ label: t("export.open"), icon: <Download />, onSelect: () => setExporting(true) }] : []),
        {
          kind: "sub" as const,
          label: t("wp.columnsMenu"),
          icon: <Columns3 />,
          items: allColumns.filter((c) => c !== "subject").map((c) => ({
            label: columnLabel(c),
            checked: spec.columns.includes(c),
            onSelect: () => state.setSpec({ ...spec, columns: spec.columns.includes(c) ? spec.columns.filter((x) => x !== c) : allColumns.filter((x) => x === c || spec.columns.includes(x)) }),
          })),
        },
        ...(view && view.kind !== "default"
          ? [
              { kind: "separator" as const },
              { label: view.favorite ? t("wp.unfavoriteView") : t("wp.favoriteView"), icon: <Star />, onSelect: () => mutations.favorite.mutate({ id: view.id, favorite: !view.favorite }) },
              ...(view.canEdit
                ? [
                    { label: t("wp.renameView"), icon: <Pencil />, onSelect: () => setRenaming(true) },
                    { label: t("wp.deleteView"), icon: <Trash2 />, danger: true, onSelect: () => mutations.remove.mutate(view.id, { onSuccess: () => navigate(base) }) },
                  ]
                : []),
            ]
          : []),
      ]
    : [];

  const panel = (
    <aside
      aria-label={t("wp.detailsPanel")}
      className={cn(
        "flex flex-col bg-bg",
        "fixed inset-x-0 top-header bottom-0 z-(--pms-z-modal)",
        "md:sticky md:inset-auto md:top-header md:z-auto md:h-[calc(100dvh-var(--pms-layout-header-h))] md:w-split md:shrink-0 md:border-l md:border-border-muted",
      )}
    >
      <Outlet />
    </aside>
  );

  return (
    <div className="flex min-h-full">
      <div className="min-w-0 flex-1 px-content pb-16 md:px-8">
        <PageHeader
          breadcrumbs={project ? [{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("wp.listTitle") }] : [{ label: t("wp.listTitle") }]}
          title={
            <span className="inline-flex items-center gap-2">
              {view?.name ?? <Skeleton className="h-7 w-40" />}
              {dirty && <Badge tone="warning">{t("wp.unsavedView")}</Badge>}
            </span>
          }
          actions={
            <>
              {canCreate && types.length > 0 && (
                <Menu
                  trigger={
                    <Button variant="primary" icon={<Plus />}>
                      {t("wp.create")}
                    </Button>
                  }
                  items={types.map((ty) => ({ label: <TypeLabel name={ty.name} color={ty.color} />, onSelect: () => navigate(`${base}/new${search ? `${search}&` : "?"}type=${ty.id}`) }))}
                />
              )}
              <Button aria-expanded={filtersOpen} aria-controls="wp-filters" icon={<Filter />} onClick={() => setFiltersOpen((o) => !o)}>
                {t("wp.filters")} {filterCount > 0 && <Counter>{filterCount}</Counter>}
              </Button>
              {project && spec && (
                <Button aria-pressed={spec.includeSubprojects} icon={<FolderTree />} onClick={() => state.setSpec({ ...spec, includeSubprojects: !spec.includeSubprojects })}>
                  <span className="hidden xl:inline">{t("wp.includeSubprojects")}</span>
                  <span className="sr-only xl:hidden">{t("wp.includeSubprojects")}</span>
                </Button>
              )}
              {spec && spec.hierarchy !== undefined && (
                <Button aria-pressed={spec.hierarchy} icon={<ListTodo />} onClick={() => state.setSpec({ ...spec, hierarchy: !spec.hierarchy })}>
                  <span className="hidden xl:inline">{t("wp.hierarchy")}</span>
                  <span className="sr-only xl:hidden">{t("wp.hierarchy")}</span>
                </Button>
              )}
              {viewMenu.length > 0 && <Menu align="end" width="w-64" trigger={<Button iconOnly aria-label={t("wp.viewMenu")} icon={<MoreHorizontal />} />} items={viewMenu} />}
            </>
          }
        />

        <div id="wp-filters">{filtersOpen && spec && <FilterPanel spec={spec} onSpec={state.setSpec} options={options} />}</div>

        {!spec || result.isPending ? (
          <div className="flex flex-col gap-2 rounded-md border border-border p-3" aria-busy="true">
            {Array.from({ length: 8 }, (_, i) => (
              <Skeleton key={i} className="h-7 w-full" />
            ))}
          </div>
        ) : result.isError ? (
          <ErrorState error={result.error} onRetry={() => result.refetch()} />
        ) : result.data.rows.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState
              icon={<ListTodo />}
              title={t("wp.empty")}
              action={
                filterCount > 0 ? (
                  <Button onClick={() => state.setSpec({ ...spec, filters: [], text: undefined })}>{t("wp.clearFilters")}</Button>
                ) : canCreate && types[0] ? (
                  <Button variant="primary" icon={<Plus />} onClick={() => navigate(`${base}/new?type=${types[0].id}`)}>
                    {t("wp.create")}
                  </Button>
                ) : undefined
              }
            >
              {t("wp.emptyBody")}
            </EmptyState>
          </div>
        ) : (
          <div className={cn("transition-opacity", result.isPlaceholderData && "opacity-60")} aria-busy={result.isFetching}>
            <WorkPackageTable
              rows={result.data.rows}
              spec={spec}
              onSpec={state.setSpec}
              selected={selected}
              options={options}
              detailsHref={(wp) =>
                project && wp.project.id === project.id ? `${base}/details/${wp.number}/overview${search}` : `/projects/${wp.project.identifier}/work-packages/${wp.number}/activity`
              }
              createIn={canCreate && types[0] ? { projectId: project!.id, typeId: types[0].id } : null}
            />
            <div className="mt-3">
              <Pagination page={page} pageSize={pageSize} total={result.data.total} onPage={state.setPage} onPageSize={state.setPageSize} />
            </div>
          </div>
        )}
      </div>
      {panelOpen && panel}
      {renaming && view && <RenameViewDialog view={view} projectId={project?.id} onClose={() => setRenaming(false)} />}
      {exporting && spec && (
        <ExportDialog
          open
          onOpenChange={setExporting}
          spec={spec}
          projectId={project?.id}
          title={view?.name ?? t("wp.listTitle")}
          allColumns={allColumns}
          columnLabel={columnLabel}
        />
      )}
      {spec && (
        <SaveViewDialog
          open={saveAs}
          onOpenChange={setSaveAs}
          spec={spec}
          project={project}
          onSaved={(id) => {
            setSaveAs(false);
            toast({ tone: "success", title: t("wp.viewSaved") });
            navigate(`${base}?view=${encodeURIComponent(id)}`);
          }}
        />
      )}
    </div>
  );
}
