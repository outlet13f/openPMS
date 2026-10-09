// S01 로그인
import { useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useSearchParams } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { LogIn } from "lucide-react";
import { api, API_MODE, isApiError } from "@/api";
import { fakeAccounts, resetFakeDb } from "@/api/fake";
import { qk, useMe } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { Banner } from "@/components/ui/feedback";
import { Badge } from "@/components/ui/badges";
import { FullPageSpinner, useDocumentTitle } from "@/app/page";

export function LoginPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("auth.submit"));
  const me = useMe();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get("next");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});
  const [busy, setBusy] = useState(false);

  if (me.isPending) return <FullPageSpinner />;
  if (me.data) return <Navigate to={next && next.startsWith("/") ? next : "/"} replace />;

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
      qc.setQueryData(qk.me, user);
      // only same-origin paths are accepted as a return target
      navigate(next && next.startsWith("/") && !next.startsWith("//") ? next : "/", { replace: true });
    } catch (err) {
      setError(isApiError(err) && err.status === 423 ? t("auth.locked") : t("auth.failed"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-surface px-content py-10">
      <div className="mb-6 flex items-center gap-2 text-lg font-bold text-fg">
        <span aria-hidden="true" className="inline-flex size-8 items-center justify-center rounded-md bg-header-bg text-sm font-black text-header-fg">
          P
        </span>
        {t("app.name")}
      </div>
      <main className="w-full max-w-modal-sm rounded-lg border border-border bg-bg p-6 shadow-card">
        <h1 className="text-xl text-fg">{t("auth.title")}</h1>
        <p className="mt-1 text-sm text-fg-muted">{t("auth.subtitle")}</p>
        {error && (
          <div className="mt-4">
            <Banner tone="danger">{error}</Banner>
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
          <Link to="/password-reset" className="self-center text-sm text-accent hover:underline">
            {t("auth.forgot")}
          </Link>
        </form>
      </main>

      {API_MODE === "fake" && <DemoAccounts onPick={(e, p) => (setEmail(e), setPassword(p), setError(null))} />}
    </div>
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
