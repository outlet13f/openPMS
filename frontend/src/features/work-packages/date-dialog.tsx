// S10 날짜 대화상자 (components.md "DatePicker"): mode, working days, start · due · duration with "오늘",
// and the two-month calendar. Clicks fill start, then due (the field marked as next); the third value comes
// from the server's schedule preview.
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { CalendarClock, Pin } from "lucide-react";
import { api, type WorkPackage, type WorkPackagePatch } from "@/api";
import { Button } from "@/components/ui/button";
import { Checkbox, Segmented } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { formatDateRange, todayIso } from "@/lib/dates";
import { useSaveWorkPackage } from "./use-save";
import { TwoMonthCalendar } from "./two-month-calendar";

type Touched = NonNullable<WorkPackagePatch["touched"]>;

export function DateDialog({ wp, open, onOpenChange }: { wp: WorkPackage; open: boolean; onOpenChange: (o: boolean) => void }) {
  const { t } = useTranslation();
  const { save, isPending } = useSaveWorkPackage();
  const [mode, setMode] = useState<"manual" | "auto">(wp.scheduleManually ? "manual" : "auto");
  const [workingOnly, setWorkingOnly] = useState(!wp.ignoreNonWorkingDays);
  const [start, setStart] = useState(wp.startDate ?? "");
  const [due, setDue] = useState(wp.dueDate ?? "");
  const [duration, setDuration] = useState(wp.duration?.toString() ?? "");
  const [touched, setTouched] = useState<Touched>([]);
  /** Which date the next calendar click sets. */
  const [next, setNext] = useState<"start" | "due">("start");

  useEffect(() => {
    if (!open) return;
    setMode(wp.scheduleManually ? "manual" : "auto");
    setWorkingOnly(!wp.ignoreNonWorkingDays);
    setStart(wp.startDate ?? "");
    setDue(wp.dueDate ?? "");
    setDuration(wp.duration?.toString() ?? "");
    setTouched([]);
    setNext("start");
  }, [open, wp]);

  const derived = mode === "auto" && wp.childCount > 0;
  const milestone = wp.type.isMilestone;
  const touch = (f: Touched[number]) => setTouched((ts) => [...ts.filter((x) => x !== f), f].slice(-2));
  const patch: Omit<WorkPackagePatch, "lockVersion"> = {
    scheduleManually: mode === "manual",
    ignoreNonWorkingDays: !workingOnly,
    ...(derived
      ? {}
      : milestone
        ? { startDate: start || null, dueDate: start || null }
        : { startDate: start || null, dueDate: due || null, duration: duration ? Number(duration) : null, touched }),
  };

  const preview = useQuery({
    queryKey: ["schedule-preview", wp.number, wp.lockVersion, patch],
    queryFn: () => api.workPackages.schedulePreview(wp.number, patch),
    enabled: open && touched.length > 0,
  });
  // Show what the server computed (S2: the third value follows from the two the user touched).
  useEffect(() => {
    const p = preview.data;
    if (!p) return;
    if (!touched.includes("startDate") && p.startDate) setStart(p.startDate);
    if (!touched.includes("dueDate") && p.dueDate) setDue(p.dueDate);
    if (!touched.includes("duration") && p.duration) setDuration(String(p.duration));
    // only react to a new server answer, not to the user typing
  }, [preview.data]);

  const setStartDate = (d: string) => (setStart(d), touch("startDate"));
  const setDueDate = (d: string) => (setDue(d), touch("dueDate"));
  /** Calendar click: start first, then due; a due before the start becomes the new start. */
  const pick = (d: string) => {
    if (milestone) return setStartDate(d);
    if (next === "start" || (start && d < start)) {
      setStartDate(d);
      if (due && d > due) setDue("");
      setNext("due");
    } else {
      setDueDate(d);
      setNext("start");
    }
  };
  const todayLink = (onClick: () => void, label: string) => (
    <Button variant="link" size="sm" className="h-auto self-start text-xs" disabled={derived} onClick={onClick} aria-label={label}>
      {t("planning.today")}
    </Button>
  );

  const submit = async () => {
    const saved = await save(wp, patch);
    if (saved) onOpenChange(false);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="md"
      title={t("wp.fields.dates")}
      banner={derived ? <Banner flush tone="info">{t("wp.derivedDates")}</Banner> : undefined}
      footer={
        <>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={isPending} onClick={() => void submit()} disabled={!wp._meta.allowedActions.edit}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Segmented
            aria-label={t("dates.mode")}
            value={mode}
            onValueChange={(v) => setMode(v as "manual" | "auto")}
            options={[
              { value: "manual", label: t("dates.manual"), icon: <Pin /> },
              { value: "auto", label: t("dates.auto"), icon: <CalendarClock /> },
            ]}
          />
          <Checkbox label={t("dates.workingOnly")} checked={workingOnly} onCheckedChange={(v) => (setWorkingOnly(v === true), touch("duration"))} />
        </div>
        <p className="text-xs text-fg-muted">{mode === "auto" ? t("dates.autoHint") : t("dates.manualHint")}</p>
        <div className={milestone ? "grid grid-cols-1 gap-3" : "grid grid-cols-1 gap-3 sm:grid-cols-3"}>
          <div className="flex flex-col gap-1">
            <Field label={milestone ? t("dates.date") : t("wp.fields.startDate")}>
              <Input
                type="date"
                data-autofocus
                value={start}
                disabled={derived}
                onFocus={() => setNext("start")}
                onChange={(e) => setStartDate(e.target.value)}
                className={!milestone && next === "start" ? "border-accent" : undefined}
              />
            </Field>
            {todayLink(() => (setStartDate(todayIso()), setNext("due")), t("dates.startToday"))}
          </div>
          {!milestone && (
            <>
              <div className="flex flex-col gap-1">
                <Field label={t("wp.fields.dueDate")}>
                  <Input
                    type="date"
                    value={due}
                    min={start || undefined}
                    disabled={derived}
                    onFocus={() => setNext("due")}
                    onChange={(e) => setDueDate(e.target.value)}
                    className={next === "due" ? "border-accent" : undefined}
                  />
                </Field>
                {todayLink(() => (setDueDate(todayIso()), setNext("start")), t("dates.dueToday"))}
              </div>
              <Field label={t("dates.durationLabel")}>
                <Input type="number" min={1} value={duration} disabled={derived} onChange={(e) => (setDuration(e.target.value), touch("duration"))} />
              </Field>
            </>
          )}
        </div>
        {!derived && (
          <>
            {!milestone && (
              <p className="text-xs text-fg-muted" aria-live="polite">
                {next === "start" ? t("dates.nextStart") : t("dates.nextDue")}
              </p>
            )}
            <TwoMonthCalendar start={start} due={due} single={milestone} workingOnly={workingOnly} disabled={!wp._meta.allowedActions.edit} onPick={pick} />
          </>
        )}
        {preview.data && preview.data.affected.length > 0 && (
          <div className="rounded-md border border-border-muted bg-surface p-3" aria-live="polite">
            <p className="text-sm font-medium text-fg">{t("dates.affected", { count: preview.data.affected.length })}</p>
            <ul className="mt-1.5 flex flex-col gap-1 text-xs text-fg-muted">
              {preview.data.affected.slice(0, 6).map((a) => (
                <li key={a.number}>
                  #{a.number} {a.subject} → <span className="tabular-nums">{formatDateRange(a.startDate, a.dueDate)}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Dialog>
  );
}
