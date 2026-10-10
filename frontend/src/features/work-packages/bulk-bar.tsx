// S06 일괄 편집: what to do with the checked rows — status, assignee, priority, copy, move to a project, delete. All or nothing:
// when one item cannot take the change, nothing changes and each refusal is listed. The row's context menu (우클릭) offers the
// same actions through useBulkActions, on the checked rows or the row itself.
import { useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { CircleDot, Copy, FolderInput, Flag, Trash2, UserRound, X } from "lucide-react";
import { api, isApiError, type BulkInput, type FilterOptions } from "@/api";
import { invalidateWorkPackages } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Banner, useToast } from "@/components/ui/feedback";
import { Dialog, Menu, type MenuEntry } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { useTargetProjects } from "./transfer-dialogs";

type Refusal = { title: string; items: { field: string; message: string }[] };

/** The bulk actions, their dialogs and the refusals; shared by the bulk bar and the row menu. */
export function useBulkActions({ options, projectId, onDone }: { options?: FilterOptions; projectId?: string; onDone: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const targets = useTargetProjects();
  const others = targets.filter((p) => p.id !== projectId);
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState<Refusal | null>(null);
  const [dialog, setDialog] = useState<{ kind: "move" | "copy" | "delete"; numbers: number[] } | null>(null);
  const [target, setTarget] = useState<string | null>(null);

  const run = async (numbers: number[], input: Omit<BulkInput, "numbers">, done: string) => {
    setBusy(true);
    setRefused(null);
    try {
      await api.workPackages.bulk({ numbers, ...input });
      invalidateWorkPackages(qc);
      toast({ tone: "success", title: done });
      setDialog(null);
      onDone();
    } catch (e) {
      if (isApiError(e) && e.fieldErrors.length) setRefused({ title: e.message, items: e.fieldErrors });
      else toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle"), description: isApiError(e) && e.message !== e.title ? e.message : undefined });
      setDialog(null);
    } finally {
      setBusy(false);
    }
  };
  const open = (kind: "move" | "copy" | "delete", numbers: number[]) => {
    setTarget(kind === "copy" ? (projectId ?? null) : null);
    setDialog({ kind, numbers });
  };

  const statusItems = (numbers: number[]): MenuEntry[] =>
    (options?.statuses ?? []).map((s) => ({ label: s.name, onSelect: () => void run(numbers, { action: "update", changes: { statusId: s.id } }, t("bulk.updated", { count: numbers.length })) }));
  const assigneeItems = (numbers: number[]): MenuEntry[] => [
    { label: t("wp.unassigned"), onSelect: () => void run(numbers, { action: "update", changes: { assigneeId: null } }, t("bulk.updated", { count: numbers.length })) },
    ...(options?.users ?? []).map((u) => ({ label: u.name, onSelect: () => void run(numbers, { action: "update", changes: { assigneeId: u.id } }, t("bulk.updated", { count: numbers.length })) })),
  ];
  const priorityItems = (numbers: number[]): MenuEntry[] =>
    (options?.priorities ?? []).map((p) => ({ label: p.name, onSelect: () => void run(numbers, { action: "update", changes: { priorityId: p.id } }, t("bulk.updated", { count: numbers.length })) }));

  /** The row menu's part: change, copy, move, delete these items. */
  const menuItems = (numbers: number[]): MenuEntry[] => [
    { kind: "sub", label: t("wp.fields.status"), icon: <CircleDot />, items: statusItems(numbers) },
    { kind: "sub", label: t("wp.fields.assignee"), icon: <UserRound />, items: assigneeItems(numbers) },
    { kind: "sub", label: t("wp.fields.priority"), icon: <Flag />, items: priorityItems(numbers) },
    { kind: "separator" },
    { label: t("bulk.copy"), icon: <Copy />, disabled: !targets.length, onSelect: () => open("copy", numbers) },
    { label: t("bulk.move"), icon: <FolderInput />, disabled: !others.length, onSelect: () => open("move", numbers) },
    { kind: "separator" },
    { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => open("delete", numbers) },
  ];

  const count = dialog?.numbers.length ?? 0;
  const dialogs: ReactNode = (
    <>
      {(dialog?.kind === "move" || dialog?.kind === "copy") && (
        <Dialog
          open
          onOpenChange={(o) => !o && setDialog(null)}
          title={t(dialog.kind === "move" ? "bulk.moveTitle" : "bulk.copyTitle", { count })}
          footer={
            <>
              <Button onClick={() => setDialog(null)}>{t("app.cancel")}</Button>
              <Button
                variant="primary"
                loading={busy}
                disabled={!target}
                onClick={() =>
                  void run(dialog.numbers, { action: dialog.kind, targetProjectId: target! }, t(dialog.kind === "move" ? "bulk.moved" : "bulk.copied", { count }))
                }
              >
                {t(dialog.kind === "move" ? "transfer.move" : "bulk.copyAction")}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-3">
            <Field label={t("transfer.targetProject")} required>
              <Select
                value={target ?? ""}
                placeholder={t("transfer.pickProject")}
                onValueChange={(v) => setTarget(v || null)}
                options={(dialog.kind === "move" ? others : targets).map((p) => ({ value: p.id, label: p.name }))}
              />
            </Field>
            <p className="text-xs text-fg-muted">{t(dialog.kind === "move" ? "bulk.moveNote" : "bulk.copyNote")}</p>
          </div>
        </Dialog>
      )}
      {dialog?.kind === "delete" && (
        <Dialog
          open
          onOpenChange={(o) => !o && setDialog(null)}
          title={t("bulk.deleteTitle", { count })}
          footer={
            <>
              <Button onClick={() => setDialog(null)}>{t("app.cancel")}</Button>
              <Button variant="danger" icon={<Trash2 />} loading={busy} onClick={() => void run(dialog.numbers, { action: "delete" }, t("bulk.deleted", { count }))}>
                {t("app.delete")}
              </Button>
            </>
          }
        >
          <p className="text-sm text-fg">{t("bulk.deleteBody")}</p>
        </Dialog>
      )}
    </>
  );
  const refusals: ReactNode = refused && (
    <Banner tone="danger" title={refused.title}>
      <ul className="mt-1 list-disc pl-5">
        {refused.items.map((x) => (
          <li key={x.field}>
            {x.field}: {x.message}
          </li>
        ))}
      </ul>
    </Banner>
  );
  return { busy, open, statusItems, assigneeItems, priorityItems, menuItems, dialogs, refusals, hasTargets: targets.length > 0, hasOthers: others.length > 0 };
}

export type BulkActions = ReturnType<typeof useBulkActions>;

export function BulkBar({ numbers, actions, onClear }: { numbers: number[]; actions: BulkActions; onClear: () => void }) {
  const { t } = useTranslation();
  const { busy } = actions;
  return (
    <section aria-label={t("bulk.region")} className="mb-3 flex flex-col gap-2 rounded-md border border-accent bg-accent-subtle px-3 py-2">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-1 text-sm font-semibold text-fg" aria-live="polite">
          {t("bulk.selected", { count: numbers.length })}
        </span>
        <Menu trigger={<Button size="sm" icon={<CircleDot />} disabled={busy}>{t("wp.fields.status")}</Button>} items={actions.statusItems(numbers)} />
        <Menu trigger={<Button size="sm" icon={<UserRound />} disabled={busy}>{t("wp.fields.assignee")}</Button>} items={actions.assigneeItems(numbers)} />
        <Menu trigger={<Button size="sm" icon={<Flag />} disabled={busy}>{t("wp.fields.priority")}</Button>} items={actions.priorityItems(numbers)} />
        <Button size="sm" icon={<Copy />} disabled={busy || !actions.hasTargets} onClick={() => actions.open("copy", numbers)}>
          {t("bulk.copy")}
        </Button>
        <Button size="sm" icon={<FolderInput />} disabled={busy || !actions.hasOthers} onClick={() => actions.open("move", numbers)}>
          {t("bulk.move")}
        </Button>
        <Button size="sm" variant="danger" icon={<Trash2 />} disabled={busy} onClick={() => actions.open("delete", numbers)}>
          {t("app.delete")}
        </Button>
        <Button size="sm" variant="ghost" icon={<X />} onClick={onClear} className="ml-auto">
          {t("bulk.clear")}
        </Button>
      </div>
    </section>
  );
}
