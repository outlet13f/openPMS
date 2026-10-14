// The work package table (S06, components.md "WorkPackageTable").
import { Fragment, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, ChevronDown, ChevronRight, EyeOff, History, ListPlus, ListX, Plus } from "lucide-react";
import { ContextMenu, Menu, type MenuEntry } from "@/components/ui/overlay";
import type { ColumnKey, FilterOptions, QueryGroup, QueryRow, QuerySpec, WorkPackage } from "@/api";
import { useCreateWorkPackage } from "@/api/hooks";
import { Avatar, Principal } from "@/components/ui/avatar";
import { Badge, Priority, TypeLabel } from "@/components/ui/badges";
import { Input } from "@/components/ui/field";
import { useToast } from "@/components/ui/feedback";
import { WorkPackageCard } from "@/components/domain/work-package-card";
import { cn } from "@/lib/cn";
import { formatDate, formatDateTime, relativeTime, todayIso } from "@/lib/dates";
import { isApiError } from "@/api";
import { StatusMenu } from "./status-menu";
import { InlinePick } from "./inline-pick";
import { useSaveWorkPackage } from "./use-save";
import { CustomValueView, cfIdOf, useColumnLabel } from "./custom-fields";
import { MoreAssignees } from "./assignees";
import { remainingLabel, slaState } from "@/features/service-desk/sla";
import { SLA_TONE } from "@/features/service-desk/sla-panel";

const WIDTH: Partial<Record<ColumnKey, string>> = {
  number: "w-16", type: "w-24", status: "w-32", assignee: "w-40", responsible: "w-40", author: "w-36", priority: "w-24",
  startDate: "w-28", dueDate: "w-28", updatedAt: "w-28", version: "w-32", project: "w-40", duration: "w-20", doneRatio: "w-20", work: "w-24", remainingWork: "w-24",
  requester: "w-36", assignmentGroup: "w-32", slaDue: "w-32", slaState: "w-28",
};
const SORTABLE: ColumnKey[] = ["number", "subject", "type", "status", "priority", "assignee", "responsible", "author", "version", "startDate", "dueDate", "work", "remainingWork", "updatedAt", "project", "slaDue"];

/** A colour at a low strength, for a tinted cell or row (readable in light and dark themes). */
export const tint = (color: string, percent = 14) => `color-mix(in srgb, ${color} ${percent}%, transparent)`;

/** Due within three days, not done yet (inline highlighting). */
function dueSoon(wp: WorkPackage) {
  if (!wp.dueDate || wp._meta.overdue || wp.status.isClosed) return false;
  const days = (Date.parse(wp.dueDate) - Date.parse(todayIso())) / 86_400_000;
  return days >= 0 && days <= 3;
}

/** S06 기준선: a changed cell keeps its value and gets a mark with the value it had then. */
function BaselineMark({ old }: { old: string | null }) {
  const { t } = useTranslation();
  const text = t("baseline.was", { value: old ?? t("baseline.empty") });
  return (
    <span title={text} className="ml-1 inline-flex size-4 items-center justify-center rounded-full bg-accent-subtle align-middle text-accent-subtle-text">
      <History className="size-3" aria-hidden="true" />
      <span className="sr-only">{text}</span>
    </span>
  );
}

function Cell({ wp, column, options, inline }: { wp: WorkPackage; column: ColumnKey; options?: FilterOptions; inline?: boolean }) {
  const { t } = useTranslation();
  const { save } = useSaveWorkPackage();
  const editable = (f: WorkPackage["_meta"]["editableFields"][number]) => wp._meta.editableFields.includes(f);
  switch (column) {
    case "type":
      return <TypeLabel name={wp.type.name} color={wp.type.color} />;
    case "status":
      return <StatusMenu wp={wp} size="sm" />;
    case "assignee":
      // 공동 담당자: the quick pick changes the main assignee; "+N" names the others
      return (
        <span className="inline-flex items-center">
        <InlinePick
          value={wp.assignee?.id ?? null}
          disabled={!editable("assignee") || !options}
          label={`${t("wp.fields.assignee")}: ${wp.assignee?.name ?? t("wp.unassigned")}`}
          onPick={(v) => void save(wp, { assigneeId: v })}
          options={[{ value: null, label: t("wp.unassigned") }, ...(options?.users ?? []).map((u) => ({ value: u.id, label: u.name, icon: <Avatar id={u.id} name={u.name} size="xs" /> }))]}
        >
          {wp.assignee ? <Principal id={wp.assignee.id} name={wp.assignee.name} size="xs" /> : <span className="text-fg-muted">-</span>}
        </InlinePick>
        <MoreAssignees assignees={wp.assignees} />
        </span>
      );
    case "responsible":
      return wp.responsible ? <Principal id={wp.responsible.id} name={wp.responsible.name} size="xs" /> : <span className="text-fg-muted">-</span>;
    case "author":
      return <Principal id={wp.author.id} name={wp.author.name} size="xs" muted />;
    case "priority":
      return (
        <InlinePick
          value={wp.priority.id}
          disabled={!editable("priority") || !options}
          label={`${t("wp.fields.priority")}: ${wp.priority.name}`}
          onPick={(v) => v && void save(wp, { priorityId: v })}
          options={(options?.priorities ?? []).map((p) => ({ value: p.id, label: p.name, icon: <span className="size-2.5 rounded-full" style={{ backgroundColor: p.color }} /> }))}
        >
          {inline ? (
            <span className="-mx-1 rounded-sm px-1 py-0.5" style={{ backgroundColor: tint(wp.priority.color, 18) }}>
              <Priority name={wp.priority.name} color={wp.priority.color} />
            </span>
          ) : (
            <Priority name={wp.priority.name} color={wp.priority.color} />
          )}
        </InlinePick>
      );
    case "startDate":
      return <span className="tabular-nums">{formatDate(wp.startDate) || <span className="text-fg-muted">-</span>}</span>;
    case "dueDate":
      return wp.dueDate ? (
        <span
          className={cn(
            "tabular-nums",
            wp._meta.overdue && "font-semibold text-danger",
            inline && wp._meta.overdue && "-mx-1 rounded-sm bg-danger-subtle px-1 py-0.5",
            inline && dueSoon(wp) && "-mx-1 rounded-sm bg-warning-subtle px-1 py-0.5 font-medium text-warning",
          )}
        >
          {formatDate(wp.dueDate)}
          {wp._meta.overdue && <span className="sr-only"> {t("wp.overdueSr")}</span>}
          {inline && dueSoon(wp) && <span className="sr-only"> {t("wp.dueSoonSr")}</span>}
        </span>
      ) : (
        <span className="text-fg-muted">-</span>
      );
    case "duration":
      return wp.duration ? t("wp.daysUnit", { value: wp.duration }) : <span className="text-fg-muted">-</span>;
    case "doneRatio":
      return wp.doneRatio !== null ? `${wp.doneRatio}%` : <span className="text-fg-muted">-</span>;
    case "work":
      return wp.workHours !== null ? <span className="tabular-nums">{t("wp.hoursUnit", { value: wp.workHours })}</span> : <span className="text-fg-muted">-</span>;
    case "remainingWork":
      return wp.remainingHours !== null ? <span className="tabular-nums">{t("wp.hoursUnit", { value: wp.remainingHours })}</span> : <span className="text-fg-muted">-</span>;
    case "updatedAt":
      return <span title={wp.updatedAt}>{relativeTime(wp.updatedAt)}</span>;
    case "version":
      return wp.version?.name ?? <span className="text-fg-muted">-</span>;
    case "project":
      return <span className="truncate">{wp.project.name}</span>;
    case "requester":
      return wp.serviceTicket ? <Principal id={wp.serviceTicket.requester.id} name={wp.serviceTicket.requester.name} size="xs" /> : null;
    case "assignmentGroup":
      return <>{wp.serviceTicket?.assignmentGroup?.name ?? ""}</>;
    case "slaDue": {
      const due = wp.serviceTicket?.sla.resolveDueAt;
      if (!due) return null;
      const st = slaState(wp.serviceTicket!.sla, Date.now());
      return (
        <span title={formatDateTime(due)} className={cn("tabular-nums", st === "breached" && "font-semibold text-danger")}>
          {st === "met" || st === "paused" ? formatDateTime(due) : remainingLabel(due, Date.now())}
        </span>
      );
    }
    case "slaState": {
      if (!wp.serviceTicket) return null;
      const st = slaState(wp.serviceTicket.sla, Date.now());
      return st === "none" ? null : <Badge tone={SLA_TONE[st]}>{t(`serviceDesk.state.${st}`)}</Badge>;
    }
    default: {
      const id = cfIdOf(column);
      if (!id) return null;
      const field = options?.customFields.find((f) => f.id === id);
      return <span className="inline-block max-w-64 truncate align-middle"><CustomValueView text={wp.customValues[id]?.text ?? ""} format={field?.format} /></span>;
    }
  }
}

/** A copy with the key added or removed: state updaters must not change the previous set (StrictMode runs them twice). */
function flip(s: ReadonlySet<string>, key: string) {
  const next = new Set(s);
  if (!next.delete(key)) next.add(key);
  return next;
}

/** A sum cell: hours for work, a percentage for % complete, the number for custom fields. */
function SumValue({ column, value }: { column: string; value: number | null | undefined }) {
  const { t } = useTranslation();
  if (value === undefined) return null;
  if (value === null) return <span className="text-fg-muted">-</span>;
  const text = column === "doneRatio" ? `${value}%` : column === "work" || column === "remainingWork" ? t("wp.hoursUnit", { value }) : value.toLocaleString("ko-KR");
  return <span className="font-semibold tabular-nums">{text}</span>;
}

/** The label of a group: a colour dot for types, statuses and priorities; "(값 없음)" for the empty group. */
function GroupLabel({ group }: { group: Pick<QueryGroup, "key" | "label" | "color"> }) {
  const { t } = useTranslation();
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5">
      {group.color && <span aria-hidden="true" className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: group.color }} />}
      <span className="truncate">{group.key === "" ? t("wp.grouping.empty") : group.label}</span>
    </span>
  );
}

export function WorkPackageTable({
  rows,
  groups,
  sums,
  spec,
  onSpec,
  selected,
  detailsHref,
  options,
  createIn,
  checked,
  onCheck,
  rowMenu,
}: {
  rows: QueryRow[];
  /** a grouped list's groups (over every match, in order); rows carry groupKey */
  groups?: QueryGroup[];
  /** totals over every match, when the list shows sums */
  sums?: Record<string, number | null>;
  spec: QuerySpec;
  onSpec: (s: QuerySpec) => void;
  selected?: number;
  detailsHref: (wp: WorkPackage) => string;
  options?: FilterOptions;
  /** Project id for the inline "새 작업 추가" row; omitted when the user cannot create here. */
  createIn?: { projectId: string; typeId: string } | null;
  /** S06: rows checked for a bulk change; with onCheck the table shows a checkbox column */
  checked?: ReadonlySet<number>;
  onCheck?: (numbers: number[], on: boolean) => void;
  /** S06 우클릭 메뉴: the actions for a row (on the checked rows when it is one of them) */
  rowMenu?: (wp: WorkPackage) => MenuEntry[];
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const columnLabel = useColumnLabel(options);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [closedGroups, setClosedGroups] = useState<Set<string>>(new Set());
  const grouped = !!groups && !!spec.groupBy;
  const groupByKey = useMemo(() => new Map((groups ?? []).map((g) => [g.key, g])), [groups]);
  const toggleGroup = (key: string) => setClosedGroups((s) => flip(s, key));
  const colCount = spec.columns.length + (onCheck ? 1 : 0);

  /** A sums row: the label in the subject column (or the first one without a sum), the sums under their columns. */
  const sumsRow = (key: string, label: ReactNode, values: Record<string, number | null>, footer = false) => {
    const labelAt = spec.columns.includes("subject") && !(("subject" as string) in values) ? "subject" : spec.columns.find((c) => !(c in values));
    return (
      <tr key={key} className={cn("h-row border-b border-border-muted bg-surface text-sm", footer && "border-t border-border")}>
        {onCheck && <td />}
        {spec.columns.map((c) => (
          <td key={c} className="px-3 py-1 whitespace-nowrap">
            {c === labelAt ? <span className="text-xs font-semibold text-fg-muted">{label}</span> : <SumValue column={c} value={values[c]} />}
          </td>
        ))}
      </tr>
    );
  };

  const visible = useMemo(() => {
    const out: QueryRow[] = [];
    let hideBelow: number | null = null;
    for (const r of rows) {
      if (hideBelow !== null && r.depth > hideBelow) continue;
      hideBelow = null;
      out.push(r);
      if (r.hasChildren && collapsed.has(r.wp.id)) hideBelow = r.depth;
    }
    return out;
  }, [rows, collapsed]);

  const toggle = (id: string) => setCollapsed((s) => flip(s, id));
  const sort = spec.sort[0];
  /** Header menu (sort, multi-sort up to 3 keys, move, hide), like the original's column menu. */
  const headerMenu = (c: ColumnKey): MenuEntry[] => {
    const idx = spec.columns.indexOf(c);
    const inSort = spec.sort.findIndex((s) => s.field === c);
    const items: MenuEntry[] = [];
    if (SORTABLE.includes(c) || cfIdOf(c)) {
      items.push(
        { label: t("wp.header.sortAsc"), icon: <ArrowUp />, onSelect: () => onSpec({ ...spec, sort: [{ field: c, dir: "asc" }] }) },
        { label: t("wp.header.sortDesc"), icon: <ArrowDown />, onSelect: () => onSpec({ ...spec, sort: [{ field: c, dir: "desc" }] }) },
      );
      if (inSort < 0 && spec.sort.length > 0 && spec.sort.length < 3)
        items.push({ label: t("wp.header.addSort"), icon: <ListPlus />, onSelect: () => onSpec({ ...spec, sort: [...spec.sort, { field: c, dir: "asc" }] }) });
      if (inSort > 0) items.push({ label: t("wp.header.removeSort"), icon: <ListX />, onSelect: () => onSpec({ ...spec, sort: spec.sort.filter((s) => s.field !== c) }) });
      items.push({ kind: "separator" });
    }
    const move = (to: number) => {
      const cols = spec.columns.filter((x) => x !== c);
      cols.splice(to, 0, c);
      onSpec({ ...spec, columns: cols });
    };
    items.push(
      { label: t("wp.header.moveLeft"), icon: <ArrowLeft />, disabled: idx <= 0, onSelect: () => move(idx - 1) },
      { label: t("wp.header.moveRight"), icon: <ArrowRight />, disabled: idx >= spec.columns.length - 1, onSelect: () => move(idx + 1) },
    );
    if (c !== "subject" && c !== "number") items.push({ label: t("wp.header.hide"), icon: <EyeOff />, onSelect: () => onSpec({ ...spec, columns: spec.columns.filter((x) => x !== c) }) });
    return items;
  };

  const openRow = (e: MouseEvent<HTMLTableRowElement>, wp: WorkPackage) => {
    if ((e.target as HTMLElement).closest("a,button,input,[role=menuitem],[role=menu]")) return;
    navigate(detailsHref(wp));
  };

  return (
    <>
      {/* ≥ md: table */}
      <div className="relative hidden overflow-x-auto rounded-md border border-border md:block">
        <table className="w-max min-w-full border-collapse text-sm">
          <thead>
            <tr className="h-row border-b border-border bg-surface text-left text-xs font-semibold text-fg-muted">
              {onCheck && (
                <th scope="col" className="w-10 px-3">
                  <input
                    type="checkbox"
                    className="size-4 accent-accent"
                    aria-label={t("bulk.checkAll")}
                    checked={visible.length > 0 && visible.every((r) => r.context || checked?.has(r.wp.number))}
                    onChange={(e) => onCheck(visible.filter((r) => !r.context).map((r) => r.wp.number), e.target.checked)}
                  />
                </th>
              )}
              {spec.columns.map((c) => {
                const active = sort?.field === c;
                const order = spec.sort.findIndex((s) => s.field === c);
                const dir = order >= 0 ? spec.sort[order].dir : null;
                return (
                  <th key={c} scope="col" aria-sort={active ? (sort.dir === "asc" ? "ascending" : "descending") : undefined} className={cn("px-3 whitespace-nowrap", WIDTH[c])}>
                    <Menu
                      width="w-56"
                      trigger={
                        <button
                          type="button"
                          className="-mx-1 inline-flex items-center gap-1 rounded-sm px-1 py-0.5 hover:bg-surface-hover hover:text-fg data-[state=open]:bg-surface-selected"
                          aria-label={t("wp.header.menu", { column: columnLabel(c) })}
                        >
                          {columnLabel(c)}
                          {dir && (dir === "asc" ? <ArrowUp className="size-3.5" aria-hidden="true" /> : <ArrowDown className="size-3.5" aria-hidden="true" />)}
                          {dir && spec.sort.length > 1 && <span className="text-[10px] tabular-nums" aria-hidden="true">{order + 1}</span>}
                        </button>
                      }
                      items={headerMenu(c)}
                    />
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {visible.map(({ wp, depth, hasChildren, context, groupKey, baseline }, i) => {
              const isSel = selected === wp.number;
              const gk = groupKey ?? "";
              const group = groupByKey.get(gk);
              const startsGroup = grouped && (i === 0 || (visible[i - 1].groupKey ?? "") !== gk);
              const endsGroup = grouped && (i === visible.length - 1 || (visible[i + 1].groupKey ?? "") !== gk);
              const closed = grouped && closedGroups.has(gk);
              const groupName = group?.key === "" ? t("wp.grouping.empty") : (group?.label ?? "");
              const highlight = spec.highlight ?? "none";
              const rowColor = highlight === "status" ? wp.status.color : highlight === "priority" ? wp.priority.color : highlight === "type" ? wp.type.color : null;
              const rowEl = (
                <tr
                  aria-selected={isSel}
                  onClick={(e) => openRow(e, wp)}
                  className={cn(
                    "h-row cursor-pointer border-b border-border-muted last:border-b-0 hover:bg-surface",
                    isSel && "bg-accent-subtle hover:bg-accent-subtle",
                    context && "text-fg-muted",
                  )}
                  style={rowColor && !isSel ? { backgroundColor: tint(rowColor, 6), boxShadow: `inset 4px 0 0 ${rowColor}` } : undefined}
                >
                  {onCheck && (
                    <td className="px-3 py-1">
                      {!context && (
                        <input
                          type="checkbox"
                          className="size-4 accent-accent"
                          aria-label={t("bulk.check", { number: wp.number, subject: wp.subject })}
                          checked={!!checked?.has(wp.number)}
                          onChange={(e) => onCheck([wp.number], e.target.checked)}
                        />
                      )}
                    </td>
                  )}
                  {spec.columns.map((c) => (
                    <td key={c} className={cn("px-3 py-1", c === "subject" ? "min-w-64" : "whitespace-nowrap")}>
                      {c === "number" ? (
                        <Link to={detailsHref(wp)} className="text-accent tabular-nums hover:underline" aria-label={`#${wp.number} ${wp.subject}`}>
                          {wp.number}
                        </Link>
                      ) : c === "subject" ? (
                        <span className="flex max-w-[36rem] min-w-0 items-center gap-1" style={{ paddingLeft: spec.hierarchy ? depth * 20 : 0 }}>
                          {spec.hierarchy &&
                            (hasChildren ? (
                              <button
                                type="button"
                                aria-expanded={!collapsed.has(wp.id)}
                                aria-label={collapsed.has(wp.id) ? t("wp.expand") : t("wp.collapse")}
                                onClick={() => toggle(wp.id)}
                                className="inline-flex size-5 shrink-0 items-center justify-center rounded-sm text-fg-muted hover:bg-surface-hover"
                              >
                                {collapsed.has(wp.id) ? <ChevronRight className="size-4" /> : <ChevronDown className="size-4" />}
                              </button>
                            ) : (
                              <span className="inline-block w-5 shrink-0" aria-hidden="true" />
                            ))}
                          <Link to={detailsHref(wp)} className={cn("min-w-0 truncate hover:underline", context ? "text-fg-muted" : "text-fg")} title={wp.subject}>
                            {wp.subject}
                          </Link>
                          {context && <span className="sr-only">{t("wp.contextRow")}</span>}
                          {baseline?.added && <span className="shrink-0 rounded-sm bg-success-subtle px-1.5 text-xs font-medium text-success">{t("baseline.added")}</span>}
                        </span>
                      ) : (
                        <Cell wp={wp} column={c} options={options} inline={highlight === "inline"} />
                      )}
                      {baseline && c in baseline.changed && <BaselineMark old={baseline.changed[c]} />}
                    </td>
                  ))}
                </tr>
              );
              return (
                <Fragment key={wp.id}>
                {startsGroup && group && (
                  <tr className="h-row border-b border-border bg-surface-sunken">
                    <th scope="colgroup" colSpan={colCount} className="px-2 py-1 text-left text-sm font-semibold text-fg">
                      <button
                        type="button"
                        aria-expanded={!closed}
                        onClick={() => toggleGroup(gk)}
                        className="inline-flex max-w-full items-center gap-1.5 rounded-sm px-1 py-0.5 hover:bg-surface-hover"
                      >
                        {closed ? <ChevronRight className="size-4 shrink-0" aria-hidden="true" /> : <ChevronDown className="size-4 shrink-0" aria-hidden="true" />}
                        <span className="sr-only">{t("wp.grouping.toggle", { label: groupName })}: </span>
                        <GroupLabel group={group} />
                        <span className="rounded-full bg-bg px-1.5 text-xs font-normal text-fg-muted tabular-nums">{t("wp.grouping.count", { count: group.count })}</span>
                      </button>
                    </th>
                  </tr>
                )}
                {!closed && (rowMenu && !context ? <ContextMenu items={rowMenu(wp)}>{rowEl}</ContextMenu> : rowEl)}
                {endsGroup && !closed && spec.sums && group?.sums && sumsRow(`sum-${gk}`, t("wp.grouping.groupSum", { label: groupName }), group.sums)}
                </Fragment>
              );
            })}
          </tbody>
          {spec.sums && sums && <tfoot>{sumsRow("sum-total", t("wp.grouping.total"), sums, true)}</tfoot>}
        </table>
        {createIn && <InlineCreate projectId={createIn.projectId} typeId={createIn.typeId} />}
      </div>

      {/* < md: cards */}
      <ul className="flex flex-col gap-2 md:hidden">
        {rows
          .filter((r) => !r.context)
          .map(({ wp, groupKey }, i, list) => (
            <Fragment key={wp.id}>
            {grouped && (i === 0 || (list[i - 1].groupKey ?? "") !== (groupKey ?? "")) && groupByKey.get(groupKey ?? "") && (
              <li className="mt-2 flex items-center gap-2 text-sm font-semibold text-fg">
                <GroupLabel group={groupByKey.get(groupKey ?? "")!} />
                <span className="text-xs font-normal text-fg-muted">{t("wp.grouping.count", { count: groupByKey.get(groupKey ?? "")!.count })}</span>
              </li>
            )}
            <li>
              <WorkPackageCard
                showStatus
                href={detailsHref(wp)}
                className={cn(selected === wp.number && "border-accent")}
                wp={{
                  number: wp.number,
                  subject: wp.subject,
                  projectName: wp.project.name,
                  type: wp.type,
                  status: wp.status,
                  assignee: wp.assignee ?? undefined,
                  assignees: wp.assignees,
                  startDate: wp.startDate ?? undefined,
                  dueDate: wp.dueDate ?? undefined,
                  overdue: wp._meta.overdue,
                  autoScheduled: !wp.scheduleManually,
                }}
              />
            </li>
            </Fragment>
          ))}
      </ul>
    </>
  );
}

function InlineCreate({ projectId, typeId }: { projectId: string; typeId: string }) {
  const { t } = useTranslation();
  const toast = useToast();
  const create = useCreateWorkPackage(projectId);
  const [open, setOpen] = useState(false);
  const [subject, setSubject] = useState("");
  const [error, setError] = useState<string | null>(null);
  const ref = useRef<HTMLInputElement>(null);
  const sending = useRef(false); // Enter pressed twice makes one item (BUG-001)

  const submit = async () => {
    if (sending.current) return;
    if (!subject.trim()) {
      setError(t("wp.subjectRequired"));
      return;
    }
    sending.current = true;
    try {
      const wp = await create.mutateAsync({ typeId, subject });
      toast({ tone: "success", title: t("wp.created", { number: wp.number }), description: wp.subject });
      setSubject("");
      setError(null);
      ref.current?.focus();
    } catch (err) {
      setError(isApiError(err) ? err.message : t("app.errorBody"));
    } finally {
      sending.current = false;
    }
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      void submit();
    } else if (e.key === "Escape") {
      setOpen(false);
      setSubject("");
      setError(null);
    }
  };

  if (!open)
    return (
      <button type="button" onClick={() => setOpen(true)} className="flex h-row w-full items-center gap-2 border-t border-border-muted px-3 text-sm text-accent hover:bg-surface">
        <Plus className="size-4" aria-hidden="true" />
        {t("wp.inlineNew")}
      </button>
    );
  return (
    <div className="border-t border-border-muted px-3 py-1.5">
      <label className="flex items-center gap-2">
        <span className="sr-only">{t("wp.inlineNew")}</span>
        <Plus className="size-4 shrink-0 text-fg-muted" aria-hidden="true" />
        <Input
          ref={ref}
          autoFocus
          size="sm"
          value={subject}
          aria-invalid={!!error}
          aria-describedby={error ? "inline-create-error" : undefined}
          placeholder={t("wp.inlinePlaceholder")}
          onChange={(e) => setSubject(e.target.value)}
          onKeyDown={onKey}
          disabled={create.isPending}
          maxLength={255}
        />
      </label>
      {error && (
        <p id="inline-create-error" className="mt-1 pl-6 text-xs text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
