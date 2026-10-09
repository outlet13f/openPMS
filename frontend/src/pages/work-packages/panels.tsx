// S07 분할 상세, S09 새 작업 (both render inside the S06 split panel), S08 전체 화면.
import { useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useParams, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { isApiError, type WorkPackageInput } from "@/api";
import { useCreateWorkPackage, useWorkPackage, useWpForm } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { Banner, useToast } from "@/components/ui/feedback";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { TypeLabel } from "@/components/ui/badges";
import { RichTextEditor } from "@/components/domain/rich-text-editor";
import { ErrorState, NotFound, useDocumentTitle } from "@/app/page";
import { useCurrentProject } from "@/app/project";
import { WorkPackageDetail, asDetailTab as asTab } from "@/features/work-packages/detail";


/** Query string of the list (view, filters, page), kept while the panel opens and closes. */
function useListSearch() {
  const [params] = useSearchParams();
  const p = new URLSearchParams(params);
  // prefill values for the new form (from "새 하위 작업", calendar or team planner) are not list state
  for (const k of ["type", "parent", "startDate", "dueDate", "assignee"]) p.delete(k);
  const s = p.toString();
  return s ? `?${s}` : "";
}

export function DetailsPanelRoute() {
  const { number, tab } = useParams();
  const project = useCurrentProject();
  const search = useListSearch();
  const base = `/projects/${project.identifier}/work-packages`;
  const n = Number(number);
  if (!Number.isInteger(n)) return <Navigate to={`${base}${search}`} replace />;
  const current = asTab(tab, "overview");
  return (
    <WorkPackageDetail
      key={n}
      number={n}
      mode="panel"
      tab={current}
      tabHref={(t) => `${base}/details/${n}/${t}${search}`}
      closeHref={`${base}${search}`}
      fullHref={`${base}/${n}/${current === "overview" ? "activity" : current}`}
      afterDeleteHref={`${base}${search}`}
    />
  );
}

export function FullViewRoute() {
  const { number, tab } = useParams();
  const project = useCurrentProject();
  const n = Number(number);
  const wp = useWorkPackage(Number.isInteger(n) ? n : undefined);
  const base = `/projects/${project.identifier}/work-packages`;
  // A work package moved to another project keeps its number: send the user to its real address.
  if (wp.data && wp.data.project.identifier !== project.identifier) return <Navigate to={`/projects/${wp.data.project.identifier}/work-packages/${n}/${tab ?? "activity"}`} replace />;
  if (!Number.isInteger(n)) return <NotFound />;
  return (
    <div className="px-content md:px-8">
      <WorkPackageDetail
        key={n}
        number={n}
        mode="full"
        tab={asTab(tab, "activity")}
        tabHref={(t) => `${base}/${n}/${t}`}
        backHref={base}
        afterDeleteHref={base}
      />
    </div>
  );
}

/** S09 in the split panel. `base` is the page that hosts the panel (list, calendar, team planner). */
export function NewPanelRoute({ base: hostBase }: { base?: string } = {}) {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const navigate = useNavigate();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const search = useListSearch();
  const base = hostBase ?? `/projects/${project.identifier}/work-packages`;
  const typeId = params.get("type") ?? undefined;
  const form = useWpForm(project.id, typeId);
  const create = useCreateWorkPackage(project.id);
  const [v, setV] = useState<Partial<WorkPackageInput> & { description: string; parent: string }>(() => ({
    description: "",
    parent: params.get("parent") ? `#${params.get("parent")}` : "",
    startDate: params.get("startDate") ?? undefined,
    dueDate: params.get("dueDate") ?? undefined,
    assigneeId: params.get("assignee") ?? undefined,
  }));
  useDocumentTitle(form.data ? t("wp.newTitle", { type: form.data.type.name }) : undefined);

  if (form.isPending)
    return (
      <div className="flex flex-col gap-3 p-4">
        <Skeleton className="h-7 w-1/2" />
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  if (form.isError) return <ErrorState error={form.error} onRetry={() => form.refetch()} />;
  const f = form.data;
  const fieldErr = (k: string) => (isApiError(create.error) ? create.error.fieldErrors.find((e) => e.field === k)?.message : undefined);
  const set = <K extends keyof typeof v>(k: K, val: (typeof v)[K]) => setV((x) => ({ ...x, [k]: val }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const wp = await create.mutateAsync({
        typeId: f.type.id,
        subject: v.subject ?? "",
        descriptionHtml: v.description,
        statusId: v.statusId || f.defaults.statusId,
        priorityId: v.priorityId || f.defaults.priorityId,
        assigneeId: v.assigneeId ?? null,
        versionId: v.versionId ?? null,
        categoryId: v.categoryId ?? null,
        parentNumber: v.parent ? Number(v.parent.replace("#", "")) : null,
        startDate: v.startDate || null,
        dueDate: f.type.isMilestone ? v.startDate || null : v.dueDate || null,
      });
      toast({ tone: "success", title: t("wp.created", { number: wp.number }), description: wp.subject });
      navigate(`${base}/details/${wp.number}/overview${search}`);
    } catch (err) {
      if (!isApiError(err)) console.error(err);
      const first = isApiError(err) ? err.fieldErrors[0]?.field : undefined;
      document.getElementById(`nw-${first ?? "subject"}`)?.focus();
    }
  };

  return (
    <form onSubmit={submit} noValidate className="flex min-h-0 flex-1 flex-col" aria-labelledby="new-wp-title">
      <div className="flex items-center justify-between gap-2 border-b border-border-muted px-4 py-3">
        <h1 id="new-wp-title" className="flex items-center gap-2 text-lg text-fg">
          {t("app.create")} <TypeLabel name={f.type.name} color={f.type.color} className="text-sm" />
        </h1>
        <Button asChild variant="ghost" size="sm" iconOnly aria-label={t("app.cancel")} icon={<X />}>
          <Link to={`${base}${search}`} />
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
        <div className="flex flex-col gap-4">
          {create.error && !(isApiError(create.error) && create.error.fieldErrors.length > 0) && (
            <Banner tone="danger">{isApiError(create.error) ? create.error.message : t("app.errorBody")}</Banner>
          )}
          <Field label={t("wp.fields.type")}>
            <Select value={f.type.id} onValueChange={(id) => setParams((p) => (p.set("type", id), p), { replace: true })} options={f.types.map((ty) => ({ value: ty.id, label: ty.name }))} />
          </Field>
          <Field label={t("wp.fields.subject")} required error={fieldErr("subject")}>
            <Input id="nw-subject" autoFocus maxLength={255} value={v.subject ?? ""} onChange={(e) => set("subject", e.target.value)} />
          </Field>
          <Field label={t("wp.fields.description")}>
            <RichTextEditor value="" onChange={(html) => set("description", html)} label={t("wp.fields.description")} minRows={4} />
          </Field>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label={t("wp.fields.status")}>
              <Select value={v.statusId || f.defaults.statusId} onValueChange={(x) => x && set("statusId", x)} options={f.statuses.map((s) => ({ value: s.id, label: s.name }))} />
            </Field>
            <Field label={t("wp.fields.priority")}>
              <Select value={v.priorityId || f.defaults.priorityId} onValueChange={(x) => x && set("priorityId", x)} options={f.priorities.map((s) => ({ value: s.id, label: s.name }))} />
            </Field>
            <Field label={t("wp.fields.assignee")} error={fieldErr("assignee")}>
              <Select
                value={v.assigneeId ?? "none"}
                onValueChange={(x) => x && set("assigneeId", x === "none" ? null : x)}
                options={[{ value: "none", label: t("wp.unassigned") }, ...f.assignees.map((u) => ({ value: u.id, label: u.name }))]}
              />
            </Field>
            {f.versions.length > 0 && (
              <Field label={t("wp.fields.version")}>
                <Select value={v.versionId ?? "none"} onValueChange={(x) => x && set("versionId", x === "none" ? null : x)} options={[{ value: "none", label: t("app.none") }, ...f.versions.map((x) => ({ value: x.id, label: x.name }))]} />
              </Field>
            )}
            {f.categories.length > 0 && (
              <Field label={t("wp.fields.category")} hint={t("wp.categoryHint")}>
                <Select
                  value={v.categoryId ?? "none"}
                  onValueChange={(x) => {
                    if (!x) return;
                    const cat = f.categories.find((c) => c.id === x);
                    // fill an empty assignee with the category's default (S25)
                    setV((cur) => ({ ...cur, categoryId: x === "none" ? null : x, assigneeId: cur.assigneeId === undefined && cat?.defaultAssigneeId ? cat.defaultAssigneeId : cur.assigneeId }));
                  }}
                  options={[{ value: "none", label: t("app.none") }, ...f.categories.map((c) => ({ value: c.id, label: c.name }))]}
                />
              </Field>
            )}
            <Field label={f.type.isMilestone ? t("dates.date") : t("wp.fields.startDate")}>
              <Input type="date" value={v.startDate ?? ""} onChange={(e) => set("startDate", e.target.value)} />
            </Field>
            {!f.type.isMilestone && (
              <Field label={t("wp.fields.dueDate")}>
                <Input type="date" min={v.startDate || undefined} value={v.dueDate ?? ""} onChange={(e) => set("dueDate", e.target.value)} />
              </Field>
            )}
            <Field label={t("wp.fields.parent")} hint={t("wp.parentHint")} error={fieldErr("parent")}>
              <Input id="nw-parent" inputMode="numeric" placeholder="#12" value={v.parent} onChange={(e) => set("parent", e.target.value)} />
            </Field>
          </div>
        </div>
      </div>
      <div className="flex justify-end gap-2 border-t border-border-muted px-4 py-3">
        <Button asChild>
          <Link to={`${base}${search}`}>{t("app.cancel")}</Link>
        </Button>
        <Button type="submit" variant="primary" loading={create.isPending}>
          {t("app.save")}
        </Button>
      </div>
    </form>
  );
}
