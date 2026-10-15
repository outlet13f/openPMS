// S24–S26 프로젝트 설정: 정보, 모듈, 유형·카테고리, 버전
import { useState, type ReactNode } from "react";
import { Navigate, NavLink, useNavigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Archive, Copy, ListPlus, MoreHorizontal, Pencil, Plus, Save, Tag, Trash2 } from "lucide-react";
import { api, isApiError, TEMPLATE_PARTS, type ModuleKey, type Project, type ProjectStatus, type TemplatePart, type Version, type VersionInput } from "@/api";
import { useCategories, useCategoryMutations, useProjectCustomFields, useProjectSettingsMutations, useSetProjectCustomFields, useVersionMutations, useVersions } from "@/api/admin-hooks";
import { useFilterOptions, useMe } from "@/api/hooks";
import { Badge, TypeLabel } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input, Textarea } from "@/components/ui/field";
import { Banner, EmptyState } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { useParentCandidates } from "@/features/work-packages/transfer-dialogs";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/dates";
import { htmlToText, textToHtml } from "@/lib/text";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { AdminTable, ConfirmDialog, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { ServiceDeskSection } from "@/features/service-desk/settings-section";

/** 서비스 데스크 settings: the module on and service.view. */
const serviceOn = (p: Project) => p.enabledModules.includes("service_desk") && can(p, "service.view");
/** The 정보·모듈·작업 설정·버전 sections: people with none of these (service desk agents) see only 서비스 데스크. */
const projectSettingsOn = (p: Project) =>
  can(p, "project.edit") || can(p, "project.manage_versions") || can(p, "project.manage_types") || can(p, "project.manage_custom_fields");

const SECTIONS = ["general", "modules", "work-packages", "versions", "service-desk"] as const;
type Section = (typeof SECTIONS)[number];

function SettingsNav({ base }: { base: string }) {
  const { t } = useTranslation();
  const project = useCurrentProject();
  return (
    <nav aria-label={t("settings.nav")} className="mb-6 flex gap-1 overflow-x-auto border-b border-border">
      {SECTIONS.filter((s) => (s === "service-desk" ? serviceOn(project) : projectSettingsOn(project))).map((s) => (
        <NavLink
          key={s}
          to={`${base}/${s}`}
          className={({ isActive }) =>
            cn("-mb-px inline-flex h-10 shrink-0 items-center border-b-2 border-transparent px-3 text-sm text-fg-muted hover:text-fg", isActive && "border-accent font-semibold text-fg")
          }
        >
          {t(`settings.sections.${s}`)}
        </NavLink>
      ))}
    </nav>
  );
}

function Section({ title, children, hint }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex max-w-form flex-col gap-4">
      <div>
        <h2 className="text-lg text-fg">{title}</h2>
        {hint && <p className="mt-1 text-sm text-fg-muted">{hint}</p>}
      </div>
      {children}
    </section>
  );
}

/* ---------- 정보 ---------- */

/** 프로젝트 복사: a new project from this one, taking what is ticked (as from a template). */
function CopyProjectDialog({ project, onClose }: { project: Project; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const runToast = useRunWithToast();
  const [name, setName] = useState(t("settings.copyName", { name: project.name }));
  const [identifier, setIdentifier] = useState(`${project.identifier}-copy`.slice(0, 100));
  const [parts, setParts] = useState<TemplatePart[]>([...TEMPLATE_PARTS]);
  const [startDate, setStartDate] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const fieldErr = (f: string) => (isApiError(error) ? error.fieldErrors.find((e) => e.field === f)?.message : undefined);
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const made = await runToast(
        api.projects.copy(project.id, { name, identifier, parentId: project.parent?.id ?? null, copy: parts, startDate: startDate || null }).catch((e) => {
          setError(e);
          throw e;
        }),
        t("settings.copied"),
      );
      if (made) navigate(`/projects/${made.identifier}`);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={t("settings.copyTitle", { name: project.name })}
      description={t("settings.copyHint")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" icon={<Copy />} loading={busy} onClick={() => void submit()}>
            {t("settings.copyAction")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("projectNew.name")} required error={fieldErr("name")}>
          <Input data-autofocus value={name} maxLength={255} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label={t("projectNew.identifier")} required hint={t("projectNew.identifierHint")} error={fieldErr("identifier")}>
          <Input value={identifier} maxLength={100} spellCheck={false} onChange={(e) => setIdentifier(e.target.value.toLowerCase())} />
        </Field>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium text-fg">{t("projectNew.copyLegend")}</legend>
          <p className="text-xs text-fg-muted">{t("projectNew.copyAlways")}</p>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {TEMPLATE_PARTS.map((part) => (
              <Checkbox
                key={part}
                label={t(`projectNew.parts.${part}`)}
                checked={parts.includes(part)}
                onCheckedChange={(c) => setParts((ps) => (c === true ? [...ps, part] : ps.filter((x) => x !== part)))}
              />
            ))}
          </div>
        </fieldset>
        <Field label={t("projectNew.startDate")} hint={t("projectNew.startDateHint")} error={fieldErr("startDate")}>
          <Input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} className="w-48" />
        </Field>
      </div>
    </Dialog>
  );
}

function GeneralSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const navigate = useNavigate();
  const m = useProjectSettingsMutations(project.id);
  const runToast = useRunWithToast();
  const [v, setV] = useState({
    name: project.name,
    description: htmlToText(project.descriptionHtml),
    status: (project.status ?? "none") as ProjectStatus | "none",
    statusExplanation: project.statusExplanation,
    isPublic: project.isPublic,
    isTemplate: project.isTemplate,
    parentId: project.parent?.id ?? "",
  });
  const parents = useParentCandidates(project);
  const [archiving, setArchiving] = useState(false);
  const [copying, setCopying] = useState(false);
  const me = useMe().data;
  const canCopy = can(project, "project.edit") && !!me?.globalPermissions.includes("global.create_projects");
  const save = () =>
    void runToast(
      m.update.mutateAsync({ name: v.name, descriptionHtml: textToHtml(v.description), status: v.status === "none" ? null : v.status, statusExplanation: v.statusExplanation, isPublic: v.isPublic, isTemplate: v.isTemplate,
        // only sent when changed: moving a project needs the right to edit the new parent too
        ...(v.parentId !== (project.parent?.id ?? "") ? { parentId: v.parentId || null } : {}),
      }),
      t("app.saved"),
    );
  return (
    <Section title={t("settings.sections.general")}>
      <Field label={t("projectNew.name")} required error={fieldErrorOf(m.update.error, "name")}>
        <Input value={v.name} maxLength={255} onChange={(e) => setV({ ...v, name: e.target.value })} />
      </Field>
      <Field label={t("projectNew.description")}>
        <Textarea rows={4} value={v.description} onChange={(e) => setV({ ...v, description: e.target.value })} />
      </Field>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-[200px_1fr]">
        <Field label={t("overview.status")}>
          <Select
            value={v.status}
            onValueChange={(s) => s && setV({ ...v, status: s as ProjectStatus | "none" })}
            options={[
              { value: "none", label: t("projects.status.none") },
              { value: "on_track", label: t("projects.status.on_track") },
              { value: "at_risk", label: t("projects.status.at_risk") },
              { value: "off_track", label: t("projects.status.off_track") },
            ]}
          />
        </Field>
        <Field label={t("settings.statusExplanation")}>
          <Input value={v.statusExplanation} maxLength={500} onChange={(e) => setV({ ...v, statusExplanation: e.target.value })} />
        </Field>
      </div>
      <Field label={t("projectNew.parent")} hint={t("projectNew.parentHint")} error={fieldErrorOf(m.update.error, "parent")}>
        <Select
          value={v.parentId || "none"}
          onValueChange={(p) => setV({ ...v, parentId: p === "none" ? "" : p })}
          options={[
            { value: "none", label: t("projectNew.noParent") },
            ...(project.parent && !parents.some((p) => p.id === project.parent!.id) ? [{ value: project.parent.id, label: project.parent.name }] : []),
            ...parents.map((p) => ({ value: p.id, label: p.name })),
          ]}
        />
      </Field>
      <Checkbox label={t("projectNew.isPublic")} description={t("projectNew.isPublicHint")} checked={v.isPublic} onCheckedChange={(c) => setV({ ...v, isPublic: c === true })} />
      <Checkbox label={t("settings.isTemplate")} description={t("settings.isTemplateHint")} checked={v.isTemplate} onCheckedChange={(c) => setV({ ...v, isTemplate: c === true })} />
      <div>
        <Button variant="primary" icon={<Save />} loading={m.update.isPending} onClick={save}>
          {t("app.save")}
        </Button>
      </div>
      {canCopy && (
        <div className="mt-6 rounded-md border border-border p-4">
          <h3 className="text-sm font-semibold text-fg">{t("settings.copyProject")}</h3>
          <p className="mt-1 text-sm text-fg-muted">{t("settings.copyHint")}</p>
          <Button className="mt-3" icon={<Copy />} onClick={() => setCopying(true)}>
            {t("settings.copyProject")}
          </Button>
        </div>
      )}
      {copying && <CopyProjectDialog project={project} onClose={() => setCopying(false)} />}
      {can(project, "project.archive") && !project.archivedAt && (
        <div className="mt-6 rounded-md border border-danger/40 p-4">
          <h3 className="text-sm font-semibold text-fg">{t("settings.archiveTitle")}</h3>
          <p className="mt-1 text-sm text-fg-muted">{t("settings.archiveBody")}</p>
          <Button variant="danger" className="mt-3" icon={<Archive />} onClick={() => setArchiving(true)}>
            {t("settings.archive")}
          </Button>
        </div>
      )}
      <ConfirmDialog
        open={archiving}
        onOpenChange={setArchiving}
        title={t("settings.archiveConfirm", { name: project.name })}
        body={t("settings.archiveBody")}
        confirm={t("settings.archive")}
        pending={m.archive.isPending}
        onConfirm={async () => {
          if (await runToast(m.archive.mutateAsync(), t("settings.archived")) !== null) navigate("/projects?filter=archived");
          setArchiving(false);
        }}
      />
    </Section>
  );
}

/* ---------- 모듈 ---------- */

const MODULES: ModuleKey[] = ["work_packages", "gantt", "boards", "calendar", "team_planner", "time_tracking", "service_desk", "cicd"];

function ModulesSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const m = useProjectSettingsMutations(project.id);
  const runToast = useRunWithToast();
  const [mods, setMods] = useState<ModuleKey[]>(project.enabledModules);
  const editable = can(project, "project.manage_modules");
  return (
    <Section title={t("settings.sections.modules")} hint={t("settings.modulesHint")}>
      <fieldset className="flex flex-col gap-3" disabled={!editable}>
        <legend className="sr-only">{t("settings.sections.modules")}</legend>
        {MODULES.map((k) => (
          <Checkbox
            key={k}
            label={t(`settings.modules.${k}`)}
            description={t(`settings.moduleHints.${k}`)}
            checked={mods.includes(k)}
            disabled={!editable || k === "work_packages"}
            onCheckedChange={(c) => setMods((s) => (c === true ? [...new Set([...s, k])] : s.filter((x) => x !== k)))}
          />
        ))}
      </fieldset>
      {editable && (
        <div>
          <Button variant="primary" icon={<Save />} loading={m.update.isPending} onClick={() => void runToast(m.update.mutateAsync({ enabledModules: mods }), t("app.saved"))}>
            {t("app.save")}
          </Button>
        </div>
      )}
    </Section>
  );
}

/* ---------- 유형·카테고리 ---------- */

/** All types (filters without a project) and the ones this project uses; project managers are not admins. */
function useTypesForProject(projectId: string) {
  const all = useFilterOptions(undefined);
  const mine = useFilterOptions(projectId);
  return { isPending: all.isPending || mine.isPending, all: all.data?.types ?? [], enabledIds: mine.data?.types.map((x) => x.id) ?? [] };
}

function CategoryDialog({ projectId, editing, onClose }: { projectId: string; editing: { id: string; name: string; defaultAssigneeId: string | null } | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useCategoryMutations(projectId);
  const people = useFilterOptions(projectId).data?.users ?? [];
  const runToast = useRunWithToast();
  const [name, setName] = useState(editing?.name ?? "");
  const [assignee, setAssignee] = useState<string>(editing?.defaultAssigneeId ?? "none");
  const mutation = editing ? m.update : m.create;
  const input = { name, defaultAssigneeId: assignee === "none" ? null : assignee };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={editing ? t("settings.categories.edit") : t("settings.categories.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            loading={mutation.isPending}
            onClick={async () => (await runToast(editing ? m.update.mutateAsync({ id: editing.id, input }) : m.create.mutateAsync(input), t("app.saved"))) && onClose()}
          >
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("admin.name")} required error={fieldErrorOf(mutation.error, "name")}>
          <Input data-autofocus value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label={t("settings.categories.defaultAssignee")} hint={t("settings.categories.defaultAssigneeHint")}>
          <Select value={assignee} onValueChange={(v) => v && setAssignee(v)} options={[{ value: "none", label: t("app.none") }, ...people.map((p) => ({ value: p.id, label: p.name }))]} />
        </Field>
      </div>
    </Dialog>
  );
}

function WorkPackagesSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const m = useProjectSettingsMutations(project.id);
  const types = useTypesForProject(project.id);
  const categories = useCategories(project.id);
  const cm = useCategoryMutations(project.id);
  const runToast = useRunWithToast();
  const [enabled, setEnabled] = useState<string[] | null>(null);
  const [dialog, setDialog] = useState<{ editing: { id: string; name: string; defaultAssigneeId: string | null } | null } | null>(null);
  const [removing, setRemoving] = useState<{ id: string; name: string } | null>(null);
  const current = enabled ?? types.enabledIds;
  const canTypes = can(project, "project.manage_types");
  const canCats = can(project, "project.manage_categories");
  return (
    <div className="flex flex-col gap-10">
      <Section title={t("settings.internalComments.title")} hint={t("settings.internalComments.hint")}>
        <Checkbox
          label={t("settings.internalComments.enable")}
          checked={project.internalComments}
          disabled={!can(project, "project.edit") || m.update.isPending}
          onCheckedChange={(c) => void runToast(m.update.mutateAsync({ internalComments: c === true }), t("app.saved"))}
        />
      </Section>
      <Section title={t("settings.types.title")} hint={t("settings.types.hint")}>
        {types.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : (
          <fieldset className="flex flex-col gap-2" disabled={!canTypes}>
            <legend className="sr-only">{t("settings.types.title")}</legend>
            {types.all.map((ty) => (
              <Checkbox
                key={ty.id}
                label={<TypeLabel name={ty.name} color={ty.color} className="text-sm" />}
                checked={current.includes(ty.id)}
                disabled={!canTypes}
                onCheckedChange={(c) => setEnabled((c === true ? [...new Set([...current, ty.id])] : current.filter((x) => x !== ty.id)))}
              />
            ))}
          </fieldset>
        )}
        {canTypes && (
          <div>
            <Button variant="primary" icon={<Save />} disabled={!enabled} loading={m.setTypes.isPending} onClick={async () => (await runToast(m.setTypes.mutateAsync(current), t("app.saved"))) && setEnabled(null)}>
              {t("app.save")}
            </Button>
          </div>
        )}
      </Section>

      <Section title={t("settings.categories.title")} hint={t("settings.categories.hint")}>
        {canCats && (
          <div>
            <Button icon={<Plus />} onClick={() => setDialog({ editing: null })}>
              {t("settings.categories.new")}
            </Button>
          </div>
        )}
        {categories.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : categories.isError ? (
          <ErrorState error={categories.error} onRetry={() => categories.refetch()} />
        ) : categories.data.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState compact icon={<Tag />} title={t("settings.categories.empty")} />
          </div>
        ) : (
          <AdminTable minWidth={420}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={th}>{t("settings.categories.defaultAssignee")}</th>
                {canCats && <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>}
              </tr>
            </thead>
            <tbody>
              {categories.data.map((c) => (
                <tr key={c.id} className={tbodyRow}>
                  <td className="px-3 text-fg">{c.name}</td>
                  <td className="px-3 text-fg-muted">{c.defaultAssignee?.name ?? "-"}</td>
                  {canCats && (
                    <td className="px-2">
                      <Menu
                        align="end"
                        trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: c.name })} icon={<MoreHorizontal />} />}
                        items={[
                          { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: { id: c.id, name: c.name, defaultAssigneeId: c.defaultAssignee?.id ?? null } }) },
                          { kind: "separator" },
                          { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(c) },
                        ]}
                      />
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </Section>
      {can(project, "project.manage_custom_fields") && <CustomFieldsSection />}
      {dialog && <CategoryDialog projectId={project.id} editing={dialog.editing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("settings.categories.deleteTitle")}
        body={removing && t("settings.categories.deleteBody", { name: removing.name })}
        confirm={t("app.delete")}
        pending={cm.remove.isPending}
        onConfirm={async () => {
          if (removing) await runToast(cm.remove.mutateAsync(removing.id), t("admin.deleted"));
          setRemoving(null);
        }}
      />
    </div>
  );
}

/* ---------- 사용자 정의 필드 (S25) ---------- */

function CustomFieldsSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const q = useProjectCustomFields(project.id);
  const save = useSetProjectCustomFields(project.id);
  const types = useTypesForProject(project.id);
  const runToast = useRunWithToast();
  const [enabled, setEnabled] = useState<string[] | null>(null);
  const current = enabled ?? q.data?.enabledIds ?? [];
  const typeName = (id: string) => types.all.find((x) => x.id === id)?.name;
  return (
    <Section title={t("customFields.project.title")} hint={t("customFields.project.hint")}>
      {q.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : q.data.fields.length === 0 ? (
        <div className="rounded-md border border-border">
          <EmptyState compact icon={<ListPlus />} title={t("customFields.project.empty")} />
        </div>
      ) : (
        <>
          <fieldset className="flex flex-col gap-3">
            <legend className="sr-only">{t("customFields.project.title")}</legend>
            {q.data.fields.map((f) => (
              <Checkbox
                key={f.id}
                label={
                  <span>
                    {f.name}
                    {f.isForAll && (
                      <Badge tone="neutral" className="ml-2">
                        {t("customFields.project.forAll")}
                      </Badge>
                    )}
                  </span>
                }
                description={t("customFields.project.detail", { format: t(`customFields.formats.${f.format}`), types: f.typeIds.map(typeName).filter(Boolean).join(", ") })}
                checked={f.isForAll || current.includes(f.id)}
                disabled={f.isForAll}
                onCheckedChange={(c) => setEnabled(c === true ? [...new Set([...current, f.id])] : current.filter((x) => x !== f.id))}
              />
            ))}
          </fieldset>
          <div>
            <Button variant="primary" icon={<Save />} disabled={!enabled} loading={save.isPending} onClick={async () => (await runToast(save.mutateAsync(current), t("app.saved"))) && setEnabled(null)}>
              {t("app.save")}
            </Button>
          </div>
        </>
      )}
    </Section>
  );
}

/* ---------- 버전 ---------- */

const VERSION_TONE = { open: "success", locked: "warning", closed: "neutral" } as const;

function VersionDialog({ projectId, editing, onClose }: { projectId: string; editing: Version | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useVersionMutations(projectId);
  const runToast = useRunWithToast();
  const [v, setV] = useState<VersionInput>(editing ? { name: editing.name, description: editing.description, status: editing.status, startDate: editing.startDate, endDate: editing.endDate } : { name: "", description: "", status: "open", startDate: null, endDate: null });
  const mutation = editing ? m.update : m.create;
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={editing ? t("settings.versions.edit") : t("settings.versions.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            loading={mutation.isPending}
            onClick={async () => (await runToast(editing ? m.update.mutateAsync({ id: editing.id, input: v }) : m.create.mutateAsync(v), t("app.saved"))) && onClose()}
          >
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("admin.name")} required error={fieldErrorOf(mutation.error, "name")}>
          <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </Field>
        <Field label={t("projectNew.description")}>
          <Input value={v.description ?? ""} maxLength={255} onChange={(e) => setV({ ...v, description: e.target.value })} />
        </Field>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label={t("settings.versions.status")}>
            <Select
              value={v.status}
              onValueChange={(s) => s && setV({ ...v, status: s as Version["status"] })}
              options={(["open", "locked", "closed"] as const).map((s) => ({ value: s, label: t(`settings.versions.statuses.${s}`) }))}
            />
          </Field>
          <Field label={t("wp.fields.startDate")}>
            <Input type="date" value={v.startDate ?? ""} onChange={(e) => setV({ ...v, startDate: e.target.value || null })} />
          </Field>
          <Field label={t("settings.versions.endDate")} error={fieldErrorOf(mutation.error, "endDate")}>
            <Input type="date" value={v.endDate ?? ""} min={v.startDate ?? undefined} onChange={(e) => setV({ ...v, endDate: e.target.value || null })} />
          </Field>
        </div>
      </div>
    </Dialog>
  );
}

function VersionsSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const versions = useVersions(project.id);
  const m = useVersionMutations(project.id);
  const runToast = useRunWithToast();
  const [dialog, setDialog] = useState<{ editing: Version | null } | null>(null);
  const [removing, setRemoving] = useState<Version | null>(null);
  const canManage = can(project, "project.manage_versions");
  return (
    <Section title={t("settings.sections.versions")} hint={t("settings.versions.hint")}>
      {canManage && (
        <div>
          <Button icon={<Plus />} onClick={() => setDialog({ editing: null })}>
            {t("settings.versions.new")}
          </Button>
        </div>
      )}
      {versions.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : versions.isError ? (
        <ErrorState error={versions.error} onRetry={() => versions.refetch()} />
      ) : versions.data.length === 0 ? (
        <div className="rounded-md border border-border">
          <EmptyState compact icon={<Tag />} title={t("settings.versions.empty")} />
        </div>
      ) : (
        <AdminTable minWidth={600}>
          <thead>
            <tr className={theadRow}>
              <th scope="col" className={th}>{t("admin.name")}</th>
              <th scope="col" className={th}>{t("settings.versions.status")}</th>
              <th scope="col" className={th}>{t("settings.versions.period")}</th>
              <th scope="col" className={`${th} text-right`}>{t("settings.versions.progress")}</th>
              {canManage && <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>}
            </tr>
          </thead>
          <tbody>
            {versions.data.map((v) => (
              <tr key={v.id} className={tbodyRow}>
                <td className="px-3 py-1.5">
                  <span className="font-medium text-fg">{v.name}</span>
                  {v.description && <span className="block text-xs text-fg-muted">{v.description}</span>}
                </td>
                <td className="px-3"><Badge tone={VERSION_TONE[v.status]}>{t(`settings.versions.statuses.${v.status}`)}</Badge></td>
                <td className="px-3 text-fg-muted tabular-nums">{[formatDate(v.startDate), formatDate(v.endDate)].filter(Boolean).join(" – ") || "-"}</td>
                <td className="px-3 text-right tabular-nums">{t("settings.versions.closedOf", { closed: v.closedCount, total: v.workPackageCount })}</td>
                {canManage && (
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: v.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: v }) },
                        { kind: "separator" },
                        { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(v) },
                      ]}
                    />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </AdminTable>
      )}
      {dialog && <VersionDialog projectId={project.id} editing={dialog.editing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("settings.versions.deleteTitle")}
        body={removing && t("settings.versions.deleteBody", { name: removing.name, count: removing.workPackageCount })}
        confirm={t("app.delete")}
        pending={m.remove.isPending}
        onConfirm={async () => {
          if (removing) await runToast(m.remove.mutateAsync(removing.id), t("admin.deleted"));
          setRemoving(null);
        }}
      />
    </Section>
  );
}

/* ---------- page ---------- */

export function ProjectSettingsPage() {
  const { t } = useTranslation();
  const { section } = useParams();
  const project = useCurrentProject();
  useDocumentTitle(`${t("nav.settings")} · ${project.name}`);
  const base = `/projects/${project.identifier}/settings`;
  if (!projectSettingsOn(project) && serviceOn(project)) {
    // a service desk agent: every other (or unknown) section goes to 서비스 데스크
    if (section !== "service-desk") return <Navigate to={`${base}/service-desk`} replace />;
  } else if (!SECTIONS.includes(section as Section) || (section === "service-desk" && !serviceOn(project))) return <Navigate to={`${base}/general`} replace />;
  if (!projectSettingsOn(project) && !serviceOn(project))
    return (
      <PageBody>
        <PageHeader title={t("nav.settings")} />
        <Banner tone="warning">{t("settings.noPermission")}</Banner>
      </PageBody>
    );
  return (
    <PageBody>
      <PageHeader title={t("nav.settings")} breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("nav.settings") }]} />
      <SettingsNav base={base} />
      {section === "general" && <GeneralSection key={project.updatedAt} />}
      {section === "modules" && <ModulesSection key={project.updatedAt} />}
      {section === "work-packages" && <WorkPackagesSection />}
      {section === "versions" && <VersionsSection />}
      {section === "service-desk" && <ServiceDeskSection />}
    </PageBody>
  );
}
