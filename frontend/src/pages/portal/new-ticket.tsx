// 서비스 요청 접수: type (from ?type=, else the desk's first), 제목, 자세한 내용, 영향 범위 and 급한 정도 (default 보통),
// the desk's portal fields for the chosen type, and files (uploaded one by one after the ticket exists).
// Only what the portal offers is sent; the server decides priority, group and SLA.
import { useId, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Paperclip, Send, X } from "lucide-react";
import type { CustomValue, IdName, Level, PortalDesk, PortalField } from "@/api";
import { isApiError } from "@/api";
import { usePortalDesks, usePortalMutations } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Banner, useToast } from "@/components/ui/feedback";
import { Field, Input, Textarea } from "@/components/ui/field";
import { MultiSelect } from "@/components/ui/multi-select";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { RichTextEditor } from "@/components/domain/rich-text-editor";
import { ErrorState, NotFound, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { fieldErrorOf } from "@/features/admin/common";
import { useUnsavedWarning } from "@/lib/use-unsaved-warning";
import { cn } from "@/lib/cn";
import { useTypeAction } from "./home";

const LEVELS: Level[] = [1, 2, 3];

/** A radio group as a fieldset with a legend (native radios: arrow keys and screen readers work as usual). */
export function RadioGroupField<T extends string | number>({
  legend,
  name,
  value,
  options,
  onChange,
  error,
}: {
  legend: string;
  name: string;
  value: T;
  options: { value: T; label: ReactNode }[];
  onChange: (v: T) => void;
  error?: string;
}) {
  const errorId = useId();
  return (
    <fieldset className="flex flex-col gap-1.5" aria-describedby={error ? errorId : undefined}>
      <legend className="mb-1.5 text-sm font-medium text-fg">{legend}</legend>
      <div className="flex flex-wrap gap-2">
        {options.map((o) => (
          <label
            key={String(o.value)}
            className={cn(
              "inline-flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm text-fg",
              "has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-focus-ring",
              value === o.value ? "border-accent bg-accent-subtle" : "border-border hover:bg-surface",
            )}
          >
            <input type="radio" name={name} className="size-4 accent-accent focus-visible:outline-none" checked={value === o.value} onChange={() => onChange(o.value)} />
            {o.label}
          </label>
        ))}
      </div>
      {error && (
        <p id={errorId} className="text-xs text-danger">
          {error}
        </p>
      )}
    </fieldset>
  );
}

/** One portal field's input by its format; a list shows the option values and sends the option ids. */
export function PortalFieldInput({ field, value, onChange, ...aria }: { field: PortalField; value: CustomValue; onChange: (v: CustomValue) => void; id?: string; "aria-invalid"?: boolean; "aria-describedby"?: string }) {
  const { t } = useTranslation();
  const text = (v: string) => onChange(v === "" ? null : v);
  switch (field.format) {
    case "text":
      return <Textarea {...aria} rows={3} value={value === null ? "" : String(value)} onChange={(e) => text(e.target.value)} />;
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
            { value: "true", label: t("portal.yes") },
            { value: "false", label: t("portal.no") },
          ]}
        />
      );
    case "list": {
      const opts = field.options.map((o) => ({ value: o.id, label: o.value }));
      if (field.multi) {
        const list = Array.isArray(value) ? value.map(String) : [];
        return <MultiSelect aria-label={field.name} value={list} placeholder={t("app.select")} onValueChange={(v) => onChange(v.length ? v : null)} options={opts} />;
      }
      return <Select {...aria} value={value === null ? "none" : String(value)} onValueChange={(v) => onChange(v === "none" ? null : v)} options={[{ value: "none", label: t("app.none") }, ...opts]} />;
    }
    default:
      return <Input {...aria} value={value === null ? "" : String(value)} maxLength={255} onChange={(e) => text(e.target.value)} />;
  }
}

export const emptyValue = (v: CustomValue | undefined) => v === undefined || v === null || v === "" || (Array.isArray(v) && v.length === 0);

function NewTicketForm({ desk, initialType }: { desk: PortalDesk; initialType: IdName }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const toast = useToast();
  const action = useTypeAction();
  const m = usePortalMutations();
  const [typeId, setTypeId] = useState(initialType.id);
  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [impact, setImpact] = useState<Level>(2);
  const [urgency, setUrgency] = useState<Level>(2);
  const [values, setValues] = useState<Record<string, CustomValue>>({});
  const [files, setFiles] = useState<File[]>([]);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const fileInputId = useId();
  const type = desk.types.find((x) => x.id === typeId) ?? initialType;
  const fields = desk.fields.filter((f) => f.typeIds.includes(typeId));
  useUnsavedWarning(!busy && (!!subject || !!description || files.length > 0));
  useDocumentTitle(t("portal.newTitle", { action: action(type), desk: desk.name }));

  const err = (f: string) => localErrors[f] ?? fieldErrorOf(m.submit.error, f);
  const formError = isApiError(m.submit.error) && !m.submit.error.fieldErrors.length ? m.submit.error : null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const local: Record<string, string> = {};
    if (!subject.trim()) local.subject = t("portal.subjectRequired");
    for (const f of fields) if (f.required && emptyValue(values[f.id])) local[`cf:${f.id}`] = t("portal.fieldRequired");
    setLocalErrors(local);
    if (Object.keys(local).length) {
      const first = Object.keys(local)[0];
      document.getElementById(first === "subject" ? "portal-subject" : `portal-${first}`)?.focus();
      return;
    }
    setBusy(true);
    try {
      // only the chosen type's portal fields go on
      const customValues = Object.fromEntries(fields.filter((f) => !emptyValue(values[f.id])).map((f) => [f.id, values[f.id]]));
      const ticket = await m.submit.mutateAsync({ identifier: desk.identifier, input: { typeId, subject: subject.trim(), descriptionHtml: description, impact, urgency, customValues } });
      for (const file of files) {
        try {
          await m.attach.mutateAsync({ number: ticket.number, file });
        } catch (x) {
          toast({ tone: "danger", title: t("portal.attachFailed", { name: file.name }), description: isApiError(x) ? x.message : undefined });
        }
      }
      toast({ tone: "success", title: t("portal.submitted") });
      navigate(`/portal/tickets/${ticket.number}`);
    } catch {
      setBusy(false); // field errors show under their fields; anything else in the banner
    }
  };

  return (
    <>
    <PageHeader title={t("portal.newTitle", { action: action(type), desk: desk.name })} breadcrumbs={[{ label: t("portal.title"), href: "/portal" }, { label: desk.name }]} />
    <form onSubmit={(e) => void submit(e)} noValidate className="flex max-w-3xl flex-col gap-5">
      {formError && <Banner tone="danger" title={formError.title}>{formError.message !== formError.title ? formError.message : null}</Banner>}
      {desk.types.length > 1 && (
        <RadioGroupField
          legend={t("portal.type")}
          name="portal-type"
          value={typeId}
          options={desk.types.map((x) => ({ value: x.id, label: action(x) }))}
          onChange={setTypeId}
          error={err("type")}
        />
      )}
      <Field label={t("portal.subject")} required error={err("subject")}>
        <Input id="portal-subject" value={subject} maxLength={255} onChange={(e) => setSubject(e.target.value)} autoFocus />
      </Field>
      <div className="flex flex-col gap-1.5">
        <p className="text-sm font-medium text-fg" aria-hidden="true">
          {t("portal.description")}
        </p>
        <RichTextEditor value="" onChange={setDescription} label={t("portal.description")} placeholder={t("portal.descriptionPlaceholder")} minRows={6} invalid={!!err("descriptionHtml")} />
        {err("descriptionHtml") && <p className="text-xs text-danger">{err("descriptionHtml")}</p>}
      </div>
      <RadioGroupField
        legend={t("portal.impact")}
        name="portal-impact"
        value={impact}
        options={LEVELS.map((l) => ({ value: l, label: t(`portal.impactLevels.${l}`) }))}
        onChange={setImpact}
        error={err("impact")}
      />
      <RadioGroupField
        legend={t("portal.urgency")}
        name="portal-urgency"
        value={urgency}
        options={LEVELS.map((l) => ({ value: l, label: t(`portal.urgencyLevels.${l}`) }))}
        onChange={setUrgency}
        error={err("urgency")}
      />
      {fields.map((f) => (
        <Field key={f.id} label={f.name} required={f.required} error={err(`cf:${f.id}`)}>
          <PortalFieldInput id={`portal-cf:${f.id}`} field={f} value={values[f.id] ?? null} onChange={(v) => setValues((o) => ({ ...o, [f.id]: v }))} />
        </Field>
      ))}
      <div className="flex flex-col gap-1.5">
        <label htmlFor={fileInputId} className="text-sm font-medium text-fg">
          {t("portal.attach")}
        </label>
        <input
          id={fileInputId}
          type="file"
          multiple
          aria-describedby={`${fileInputId}-hint`}
          className="text-sm text-fg file:mr-3 file:h-control-sm file:cursor-pointer file:rounded-md file:border file:border-border file:bg-surface file:px-2.5 file:text-xs file:text-fg hover:file:bg-surface-hover"
          onChange={(e) => {
            const picked = Array.from(e.target.files ?? []);
            setFiles((xs) => [...xs, ...picked]);
            e.target.value = "";
          }}
        />
        <p id={`${fileInputId}-hint`} className="text-xs text-fg-muted">
          {t("portal.attachHint")}
        </p>
        {files.length > 0 && (
          <ul className="flex flex-col gap-1">
            {files.map((f, i) => (
              <li key={`${f.name}-${i}`} className="flex items-center gap-2 text-sm text-fg">
                <Paperclip className="size-4 text-fg-muted" aria-hidden="true" />
                <span className="truncate">{f.name}</span>
                <Button variant="ghost" size="sm" iconOnly aria-label={t("portal.removeFile", { name: f.name })} icon={<X />} onClick={() => setFiles((xs) => xs.filter((_, j) => j !== i))} />
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" variant="primary" icon={<Send />} loading={busy}>
          {t("portal.submit")}
        </Button>
        <Button asChild variant="ghost">
          <Link to="/portal">{t("app.cancel")}</Link>
        </Button>
      </div>
    </form>
    </>
  );
}

export function PortalNewTicketPage() {
  const { t } = useTranslation();
  const { desk: identifier } = useParams();
  const [params] = useSearchParams();
  const desks = usePortalDesks();
  if (desks.isPending)
    return (
      <PageBody>
        <Skeleton className="mt-6 h-96 max-w-3xl" />
      </PageBody>
    );
  if (desks.isError) return <ErrorState error={desks.error} onRetry={() => desks.refetch()} />;
  const desk = desks.data.find((d) => d.identifier === identifier);
  const type = desk?.types.find((x) => x.id === params.get("type")) ?? desk?.types[0];
  if (!desk || !type) return <NotFound title={t("portal.deskNotFound")} />;
  return (
    <PageBody>
      <NewTicketForm key={`${desk.identifier}:${type.id}`} desk={desk} initialType={type} />
    </PageBody>
  );
}
