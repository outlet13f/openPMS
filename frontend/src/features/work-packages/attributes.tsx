// Attribute groups on the work package detail (사람 · 일정 · 진행률 · 세부 정보).
import { useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { CalendarClock, CalendarDays, Pin } from "lucide-react";
import type { WorkPackage } from "@/api";
import { useFilterOptions } from "@/api/hooks";
import { Avatar, Principal } from "@/components/ui/avatar";
import { Priority, TypeLabel } from "@/components/ui/badges";
import { formatDateRange } from "@/lib/dates";
import { cn } from "@/lib/cn";
import { InlinePick } from "./inline-pick";
import { InlineText } from "./inline-text";
import { useSaveWorkPackage } from "./use-save";
import { DateDialog } from "./date-dialog";

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[112px_1fr] items-start gap-x-3 py-1.5 sm:grid-cols-[140px_1fr]">
      <dt className="pt-0.5 text-sm text-fg-muted">{label}</dt>
      <dd className="min-w-0 text-sm text-fg">{children}</dd>
    </div>
  );
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="mt-5 first:mt-0">
      <h3 className="mb-1 border-b border-border-muted pb-1.5 text-sm font-semibold text-fg">{title}</h3>
      <dl>{children}</dl>
    </section>
  );
}

const num = (v: string) => (v === "" ? null : Number(v));

export function Attributes({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const { save } = useSaveWorkPackage();
  const options = useFilterOptions(wp.project.id).data;
  const [dates, setDates] = useState(false);
  const can = (f: WorkPackage["_meta"]["editableFields"][number]) => wp._meta.editableFields.includes(f);
  const people = options?.users ?? [];
  const person = (u: WorkPackage["assignee"]) => (u ? <Principal id={u.id} name={u.name} size="xs" /> : <span className="text-fg-muted">-</span>);
  const ok = async (p: Parameters<typeof save>[1]) => !!(await save(wp, p));

  return (
    <div>
      <Group title={t("wp.groups.people")}>
        <Row label={t("wp.fields.assignee")}>
          <InlinePick
            value={wp.assignee?.id ?? null}
            disabled={!can("assignee")}
            label={`${t("wp.fields.assignee")}: ${wp.assignee?.name ?? t("wp.unassigned")}`}
            onPick={(v) => void save(wp, { assigneeId: v })}
            options={[{ value: null, label: t("wp.unassigned") }, ...people.map((u) => ({ value: u.id, label: u.name, icon: <Avatar id={u.id} name={u.name} size="xs" /> }))]}
          >
            {person(wp.assignee)}
          </InlinePick>
        </Row>
        <Row label={t("wp.fields.responsible")}>
          <InlinePick
            value={wp.responsible?.id ?? null}
            disabled={!can("responsible")}
            label={`${t("wp.fields.responsible")}: ${wp.responsible?.name ?? t("app.none")}`}
            onPick={(v) => void save(wp, { responsibleId: v })}
            options={[{ value: null, label: t("app.none") }, ...people.map((u) => ({ value: u.id, label: u.name, icon: <Avatar id={u.id} name={u.name} size="xs" /> }))]}
          >
            {person(wp.responsible)}
          </InlinePick>
        </Row>
      </Group>

      <Group title={t("wp.groups.schedule")}>
        <Row label={t("wp.fields.dates")}>
          {can("dates") || wp._meta.derived.includes("dates") ? (
            <>
              <button
                type="button"
                onClick={() => setDates(true)}
                className="-mx-1.5 inline-flex items-center gap-1.5 rounded-sm px-1.5 py-0.5 text-left hover:bg-surface-hover"
                aria-label={`${t("wp.fields.dates")}: ${formatDateRange(wp.startDate, wp.dueDate) || t("wp.noDates")}. ${t("app.edit")}`}
              >
                {wp.scheduleManually ? <Pin className="size-3.5 text-fg-muted" aria-hidden="true" /> : <CalendarClock className="size-3.5 text-fg-muted" aria-hidden="true" />}
                <span className={cn("tabular-nums", wp._meta.overdue && "font-semibold text-danger")}>{formatDateRange(wp.startDate, wp.dueDate) || <span className="text-fg-muted">{t("wp.noDates")}</span>}</span>
                {wp._meta.overdue && <span className="sr-only">{t("wp.overdueSr")}</span>}
              </button>
              <DateDialog wp={wp} open={dates} onOpenChange={setDates} />
            </>
          ) : (
            <span className="inline-flex items-center gap-1.5 tabular-nums">
              <CalendarDays className="size-3.5 text-fg-muted" aria-hidden="true" />
              {formatDateRange(wp.startDate, wp.dueDate) || <span className="text-fg-muted">{t("wp.noDates")}</span>}
            </span>
          )}
        </Row>
        <Row label={t("wp.fields.duration")}>{wp.duration ? t("wp.daysUnit", { value: wp.duration }) : <span className="text-fg-muted">-</span>}</Row>
      </Group>

      <Group title={t("wp.groups.progress")}>
        <Row label={t("wp.fields.work")}>
          <InlineText type="number" label={t("wp.fields.work")} disabled={!can("work")} value={wp.workHours?.toString() ?? ""} display={wp.workHours !== null ? t("wp.hoursUnit", { value: wp.workHours }) : undefined} onSave={(v) => ok({ workHours: num(v) })} />
        </Row>
        <Row label={t("wp.fields.remainingWork")}>
          <InlineText type="number" label={t("wp.fields.remainingWork")} disabled={!can("remainingWork")} value={wp.remainingHours?.toString() ?? ""} display={wp.remainingHours !== null ? t("wp.hoursUnit", { value: wp.remainingHours }) : undefined} onSave={(v) => ok({ remainingHours: num(v) })} />
        </Row>
        <Row label={t("wp.fields.doneRatio")}>
          <InlineText type="number" label={t("wp.fields.doneRatio")} disabled={!can("doneRatio")} value={wp.doneRatio?.toString() ?? ""} display={wp.doneRatio !== null ? `${wp.doneRatio}%` : undefined} onSave={(v) => ok({ doneRatio: num(v) === null ? null : Math.max(0, Math.min(100, Math.round(num(v)!))) })} />
        </Row>
      </Group>

      <Group title={t("wp.groups.details")}>
        <Row label={t("wp.fields.type")}>
          <InlinePick
            value={wp.type.id}
            disabled={!can("type") || !options}
            label={`${t("wp.fields.type")}: ${wp.type.name}`}
            onPick={(v) => v && void save(wp, { typeId: v })}
            options={(options?.types ?? []).map((x) => ({ value: x.id, label: <TypeLabel name={x.name} color={x.color} /> }))}
          >
            <TypeLabel name={wp.type.name} color={wp.type.color} />
          </InlinePick>
        </Row>
        <Row label={t("wp.fields.priority")}>
          <InlinePick
            value={wp.priority.id}
            disabled={!can("priority") || !options}
            label={`${t("wp.fields.priority")}: ${wp.priority.name}`}
            onPick={(v) => v && void save(wp, { priorityId: v })}
            options={(options?.priorities ?? []).map((p) => ({ value: p.id, label: p.name, icon: <span className="size-2.5 rounded-full" style={{ backgroundColor: p.color }} /> }))}
          >
            <Priority name={wp.priority.name} color={wp.priority.color} />
          </InlinePick>
        </Row>
        <Row label={t("wp.fields.version")}>
          <InlinePick
            value={wp.version?.id ?? null}
            disabled={!can("version") || !options}
            label={`${t("wp.fields.version")}: ${wp.version?.name ?? t("app.none")}`}
            onPick={(v) => void save(wp, { versionId: v })}
            options={[{ value: null, label: t("app.none") }, ...(options?.versions ?? []).map((v) => ({ value: v.id, label: v.name }))]}
          >
            {wp.version?.name ?? <span className="text-fg-muted">-</span>}
          </InlinePick>
        </Row>
        <Row label={t("wp.fields.category")}>
          <InlinePick
            value={wp.category?.id ?? null}
            disabled={!can("category") || !options}
            label={`${t("wp.fields.category")}: ${wp.category?.name ?? t("app.none")}`}
            onPick={(v) => void save(wp, { categoryId: v })}
            options={[{ value: null, label: t("app.none") }, ...(options?.categories ?? []).map((c) => ({ value: c.id, label: c.name }))]}
          >
            {wp.category?.name ?? <span className="text-fg-muted">-</span>}
          </InlinePick>
        </Row>
        <Row label={t("wp.fields.parent")}>
          {wp.parent ? (
            <Link to={`/projects/${wp.parent.project.identifier}/work-packages/${wp.parent.number}/activity`} className="text-accent hover:underline">
              #{wp.parent.number} {wp.parent.subject}
            </Link>
          ) : (
            <span className="text-fg-muted">-</span>
          )}
        </Row>
      </Group>
    </div>
  );
}
