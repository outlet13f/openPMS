// S33 관리: 근무일 및 휴무일 — changes here re-plan every schedule (architecture.md §3.3 S11).
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { CalendarOff, ChevronLeft, ChevronRight, Plus, Save, Trash2 } from "lucide-react";
import { useAdminNonWorkingDays, useCalendarMutations, useCalendarSettings } from "@/api/admin-hooks";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner, EmptyState } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { formatDateLong, todayIso } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { ConfirmDialog, fieldErrorOf, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

const DAYS = [1, 2, 3, 4, 5, 6, 7] as const;

export function AdminCalendarPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.calendar"));
  const settings = useCalendarSettings();
  const m = useCalendarMutations();
  const runToast = useRunWithToast();
  const [days, setDays] = useState<number[]>([]);
  const [hours, setHours] = useState(8);
  const [confirming, setConfirming] = useState(false);
  const [year, setYear] = useState(Number(todayIso().slice(0, 4)));
  const holidays = useAdminNonWorkingDays(year);
  const [newDate, setNewDate] = useState("");
  const [newName, setNewName] = useState("");
  useEffect(() => {
    if (settings.data) {
      setDays(settings.data.workingDays);
      setHours(settings.data.hoursPerDay);
    }
  }, [settings.data]);
  const daysChanged = !!settings.data && days.slice().sort().join() !== settings.data.workingDays.join();
  const dirty = daysChanged || (!!settings.data && hours !== settings.data.hoursPerDay);

  const save = async () => {
    setConfirming(false);
    await runToast(m.save.mutateAsync({ workingDays: days, hoursPerDay: hours }), daysChanged ? t("admin.calendar.savedRescheduled") : t("app.saved"));
  };
  const add = async () => {
    const ok = await runToast(m.add.mutateAsync({ date: newDate, name: newName }), t("admin.calendar.holidayAdded"));
    if (ok !== null) {
      setNewDate("");
      setNewName("");
    }
  };

  return (
    <PageBody>
      <PageHeader title={t("admin.sections.calendar")} breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.calendar") }]} />
      <AdminGuard>
        {settings.isPending ? (
          <Skeleton className="h-64 w-full" />
        ) : settings.isError ? (
          <ErrorState error={settings.error} onRetry={() => settings.refetch()} />
        ) : (
          <div className="grid grid-cols-1 gap-8 xl:grid-cols-2">
            <section aria-labelledby="workweek-h" className="flex flex-col gap-4">
              <h2 id="workweek-h" className="text-lg text-fg">{t("admin.calendar.workWeek")}</h2>
              <Banner tone="warning">{t("admin.calendar.warning")}</Banner>
              <fieldset className="flex flex-wrap gap-x-5 gap-y-2">
                <legend className="mb-2 text-sm font-medium text-fg">{t("admin.calendar.workingDays")}</legend>
                {DAYS.map((d) => (
                  <Checkbox
                    key={d}
                    label={t(`admin.calendar.weekdays.${d}` as "admin.calendar.weekdays.1")}
                    checked={days.includes(d)}
                    onCheckedChange={(c) => setDays((s) => (c === true ? [...new Set([...s, d])] : s.filter((x) => x !== d)))}
                  />
                ))}
              </fieldset>
              {fieldErrorOf(m.save.error, "workingDays") && <p className="text-xs text-danger">{fieldErrorOf(m.save.error, "workingDays")}</p>}
              <Field label={t("admin.calendar.hoursPerDay")} hint={t("admin.calendar.hoursPerDayHint")} error={fieldErrorOf(m.save.error, "hoursPerDay")}>
                <Input type="number" min={1} max={24} className="w-24" value={hours} onChange={(e) => setHours(Number(e.target.value))} />
              </Field>
              <div>
                <Button variant="primary" icon={<Save />} disabled={!dirty} loading={m.save.isPending} onClick={() => (daysChanged ? setConfirming(true) : void save())}>
                  {t("app.save")}
                </Button>
              </div>
            </section>

            <section aria-labelledby="holidays-h" className="flex flex-col gap-4">
              <div className="flex items-center justify-between gap-2">
                <h2 id="holidays-h" className="text-lg text-fg">{t("admin.calendar.holidays")}</h2>
                <div className="flex items-center gap-1">
                  <Button variant="ghost" size="sm" iconOnly aria-label={t("admin.calendar.prevYear")} icon={<ChevronLeft />} onClick={() => setYear((y) => y - 1)} />
                  <span className="min-w-16 text-center text-sm font-semibold tabular-nums" aria-live="polite">
                    {t("admin.calendar.year", { year })}
                  </span>
                  <Button variant="ghost" size="sm" iconOnly aria-label={t("admin.calendar.nextYear")} icon={<ChevronRight />} onClick={() => setYear((y) => y + 1)} />
                </div>
              </div>
              <p className="text-sm text-fg-muted">{t("admin.calendar.holidaysHint")}</p>
              {holidays.isPending ? (
                <Skeleton className="h-24 w-full" />
              ) : holidays.data && holidays.data.length > 0 ? (
                <ul className="divide-y divide-border-muted rounded-md border border-border">
                  {holidays.data.map((h) => (
                    <li key={h.id} className="flex items-center justify-between gap-3 px-3 py-2">
                      <span className="text-sm">
                        <span className="font-medium text-fg">{h.name}</span>
                        <span className="ml-2 text-fg-muted tabular-nums">{formatDateLong(h.date)}</span>
                      </span>
                      <Button
                        variant="ghost"
                        size="sm"
                        iconOnly
                        aria-label={t("admin.calendar.removeHoliday", { name: h.name })}
                        icon={<Trash2 />}
                        onClick={() => void runToast(m.remove.mutateAsync(h.id), t("admin.calendar.holidayRemoved"))}
                      />
                    </li>
                  ))}
                </ul>
              ) : (
                <div className="rounded-md border border-border">
                  <EmptyState compact icon={<CalendarOff />} title={t("admin.calendar.noHolidays", { year })} />
                </div>
              )}
              <div className="grid grid-cols-1 items-end gap-3 rounded-md border border-border bg-surface p-3 sm:grid-cols-[160px_1fr_auto]">
                <Field label={t("admin.calendar.date")} error={fieldErrorOf(m.add.error, "date")}>
                  <Input type="date" value={newDate} onChange={(e) => setNewDate(e.target.value)} />
                </Field>
                <Field label={t("admin.calendar.holidayName")} error={fieldErrorOf(m.add.error, "name")}>
                  <Input value={newName} maxLength={100} placeholder={t("admin.calendar.holidayPlaceholder")} onChange={(e) => setNewName(e.target.value)} />
                </Field>
                <Button icon={<Plus />} loading={m.add.isPending} onClick={() => void add()}>
                  {t("admin.calendar.addHoliday")}
                </Button>
              </div>
            </section>
          </div>
        )}
      </AdminGuard>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t("admin.calendar.confirmTitle")}
        body={t("admin.calendar.confirmBody")}
        confirm={t("admin.calendar.confirm")}
        pending={m.save.isPending}
        onConfirm={() => void save()}
      />
    </PageBody>
  );
}
