// Filter panel (components.md "FilterPanel"): one fieldset per filter: field · operator · value.
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Search, X } from "lucide-react";
import type { FilterField, FilterOp, FilterOptions, QueryFilter, QuerySpec } from "@/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/field";
import { Select, type SelectOption } from "@/components/ui/select";

type Def = { label: string; ops: { op: FilterOp; label: string; needsValue: boolean }[]; values: (o: FilterOptions) => SelectOption[] };

function useDefs(): Record<Exclude<FilterField, "subject" | "updatedAt" | "isMilestone" | "responsible">, Def> {
  const { t } = useTranslation();
  const f = (k: string) => t(`filters.${k}` as "filters.is");
  const people = (o: FilterOptions) => [{ value: "me", label: f("me") }, ...o.users.map((u) => ({ value: u.id, label: u.name }))];
  const refOps = [
    { op: "eq" as const, label: f("is"), needsValue: true },
    { op: "neq" as const, label: f("isNot"), needsValue: true },
  ];
  const dateOps = [
    { op: "overdue" as const, label: f("overdue"), needsValue: false },
    { op: "today" as const, label: f("today"), needsValue: false },
    { op: "this_week" as const, label: f("thisWeek"), needsValue: false },
    { op: "next_days" as const, label: f("next7"), needsValue: false },
    { op: "any" as const, label: f("any"), needsValue: false },
    { op: "none" as const, label: f("none"), needsValue: false },
  ];
  return {
    status: {
      label: t("wp.columns.status"),
      ops: [{ op: "open", label: f("open"), needsValue: false }, { op: "closed", label: f("closed"), needsValue: false }, ...refOps],
      values: (o) => o.statuses.map((s) => ({ value: s.id, label: s.name })),
    },
    type: { label: t("wp.columns.type"), ops: refOps, values: (o) => o.types.map((s) => ({ value: s.id, label: s.name })) },
    priority: { label: t("wp.columns.priority"), ops: refOps, values: (o) => o.priorities.map((s) => ({ value: s.id, label: s.name })) },
    assignee: {
      label: t("wp.columns.assignee"),
      ops: [...refOps, { op: "none", label: f("unassigned"), needsValue: false }, { op: "any", label: f("assigned"), needsValue: false }],
      values: people,
    },
    author: { label: t("wp.columns.author"), ops: refOps, values: people },
    version: { label: t("wp.columns.version"), ops: [...refOps, { op: "none", label: f("none"), needsValue: false }], values: (o) => o.versions.map((v) => ({ value: v.id, label: v.name })) },
    dueDate: { label: t("wp.columns.dueDate"), ops: dateOps, values: () => [] },
    startDate: { label: t("wp.columns.startDate"), ops: dateOps.filter((d) => d.op !== "overdue"), values: () => [] },
  };
}

export function FilterPanel({ spec, onSpec, options }: { spec: QuerySpec; onSpec: (s: QuerySpec) => void; options?: FilterOptions }) {
  const { t } = useTranslation();
  const defs = useDefs();
  const [text, setText] = useState(spec.text ?? "");
  useEffect(() => setText(spec.text ?? ""), [spec.text]);
  useEffect(() => {
    if ((spec.text ?? "") === text) return;
    const id = setTimeout(() => onSpec({ ...spec, text: text || undefined }), 300);
    return () => clearTimeout(id);
  }, [text, spec, onSpec]);

  const editable = spec.filters.filter((f) => f.field in defs);
  const others = spec.filters.filter((f) => !(f.field in defs));
  const unused = (Object.keys(defs) as (keyof typeof defs)[]).filter((k) => !editable.some((f) => f.field === k));
  const setFilters = (fs: QueryFilter[]) => onSpec({ ...spec, filters: [...others, ...fs] });
  const update = (i: number, next: QueryFilter) => setFilters(editable.map((f, j) => (j === i ? next : f)));

  return (
    <section aria-label={t("wp.filters")} className="mb-3 rounded-md border border-border bg-surface p-3">
      <label className="relative mb-3 block max-w-md">
        <span className="sr-only">{t("wp.filterText")}</span>
        <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-fg-muted" />
        <Input type="search" size="sm" className="pl-8" placeholder={t("wp.filterText")} value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <div className="flex flex-col gap-2">
        {editable.map((f, i) => {
          const def = defs[f.field as keyof typeof defs];
          const op = def.ops.find((o) => o.op === f.op) ?? def.ops[0];
          return (
            <fieldset key={f.field} className="grid grid-cols-1 items-center gap-2 sm:grid-cols-[120px_160px_minmax(0,240px)_auto]">
              <legend className="sr-only">{def.label}</legend>
              <span className="text-sm font-medium text-fg">{def.label}</span>
              <Select
                size="sm"
                aria-label={`${def.label} ${t("filters.operator")}`}
                value={op.op}
                onValueChange={(v) => {
                  const nextOp = def.ops.find((o) => o.op === v)!;
                  update(i, { field: f.field, op: nextOp.op, values: nextOp.needsValue ? f.values ?? [] : undefined });
                }}
                options={def.ops.map((o) => ({ value: o.op, label: o.label }))}
              />
              {op.needsValue ? (
                <Select
                  size="sm"
                  aria-label={`${def.label} ${t("filters.value")}`}
                  value={f.values?.[0]}
                  placeholder={t("filters.pickValue")}
                  onValueChange={(v) => update(i, { ...f, values: [v] })}
                  options={options ? def.values(options) : []}
                />
              ) : (
                <span aria-hidden="true" />
              )}
              <Button variant="ghost" size="sm" iconOnly aria-label={t("filters.remove", { field: def.label })} icon={<X />} onClick={() => setFilters(editable.filter((_, j) => j !== i))} />
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
              const def = defs[field as keyof typeof defs];
              setFilters([...editable, { field: field as FilterField, op: def.ops[0].op, values: def.ops[0].needsValue ? [] : undefined }]);
            }}
            options={unused.map((k) => ({ value: k, label: defs[k].label }))}
          />
        )}
        {(editable.length > 0 || spec.text) && (
          <Button variant="link" size="sm" onClick={() => onSpec({ ...spec, filters: others, text: undefined })}>
            {t("wp.clearFilters")}
          </Button>
        )}
      </div>
    </section>
  );
}
