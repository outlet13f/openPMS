// S06 작업 목록 (project or global) with the split panel outlet for S07/S09.
import { useState } from "react";
import { Outlet, useMatch, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { Columns3, Download, Filter, FolderTree, Group, Highlighter, History, ListTodo, Maximize2, MoreHorizontal, PanelRight, Pencil, Plus, RotateCcw, Save, Sigma, Star, Trash2 } from "lucide-react";
import { GROUPABLE_COLUMNS, HIGHLIGHTS, type ColumnKey, type Project, type QuerySpec, type SavedQuery, type WorkPackage } from "@/api";
import { isApiError } from "@/api";
import { useFilterOptions, useSavedQueryMutations, useWpForm, useWpQuery } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Badge, Counter, TypeLabel } from "@/components/ui/badges";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner, EmptyState, useToast } from "@/components/ui/feedback";
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
import { BulkBar, useBulkActions } from "@/features/work-packages/bulk-bar";
import { useListState } from "@/features/work-packages/view-state";
import { BaselineRemovedSection } from "@/features/work-packages/baseline-removed";
import { formatDateTime, todayIso } from "@/lib/dates";

const ALL_COLUMNS: ColumnKey[] = ["number", "subject", "type", "status", "priority", "assignee", "responsible", "author", "version", "startDate", "dueDate", "duration", "work", "remainingWork", "doneRatio", "updatedAt", "project"];
/** 서비스 데스크 columns: only in a project with the module on (they are empty everywhere else). */
const SERVICE_COLUMNS: ColumnKey[] = ["requester", "assignmentGroup", "catalogItem", "slaDue", "slaState"];

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
  const serviceOn = !!project && project.enabledModules.includes("service_desk") && can(project, "service.view");
  const allColumns: ColumnKey[] = [...ALL_COLUMNS, ...(serviceOn ? SERVICE_COLUMNS : []), ...(options?.customFields ?? []).map((f) => cfColumn(f.id))];
  const groupColumns: ColumnKey[] = [...GROUPABLE_COLUMNS, ...(options?.customFields ?? []).filter((f) => ["list", "user", "bool"].includes(f.format)).map((f) => cfColumn(f.id))];
  const form = useWpForm(project && can(project, "wp.create") ? project.id : undefined);
  const mutations = useSavedQueryMutations(project?.id);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [saveAs, setSaveAs] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [pickingBaseline, setPickingBaseline] = useState(false);
  const [baselineDate, setBaselineDate] = useState("");
  // S06: rows checked for a bulk change; a different list or page starts empty
  const [checked, setChecked] = useState<ReadonlySet<number>>(new Set());
  const listKey = `${JSON.stringify(spec ?? null)}|${page}|${pageSize}`;
  const [checkedFor, setCheckedFor] = useState(listKey);
  if (checkedFor !== listKey) (setCheckedFor(listKey), setChecked(new Set()));
  const onCheck = (numbers: number[], on: boolean) =>
    setChecked((cur) => {
      const next = new Set(cur);
      numbers.forEach((n) => (on ? next.add(n) : next.delete(n)));
      return next;
    });
  const details = useMatch(`${base}/details/:number/*`);
  const creating = useMatch(`${base}/new`);
  const panelOpen = !!(details || creating);
  const selected = details ? Number(details.params.number) : undefined;
  useDocumentTitle(view ? `${view.name} · ${project?.name ?? t("wp.listTitle")}` : undefined);
  const bulk = useBulkActions({ options, projectId: project?.id, onDone: () => setChecked(new Set()) });
  /** S06 우클릭: the checked rows when the row is one of them, else the row itself */
  const rowMenu = (wp: WorkPackage): MenuEntry[] => {
    const numbers = checked.has(wp.number) ? [...checked] : [wp.number];
    const full = `/projects/${wp.project.identifier}/work-packages/${wp.number}/activity`;
    return [
      ...(numbers.length === 1
        ? [
            { label: t("wp.openDetails"), icon: <PanelRight />, onSelect: () => navigate(project && wp.project.id === project.id ? `${base}/details/${wp.number}/overview${search}` : full) },
            { label: t("wp.openFull"), icon: <Maximize2 />, onSelect: () => navigate(full) },
            { kind: "separator" as const },
          ]
        : [{ kind: "label" as const, label: t("bulk.selected", { count: numbers.length }) }]),
      ...bulk.menuItems(numbers),
    ];
  };

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
            onSelect: () =>
              state.setSpec((s) => ({ ...s, columns: s.columns.includes(c) ? s.columns.filter((x) => x !== c) : allColumns.filter((x) => x === c || s.columns.includes(x)) })),
          })),
        },
        {
          kind: "sub" as const,
          label: t("wp.grouping.menu"),
          icon: <Group />,
          items: [
            { label: t("wp.grouping.none"), checked: !spec.groupBy, onSelect: () => state.setSpec((s) => ({ ...s, groupBy: null })) },
            ...groupColumns.map((c) => ({
              label: columnLabel(c),
              checked: spec.groupBy === c,
              // grouped lists are flat, as in the original
              onSelect: () => state.setSpec((s) => ({ ...s, groupBy: c, hierarchy: false })),
            })),
          ],
        },
        { label: t("wp.grouping.sums"), icon: <Sigma />, checked: spec.sums, onSelect: () => state.setSpec((s) => ({ ...s, sums: !s.sums })) },
        {
          kind: "sub" as const,
          label: t("wp.highlight.menu"),
          icon: <Highlighter />,
          items: HIGHLIGHTS.map((h) => ({ label: t(`wp.highlight.${h}`), checked: (spec.highlight ?? "none") === h, onSelect: () => state.setSpec((s) => ({ ...s, highlight: h })) })),
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
              {spec && (
                <Menu
                  trigger={
                    <Button aria-pressed={!!spec.baseline} icon={<History />}>
                      <span className="hidden xl:inline">{t("baseline.button")}</span>
                      <span className="sr-only xl:hidden">{t("baseline.button")}</span>
                    </Button>
                  }
                  items={[
                    ...(["off", "yesterday", "lastWeek", "lastMonth"] as const).map((k) => ({
                      label: t(`baseline.kinds.${k}`),
                      checked: k === "off" ? !spec.baseline : spec.baseline?.kind === k,
                      onSelect: () => state.setSpec((s) => ({ ...s, baseline: k === "off" ? null : { kind: k } })),
                    })),
                    { label: t("baseline.kinds.date"), checked: spec.baseline?.kind === "date", onSelect: () => setPickingBaseline(true) },
                  ]}
                />
              )}
              {project && spec && (
                <Button aria-pressed={spec.includeSubprojects} icon={<FolderTree />} onClick={() => state.setSpec((s) => ({ ...s, includeSubprojects: !s.includeSubprojects }))}>
                  <span className="hidden xl:inline">{t("wp.includeSubprojects")}</span>
                  <span className="sr-only xl:hidden">{t("wp.includeSubprojects")}</span>
                </Button>
              )}
              {spec && spec.hierarchy !== undefined && (
                <Button
                  aria-pressed={spec.hierarchy}
                  icon={<ListTodo />}
                  title={spec.groupBy ? t("wp.grouping.noHierarchy") : undefined}
                  onClick={() => state.setSpec((s) => ({ ...s, hierarchy: !s.hierarchy, groupBy: s.hierarchy ? s.groupBy : null }))}
                >
                  <span className="hidden xl:inline">{t("wp.hierarchy")}</span>
                  <span className="sr-only xl:hidden">{t("wp.hierarchy")}</span>
                </Button>
              )}
              {viewMenu.length > 0 && <Menu align="end" width="w-64" trigger={<Button iconOnly aria-label={t("wp.viewMenu")} icon={<MoreHorizontal />} />} items={viewMenu} />}
            </>
          }
        />

        <div id="wp-filters">{filtersOpen && spec && <FilterPanel spec={spec} onSpec={state.setSpec} options={options} />}</div>
        {spec?.baseline && result.data?.baselineAt && (
          <div className="mb-3">
          <Banner tone="info">
            <span className="inline-flex flex-wrap items-center gap-x-3 gap-y-1">
              {t("baseline.banner", { at: formatDateTime(result.data.baselineAt) })}
              <span className="inline-flex items-center gap-1 text-xs">
                <History className="size-3.5" aria-hidden="true" />
                {t("baseline.legendChanged")}
              </span>
              <span className="rounded-sm bg-success-subtle px-1.5 text-xs font-medium text-success">{t("baseline.added")}</span>
              <Button size="sm" variant="ghost" onClick={() => state.setSpec((s) => ({ ...s, baseline: null }))}>
                {t("baseline.off")}
              </Button>
            </span>
          </Banner>
          </div>
        )}
        {pickingBaseline && (
          <Dialog
            open
            onOpenChange={(o) => !o && setPickingBaseline(false)}
            size="sm"
            title={t("baseline.pickTitle")}
            description={t("baseline.pickHint")}
            footer={
              <>
                <Button onClick={() => setPickingBaseline(false)}>{t("app.cancel")}</Button>
                <Button
                  variant="primary"
                  disabled={!baselineDate || baselineDate > todayIso()}
                  onClick={() => (state.setSpec((s) => ({ ...s, baseline: { kind: "date", date: baselineDate } })), setPickingBaseline(false))}
                >
                  {t("baseline.apply")}
                </Button>
              </>
            }
          >
            <Field label={t("baseline.date")}>
              <Input type="date" max={todayIso()} value={baselineDate} onChange={(e) => setBaselineDate(e.target.value)} />
            </Field>
          </Dialog>
        )}

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
            {checked.size > 0 && <BulkBar numbers={[...checked]} actions={bulk} onClear={() => setChecked(new Set())} />}
            {bulk.refusals && <div className="mb-3">{bulk.refusals}</div>}
            {bulk.dialogs}
            <WorkPackageTable
              checked={checked}
              onCheck={onCheck}
              rows={result.data.rows}
              groups={result.data.groups ?? undefined}
              sums={result.data.sums ?? undefined}
              spec={spec}
              onSpec={state.setSpec}
              selected={selected}
              options={options}
              detailsHref={(wp) =>
                project && wp.project.id === project.id ? `${base}/details/${wp.number}/overview${search}` : `/projects/${wp.project.identifier}/work-packages/${wp.number}/activity`
              }
              createIn={canCreate && types[0] ? { projectId: project!.id, typeId: types[0].id } : null}
              rowMenu={rowMenu}
            />
            <div className="mt-3">
              <Pagination page={page} pageSize={pageSize} total={result.data.total} onPage={state.setPage} onPageSize={state.setPageSize} />
            </div>
          </div>
        )}
        {spec?.baseline && result.data?.baselineRemoved && <BaselineRemovedSection items={result.data.baselineRemoved} columnLabel={(c) => columnLabel(c as ColumnKey)} />}
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
