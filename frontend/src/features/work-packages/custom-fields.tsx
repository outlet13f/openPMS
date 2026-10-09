// Custom fields on work packages (S34 정의, S25 프로젝트 활성화): column labels, display, form inputs and the
// click-to-edit rows on the detail. Values travel as stored (option and user ids, ISO dates, numbers, booleans).
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink } from "lucide-react";
import type { ColumnKey, CustomField, CustomValue, FilterOptions, UserRef, WorkPackage } from "@/api";
import { Avatar } from "@/components/ui/avatar";
import { Input, Textarea } from "@/components/ui/field";
import { MultiSelect } from "@/components/ui/multi-select";
import { Select } from "@/components/ui/select";
import { InlinePick } from "./inline-pick";
import { InlineText } from "./inline-text";

export const cfColumn = (id: string): ColumnKey => `cf:${id}`;
export const cfIdOf = (key: string) => (key.startsWith("cf:") ? key.slice(3) : null);

/** Header text for any column, built-in or custom. */
export function useColumnLabel(options?: FilterOptions) {
  const { t } = useTranslation();
  return (c: ColumnKey) => {
    const id = cfIdOf(c);
    if (id) return options?.customFields.find((f) => f.id === id)?.name ?? t("customFields.unknown");
    return t(`wp.columns.${c as Exclude<ColumnKey, `cf:${string}`>}`);
  };
}

/** A stored value's display text, linked when it is an address. */
export function CustomValueView({ text, format }: { text: string; format?: CustomField["format"] }) {
  if (!text) return <span className="text-fg-muted">-</span>;
  if (format === "link" || (!format && /^https?:\/\//.test(text)))
    return (
      <a href={text} target="_blank" rel="noopener noreferrer" className="inline-flex max-w-full items-center gap-1 text-accent hover:underline" onClick={(e) => e.stopPropagation()}>
        <span className="truncate">{text}</span>
        <ExternalLink className="size-3 shrink-0" aria-hidden="true" />
      </a>
    );
  return <span className="break-words">{text}</span>;
}

const asList = (v: CustomValue) => (v === null ? [] : Array.isArray(v) ? v.map(String) : [String(v)]);

/** Controlled input for forms (new work package, a field's default value in administration). */
export function CustomFieldInput({
  field,
  value,
  onChange,
  people,
  id,
  label,
  "aria-invalid": invalid,
  "aria-describedby": describedBy,
}: {
  field: CustomField;
  value: CustomValue;
  onChange: (v: CustomValue) => void;
  people: UserRef[];
  /** accessible name when it is not the field's own name (e.g. its default value in administration) */
  label?: string;
  /** id and aria-* come from <Field>, which labels the control */
  id?: string;
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
}) {
  const { t } = useTranslation();
  const aria = { id, "aria-invalid": invalid || undefined, "aria-describedby": describedBy, "aria-label": id ? undefined : field.name };
  const text = (v: string) => onChange(v === "" ? null : v);
  switch (field.format) {
    case "text":
      return <Textarea {...aria} rows={3} value={value === null ? "" : String(value)} maxLength={field.maxLength ?? 10000} onChange={(e) => text(e.target.value)} />;
    case "integer":
    case "float":
      return (
        <Input {...aria} type="number" step={field.format === "integer" ? 1 : "any"} value={value === null ? "" : String(value)} onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))} />
      );
    case "date":
      return <Input {...aria} type="date" value={value === null ? "" : String(value)} onChange={(e) => text(e.target.value)} />;
    case "link":
      return <Input {...aria} type="url" placeholder="https://" value={value === null ? "" : String(value)} onChange={(e) => text(e.target.value)} />;
    case "bool":
      return (
        <Select
          {...aria}
          value={value === null ? "none" : value ? "true" : "false"}
          onValueChange={(v) => onChange(v === "none" ? null : v === "true")}
          options={[
            { value: "none", label: t("app.none") },
            { value: "true", label: t("customFields.yes") },
            { value: "false", label: t("customFields.no") },
          ]}
        />
      );
    case "list":
    case "user": {
      const opts =
        field.format === "list"
          ? field.options.map((o) => ({ value: o.id, label: o.value }))
          : people.map((u) => ({ value: u.id, textValue: u.name, label: <span className="inline-flex items-center gap-2"><Avatar id={u.id} name={u.name} size="xs" />{u.name}</span> }));
      if (field.isMultiValue)
        return <MultiSelect aria-label={label ?? field.name} value={asList(value)} placeholder={t("app.select")} onValueChange={(v) => onChange(v.length ? v : null)} options={opts} />;
      return (
        <Select {...aria} value={value === null ? "none" : String(value)} onValueChange={(v) => onChange(v === "none" ? null : v)} options={[{ value: "none", label: t("app.none") }, ...opts]} />
      );
    }
    default:
      return <Input {...aria} value={value === null ? "" : String(value)} maxLength={field.maxLength ?? 255} onChange={(e) => text(e.target.value)} />;
  }
}

/** One click-to-edit value on the detail. `save` resolves true when the server took it. */
export function CustomFieldInline({
  field,
  wp,
  people,
  disabled,
  save,
}: {
  field: CustomField;
  wp: WorkPackage;
  people: UserRef[];
  disabled: boolean;
  save: (v: CustomValue) => Promise<boolean>;
}) {
  const { t } = useTranslation();
  const cur = wp.customValues[field.id] ?? { value: null, text: "" };
  const view: ReactNode = <CustomValueView text={cur.text} format={field.format} />;
  const label = `${field.name}: ${cur.text || t("app.none")}`;

  switch (field.format) {
    case "list":
    case "user":
      if (field.isMultiValue) {
        if (disabled) return view;
        const opts = field.format === "list" ? field.options.map((o) => ({ value: o.id, label: o.value })) : people.map((u) => ({ value: u.id, label: u.name }));
        return <MultiSelect size="sm" aria-label={field.name} value={asList(cur.value)} placeholder={t("app.none")} onValueChange={(v) => void save(v.length ? v : null)} options={opts} />;
      }
      return (
        <InlinePick
          value={cur.value === null ? null : String(cur.value)}
          disabled={disabled}
          label={label}
          onPick={(v) => void save(v)}
          options={[
            { value: null, label: t("app.none") },
            ...(field.format === "list"
              ? field.options.map((o) => ({ value: o.id, label: o.value }))
              : people.map((u) => ({ value: u.id, label: u.name, icon: <Avatar id={u.id} name={u.name} size="xs" /> }))),
          ]}
        >
          {view}
        </InlinePick>
      );
    case "bool":
      return (
        <InlinePick
          value={cur.value === null ? null : String(cur.value)}
          disabled={disabled}
          label={label}
          onPick={(v) => void save(v === null ? null : v === "true")}
          options={[
            { value: null, label: t("app.none") },
            { value: "true", label: t("customFields.yes") },
            { value: "false", label: t("customFields.no") },
          ]}
        >
          {view}
        </InlinePick>
      );
    default: {
      const type = field.format === "integer" || field.format === "float" ? "number" : field.format === "date" ? "date" : field.format === "link" ? "url" : "text";
      return (
        <InlineText
          type={type}
          label={field.name}
          disabled={disabled}
          maxLength={field.maxLength ?? (field.format === "text" ? 10000 : 255)}
          value={cur.value === null ? "" : String(cur.value)}
          display={cur.text ? view : undefined}
          onSave={(v) => save(v === "" ? null : type === "number" ? Number(v) : v)}
        />
      );
    }
  }
}
