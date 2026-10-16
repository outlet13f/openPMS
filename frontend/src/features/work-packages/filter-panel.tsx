// Filter panel (components.md "FilterPanel"): one fieldset per filter: field · operator · value.
// About 30 filters (S06 F03): references take several values, dates take a day, a range or "the last/next N
// days", numbers compare, text searches. Values are strings in the spec (architecture.md §4.2 "spec").
import { useEffect, useState, type ComponentProps } from "react";
import { useTranslation } from "react-i18next";
import { Search, X } from "lucide-react";
import type { FilterField, FilterOp, FilterOptions, QueryFilter, QuerySpec } from "@/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/field";
import { MultiSelect } from "@/components/ui/multi-select";
import { Select, type SelectOption } from "@/components/ui/select";
import { useLatestSent } from "@/lib/use-latest-sent";

/** What the value part of a filter row looks like for an operator. */
type ValueKind = "multi" | "text" | "number" | "days" | "date" | "range" | "bool" | "wp";
type OpDef = { op: FilterOp; label: string; input?: ValueKind };
type Def = { label: string; ops: OpDef[]; values?: (o: FilterOptions) => SelectOption[] };

function useDefs(options?: FilterOptions): Record<FilterField, Def> {
  const { t } = useTranslation();
  const f = (k: string) => t(`filters.${k}` as "filters.is");
  const col = (k: string) => t(`filters.fields.${k}` as "filters.fields.status");
  const people = (o: FilterOptions) => [{ value: "me", label: f("me") }, ...o.users.map((u) => ({ value: u.id, label: u.name }))];
  const is: OpDef = { op: "eq", label: f("is"), input: "multi" };
  const isNot: OpDef = { op: "neq", label: f("isNot"), input: "multi" };
  const any: OpDef = { op: "any", label: f("any") };
  const none: OpDef = { op: "none", label: f("none") };
  const ref = [is, isNot, any, none];
  const date = (withOverdue: boolean): OpDef[] => [
    ...(withOverdue ? [{ op: "overdue" as const, label: f("overdue") }] : []),
    { op: "today", label: f("today") },
    { op: "this_week", label: f("thisWeek") },
    { op: "next_days", label: f("nextDays"), input: "days" },
    { op: "last_days", label: f("lastDays"), input: "days" },
    { op: "on", label: f("on"), input: "date" },
    { op: "between", label: f("between"), input: "range" },
    any,
    none,
  ];
  const past: OpDef[] = date(false).filter((o) => o.op !== "next_days");
  const num: OpDef[] = [
    { op: "gte", label: f("gte"), input: "number" },
    { op: "lte", label: f("lte"), input: "number" },
    { op: "eq", label: f("is"), input: "number" },
    any,
    none,
  ];
  const text: OpDef[] = [
    { op: "contains", label: f("contains"), input: "text" },
    { op: "not_contains", label: f("notContains"), input: "text" },
  ];
  const yesNo = () => [
    { value: "true", label: f("yes") },
    { value: "false", label: f("no") },
  ];
  const bool: OpDef[] = [{ op: "eq", label: f("is"), input: "bool" }];
  return {
    number: { label: col("number"), ops: [{ op: "eq", label: f("is"), input: "text" }, { op: "neq", label: f("isNot"), input: "text" }] },
    subject: { label: col("subject"), ops: text },
    description: { label: col("description"), ops: text },
    comment: { label: col("comment"), ops: text },
    status: {
      label: col("status"),
      ops: [{ op: "open", label: f("open") }, { op: "closed", label: f("closed") }, is, isNot],
      values: (o) => o.statuses.map((s) => ({ value: s.id, label: s.name })),
    },
    type: { label: col("type"), ops: [is, isNot], values: (o) => o.types.map((s) => ({ value: s.id, label: s.name })) },
    priority: { label: col("priority"), ops: [is, isNot], values: (o) => o.priorities.map((s) => ({ value: s.id, label: s.name })) },
    assignee: { label: col("assignee"), ops: [is, isNot, { op: "none", label: f("unassigned") }, { op: "any", label: f("assigned") }], values: people },
    assigneeGroup: { label: col("assigneeGroup"), ops: [is, isNot], values: (o) => o.groups.map((g) => ({ value: g.id, label: g.name })) },
    assigneeRole: { label: col("assigneeRole"), ops: [is, isNot], values: (o) => o.roles.map((r) => ({ value: r.id, label: r.name })) },
    responsible: { label: col("responsible"), ops: ref, values: people },
    author: { label: col("author"), ops: [is, isNot], values: people },
    watcher: { label: col("watcher"), ops: [is, isNot], values: people },
    version: { label: col("version"), ops: ref, values: (o) => o.versions.map((v) => ({ value: v.id, label: v.name })) },
    category: { label: col("category"), ops: ref, values: (o) => o.categories.map((c) => ({ value: c.id, label: c.name })) },
    project: { label: col("project"), ops: [is, isNot], values: (o) => o.projects.map((p) => ({ value: p.id, label: p.name })) },
    parent: { label: col("parent"), ops: [{ op: "eq", label: f("is"), input: "wp" }, { op: "any", label: f("hasParent") }, { op: "none", label: f("noParent") }] },
    startDate: { label: col("startDate"), ops: date(false) },
    dueDate: { label: col("dueDate"), ops: date(true) },
    dates: { label: col("dates"), ops: [{ op: "between", label: f("overlapsRange"), input: "range" }, any, none] },
    createdAt: { label: col("createdAt"), ops: past },
    updatedAt: { label: col("updatedAt"), ops: past },
    duration: { label: col("duration"), ops: num },
    doneRatio: { label: col("doneRatio"), ops: num },
    work: { label: col("work"), ops: num },
    remainingWork: { label: col("remainingWork"), ops: num },
    scheduleManually: {
      label: col("scheduleManually"),
      ops: bool,
      values: () => [
        { value: "true", label: t("dates.manual") },
        { value: "false", label: t("dates.auto") },
      ],
    },
    isMilestone: { label: col("isMilestone"), ops: bool, values: yesNo },
    blocked: { label: col("blocked"), ops: bool, values: yesNo },
    // custom fields on in this scope that the administrator made filterable
    ...Object.fromEntries(
      (options?.customFields ?? [])
        .filter((cf) => cf.isFilterable)
        .map((cf): [FilterField, Def] => {
          const key: FilterField = `cf:${cf.id}`;
          switch (cf.format) {
            case "list":
              return [key, { label: cf.name, ops: ref, values: () => cf.options.map((o) => ({ value: o.id, label: o.value })) }];
            case "user":
              return [key, { label: cf.name, ops: ref, values: people }];
            case "integer":
            case "float":
              return [key, { label: cf.name, ops: num }];
            case "date":
              return [key, { label: cf.name, ops: date(false) }];
            case "bool":
              return [key, { label: cf.name, ops: bool, values: yesNo }];
            default:
              return [key, { label: cf.name, ops: [...text, any, none] }];
          }
        }),
    ),
  };
}

/** A text or number box that writes to the spec after a short pause, not on every key. */
function LazyInput({ value, onCommit, ...rest }: { value: string; onCommit: (v: string) => void } & Omit<ComponentProps<typeof Input>, "value" | "onChange">) {
  const [v, setV] = useState(value);
  useEffect(() => setV(value), [value]);
  useEffect(() => {
    if (v === value) return;
    const id = setTimeout(() => onCommit(v), 400);
    return () => clearTimeout(id);
  }, [v, value, onCommit]);
  return <Input size="sm" {...rest} value={v} onChange={(e) => setV(e.target.value)} />;
}

function ValueInput({ def, op, filter, options, onValues }: { def: Def; op: OpDef; filter: QueryFilter; options?: FilterOptions; onValues: (v: string[]) => void }) {
  const { t } = useTranslation();
  const label = `${def.label} ${t("filters.value")}`;
  const v = filter.values ?? [];
  switch (op.input) {
    case "multi":
      return <MultiSelect size="sm" aria-label={label} value={v} placeholder={t("filters.pickValue")} onValueChange={onValues} options={options && def.values ? def.values(options) : []} />;
    case "bool":
      return <Select size="sm" aria-label={label} value={v[0]} placeholder={t("filters.pickValue")} onValueChange={(x) => onValues([x])} options={options && def.values ? def.values(options) : []} />;
    case "text":
      return <LazyInput aria-label={label} value={v[0] ?? ""} onCommit={(x) => onValues([x])} placeholder={filter.field === "number" ? t("filters.numbersHint") : undefined} />;
    case "wp":
      return <LazyInput aria-label={label} value={v[0] ?? ""} onCommit={(x) => onValues([x.replace("#", "").trim()])} placeholder="#12" />;
    case "number":
      return <LazyInput aria-label={label} type="number" min={0} value={v[0] ?? ""} onCommit={(x) => onValues([x])} />;
    case "days":
      return (
        <span className="flex items-center gap-1.5 text-xs text-fg-muted">
          <LazyInput aria-label={t("filters.daysLabel", { field: def.label })} type="number" min={1} className="w-20" value={v[0] ?? "7"} onCommit={(x) => onValues([x])} />
          {t("filters.daysUnit")}
        </span>
      );
    case "date":
      return <Input size="sm" type="date" aria-label={label} value={v[0] ?? ""} onChange={(e) => onValues([e.target.value])} />;
    case "range":
      return (
        <span className="flex items-center gap-1.5">
          <Input size="sm" type="date" aria-label={t("filters.from", { field: def.label })} value={v[0] ?? ""} onChange={(e) => onValues([e.target.value, v[1] ?? ""])} />
          <span aria-hidden="true" className="text-fg-muted">
            –
          </span>
          <Input size="sm" type="date" aria-label={t("filters.to", { field: def.label })} value={v[1] ?? ""} min={v[0] || undefined} onChange={(e) => onValues([v[0] ?? "", e.target.value])} />
        </span>
      );
    default:
      return <span aria-hidden="true" />;
  }
}

export function FilterPanel({ spec, onSpec, options }: { spec: QuerySpec; onSpec: (s: QuerySpec) => void; options?: FilterOptions }) {
  const { t } = useTranslation();
  const defs = useDefs(options);
  // The spec as last sent, ahead of its round trip through the URL: two quick edits (removing two filters,
  // say) must build on each other, not on the same stale props.
  const { latest, send } = useLatestSent(spec, (s) => JSON.stringify(s));
  const emit = (next: QuerySpec) => {
    send(next);
    onSpec(next);
  };
  const [text, setText] = useState(spec.text ?? "");
  useEffect(() => setText(spec.text ?? ""), [spec.text]);
  useEffect(() => {
    if ((spec.text ?? "") === text) return;
    const id = setTimeout(() => emit({ ...latest.current, text: text || undefined }), 300);
    return () => clearTimeout(id);
  }, [text, spec, onSpec]);

  // the calendar's window filter (dates · overlaps) is added at query time and never shown here
  const shownHere = (f: QueryFilter) => f.field in defs && !(f.field === "dates" && f.op === "overlaps");
  const cur = latest.current; // newest spec, even before the URL has caught up
  const editable = cur.filters.filter(shownHere);
  const others = cur.filters.filter((f) => !shownHere(f));
  const unused = (Object.keys(defs) as FilterField[])
    .filter((k) => !editable.some((f) => f.field === k))
    .sort((a, b) => defs[a].label.localeCompare(defs[b].label, "ko"));
  /** Rewrites the shown filters from the latest spec; `change` gets them and returns the new list. */
  const setFilters = (change: (shown: QueryFilter[]) => QueryFilter[]) => {
    const cur = latest.current;
    emit({ ...cur, filters: [...cur.filters.filter((f) => !shownHere(f)), ...change(cur.filters.filter(shownHere))] });
  };
  const update = (field: FilterField, next: QueryFilter) => setFilters((fs) => fs.map((f) => (f.field === field ? next : f)));
  const initialValues = (op: OpDef) => (op.input === "days" ? ["7"] : op.input === "bool" ? ["true"] : op.input ? [] : undefined);

  return (
    <section aria-label={t("wp.filters")} className="mb-3 rounded-md border border-border bg-surface p-3">
      <label className="relative mb-3 block max-w-md">
        <span className="sr-only">{t("wp.filterText")}</span>
        <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-fg-muted" />
        <Input type="search" size="sm" className="pl-8" placeholder={t("wp.filterText")} value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <div className="flex flex-col gap-2">
        {editable.map((f) => {
          const def = defs[f.field];
          const op = def.ops.find((o) => o.op === f.op) ?? def.ops[0];
          return (
            <fieldset key={f.field} className="grid grid-cols-1 items-center gap-2 sm:grid-cols-[136px_168px_minmax(0,320px)_auto]">
              <legend className="sr-only">{def.label}</legend>
              <span className="text-sm font-medium text-fg">{def.label}</span>
              <Select
                size="sm"
                aria-label={`${def.label} ${t("filters.operator")}`}
                value={op.op}
                onValueChange={(v) => {
                  const nextOp = def.ops.find((o) => o.op === v)!;
                  // keep picked values when the new operator takes the same kind of value
                  update(f.field, { field: f.field, op: nextOp.op, values: nextOp.input === op.input ? f.values : initialValues(nextOp) });
                }}
                options={def.ops.map((o) => ({ value: o.op, label: o.label }))}
              />
              <ValueInput def={def} op={op} filter={f} options={options} onValues={(values) => update(f.field, { ...f, op: op.op, values })} />
              <Button variant="ghost" size="sm" iconOnly aria-label={t("filters.remove", { field: def.label })} icon={<X />} onClick={() => setFilters((fs) => fs.filter((x) => x.field !== f.field))} />
            </fieldset>
          );
        })}
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {unused.length > 0 && (
          <Select
            size="sm"
            className="w-48"
            aria-label={t("filters.add")}
            placeholder={t("filters.add")}
            value=""
            onValueChange={(field) => {
              const def = defs[field as FilterField];
              setFilters((fs) => [...fs, { field: field as FilterField, op: def.ops[0].op, values: initialValues(def.ops[0]) }]);
            }}
            options={unused.map((k) => ({ value: k, label: defs[k].label }))}
          />
        )}
        {(editable.length > 0 || cur.text) && (
          <Button variant="link" size="sm" onClick={() => emit({ ...latest.current, filters: others, text: undefined })}>
            {t("wp.clearFilters")}
          </Button>
        )}
      </div>
    </section>
  );
}
