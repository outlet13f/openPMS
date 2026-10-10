// 관리 · 로그인과 비밀번호: password rules (length, kinds of characters), the lockout after failed sign-ins, whether a password
// reset can be asked for, how long mailed links last, and the company SSO (OpenID Connect) providers.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { MoreHorizontal, Pencil, Plus, Save, Trash2 } from "lucide-react";
import type { AdminOidcProvider, AuthSettings, OidcProviderInput, PasswordClass } from "@/api";
import { useAuthSettings, useOidcMutations, useOidcProviders, useSaveAuthSettings } from "@/api/admin-hooks";
import { PASSWORD_CLASSES, describeRules } from "@/api/password-policy";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { useToast } from "@/components/ui/feedback";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { Badge } from "@/components/ui/badges";
import { Dialog, Menu } from "@/components/ui/overlay";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ConfirmDialog, YesNo, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
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

/* ---------- SSO (OpenID Connect) ---------- */

const EMPTY_OIDC: OidcProviderInput = { slug: "", displayName: "", issuer: "", clientId: "", clientSecret: "", scopes: "openid email profile", claimEmail: "email", claimName: "name", autoCreate: false, enabled: true };

function OidcDialog({ editing, onClose }: { editing: AdminOidcProvider | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useOidcMutations();
  const runToast = useRunWithToast();
  const [v, setV] = useState<OidcProviderInput>(editing ? { ...editing, clientSecret: "" } : EMPTY_OIDC);
  const mutation = editing ? m.update : m.create;
  const err = (k: string) => fieldErrorOf(mutation.error, k);
  const submit = async () => {
    const { slug, ...patch } = v;
    const ok = editing ? await runToast(m.update.mutateAsync({ id: editing.id, patch }), t("app.saved")) : await runToast(m.create.mutateAsync({ ...patch, slug }), t("admin.created"));
    if (ok) onClose();
  };
  const text = (k: keyof OidcProviderInput, label: string, extra: { hint?: string; required?: boolean; placeholder?: string; type?: string } = {}) => (
    <Field label={label} hint={extra.hint} required={extra.required} error={err(k)}>
      <Input type={extra.type ?? "text"} autoComplete="off" spellCheck={false} placeholder={extra.placeholder} value={String(v[k] ?? "")} onChange={(e) => setV({ ...v, [k]: e.target.value })} />
    </Field>
  );
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={editing ? t("authSettings.sso.edit") : t("authSettings.sso.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={mutation.isPending} onClick={() => void submit()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("authSettings.sso.displayName")} hint={t("authSettings.sso.displayNameHint")} required error={err("displayName")}>
          <Input data-autofocus value={v.displayName} maxLength={60} onChange={(e) => setV({ ...v, displayName: e.target.value })} />
        </Field>
        {editing ? (
          <div className="text-sm">
            <p className="font-medium text-fg">{t("authSettings.sso.redirectUri")}</p>
            <p className="mt-1 break-all rounded-md bg-surface px-2 py-1 font-mono text-xs text-fg">{editing.redirectUri}</p>
            <p className="mt-1 text-xs text-fg-muted">{t("authSettings.sso.redirectUriHint")}</p>
          </div>
        ) : (
          text("slug", t("authSettings.sso.slug"), { hint: t("authSettings.sso.slugHint"), required: true, placeholder: "company" })
        )}
        {text("issuer", t("authSettings.sso.issuer"), { hint: t("authSettings.sso.issuerHint"), required: true, placeholder: "https://sso.example.co.kr/realms/company" })}
        {text("clientId", t("authSettings.sso.clientId"), { required: true })}
        {text("clientSecret", t("authSettings.sso.clientSecret"), { type: "password", required: !editing, placeholder: editing?.secretSet ? t("authSettings.sso.secretKeep") : undefined })}
        {text("scopes", t("authSettings.sso.scopes"), { hint: t("authSettings.sso.scopesHint") })}
        <div className="grid gap-4 sm:grid-cols-2">
          {text("claimEmail", t("authSettings.sso.claimEmail"))}
          {text("claimName", t("authSettings.sso.claimName"), { hint: t("authSettings.sso.claimNameHint") })}
        </div>
        <Checkbox label={t("authSettings.sso.autoCreate")} description={t("authSettings.sso.autoCreateHint")} checked={v.autoCreate} onCheckedChange={(c) => setV({ ...v, autoCreate: c === true })} />
        <Checkbox label={t("authSettings.sso.enabled")} description={t("authSettings.sso.enabledHint")} checked={v.enabled} onCheckedChange={(c) => setV({ ...v, enabled: c === true })} />
      </div>
    </Dialog>
  );
}

function SsoSection() {
  const { t } = useTranslation();
  const list = useOidcProviders();
  const m = useOidcMutations();
  const runToast = useRunWithToast();
  const [dialog, setDialog] = useState<{ editing: AdminOidcProvider | null } | null>(null);
  const [removing, setRemoving] = useState<AdminOidcProvider | null>(null);
  return (
    <section aria-labelledby="au-sso" className="mt-10 flex max-w-5xl flex-col gap-4 border-t border-border pt-8">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="au-sso" className="text-lg text-fg">{t("authSettings.sso.title")}</h2>
          <p className="mt-1 max-w-3xl text-sm text-fg-muted">{t("authSettings.sso.intro")}</p>
        </div>
        <Button icon={<Plus />} onClick={() => setDialog({ editing: null })}>
          {t("authSettings.sso.new")}
        </Button>
      </div>
      {list.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : list.isError ? (
        <ErrorState error={list.error} onRetry={() => list.refetch()} />
      ) : !list.data.length ? (
        <p className="rounded-md border border-dashed border-border-input px-4 py-6 text-center text-sm text-fg-muted">{t("authSettings.sso.empty")}</p>
      ) : (
        <AdminTable minWidth={760}>
          <thead>
            <tr className={theadRow}>
              <th scope="col" className={th}>{t("authSettings.sso.displayName")}</th>
              <th scope="col" className={th}>{t("authSettings.sso.redirectUri")}</th>
              <th scope="col" className={th}>{t("authSettings.sso.autoCreateShort")}</th>
              <th scope="col" className={`${th} text-right`}>{t("authSettings.sso.linked")}</th>
              <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>
            </tr>
          </thead>
          <tbody>
            {list.data.map((p) => (
              <tr key={p.id} className={tbodyRow}>
                <td className="px-3">
                  <span className="font-medium text-fg">{p.displayName}</span> {!p.enabled && <Badge>{t("authSettings.sso.off")}</Badge>}
                  <span className="block truncate text-xs text-fg-muted">{p.issuer}</span>
                </td>
                <td className="px-3"><span className="break-all font-mono text-xs text-fg">{p.redirectUri}</span></td>
                <td className="px-3"><YesNo value={p.autoCreate} /></td>
                <td className="px-3 text-right tabular-nums">{p.linkedUsers}</td>
                <td className="px-2">
                  <Menu
                    align="end"
                    trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: p.displayName })} icon={<MoreHorizontal />} />}
                    items={[
                      { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: p }) },
                      { kind: "separator" },
                      { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(p) },
                    ]}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </AdminTable>
      )}
      {dialog && <OidcDialog key={dialog.editing?.id ?? "new"} editing={dialog.editing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("authSettings.sso.deleteTitle")}
        body={removing && t("authSettings.sso.deleteBody", { name: removing.displayName, count: removing.linkedUsers })}
        confirm={t("app.delete")}
        pending={m.remove.isPending}
        onConfirm={async () => {
          if (removing) await runToast(m.remove.mutateAsync(removing.id), t("admin.deleted"));
          setRemoving(null);
        }}
      />
    </section>
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
        <SsoSection />
      </AdminGuard>
    </PageBody>
  );
}
