// 관리: 서비스 달력 — the hours SLA deadlines count: every minute (24시간) or weekly working windows (근무 시간). A calendar
// an SLA policy uses cannot be deleted (409, the server's words in a toast).
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { CalendarClock, MoreHorizontal, Pencil, Plus, Trash2 } from "lucide-react";
import type { ServiceCalendar } from "@/api";
import { useServiceCalendars } from "@/api/hooks";
import { useServiceCalendarMutations } from "@/api/admin-hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Segmented } from "@/components/ui/choice";
import { EmptyState } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ConfirmDialog, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

const DAYS = [1, 2, 3, 4, 5, 6, 7] as const;
type Mode = ServiceCalendar["mode"];
type Window = ServiceCalendar["hours"][number];

/** One working window per weekday in the form; a day with more than one keeps the others as they are. */
function CalendarDialog({ editing, onClose }: { editing: ServiceCalendar | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useServiceCalendarMutations();
  const runToast = useRunWithToast();
  const [name, setName] = useState(editing?.name ?? "");
  const [mode, setMode] = useState<Mode>(editing?.mode ?? "business");
  const [timeZone, setTimeZone] = useState(editing?.timeZone ?? "Asia/Seoul");
  const firstOf = (d: number) => editing?.hours.find((h) => h.day === d);
  const [days, setDays] = useState<Record<number, { start: string; end: string }>>(() =>
    Object.fromEntries(DAYS.map((d) => [d, { start: firstOf(d)?.start ?? (editing ? "" : d <= 5 ? "09:00" : ""), end: firstOf(d)?.end ?? (editing ? "" : d <= 5 ? "18:00" : "") }])),
  );
  const extra: Window[] = DAYS.flatMap((d) => (editing?.hours.filter((h) => h.day === d) ?? []).slice(1));
  const mutation = editing ? m.update : m.create;
  const err = (f: string) => fieldErrorOf(mutation.error, f);
  const dayName = (d: number) => t(`serviceDesk.calendars.days.${d as 1}`);

  const submit = async () => {
    // a day left empty (both times) is a day off; half-filled days go to the server, which says what is wrong
    const hours: Window[] =
      mode === "24x7" ? [] : [...DAYS.filter((d) => days[d].start || days[d].end).map((d) => ({ day: d, start: days[d].start, end: days[d].end })), ...extra].sort((a, b) => a.day - b.day || a.start.localeCompare(b.start));
    const input = { name: name.trim(), mode, timeZone: timeZone.trim(), hours };
    const ok = editing ? await runToast(m.update.mutateAsync({ id: editing.id, patch: input }), t("app.saved")) : await runToast(m.create.mutateAsync(input), t("admin.created"));
    if (ok) onClose();
  };

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={editing ? t("serviceDesk.calendars.edit") : t("serviceDesk.calendars.add")}
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
        <Field label={t("serviceDesk.calendars.name")} required error={err("name")}>
          <Input data-autofocus value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </Field>
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-medium text-fg">{t("serviceDesk.calendars.mode")}</span>
          <Segmented
            aria-label={t("serviceDesk.calendars.mode")}
            value={mode}
            onValueChange={(v) => setMode(v as Mode)}
            options={(["24x7", "business"] as const).map((x) => ({ value: x, label: t(`serviceDesk.calendars.modes.${x}`) }))}
          />
          {err("mode") && <p className="text-xs text-danger">{err("mode")}</p>}
        </div>
        <Field label={t("serviceDesk.calendars.timeZone")} error={err("timeZone")}>
          <Input value={timeZone} maxLength={60} spellCheck={false} className="w-56" onChange={(e) => setTimeZone(e.target.value)} />
        </Field>
        {mode === "business" && (
          <fieldset className="flex flex-col gap-2" aria-describedby={err("hours") ? "sc-hours-err" : "sc-hours-hint"}>
            <legend className="mb-1 text-sm font-medium text-fg">{t("serviceDesk.calendars.hours")}</legend>
            <p id="sc-hours-hint" className="text-xs text-fg-muted">
              {t("serviceDesk.calendars.hoursHint")}
            </p>
            {DAYS.map((d) => (
              <div key={d} className="grid w-fit grid-cols-[2rem_9rem_auto_9rem] items-center gap-2 text-sm">
                <span className="font-medium text-fg">{dayName(d)}</span>
                <Input
                  type="time"
                  size="sm"
                  aria-label={t("serviceDesk.calendars.start", { day: dayName(d) })}
                  value={days[d].start}
                  onChange={(e) => setDays((cur) => ({ ...cur, [d]: { ...cur[d], start: e.target.value } }))}
                />
                <span aria-hidden="true" className="text-fg-muted">
                  –
                </span>
                <Input
                  type="time"
                  size="sm"
                  aria-label={t("serviceDesk.calendars.end", { day: dayName(d) })}
                  value={days[d].end}
                  onChange={(e) => setDays((cur) => ({ ...cur, [d]: { ...cur[d], end: e.target.value } }))}
                />
              </div>
            ))}
            {err("hours") && (
              <p id="sc-hours-err" className="text-xs text-danger">
                {err("hours")}
              </p>
            )}
          </fieldset>
        )}
      </div>
    </Dialog>
  );
}

export function AdminServiceCalendarsPage() {
  const { t } = useTranslation();
  const title = t("admin.sections.serviceCalendars");
  useDocumentTitle(title);
  const list = useServiceCalendars();
  const m = useServiceCalendarMutations();
  const runToast = useRunWithToast();
  const [dialog, setDialog] = useState<{ editing: ServiceCalendar | null } | null>(null);
  const [removing, setRemoving] = useState<ServiceCalendar | null>(null);
  const dayName = (d: number) => t(`serviceDesk.calendars.days.${d as 1}`);
  const hoursText = (c: ServiceCalendar) =>
    c.mode === "24x7" ? t("serviceDesk.calendars.allDay") : c.hours.map((h) => `${dayName(h.day)} ${h.start}–${h.end}`).join(", ");
  return (
    <PageBody>
      <PageHeader
        title={title}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: title }]}
        actions={
          <Button variant="primary" icon={<Plus />} onClick={() => setDialog({ editing: null })}>
            {t("serviceDesk.calendars.add")}
          </Button>
        }
      />
      <AdminGuard>
        <p className="mb-4 max-w-form text-sm text-fg-muted">{t("serviceDesk.calendars.hint")}</p>
        {list.isPending ? (
          <Skeleton className="h-32 w-full" />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => list.refetch()} />
        ) : list.data.length === 0 ? (
          <EmptyState icon={<CalendarClock />} title={t("serviceDesk.calendars.empty")} />
        ) : (
          <AdminTable minWidth={720}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("serviceDesk.calendars.name")}</th>
                <th scope="col" className={th}>{t("serviceDesk.calendars.mode")}</th>
                <th scope="col" className={th}>{t("serviceDesk.calendars.timeZone")}</th>
                <th scope="col" className={th}>{t("serviceDesk.calendars.hours")}</th>
                <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((c) => (
                <tr key={c.id} className={tbodyRow}>
                  <td className="px-3 font-medium text-fg">{c.name}</td>
                  <td className="px-3">
                    <Badge tone={c.mode === "24x7" ? "accent" : "neutral"}>{t(`serviceDesk.calendars.modes.${c.mode}`)}</Badge>
                  </td>
                  <td className="px-3 text-fg-muted">{c.timeZone}</td>
                  <td className="px-3 py-1.5 text-fg-muted tabular-nums">{hoursText(c)}</td>
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: c.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: c }) },
                        { kind: "separator" },
                        { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(c) },
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {dialog && <CalendarDialog key={dialog.editing?.id ?? "new"} editing={dialog.editing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={removing ? t("serviceDesk.calendars.deleteTitle", { name: removing.name }) : ""}
        body={t("serviceDesk.calendars.deleteBody")}
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
