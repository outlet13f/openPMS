// S30 관리: 상태, S36 관리: 우선순위
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { MoreHorizontal, Pencil, Plus, Trash2 } from "lucide-react";
import type { AdminPriority, AdminStatus, PriorityInput, StatusInput } from "@/api";
import { useAdminPriorities, useAdminStatuses, usePriorityMutations, useStatusMutations } from "@/api/admin-hooks";
import { Badge, Priority, StatusPill } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ColorField, ConfirmDialog, OrderMenu, YesNo, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

/* ---------- statuses ---------- */

const EMPTY_STATUS: StatusInput = { name: "", color: "#0b7575", isDefault: false, isClosed: false, isReadonly: false, defaultDoneRatio: 0, excludedFromTotals: false };

function StatusDialog({ editing, onClose }: { editing: AdminStatus | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useStatusMutations();
  const runToast = useRunWithToast();
  const [v, setV] = useState<StatusInput>(editing ? { ...editing } : EMPTY_STATUS);
  const mutation = editing ? m.update : m.create;
  const submit = async () => {
    const ok = editing ? await runToast(m.update.mutateAsync({ id: editing.id, patch: v }), t("app.saved")) : await runToast(m.create.mutateAsync(v), t("admin.created"));
    if (ok) onClose();
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={editing ? t("admin.statuses.edit") : t("admin.statuses.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={mutation.isPending} onClick={() => void submit()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("admin.name")} required error={fieldErrorOf(mutation.error, "name")}>
          <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </Field>
        <ColorField label={t("admin.color")} value={v.color} error={fieldErrorOf(mutation.error, "color")} onChange={(color) => setV({ ...v, color })} />
        <Field label={t("admin.statuses.doneRatio")} hint={t("admin.statuses.doneRatioHint")} error={fieldErrorOf(mutation.error, "defaultDoneRatio")}>
          <Input type="number" min={0} max={100} className="w-28" value={v.defaultDoneRatio} onChange={(e) => setV({ ...v, defaultDoneRatio: Number(e.target.value) })} />
        </Field>
        <div className="flex flex-col gap-3">
          <Checkbox label={t("admin.statuses.isDefault")} description={t("admin.statuses.isDefaultHint")} checked={v.isDefault} disabled={editing?.isDefault} onCheckedChange={(c) => setV({ ...v, isDefault: c === true })} />
          <Checkbox label={t("admin.statuses.isClosed")} description={t("admin.statuses.isClosedHint")} checked={v.isClosed} onCheckedChange={(c) => setV({ ...v, isClosed: c === true })} />
          <Checkbox label={t("admin.statuses.isReadonly")} description={t("admin.statuses.isReadonlyHint")} checked={v.isReadonly} onCheckedChange={(c) => setV({ ...v, isReadonly: c === true })} />
          <Checkbox label={t("admin.statuses.excluded")} description={t("admin.statuses.excludedHint")} checked={v.excludedFromTotals} onCheckedChange={(c) => setV({ ...v, excludedFromTotals: c === true })} />
        </div>
        <p className="text-xs text-fg-muted">
          {t("admin.preview")}: <StatusPill name={v.name || t("admin.name")} color={/^#[0-9a-fA-F]{6}$/.test(v.color) ? v.color : "#000000"} />
        </p>
      </div>
    </Dialog>
  );
}

export function AdminStatusesPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.statuses"));
  const list = useAdminStatuses();
  const m = useStatusMutations();
  const runToast = useRunWithToast();
  const [dialog, setDialog] = useState<{ editing: AdminStatus | null } | null>(null);
  const [removing, setRemoving] = useState<AdminStatus | null>(null);
  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.statuses")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.statuses") }]}
        actions={
          <Button variant="primary" icon={<Plus />} onClick={() => setDialog({ editing: null })}>
            {t("admin.statuses.new")}
          </Button>
        }
      />
      <AdminGuard>
        {list.isPending ? (
          <Skeleton className="h-48 w-full" />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => list.refetch()} />
        ) : (
          <AdminTable minWidth={820}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("admin.order")}</span></th>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={th}>{t("admin.statuses.isDefaultShort")}</th>
                <th scope="col" className={th}>{t("admin.statuses.isClosed")}</th>
                <th scope="col" className={th}>{t("admin.statuses.isReadonly")}</th>
                <th scope="col" className={`${th} text-right`}>{t("admin.statuses.doneRatioShort")}</th>
                <th scope="col" className={th}>{t("admin.statuses.excludedShort")}</th>
                <th scope="col" className={`${th} text-right`}>{t("admin.workPackages")}</th>
                <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((s) => (
                <tr key={s.id} className={tbodyRow}>
                  <td className="px-2"><OrderMenu items={list.data} item={s} label={s.name} onReorder={(ids) => void runToast(m.reorder.mutateAsync(ids))} /></td>
                  <td className="px-3"><StatusPill name={s.name} color={s.color} /></td>
                  <td className="px-3">{s.isDefault ? <Badge tone="accent">{t("admin.default")}</Badge> : <YesNo value={false} />}</td>
                  <td className="px-3"><YesNo value={s.isClosed} /></td>
                  <td className="px-3"><YesNo value={s.isReadonly} /></td>
                  <td className="px-3 text-right tabular-nums">{s.defaultDoneRatio}%</td>
                  <td className="px-3"><YesNo value={s.excludedFromTotals} /></td>
                  <td className="px-3 text-right tabular-nums">{s.workPackageCount}</td>
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: s.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: s }) },
                        { kind: "separator" },
                        { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(s) },
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {dialog && <StatusDialog key={dialog.editing?.id ?? "new"} editing={dialog.editing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("admin.statuses.deleteTitle")}
        body={removing && t("admin.statuses.deleteBody", { name: removing.name })}
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

/* ---------- priorities ---------- */

const EMPTY_PRIORITY: PriorityInput = { name: "", color: "#1f5fc4", isDefault: false, isActive: true };

function PriorityDialog({ editing, onClose }: { editing: AdminPriority | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = usePriorityMutations();
  const runToast = useRunWithToast();
  const [v, setV] = useState<PriorityInput>(editing ? { name: editing.name, color: editing.color, isDefault: editing.isDefault, isActive: editing.isActive } : EMPTY_PRIORITY);
  const mutation = editing ? m.update : m.create;
  const submit = async () => {
    const ok = editing ? await runToast(m.update.mutateAsync({ id: editing.id, patch: v }), t("app.saved")) : await runToast(m.create.mutateAsync(v), t("admin.created"));
    if (ok) onClose();
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={editing ? t("admin.priorities.edit") : t("admin.priorities.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={mutation.isPending} onClick={() => void submit()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("admin.name")} required error={fieldErrorOf(mutation.error, "name")}>
          <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </Field>
        <ColorField label={t("admin.color")} value={v.color} error={fieldErrorOf(mutation.error, "color")} onChange={(color) => setV({ ...v, color })} />
        <Checkbox label={t("admin.priorities.isDefault")} checked={v.isDefault} disabled={editing?.isDefault} onCheckedChange={(c) => setV({ ...v, isDefault: c === true })} />
        <Checkbox label={t("admin.priorities.isActive")} description={t("admin.priorities.isActiveHint")} checked={v.isActive} onCheckedChange={(c) => setV({ ...v, isActive: c === true })} />
      </div>
    </Dialog>
  );
}

export function AdminPrioritiesPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.priorities"));
  const list = useAdminPriorities();
  const m = usePriorityMutations();
  const runToast = useRunWithToast();
  const [dialog, setDialog] = useState<{ editing: AdminPriority | null } | null>(null);
  const [removing, setRemoving] = useState<AdminPriority | null>(null);
  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.priorities")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.priorities") }]}
        actions={
          <Button variant="primary" icon={<Plus />} onClick={() => setDialog({ editing: null })}>
            {t("admin.priorities.new")}
          </Button>
        }
      />
      <AdminGuard>
        {list.isPending ? (
          <Skeleton className="h-40 w-full" />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => list.refetch()} />
        ) : (
          <AdminTable minWidth={560}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("admin.order")}</span></th>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={th}>{t("admin.default")}</th>
                <th scope="col" className={th}>{t("admin.priorities.isActiveShort")}</th>
                <th scope="col" className={`${th} text-right`}>{t("admin.workPackages")}</th>
                <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((p) => (
                <tr key={p.id} className={tbodyRow}>
                  <td className="px-2"><OrderMenu items={list.data} item={p} label={p.name} onReorder={(ids) => void runToast(m.reorder.mutateAsync(ids))} /></td>
                  <td className="px-3"><Priority name={p.name} color={p.color} /></td>
                  <td className="px-3">{p.isDefault ? <Badge tone="accent">{t("admin.default")}</Badge> : <YesNo value={false} />}</td>
                  <td className="px-3">{p.isActive ? <YesNo value /> : <Badge tone="warning">{t("admin.priorities.hidden")}</Badge>}</td>
                  <td className="px-3 text-right tabular-nums">{p.workPackageCount}</td>
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: p.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: p }) },
                        { kind: "separator" },
                        { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(p) },
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {dialog && <PriorityDialog key={dialog.editing?.id ?? "new"} editing={dialog.editing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("admin.priorities.deleteTitle")}
        body={removing && t("admin.priorities.deleteBody", { name: removing.name })}
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
