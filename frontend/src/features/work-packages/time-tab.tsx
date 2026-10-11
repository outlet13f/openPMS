// A work package's 시간 tab: everyone's time on it (for whoever may see time in the project), the total, and logging
// or changing an entry. The weekly sheet (내 공수) is the faster way to fill a week; this is for details (activity, note).
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Clock, Pencil, Plus, Trash2 } from "lucide-react";
import { isApiError, type TimeEntry, type WorkPackage } from "@/api";
import { useTimeActivities, useTimeEntries, useTimeMutations } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { formatDate, todayIso } from "@/lib/dates";
import { ErrorState } from "@/app/page";
import { parseHours } from "@/pages/timesheet";

type Draft = { spentOn: string; hours: string; activityId: string; comment: string };

function EntryDialog({ title, initial, onSubmit, onClose }: { title: string; initial: Draft; onSubmit: (d: Draft) => Promise<unknown>; onClose: () => void }) {
  const { t } = useTranslation();
  const activities = useTimeActivities();
  const [d, setD] = useState(initial);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const hours = parseHours(d.hours);
  const submit = async () => {
    setBusy(true);
    try {
      await onSubmit(d);
      onClose();
    } catch (e) {
      if (!isApiError(e)) throw e;
      setErrors(Object.fromEntries((e.fieldErrors.length ? e.fieldErrors : [{ field: "hours", message: e.message }]).map((f) => [f.field, f.message])));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={title}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={busy} disabled={!d.spentOn || !(hours > 0 && hours <= 24)} onClick={() => void submit()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label={t("time.date")} error={errors.spentOn} required>
          <Input type="date" value={d.spentOn} onChange={(e) => setD({ ...d, spentOn: e.target.value })} />
        </Field>
        <Field label={t("time.hours")} error={errors.hours} hint="1.5 또는 1:30" required>
          <Input inputMode="decimal" value={d.hours} onChange={(e) => setD({ ...d, hours: e.target.value })} autoFocus />
        </Field>
        <div className="col-span-2">
          <Field label={t("time.activity")} error={errors.activityId}>
            <Select
              aria-label={t("time.activity")}
              value={d.activityId}
              onValueChange={(v) => setD({ ...d, activityId: v })}
              options={(activities.data ?? []).map((a) => ({ value: a.id, label: a.name }))}
            />
          </Field>
        </div>
        <div className="col-span-2">
          <Field label={t("time.comment")} error={errors.comment}>
            <Input value={d.comment} maxLength={1000} onChange={(e) => setD({ ...d, comment: e.target.value })} />
          </Field>
        </div>
      </div>
    </Dialog>
  );
}

export function TimeTab({ wp, canLog }: { wp: WorkPackage; canLog: boolean }) {
  const { t } = useTranslation();
  const toast = useToast();
  const list = useTimeEntries({ workPackage: wp.number });
  const activities = useTimeActivities();
  const m = useTimeMutations();
  const [editing, setEditing] = useState<TimeEntry | "new" | null>(null);
  const defaultActivity = activities.data?.find((a) => a.isDefault)?.id ?? activities.data?.[0]?.id ?? "";
  const total = (list.data ?? []).reduce((s, e) => s + e.hours, 0);

  if (list.isPending) return <Skeleton className="mt-3 h-24 w-full" />;
  if (list.isError) return <ErrorState error={list.error} onRetry={() => list.refetch()} />;
  return (
    <section aria-label={t("time.tabTitle")} className="flex flex-col gap-3 pt-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium tabular-nums">{t("time.tabTotal", { hours: Math.round(total * 100) / 100 })}</p>
        {canLog && (
          <Button size="sm" icon={<Plus />} onClick={() => setEditing("new")}>
            {t("time.logTime")}
          </Button>
        )}
      </div>
      {list.data.length === 0 ? (
        <EmptyState compact icon={<Clock />} title={t("time.none")} />
      ) : (
        <ul className="divide-y divide-border rounded-md border border-border">
          {list.data.map((e) => (
            <li key={e.id} className="flex items-start gap-3 px-3 py-2 text-sm">
              <span className="w-20 shrink-0 tabular-nums text-fg-muted">{formatDate(e.spentOn)}</span>
              <span className="min-w-0 flex-1">
                <span className="font-medium">{e.user.name}</span>
                {e.activity && <span className="ml-2 text-xs text-fg-muted">{e.activity.name}</span>}
                {e.comment && <span className="block truncate text-fg-muted" title={e.comment}>{e.comment}</span>}
              </span>
              <span className="shrink-0 tabular-nums font-medium">{t("time.hoursUnit", { hours: e.hours })}</span>
              {e.canEdit && (
                <span className="flex shrink-0 gap-1">
                  <Button size="sm" variant="ghost" iconOnly aria-label={`${t("time.edit")}: ${formatDate(e.spentOn)} ${e.user.name}`} icon={<Pencil />} onClick={() => setEditing(e)} />
                  <Button
                    size="sm"
                    variant="ghost"
                    iconOnly
                    aria-label={`${t("time.remove")}: ${formatDate(e.spentOn)} ${e.user.name}`}
                    icon={<Trash2 />}
                    onClick={() => window.confirm(t("time.removeConfirm")) && m.remove.mutate(e.id, { onSuccess: () => toast({ tone: "success", title: t("time.removed") }) })}
                  />
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing && (
        <EntryDialog
          title={editing === "new" ? t("time.logTime") : t("time.edit")}
          initial={
            editing === "new"
              ? { spentOn: todayIso(), hours: "", activityId: defaultActivity, comment: "" }
              : { spentOn: editing.spentOn, hours: String(editing.hours), activityId: editing.activity?.id ?? defaultActivity, comment: editing.comment }
          }
          onClose={() => setEditing(null)}
          onSubmit={async (d) => {
            const body = { spentOn: d.spentOn, hours: Math.round(parseHours(d.hours) * 100) / 100, activityId: d.activityId || null, comment: d.comment };
            if (editing === "new") await m.create.mutateAsync({ workPackageNumber: wp.number, ...body });
            else await m.update.mutateAsync({ id: editing.id, patch: body });
            toast({ tone: "success", title: t("time.logged") });
          }}
        />
      )}
    </section>
  );
}
