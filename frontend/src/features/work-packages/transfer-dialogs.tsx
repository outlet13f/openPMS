// S08 복사 · 다른 프로젝트로 이동 (and the project picker the bulk bar shares). The target's create form tells which types
// it uses, so a type that does not fit is caught before sending.
import { useState } from "react";
import { useNavigate } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Copy, FolderInput } from "lucide-react";
import { api, isApiError, type WorkPackage } from "@/api";
import { invalidateWorkPackages, useProjects, useWpForm } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner, useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";

/** Active projects where I may create work packages. */
/** 상위 프로젝트 후보: active projects the person may edit, minus the project itself and those below it. */
export function useParentCandidates(self?: { id: string }) {
  const q = useProjects({ filter: "active", pageSize: 100 });
  const all = q.data?.items ?? [];
  const below = new Set<string>(self ? [self.id] : []);
  for (let grew = true; grew; ) {
    grew = false;
    for (const p of all) if (p.parent && below.has(p.parent.id) && !below.has(p.id)) (below.add(p.id), (grew = true));
  }
  return all.filter((p) => p.myPermissions.includes("project.edit") && !below.has(p.id));
}

export function useTargetProjects() {
  const q = useProjects({ filter: "active", pageSize: 100 });
  return (q.data?.items ?? []).filter((p) => p.myPermissions.includes("wp.create"));
}

const messageOf = (e: unknown, fallback: string) => (isApiError(e) ? (e.fieldErrors[0]?.message ?? (e.message !== e.title ? `${e.title} ${e.message}` : e.title)) : fallback);

export function CopyDialog({ wp, onClose }: { wp: WorkPackage; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const projects = useTargetProjects();
  const [target, setTarget] = useState(wp.project.id);
  const [subject, setSubject] = useState(wp.subject);
  const [children, setChildren] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useWpForm(target);
  const typeMissing = !!form.data && !form.data.types.some((x) => x.id === wp.type.id);

  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      const made = await api.workPackages.copy(wp.number, { targetProjectId: target, subject: subject.trim() || undefined, includeChildren: children });
      invalidateWorkPackages(qc);
      toast({ tone: "success", title: t("transfer.copied", { number: made.number }), description: made.subject });
      onClose();
      navigate(`/projects/${made.project.identifier}/work-packages/${made.number}/activity`);
    } catch (e) {
      setError(messageOf(e, t("app.errorBody")));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={t("transfer.copyTitle", { number: wp.number })}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" icon={<Copy />} loading={busy} disabled={typeMissing || !subject.trim()} onClick={() => void run()}>
            {t("transfer.copy")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && <Banner tone="danger">{error}</Banner>}
        <Field label={t("transfer.targetProject")}>
          <Select value={target} onValueChange={(v) => v && setTarget(v)} options={projects.map((p) => ({ value: p.id, label: p.name }))} />
        </Field>
        {typeMissing && <Banner tone="warning">{t("transfer.typeMissingCopy", { type: wp.type.name })}</Banner>}
        <Field label={t("wp.fields.subject")} required>
          <Input maxLength={255} value={subject} onChange={(e) => setSubject(e.target.value)} />
        </Field>
        <Checkbox label={t("transfer.includeChildren")} description={t("transfer.includeChildrenHint")} checked={children} onCheckedChange={(c) => setChildren(c === true)} />
        <p className="text-xs text-fg-muted">{t("transfer.copyNote")}</p>
      </div>
    </Dialog>
  );
}

export function MoveDialog({ wp, onClose }: { wp: WorkPackage; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const projects = useTargetProjects().filter((p) => p.id !== wp.project.id);
  const [target, setTarget] = useState<string | null>(null);
  const [typeId, setTypeId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useWpForm(target ?? undefined);
  const types = target && form.data ? form.data.types : [];
  const typeFits = types.some((x) => x.id === wp.type.id);
  const needType = !!target && !!form.data && !typeFits;

  const run = async () => {
    if (!target) return;
    setBusy(true);
    setError(null);
    try {
      const moved = await api.workPackages.move(wp.number, { targetProjectId: target, typeId: needType ? (typeId ?? undefined) : undefined, lockVersion: wp.lockVersion });
      invalidateWorkPackages(qc);
      toast({ tone: "success", title: t("transfer.moved", { number: moved.number, project: moved.project.name }) });
      onClose();
      navigate(`/projects/${moved.project.identifier}/work-packages/${moved.number}/activity`);
    } catch (e) {
      setError(messageOf(e, t("app.errorBody")));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={t("transfer.moveTitle", { number: wp.number })}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" icon={<FolderInput />} loading={busy} disabled={!target || !form.data || (needType && !typeId)} onClick={() => void run()}>
            {t("transfer.move")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && <Banner tone="danger">{error}</Banner>}
        {projects.length === 0 ? (
          <Banner tone="info">{t("transfer.noTargets")}</Banner>
        ) : (
          <Field label={t("transfer.targetProject")} required>
            <Select value={target ?? ""} placeholder={t("transfer.pickProject")} onValueChange={(v) => (setTarget(v || null), setTypeId(null))} options={projects.map((p) => ({ value: p.id, label: p.name }))} />
          </Field>
        )}
        {needType && (
          <Field label={t("transfer.newType")} required hint={t("transfer.newTypeHint", { type: wp.type.name })}>
            <Select value={typeId ?? ""} placeholder={t("transfer.pickType")} onValueChange={(v) => setTypeId(v || null)} options={types.map((x) => ({ value: x.id, label: x.name }))} />
          </Field>
        )}
        <p className="text-xs text-fg-muted">{t("transfer.moveNote")}</p>
      </div>
    </Dialog>
  );
}
