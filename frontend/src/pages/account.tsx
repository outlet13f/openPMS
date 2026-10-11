// S37 계정 설정 (/my/account): my name, time zone and theme, how activity is shown, my password, and signing
// out of other devices. Email and roles are changed by an administrator (S35).
import { useMemo, useState, type FormEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { usePasswordRules } from "@/api/hooks";
import { DEFAULT_AUTH_SETTINGS, describeRules } from "@/api/password-policy";
import { KeyRound, LogOut, Mail, Monitor, Moon, Save, Sun } from "lucide-react";
import { isApiError, type Theme } from "@/api";
import { useAccountMutations, useMe } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Checkbox, RadioCards } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { useToast } from "@/components/ui/feedback";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ConfirmDialog, fieldErrorOf } from "@/features/admin/common";
import { PageBody, PageHeader, useDocumentTitle } from "@/app/page";

function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-4 border-b border-border-muted pb-8 last:border-b-0">
      <div>
        <h2 className="text-lg text-fg">{title}</h2>
        {hint && <p className="mt-0.5 text-sm text-fg-muted">{hint}</p>}
      </div>
      {children}
    </section>
  );
}

/** "Asia/Seoul (UTC+09:00)" for every zone the browser knows, the current offset in the label. */
function useZones() {
  return useMemo(() => {
    const now = new Date();
    const offset = (zone: string) => {
      const part = new Intl.DateTimeFormat("en-US", { timeZone: zone, timeZoneName: "longOffset" }).formatToParts(now).find((p) => p.type === "timeZoneName");
      return part?.value.replace("GMT", "UTC") || "UTC";
    };
    return ["UTC", ...Intl.supportedValuesOf("timeZone")].map((z) => ({ value: z, textValue: z, label: `${z} (${offset(z).replace(/^UTC$/, "UTC+00:00")})` }));
  }, []);
}

function Profile() {
  const { t } = useTranslation();
  const toast = useToast();
  const me = useMe().data!;
  const m = useAccountMutations();
  const zones = useZones();
  const [name, setName] = useState(me.name);
  const [zone, setZone] = useState(me.timeZone);
  const dirty = name.trim() !== me.name || zone !== me.timeZone;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    m.profile.mutate({ name, timeZone: zone }, { onSuccess: () => toast({ tone: "success", title: t("app.saved") }) });
  };
  return (
    <Section title={t("account.profile")}>
      <form onSubmit={submit} noValidate className="flex max-w-form flex-col gap-4">
        <Field label={t("account.name")} required error={fieldErrorOf(m.profile.error, "name")}>
          <Input value={name} maxLength={100} autoComplete="name" onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label={t("account.email")} hint={t("account.emailHint")}>
          <Input value={me.email} readOnly />
        </Field>
        <Field label={t("account.timeZone")} hint={t("account.timeZoneHint")} error={fieldErrorOf(m.profile.error, "timeZone")}>
          <Select value={zone} onValueChange={setZone} options={zones} />
        </Field>
        <div>
          <Button type="submit" variant="primary" icon={<Save />} disabled={!dirty} loading={m.profile.isPending}>
            {t("account.saveProfile")}
          </Button>
        </div>
      </form>
    </Section>
  );
}

function Display() {
  const { t } = useTranslation();
  const toast = useToast();
  const me = useMe().data!;
  const m = useAccountMutations();
  const fail = (e: unknown) => toast({ tone: "danger", title: isApiError(e) ? e.message : t("app.errorTitle") });
  const icons = { system: <Monitor />, light: <Sun />, dark: <Moon /> };
  return (
    <Section title={t("account.display")}>
      <div className="flex flex-col gap-2">
        <h3 className="text-sm font-medium text-fg">{t("account.theme")}</h3>
        <RadioCards
          aria-label={t("account.theme")}
          columns={3}
          value={me.theme}
          onValueChange={(v) => m.profile.mutate({ theme: v as Theme }, { onError: fail })}
          options={(["system", "light", "dark"] as const).map((th) => ({ value: th, title: t(`account.themes.${th}`), description: t(`account.themeHints.${th}`), icon: icons[th] }))}
        />
      </div>
      <div className="flex max-w-form flex-col gap-3">
        <Checkbox
          label={t("account.commentsNewestFirst")}
          description={t("account.commentsNewestFirstHint")}
          checked={me.preferences.commentsNewestFirst}
          onCheckedChange={(c) => m.preferences.mutate({ commentsNewestFirst: c === true }, { onError: fail })}
        />
        <Checkbox
          label={t("account.warnOnUnsaved")}
          description={t("account.warnOnUnsavedHint")}
          checked={me.preferences.warnOnUnsaved}
          onCheckedChange={(c) => m.preferences.mutate({ warnOnUnsaved: c === true }, { onError: fail })}
        />
        <Checkbox
          label={t("account.keyboardShortcuts")}
          description={t("account.keyboardShortcutsHint")}
          checked={me.preferences.keyboardShortcuts}
          onCheckedChange={(c) => m.preferences.mutate({ keyboardShortcuts: c === true }, { onError: fail })}
        />
      </div>
    </Section>
  );
}

/** Someone who came in through company SSO has no password: a link to their own address makes one. */
function PasswordSetup({ email, providers }: { email: string; providers: string[] }) {
  const { t } = useTranslation();
  const toast = useToast();
  const m = useAccountMutations();
  return (
    <Section title={t("account.passwordSetup")} hint={t("account.passwordSetupHint", { providers: providers.join(", ") || t("account.sso") })}>
      <div>
        <Button
          icon={<Mail />}
          loading={m.passwordSetup.isPending}
          onClick={() =>
            m.passwordSetup.mutate(undefined, {
              onSuccess: () => toast({ tone: "success", title: t("account.passwordSetupSent", { email }) }),
              onError: (e) => toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle"), description: isApiError(e) && e.message !== e.title ? e.message : undefined }),
            })
          }
        >
          {t("account.passwordSetupSend")}
        </Button>
      </div>
    </Section>
  );
}

function Password() {
  const me = useMe().data;
  return me && !me.hasPassword ? <PasswordSetup email={me.email} providers={me.signInWith} /> : <PasswordChange />;
}

function PasswordChange() {
  const { t } = useTranslation();
  const rules = usePasswordRules().data ?? DEFAULT_AUTH_SETTINGS;
  const toast = useToast();
  const m = useAccountMutations();
  const me = useMe().data;
  const [v, setV] = useState({ current: "", next: "", confirm: "" });
  const [mismatch, setMismatch] = useState(false);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (v.next !== v.confirm) return setMismatch(true);
    setMismatch(false);
    m.password.mutate(
      { current: v.current, next: v.next },
      { onSuccess: () => (setV({ current: "", next: "", confirm: "" }), toast({ tone: "success", title: t("account.passwordChanged") })) },
    );
  };
  return (
    <Section
      title={t("account.password")}
      hint={t("account.passwordHint", { rules: describeRules(rules) }) + (me?.signInWith.length ? " " + t("account.alsoSso", { providers: me.signInWith.join(", ") }) : "")}
    >
      <form onSubmit={submit} noValidate className="flex max-w-form flex-col gap-4">
        <Field label={t("account.currentPassword")} required error={fieldErrorOf(m.password.error, "currentPassword")}>
          <Input type="password" autoComplete="current-password" value={v.current} onChange={(e) => setV({ ...v, current: e.target.value })} />
        </Field>
        <Field label={t("account.newPassword")} required error={fieldErrorOf(m.password.error, "newPassword")}>
          <Input type="password" autoComplete="new-password" value={v.next} onChange={(e) => setV({ ...v, next: e.target.value })} />
        </Field>
        <Field label={t("account.confirmPassword")} required error={mismatch ? t("account.mismatch") : undefined}>
          <Input type="password" autoComplete="new-password" value={v.confirm} onChange={(e) => setV({ ...v, confirm: e.target.value })} />
        </Field>
        <div>
          <Button type="submit" variant="primary" icon={<KeyRound />} disabled={!v.current || !v.next || !v.confirm} loading={m.password.isPending}>
            {t("account.changePassword")}
          </Button>
        </div>
      </form>
    </Section>
  );
}

function Sessions() {
  const { t } = useTranslation();
  const toast = useToast();
  const m = useAccountMutations();
  const [confirm, setConfirm] = useState(false);
  return (
    <Section title={t("account.sessions")} hint={t("account.sessionsHint")}>
      <div>
        <Button icon={<LogOut />} onClick={() => setConfirm(true)}>
          {t("account.endOthers")}
        </Button>
      </div>
      <ConfirmDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={t("account.endOthersConfirm")}
        body={t("account.sessionsHint")}
        confirm={t("account.endOthers")}
        pending={m.endOthers.isPending}
        onConfirm={async () => {
          await m.endOthers.mutateAsync().then(() => toast({ tone: "success", title: t("account.endOthersDone") }));
          setConfirm(false);
        }}
      />
    </Section>
  );
}

export function AccountPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("nav.myAccount"));
  const me = useMe().data;
  return (
    <PageBody>
      <PageHeader title={t("nav.myAccount")} />
      {!me ? (
        <Skeleton className="h-96 w-full" />
      ) : (
        <div className="flex flex-col gap-8">
          <Profile />
          <Display />
          <Password />
          <Sessions />
        </div>
      )}
    </PageBody>
  );
}
