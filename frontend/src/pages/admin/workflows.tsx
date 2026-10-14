// S29 관리: 워크플로 — which status changes each role may make, per type (architecture.md §3.2).
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Copy, RotateCcw, Save } from "lucide-react";
import type { Transition } from "@/api";
import { useAdminRoles, useAdminStatuses, useAdminTypes, useWorkflow, useWorkflowMutations } from "@/api/admin-hooks";
import { Button } from "@/components/ui/button";
import { StatusPill } from "@/components/ui/badges";
import { Checkbox } from "@/components/ui/choice";
import { Field } from "@/components/ui/field";
import { Banner } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { ConfirmDialog, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

const key = (from: string, to: string) => `${from}>${to}`;
/** Project roles whose workflow can be edited: the built-in 요청자 too (the portal checks it), not 비멤버 (never used for status changes). */
const workflowRole = (r: { scope: string; builtin: string | null }) => r.scope === "project" && r.builtin !== "non_member";

function CopyDialog({ open, onOpenChange, typeId, roleId }: { open: boolean; onOpenChange: (o: boolean) => void; typeId: string; roleId: string }) {
  const { t } = useTranslation();
  const types = useAdminTypes().data ?? [];
  const roles = (useAdminRoles().data ?? []).filter(workflowRole);
  const { copy } = useWorkflowMutations();
  const runToast = useRunWithToast();
  const [toTypes, setToTypes] = useState<string[]>([typeId]);
  const [toRoles, setToRoles] = useState<string[]>([]);
  const toggle = (list: string[], id: string, on: boolean) => (on ? [...new Set([...list, id])] : list.filter((x) => x !== id));
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="md"
      title={t("admin.workflows.copyTitle")}
      description={t("admin.workflows.copyBody")}
      footer={
        <>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            loading={copy.isPending}
            disabled={!toTypes.length || !toRoles.length}
            onClick={async () => {
              if (await runToast(copy.mutateAsync({ from: { typeId, roleId }, to: { typeIds: toTypes, roleIds: toRoles } }), t("admin.workflows.copied")) !== null) onOpenChange(false);
            }}
          >
            {t("admin.workflows.copy")}
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-semibold text-fg">{t("admin.workflows.toTypes")}</legend>
          {types.map((ty) => (
            <Checkbox key={ty.id} label={ty.name} checked={toTypes.includes(ty.id)} onCheckedChange={(c) => setToTypes((l) => toggle(l, ty.id, c === true))} />
          ))}
        </fieldset>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-semibold text-fg">{t("admin.workflows.toRoles")}</legend>
          {roles.map((r) => (
            <Checkbox key={r.id} label={r.name} checked={toRoles.includes(r.id)} onCheckedChange={(c) => setToRoles((l) => toggle(l, r.id, c === true))} />
          ))}
        </fieldset>
      </div>
    </Dialog>
  );
}

export function AdminWorkflowsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.workflows"));
  const [params, setParams] = useSearchParams();
  const types = useAdminTypes();
  const roles = useAdminRoles();
  const statuses = useAdminStatuses();
  const projectRoles = (roles.data ?? []).filter(workflowRole);
  const typeId = params.get("type") ?? types.data?.[0]?.id;
  const roleId = params.get("role") ?? projectRoles.find((r) => r.name === "멤버")?.id ?? projectRoles[0]?.id;
  const wf = useWorkflow(typeId, roleId);
  const { save } = useWorkflowMutations();
  const runToast = useRunWithToast();
  const [cells, setCells] = useState<Set<string>>(new Set());
  const [pending, setPending] = useState<{ k: "type" | "role"; v: string } | null>(null);
  const [copying, setCopying] = useState(false);

  const original = useMemo(() => new Set((wf.data ?? []).map(([a, b]) => key(a, b))), [wf.data]);
  useEffect(() => setCells(new Set(original)), [original]);
  const dirty = cells.size !== original.size || [...cells].some((c) => !original.has(c));

  const select = (k: "type" | "role", v: string) => {
    if (dirty) setPending({ k, v });
    else setParams((p) => (p.set(k, v), p), { replace: true });
  };
  const set = (from: string, to: string, on: boolean) =>
    setCells((s) => {
      const n = new Set(s);
      if (on) n.add(key(from, to));
      else n.delete(key(from, to));
      return n;
    });

  const list = statuses.data ?? [];
  const rowAll = (from: string) => list.filter((s) => s.id !== from).every((s) => cells.has(key(from, s.id)));
  const colAll = (to: string) => list.filter((s) => s.id !== to).every((s) => cells.has(key(s.id, to)));
  const typeName = types.data?.find((x) => x.id === typeId)?.name ?? "";
  const roleName = projectRoles.find((x) => x.id === roleId)?.name ?? "";

  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.workflows")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.workflows") }]}
        actions={
          <>
            <Button icon={<Copy />} disabled={!typeId || !roleId || dirty} onClick={() => setCopying(true)}>
              {t("admin.workflows.copy")}
            </Button>
            <Button icon={<RotateCcw />} disabled={!dirty} onClick={() => setCells(new Set(original))}>
              {t("admin.revert")}
            </Button>
            <Button
              variant="primary"
              icon={<Save />}
              disabled={!dirty}
              loading={save.isPending}
              onClick={() => typeId && roleId && void runToast(save.mutateAsync({ typeId, roleId, transitions: [...cells].map((c) => c.split(">") as Transition) }), t("app.saved"))}
            >
              {t("app.save")}
            </Button>
          </>
        }
      >
        <p className="mt-1 max-w-3xl text-sm text-fg-muted">{t("admin.workflows.intro")}</p>
      </PageHeader>
      <AdminGuard>
        {types.isError || roles.isError || statuses.isError ? (
          <ErrorState error={types.error ?? roles.error ?? statuses.error} />
        ) : !types.data || !roles.data || !statuses.data ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="grid max-w-modal-md grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label={t("admin.workflows.type")}>
                <Select value={typeId} onValueChange={(v) => v && select("type", v)} options={types.data.map((x) => ({ value: x.id, label: x.name }))} />
              </Field>
              <Field label={t("admin.workflows.role")}>
                <Select value={roleId} onValueChange={(v) => v && select("role", v)} options={projectRoles.map((x) => ({ value: x.id, label: x.name }))} />
              </Field>
            </div>
            {wf.isPending ? (
              <Skeleton className="h-64 w-full" />
            ) : cells.size === 0 ? (
              <Banner tone="warning" title={t("admin.workflows.emptyTitle")}>
                {t("admin.workflows.emptyBody", { type: typeName, role: roleName })}
              </Banner>
            ) : null}
            {!wf.isPending && (
              <div className="relative overflow-x-auto rounded-md border border-border">
                <table className="w-max min-w-full border-collapse text-sm">
                  <caption className="sr-only">{t("admin.workflows.caption", { type: typeName, role: roleName })}</caption>
                  <thead>
                    <tr className="border-b border-border bg-surface">
                      <th scope="col" className="px-3 py-2 text-left text-xs font-semibold text-fg-muted">
                        {t("admin.workflows.fromTo")}
                      </th>
                      {list.map((to) => (
                        <th key={to.id} scope="col" className="px-3 py-2 text-center">
                          <div className="flex flex-col items-center gap-1.5">
                            <StatusPill size="sm" name={to.name} color={to.color} />
                            <Button
                              variant="link"
                              size="sm"
                              className="h-5 text-xs"
                              aria-label={t(colAll(to.id) ? "admin.workflows.clearColumn" : "admin.workflows.fillColumn", { status: to.name })}
                              onClick={() => list.filter((s) => s.id !== to.id).forEach((s) => set(s.id, to.id, !colAll(to.id)))}
                            >
                              {colAll(to.id) ? t("admin.workflows.none") : t("admin.workflows.all")}
                            </Button>
                          </div>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {list.map((from) => (
                      <tr key={from.id} className="h-row border-b border-border-muted last:border-b-0">
                        <th scope="row" className="px-3 text-left font-normal">
                          <div className="flex items-center justify-between gap-3">
                            <StatusPill size="sm" name={from.name} color={from.color} />
                            <Button
                              variant="link"
                              size="sm"
                              className="h-5 text-xs"
                              aria-label={t(rowAll(from.id) ? "admin.workflows.clearRow" : "admin.workflows.fillRow", { status: from.name })}
                              onClick={() => list.filter((s) => s.id !== from.id).forEach((s) => set(from.id, s.id, !rowAll(from.id)))}
                            >
                              {rowAll(from.id) ? t("admin.workflows.none") : t("admin.workflows.all")}
                            </Button>
                          </div>
                        </th>
                        {list.map((to) => (
                          <td key={to.id} className="px-3 text-center">
                            {from.id === to.id ? (
                              <span className="text-fg-disabled" aria-hidden="true">—</span>
                            ) : (
                              <span className="inline-flex justify-center">
                                <Checkbox
                                  aria-label={t("admin.workflows.cell", { from: from.name, to: to.name })}
                                  checked={cells.has(key(from.id, to.id))}
                                  onCheckedChange={(c) => set(from.id, to.id, c === true)}
                                />
                              </span>
                            )}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </AdminGuard>
      {copying && typeId && roleId && <CopyDialog open onOpenChange={setCopying} typeId={typeId} roleId={roleId} />}
      <ConfirmDialog
        open={!!pending}
        onOpenChange={(o) => !o && setPending(null)}
        title={t("admin.discardTitle")}
        body={t("admin.discardBody")}
        confirm={t("admin.discard")}
        onConfirm={() => {
          if (pending) setParams((p) => (p.set(pending.k, pending.v), p), { replace: true });
          setPending(null);
        }}
      />
    </PageBody>
  );
}
