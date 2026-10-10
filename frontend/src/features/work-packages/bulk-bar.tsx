// S06 일괄 편집: what to do with the checked rows — status, assignee, priority, move to a project, delete. All or nothing:
// when one item cannot take the change, nothing changes and each refusal is listed.
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { CircleDot, FolderInput, Flag, Trash2, UserRound, X } from "lucide-react";
import { api, isApiError, type BulkInput, type FilterOptions } from "@/api";
import { invalidateWorkPackages } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Banner, useToast } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { useTargetProjects } from "./transfer-dialogs";

export function BulkBar({ numbers, options, projectId, onDone, onClear }: { numbers: number[]; options?: FilterOptions; projectId?: string; onDone: () => void; onClear: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const projects = useTargetProjects().filter((p) => p.id !== projectId);
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState<{ title: string; items: { field: string; message: string }[] } | null>(null);
  const [dialog, setDialog] = useState<"move" | "delete" | null>(null);
  const [target, setTarget] = useState<string | null>(null);

  const run = async (input: Omit<BulkInput, "numbers">, done: string) => {
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
  const updated = t("bulk.updated", { count: numbers.length });

  return (
    <section aria-label={t("bulk.region")} className="mb-3 flex flex-col gap-2 rounded-md border border-accent bg-accent-subtle px-3 py-2">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-1 text-sm font-semibold text-fg" aria-live="polite">
          {t("bulk.selected", { count: numbers.length })}
        </span>
        <Menu
          trigger={<Button size="sm" icon={<CircleDot />} disabled={busy}>{t("wp.fields.status")}</Button>}
          items={(options?.statuses ?? []).map((s) => ({ label: s.name, onSelect: () => void run({ action: "update", changes: { statusId: s.id } }, updated) }))}
        />
        <Menu
          trigger={<Button size="sm" icon={<UserRound />} disabled={busy}>{t("wp.fields.assignee")}</Button>}
          items={[
            { label: t("wp.unassigned"), onSelect: () => void run({ action: "update", changes: { assigneeId: null } }, updated) },
            ...(options?.users ?? []).map((u) => ({ label: u.name, onSelect: () => void run({ action: "update", changes: { assigneeId: u.id } }, updated) })),
          ]}
        />
        <Menu
          trigger={<Button size="sm" icon={<Flag />} disabled={busy}>{t("wp.fields.priority")}</Button>}
          items={(options?.priorities ?? []).map((p) => ({ label: p.name, onSelect: () => void run({ action: "update", changes: { priorityId: p.id } }, updated) }))}
        />
        <Button size="sm" icon={<FolderInput />} disabled={busy || !projects.length} onClick={() => (setTarget(null), setDialog("move"))}>
          {t("bulk.move")}
        </Button>
        <Button size="sm" variant="danger" icon={<Trash2 />} disabled={busy} onClick={() => setDialog("delete")}>
          {t("app.delete")}
        </Button>
        <Button size="sm" variant="ghost" icon={<X />} onClick={onClear} className="ml-auto">
          {t("bulk.clear")}
        </Button>
      </div>
      {refused && (
        <Banner tone="danger" title={refused.title}>
          <ul className="mt-1 list-disc pl-5">
            {refused.items.map((x) => (
              <li key={x.field}>
                {x.field}: {x.message}
              </li>
            ))}
          </ul>
        </Banner>
      )}
      {dialog === "move" && (
        <Dialog
          open
          onOpenChange={(o) => !o && setDialog(null)}
          title={t("bulk.moveTitle", { count: numbers.length })}
          footer={
            <>
              <Button onClick={() => setDialog(null)}>{t("app.cancel")}</Button>
              <Button variant="primary" loading={busy} disabled={!target} onClick={() => void run({ action: "move", targetProjectId: target! }, t("bulk.moved", { count: numbers.length }))}>
                {t("transfer.move")}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-3">
            <Field label={t("transfer.targetProject")} required>
              <Select value={target ?? ""} placeholder={t("transfer.pickProject")} onValueChange={(v) => setTarget(v || null)} options={projects.map((p) => ({ value: p.id, label: p.name }))} />
            </Field>
            <p className="text-xs text-fg-muted">{t("bulk.moveNote")}</p>
          </div>
        </Dialog>
      )}
      {dialog === "delete" && (
        <Dialog
          open
          onOpenChange={(o) => !o && setDialog(null)}
          title={t("bulk.deleteTitle", { count: numbers.length })}
          footer={
            <>
              <Button onClick={() => setDialog(null)}>{t("app.cancel")}</Button>
              <Button variant="danger" icon={<Trash2 />} loading={busy} onClick={() => void run({ action: "delete" }, t("bulk.deleted", { count: numbers.length }))}>
                {t("app.delete")}
              </Button>
            </>
          }
        >
          <p className="text-sm text-fg">{t("bulk.deleteBody")}</p>
        </Dialog>
      )}
    </section>
  );
}
