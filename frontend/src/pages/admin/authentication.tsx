// 관리 · 로그인과 비밀번호: password rules (length, kinds of characters), the lockout after failed sign-ins, whether a password
// reset can be asked for, and how long mailed links last.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Save } from "lucide-react";
import type { AuthSettings, PasswordClass } from "@/api";
import { useAuthSettings, useSaveAuthSettings } from "@/api/admin-hooks";
import { PASSWORD_CLASSES, describeRules } from "@/api/password-policy";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { useToast } from "@/components/ui/feedback";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { fieldErrorOf } from "@/features/admin/common";
import { useUnsavedWarning } from "@/lib/use-unsaved-warning";
import { AdminGuard } from "./index";

function Form({ saved }: { saved: AuthSettings }) {
  const { t } = useTranslation();
  const toast = useToast();
  const save = useSaveAuthSettings();
  const [v, setV] = useState(saved);
  // newer settings from the server replace the form only while it has no edits of its own
  const [base, setBase] = useState(saved);
  if (JSON.stringify(saved) !== JSON.stringify(base)) {
    setBase(saved);
    if (JSON.stringify(v) === JSON.stringify(base)) setV(saved);
  }
  const dirty = JSON.stringify(v) !== JSON.stringify(saved);
  useUnsavedWarning(dirty);
  const err = (k: string) => fieldErrorOf(save.error, k);
  const num = (k: keyof AuthSettings, label: string, hint?: string) => (
    <Field label={label} hint={hint} error={err(k)}>
      <Input type="number" inputMode="numeric" className="max-w-32" value={String(v[k])} onChange={(e) => setV({ ...v, [k]: e.target.value === "" ? 0 : Number(e.target.value) })} />
    </Field>
  );
  const toggleClass = (k: PasswordClass, on: boolean) => {
    const requiredClasses = on ? [...v.requiredClasses, k] : v.requiredClasses.filter((x) => x !== k);
    setV({ ...v, requiredClasses, minClasses: Math.max(v.minClasses, requiredClasses.length) });
  };
  return (
    <form
      className="flex max-w-3xl flex-col gap-8"
      noValidate
      onSubmit={(e) => (e.preventDefault(), save.mutate(v, { onSuccess: () => toast({ tone: "success", title: t("app.saved") }) }))}
    >
      <section aria-labelledby="au-pw" className="flex flex-col gap-4">
        <h2 id="au-pw" className="text-lg text-fg">{t("authSettings.password")}</h2>
        {num("minLength", t("authSettings.minLength"), t("authSettings.minLengthHint"))}
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium text-fg">{t("authSettings.required")}</legend>
          {PASSWORD_CLASSES.map((k) => (
            <Checkbox key={k} label={t(`authSettings.classes.${k}`)} checked={v.requiredClasses.includes(k)} onCheckedChange={(c) => toggleClass(k, c === true)} />
          ))}
          {err("requiredClasses") && <p className="text-sm text-danger">{err("requiredClasses")}</p>}
        </fieldset>
        <Field label={t("authSettings.minClasses")} hint={t("authSettings.minClassesHint")} error={err("minClasses")}>
          <Select
            className="max-w-48"
            value={String(v.minClasses)}
            onValueChange={(x) => setV({ ...v, minClasses: Number(x) })}
            options={[0, 1, 2, 3, 4].map((n) => ({ value: String(n), label: n === 0 ? t("authSettings.noRule") : t("authSettings.kinds", { count: n }), disabled: n < v.requiredClasses.length }))}
          />
        </Field>
        <p className="rounded-md bg-surface px-3 py-2 text-sm text-fg-muted">{t("authSettings.preview", { rules: describeRules(v) })}</p>
        <p className="text-xs text-fg-muted">{t("authSettings.existingNote")}</p>
      </section>

      <section aria-labelledby="au-lock" className="flex flex-col gap-4">
        <h2 id="au-lock" className="text-lg text-fg">{t("authSettings.lockout")}</h2>
        {num("lockoutAttempts", t("authSettings.lockoutAttempts"), t("authSettings.lockoutAttemptsHint"))}
        {num("lockoutMinutes", t("authSettings.lockoutMinutes"))}
      </section>

      <section aria-labelledby="au-links" className="flex flex-col gap-4">
        <h2 id="au-links" className="text-lg text-fg">{t("authSettings.links")}</h2>
        <Checkbox label={t("authSettings.resetEnabled")} description={t("authSettings.resetEnabledHint")} checked={v.resetEnabled} onCheckedChange={(c) => setV({ ...v, resetEnabled: c === true })} />
        {num("resetTtlMinutes", t("authSettings.resetTtl"))}
        {num("invitationTtlHours", t("authSettings.invitationTtl"))}
      </section>

      <div>
        <Button type="submit" variant="primary" icon={<Save />} disabled={!dirty} loading={save.isPending}>
          {t("app.save")}
        </Button>
      </div>
    </form>
  );
}

export function AdminAuthenticationPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.authentication"));
  const q = useAuthSettings();
  return (
    <PageBody>
      <PageHeader title={t("admin.sections.authentication")} breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.authentication") }]} />
      <AdminGuard>
        {q.isPending ? <Skeleton className="h-64 w-full" /> : q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : <Form saved={q.data} />}
      </AdminGuard>
    </PageBody>
  );
}
