// S27 관리: 유형 (색, 마일스톤 여부, 새 프로젝트 기본 활성, 로드맵 표시, 순서)
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { GitBranch, MoreHorizontal, Pencil, Plus, Trash2 } from "lucide-react";
import type { AdminType, TypeInput } from "@/api";
import { useAdminTypes, useTypeMutations } from "@/api/admin-hooks";
import { Badge, TypeLabel } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ColorField, ConfirmDialog, OrderMenu, YesNo, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

const EMPTY: TypeInput = { name: "", color: "#1f5fc4", isMilestone: false, isDefault: false, isInRoadmap: true };

function TypeDialog({ open, onOpenChange, editing }: { open: boolean; onOpenChange: (o: boolean) => void; editing: AdminType | null }) {
  const { t } = useTranslation();
  const m = useTypeMutations();
  const runToast = useRunWithToast();
  const [v, setV] = useState<TypeInput>(
    editing ? { name: editing.name, color: editing.color, isMilestone: editing.isMilestone, isDefault: editing.isDefault, isInRoadmap: editing.isInRoadmap } : EMPTY,
  );
  const [created, setCreated] = useState<AdminType | null>(null);
  const mutation = editing ? m.update : m.create;
  const submit = async () => {
    if (editing) {
      if (await runToast(m.update.mutateAsync({ id: editing.id, patch: v }), t("app.saved"))) onOpenChange(false);
    } else {
      const r = await runToast(m.create.mutateAsync(v), t("admin.types.created"));
      if (r) setCreated(r);
    }
  };
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="md"
      title={editing ? t("admin.types.edit") : t("admin.types.new")}
      footer={
        created ? (
          <>
            <Button onClick={() => onOpenChange(false)}>{t("app.close")}</Button>
            <Button asChild variant="primary" icon={<GitBranch />}>
              <Link to={`/admin/workflows?type=${created.id}`}>{t("admin.types.setWorkflow")}</Link>
            </Button>
          </>
        ) : (
          <>
            <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
            <Button variant="primary" loading={mutation.isPending} onClick={() => void submit()}>
              {t("app.save")}
            </Button>
          </>
        )
      }
    >
      {created ? (
        <Banner tone="warning" title={t("admin.types.noWorkflowTitle")}>
          {t("admin.types.noWorkflowBody", { name: created.name })}
        </Banner>
      ) : (
        <div className="flex flex-col gap-4">
          <Field label={t("admin.name")} required error={fieldErrorOf(mutation.error, "name")}>
            <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => setV({ ...v, name: e.target.value })} />
          </Field>
          <ColorField label={t("admin.color")} value={v.color} asText error={fieldErrorOf(mutation.error, "color")} onChange={(color) => setV({ ...v, color })} />
          <div className="flex flex-col gap-3">
            <Checkbox label={t("admin.types.isMilestone")} description={t("admin.types.isMilestoneHint")} checked={v.isMilestone} disabled={!!editing && editing.workPackageCount > 0} onCheckedChange={(c) => setV({ ...v, isMilestone: c === true })} />
            <Checkbox label={t("admin.types.isDefault")} description={t("admin.types.isDefaultHint")} checked={v.isDefault} onCheckedChange={(c) => setV({ ...v, isDefault: c === true })} />
            <Checkbox label={t("admin.types.isInRoadmap")} checked={v.isInRoadmap} onCheckedChange={(c) => setV({ ...v, isInRoadmap: c === true })} />
          </div>
          <p className="text-xs text-fg-muted">
            {t("admin.preview")}: <TypeLabel name={v.name || t("admin.name")} color={/^#[0-9a-fA-F]{6}$/.test(v.color) ? v.color : "#000000"} />
          </p>
        </div>
      )}
    </Dialog>
  );
}

export function AdminTypesPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.types"));
  const types = useAdminTypes();
  const m = useTypeMutations();
  const navigate = useNavigate();
  const runToast = useRunWithToast();
  const [dialog, setDialog] = useState<{ open: boolean; editing: AdminType | null }>({ open: false, editing: null });
  const [removing, setRemoving] = useState<AdminType | null>(null);

  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.types")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.types") }]}
        actions={
          <Button variant="primary" icon={<Plus />} onClick={() => setDialog({ open: true, editing: null })}>
            {t("admin.types.new")}
          </Button>
        }
      />
      <AdminGuard>
        {types.isPending ? (
          <Skeleton className="h-48 w-full" />
        ) : types.isError ? (
          <ErrorState error={types.error} onRetry={() => types.refetch()} />
        ) : (
          <AdminTable minWidth={760}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={`${th} w-12`}>
                  <span className="sr-only">{t("admin.order")}</span>
                </th>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={th}>{t("admin.types.isMilestone")}</th>
                <th scope="col" className={th}>{t("admin.types.isDefaultShort")}</th>
                <th scope="col" className={th}>{t("admin.types.isInRoadmap")}</th>
                <th scope="col" className={`${th} text-right`}>{t("admin.workPackages")}</th>
                <th scope="col" className={`${th} text-right`}>{t("admin.projects")}</th>
                <th scope="col" className={`${th} w-12`}>
                  <span className="sr-only">{t("app.more")}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {types.data.map((ty) => (
                <tr key={ty.id} className={tbodyRow}>
                  <td className="px-2">
                    <OrderMenu items={types.data} item={ty} label={ty.name} onReorder={(ids) => void runToast(m.reorder.mutateAsync(ids))} />
                  </td>
                  <td className="px-3">
                    <TypeLabel name={ty.name} color={ty.color} className="text-sm" />
                  </td>
                  <td className="px-3">{ty.isMilestone ? <Badge tone="success">{t("admin.yes")}</Badge> : <YesNo value={false} />}</td>
                  <td className="px-3"><YesNo value={ty.isDefault} /></td>
                  <td className="px-3"><YesNo value={ty.isInRoadmap} /></td>
                  <td className="px-3 text-right tabular-nums">{ty.workPackageCount}</td>
                  <td className="px-3 text-right tabular-nums">{ty.projectCount}</td>
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: ty.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ open: true, editing: ty }) },
                        { label: t("admin.sections.workflows"), icon: <GitBranch />, onSelect: () => navigate(`/admin/workflows?type=${ty.id}`) },
                        { kind: "separator" },
                        { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(ty) },
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {dialog.open && <TypeDialog key={dialog.editing?.id ?? "new"} open editing={dialog.editing} onOpenChange={(o) => setDialog((d) => ({ ...d, open: o }))} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("admin.types.deleteTitle")}
        body={removing && t("admin.types.deleteBody", { name: removing.name })}
        confirm={t("app.delete")}
        pending={m.remove.isPending}
        onConfirm={async () => {
          if (removing) await runToast(m.remove.mutateAsync(removing.id), t("admin.deleted"));
          setRemoving(null);
        }}
      />
    </PageBody>
  );
}
