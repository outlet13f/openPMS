// S01 로그인
import { useState, type FormEvent, type ReactNode } from "react";
import { Link, Navigate, useNavigate, useSearchParams } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { KeyRound, LogIn } from "lucide-react";
import { api, API_MODE, isApiError, type Me } from "@/api";
import { fakeAccounts, fakeOutbox, resetFakeDb } from "@/api/fake";
import { qk, useMe, usePasswordRules, useSsoProviders } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { Banner } from "@/components/ui/feedback";
import { Badge } from "@/components/ui/badges";
import { FullPageSpinner, useDocumentTitle } from "@/app/page";
import { PasswordChangeForm } from "./new-password";

export function LoginPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("auth.submit"));
  const me = useMe();
  const rules = usePasswordRules();
  const sso = useSsoProviders();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get("next");
  // a refused SSO sign-in comes back as /login?sso_error=code (OidcLogin)
  const ssoError = params.get("sso_error");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});
  const [busy, setBusy] = useState(false);
  // set when the sign-in answered password_change_required; kept in memory only, never in the address or storage
  const [changeToken, setChangeToken] = useState<string | null>(null);

  if (me.isPending) return <FullPageSpinner />;
  if (me.data) return <Navigate to={next && next.startsWith("/") ? next : "/"} replace />;

  const signedIn = (user: Me) => {
    qc.setQueryData(qk.me, user);
    // only same-origin paths are accepted as a return target
    navigate(next && next.startsWith("/") && !next.startsWith("//") ? next : "/", { replace: true });
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const fe: typeof fieldErrors = {};
    if (!email.trim()) fe.email = t("auth.required");
    if (!password) fe.password = t("auth.required");
    setFieldErrors(fe);
    setError(null);
    if (fe.email || fe.password) {
      (document.getElementById(fe.email ? "login-email" : "login-password") as HTMLInputElement | null)?.focus();
      return;
    }
    setBusy(true);
    try {
      const user = await api.auth.login(email, password);
      signedIn(user);
    } catch (err) {
      const reason = isApiError(err) ? (err.current as { code?: string; changeToken?: string } | undefined) : undefined;
      if (reason?.code === "password_change_required" && reason.changeToken) {
        setPassword("");
        setChangeToken(reason.changeToken);
      } else if (reason?.code === "temporary_password_expired") setError(t("auth.temporary.expired"));
      else setError(isApiError(err) && err.status === 423 ? t("auth.locked") : t("auth.failed"));
    } finally {
      setBusy(false);
    }
  };

  if (changeToken)
    return (
      <AuthShell>
        <PasswordChangeForm token={changeToken} onSignedIn={signedIn} onExpired={() => (setChangeToken(null), setError(t("auth.temporary.tokenExpired")))} />
      </AuthShell>
    );
  return (
    <AuthShell
      after={
        API_MODE === "fake" && (
          <>
            <DevOutbox />
            <DemoAccounts onPick={(e, p) => (setEmail(e), setPassword(p), setError(null))} />
          </>
        )
      }
    >
      <h1 className="text-xl text-fg">{t("auth.title")}</h1>
      <p className="mt-1 text-sm text-fg-muted">{t("auth.subtitle")}</p>
      {(error || ssoError) && (
        <div className="mt-4">
          <Banner tone="danger">{error ?? t(`auth.sso.errors.${ssoError}`, { defaultValue: t("auth.sso.errors.failed") })}</Banner>
        </div>
      )}
      <form className="mt-5 flex flex-col gap-4" onSubmit={submit} noValidate>
        <Field label={t("auth.email")} required error={fieldErrors.email}>
          <Input id="login-email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
        </Field>
        <Field label={t("auth.password")} required error={fieldErrors.password}>
          <Input id="login-password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <Button type="submit" variant="primary" size="lg" loading={busy} icon={<LogIn />}>
          {t("auth.submit")}
        </Button>
        {rules.data?.resetEnabled !== false && (
          <Link to="/password-reset" className="self-center text-sm text-accent hover:underline">
            {t("auth.forgot")}
          </Link>
        )}
      </form>
      {!!sso.data?.length && (
        <div className="mt-5 flex flex-col gap-3">
          <p className="flex items-center gap-3 text-xs text-fg-muted before:h-px before:flex-1 before:bg-border after:h-px after:flex-1 after:bg-border">{t("auth.sso.or")}</p>
          {sso.data.map((p) => (
            // a full page navigation: the provider's sign-in page is on another site
            <Button key={p.slug} asChild size="lg" icon={<KeyRound />}>
              <a href={api.auth.ssoStartUrl(p.slug, next && next.startsWith("/") && !next.startsWith("//") ? next : null)}>{t("auth.sso.signIn", { name: p.displayName })}</a>
            </Button>
          ))}
        </div>
      )}
    </AuthShell>
  );
}

/** The signed-out page frame (S01): name, one card, and whatever goes under it. */
export function AuthShell({ children, after }: { children: ReactNode; after?: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-surface px-content py-10">
      <div className="mb-6 flex items-center gap-2 text-lg font-bold text-fg">
        <span aria-hidden="true" className="inline-flex size-8 items-center justify-center rounded-md bg-header-bg text-sm font-black text-header-fg">
          P
        </span>
        {t("app.name")}
      </div>
      <main className="w-full max-w-modal-sm rounded-lg border border-border bg-bg p-6 shadow-card">{children}</main>
      {after}
    </div>
  );
}

/** Fake mode only: the mail the real backend would have sent, so invitation and reset links can be opened. */
export function DevOutbox() {
  const { t } = useTranslation();
  const mails = fakeOutbox();
  if (!mails.length) return null;
  return (
    <section aria-labelledby="outbox-h" className="mt-6 w-full max-w-modal-sm rounded-lg border border-dashed border-border-input bg-bg p-4">
      <h2 id="outbox-h" className="text-sm font-semibold text-fg">{t("auth.outboxTitle")}</h2>
      <p className="mt-1 text-xs text-fg-muted">{t("auth.outboxBody")}</p>
      <ul className="mt-3 flex flex-col divide-y divide-border-muted">
        {mails.map((m) => (
          <li key={m.id} className="flex items-center justify-between gap-3 py-2">
            <span className="min-w-0 text-sm">
              <span id={`mail-${m.id}`}>
                <span className="block truncate font-medium text-fg">{m.subject}</span>
                <span className="block truncate text-xs text-fg-muted">{m.to}</span>
              </span>
            </span>
            {/* described by, not named by, the subject: a label naming 비밀번호 would collide with the form's field */}
            <Link to={m.link} className="shrink-0 text-sm text-accent hover:underline" aria-describedby={`mail-${m.id}`}>
              {t("auth.outboxOpen")}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

function DemoAccounts({ onPick }: { onPick: (email: string, password: string) => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const accounts = fakeAccounts();
  return (
    <section aria-labelledby="demo-h" className="mt-6 w-full max-w-modal-sm rounded-lg border border-dashed border-border-input bg-bg p-4">
      <h2 id="demo-h" className="text-sm font-semibold text-fg">
        {t("auth.demoTitle")}
      </h2>
      <p className="mt-1 text-xs text-fg-muted">{t("auth.demoBody", { password: accounts[0]?.password })}</p>
      <ul className="mt-3 flex flex-col divide-y divide-border-muted">
        {accounts.map((a) => (
          <li key={a.email} className="flex items-center justify-between gap-3 py-2">
            <span className="min-w-0 text-sm">
              <span className="font-medium text-fg">{a.name}</span>{" "}
              {a.isAdmin && <Badge tone="accent">{t("auth.roleAdmin")}</Badge>}
              {a.status === "locked" && <Badge tone="danger">{t("auth.roleLocked")}</Badge>}
              <span className="block truncate text-xs text-fg-muted">{a.email}</span>
            </span>
            <Button size="sm" onClick={() => onPick(a.email, a.password)} aria-label={`${a.name}: ${t("auth.demoUse")}`}>
              {t("auth.demoUse")}
            </Button>
          </li>
        ))}
      </ul>
      <Button
        variant="link"
        size="sm"
        className="mt-2"
        onClick={() => {
          resetFakeDb();
          qc.clear();
        }}
      >
        {t("nav.resetDemo")}
      </Button>
    </section>
  );
}
