// 항목 요청 (/portal/items/:itemId): one catalog item's request form — 제목, 자세한 내용, the item's own form fields
// (always shown, required by the item's flag) and files (uploaded one by one after the request exists). The server
// decides type, priority, group and approvals from the item. Server 422 keys (subject, cf:<id>, catalogItem, ...)
// show under their fields.
import { useId, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Paperclip, Send, X } from "lucide-react";
import type { CustomValue, PortalDesk, PortalItem } from "@/api";
import { isApiError } from "@/api";
import { usePortalDesks, usePortalMutations } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Banner, useToast } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/spinner";
import { RichText } from "@/components/domain/rich-text";
import { RichTextEditor } from "@/components/domain/rich-text-editor";
import { ErrorState, NotFound, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { fieldErrorOf } from "@/features/admin/common";
import { useUnsavedWarning } from "@/lib/use-unsaved-warning";
import { emptyValue, PortalFieldInput } from "./new-ticket";

function ItemForm({ desk, item }: { desk: PortalDesk; item: PortalItem }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const toast = useToast();
  const m = usePortalMutations();
  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [values, setValues] = useState<Record<string, CustomValue>>({});
  const [files, setFiles] = useState<File[]>([]);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const fileInputId = useId();
  const title = t("portal.itemTitle", { name: item.name });
  // 관리자 → 그룹 책임자: the steps in order; two kinds of one step (either may decide) are joined with "·"
  const steps = item.approvalKinds
    .map((s) => s.split(",").map((k) => t(`portal.approvalKinds.${k}`, { defaultValue: k })).join(t("portal.approvalEither")))
    .join(t("portal.approvalThen"));
  useUnsavedWarning(!busy && (!!subject || !!description || files.length > 0));
  useDocumentTitle(title);

  const err = (f: string) => localErrors[f] ?? fieldErrorOf(m.submit.error, f);
  const apiError = isApiError(m.submit.error) ? m.submit.error : null;
  const catalogError = fieldErrorOf(m.submit.error, "catalogItem");
  const formError = apiError && (!apiError.fieldErrors.length || catalogError) ? apiError : null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const local: Record<string, string> = {};
    if (!subject.trim()) local.subject = t("portal.subjectRequired");
    for (const f of item.fields) if (f.required && emptyValue(values[f.id])) local[`cf:${f.id}`] = t("portal.fieldRequired");
    setLocalErrors(local);
    if (Object.keys(local).length) {
      const first = Object.keys(local)[0];
      document.getElementById(first === "subject" ? "portal-subject" : `portal-${first}`)?.focus();
      return;
    }
    setBusy(true);
    try {
      const customValues = Object.fromEntries(item.fields.filter((f) => !emptyValue(values[f.id])).map((f) => [f.id, values[f.id]]));
      // the item decides type and priority; impact and urgency are the portal's middle defaults
      const ticket = await m.submit.mutateAsync({
        identifier: desk.identifier,
        input: { catalogItemId: item.id, typeId: item.typeId, subject: subject.trim(), descriptionHtml: description, impact: 2, urgency: 2, customValues },
      });
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
      <PageHeader title={title} breadcrumbs={[{ label: t("portal.title"), href: "/portal" }, { label: desk.name }, { label: item.name }]} />
      {item.descriptionHtml && <RichText html={item.descriptionHtml} className="mb-5 max-w-3xl text-sm text-fg-muted" />}
      <form onSubmit={(e) => void submit(e)} noValidate className="flex max-w-3xl flex-col gap-5">
        {item.approvalKinds.length > 0 && <Banner tone="info" title={t("portal.approvalNotice", { steps })} />}
        {formError && (
          <Banner tone="danger" title={formError.title}>
            {formError.message !== formError.title ? formError.message : catalogError ?? null}
          </Banner>
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
        {item.fields.map((f) => (
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
            {t("portal.request")}
          </Button>
          <Button asChild variant="ghost">
            <Link to="/portal">{t("app.cancel")}</Link>
          </Button>
        </div>
      </form>
    </>
  );
}

export function PortalItemPage() {
  const { t } = useTranslation();
  const { itemId } = useParams();
  const desks = usePortalDesks();
  if (desks.isPending)
    return (
      <PageBody>
        <Skeleton className="mt-6 h-96 max-w-3xl" />
      </PageBody>
    );
  if (desks.isError) return <ErrorState error={desks.error} onRetry={() => desks.refetch()} />;
  for (const desk of desks.data)
    for (const cat of desk.catalog) {
      const item = cat.items.find((i) => i.id === itemId);
      if (item)
        return (
          <PageBody>
            <ItemForm key={item.id} desk={desk} item={item} />
          </PageBody>
        );
    }
  return <NotFound title={t("portal.itemNotFound")} body={t("portal.itemNotFoundBody")} />;
}
