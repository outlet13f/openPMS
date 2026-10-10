// S01 비밀번호 재설정 (요청 · 새 비밀번호) and 초대 수락: the pages behind the one-time links in mail.
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { KeyRound, Mail, UserCheck } from "lucide-react";
import { api, API_MODE, isApiError } from "@/api";
import { qk, useMe } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { Banner, useToast } from "@/components/ui/feedback";
import { FullPageSpinner, useDocumentTitle } from "@/app/page";
import { AuthShell, DevOutbox } from "./login";

const PASSWORD_MIN = 10; // auth.password_min_length; the server has the last word

type Errors = Partial<Record<"email" | "name" | "password" | "confirm", string>>;

/** Server field errors (newPassword is the reset's name for it) onto this form's fields. */
function fieldErrorsOf(err: unknown): Errors {
  if (!isApiError(err)) return {};
  const out: Errors = {};
  for (const f of err.fieldErrors) {
    const k = f.field === "newPassword" ? "password" : f.field;
    if (k === "email" || k === "name" || k === "password") out[k] = f.message;
  }
  return out;
}

function BackToLogin() {
  const { t } = useTranslation();
  return (
    <Link to="/login" className="self-center text-sm text-accent hover:underline">
      {t("authLinks.backToLogin")}
    </Link>
  );
}

/** A link that no longer works: say so, and say what to do instead. */
function Expired({ title, body, action }: { title: string; body: string; action?: { label: string; to: string } }) {
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl text-fg">{title}</h1>
      <Banner tone="warning">{body}</Banner>
      {action && (
        <Button asChild variant="primary">
          <Link to={action.to}>{action.label}</Link>
        </Button>
      )}
      <BackToLogin />
    </div>
  );
}

/** New password twice; checked here first so a typo does not cost a round trip. */
function NewPasswordFields({ password, confirm, onPassword, onConfirm, errors }: { password: string; confirm: string; onPassword: (v: string) => void; onConfirm: (v: string) => void; errors: Errors }) {
  const { t } = useTranslation();
  return (
    <>
      <Field label={t("authLinks.newPassword")} required hint={t("authLinks.passwordHint", { min: PASSWORD_MIN })} error={errors.password}>
        <Input type="password" autoComplete="new-password" maxLength={128} value={password} onChange={(e) => onPassword(e.target.value)} />
      </Field>
      <Field label={t("authLinks.confirm")} required error={errors.confirm}>
        <Input type="password" autoComplete="new-password" maxLength={128} value={confirm} onChange={(e) => onConfirm(e.target.value)} />
      </Field>
    </>
  );
}

function usePasswordCheck() {
  const { t } = useTranslation();
  return (password: string, confirm: string): Errors => {
    if (password.length < PASSWORD_MIN) return { password: t("authLinks.tooShort", { min: PASSWORD_MIN }) };
    if (password !== confirm) return { confirm: t("authLinks.mismatch") };
    return {};
  };
}

/** Puts focus on the first field with an error (by its label), so keyboard and screen reader users land on it. */
function focusFirstError(form: HTMLFormElement | null) {
  requestAnimationFrame(() => form?.querySelector<HTMLInputElement>("[aria-invalid='true']")?.focus());
}

/* ---------- /password-reset ---------- */

export function PasswordResetPage() {
  const [params] = useSearchParams();
  const token = params.get("token");
  return token ? <ResetConfirm token={token} /> : <ResetRequest />;
}

function ResetRequest() {
  const { t } = useTranslation();
  useDocumentTitle(t("authLinks.resetTitle"));
  const [email, setEmail] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    setFailure(null);
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) {
      setErrors({ email: t("authLinks.emailInvalid") });
      focusFirstError(form);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      await api.auth.requestPasswordReset(email.trim());
      setSentTo(email.trim());
    } catch (err) {
      setFailure(isApiError(err) && err.status === 429 ? t("authLinks.tooMany") : t("app.errorBody"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthShell after={API_MODE === "fake" && <DevOutbox />}>
      {sentTo ? (
        <div className="flex flex-col gap-4">
          <h1 className="text-xl text-fg">{t("authLinks.sentTitle")}</h1>
          <Banner tone="success">{t("authLinks.sentBody", { email: sentTo })}</Banner>
          <p className="text-sm text-fg-muted">{t("authLinks.sentHint")}</p>
          <Button variant="secondary" onClick={() => setSentTo(null)}>
            {t("authLinks.sendAgain")}
          </Button>
          <BackToLogin />
        </div>
      ) : (
        <>
          <h1 className="text-xl text-fg">{t("authLinks.resetTitle")}</h1>
          <p className="mt-1 text-sm text-fg-muted">{t("authLinks.resetSubtitle")}</p>
          {failure && (
            <div className="mt-4">
              <Banner tone="danger">{failure}</Banner>
            </div>
          )}
          <form className="mt-5 flex flex-col gap-4" onSubmit={submit} noValidate>
            <Field label={t("auth.email")} required error={errors.email}>
              <Input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
            </Field>
            <Button type="submit" variant="primary" size="lg" loading={busy} icon={<Mail />}>
              {t("authLinks.sendLink")}
            </Button>
            <BackToLogin />
          </form>
        </>
      )}
    </AuthShell>
  );
}

function ResetConfirm({ token }: { token: string }) {
  const { t } = useTranslation();
  useDocumentTitle(t("authLinks.newPasswordTitle"));
  const qc = useQueryClient();
  const checkPasswords = usePasswordCheck();
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [state, setState] = useState<"form" | "done" | "expired">("form");
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    setFailure(null);
    const local = checkPasswords(password, confirm);
    setErrors(local);
    if (Object.keys(local).length) return focusFirstError(form);
    setBusy(true);
    try {
      await api.auth.confirmPasswordReset(token, password);
      qc.setQueryData(qk.me, null); // the reset ended every session, this browser's too
      setState("done");
    } catch (err) {
      if (isApiError(err) && err.status === 410) setState("expired");
      else if (isApiError(err) && err.fieldErrors.length) (setErrors(fieldErrorsOf(err)), focusFirstError(form));
      else setFailure(t("app.errorBody"));
    } finally {
      setBusy(false);
    }
  };

  if (state === "expired")
    return (
      <AuthShell>
        <Expired title={t("authLinks.expiredTitle")} body={t("authLinks.resetExpired")} action={{ label: t("authLinks.requestNew"), to: "/password-reset" }} />
      </AuthShell>
    );
  if (state === "done")
    return (
      <AuthShell>
        <div className="flex flex-col gap-4">
          <h1 className="text-xl text-fg">{t("authLinks.doneTitle")}</h1>
          <Banner tone="success">{t("authLinks.doneBody")}</Banner>
          <Button asChild variant="primary" size="lg">
            <Link to="/login">{t("auth.submit")}</Link>
          </Button>
        </div>
      </AuthShell>
    );
  return (
    <AuthShell>
      <h1 className="text-xl text-fg">{t("authLinks.newPasswordTitle")}</h1>
      <p className="mt-1 text-sm text-fg-muted">{t("authLinks.newPasswordSubtitle")}</p>
      {failure && (
        <div className="mt-4">
          <Banner tone="danger">{failure}</Banner>
        </div>
      )}
      <form className="mt-5 flex flex-col gap-4" onSubmit={submit} noValidate>
        <NewPasswordFields password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} errors={errors} />
        <Button type="submit" variant="primary" size="lg" loading={busy} icon={<KeyRound />}>
          {t("authLinks.savePassword")}
        </Button>
        <BackToLogin />
      </form>
    </AuthShell>
  );
}

/* ---------- /invitations/:token ---------- */

export function InvitationPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("authLinks.inviteTitle"));
  const { token = "" } = useParams();
  const invitation = useQuery({ queryKey: ["invitation", token], queryFn: () => api.auth.invitation(token), retry: false, staleTime: Infinity });
  const me = useMe();

  if (invitation.isPending || me.isPending) return <FullPageSpinner />;
  if (invitation.isError)
    return (
      <AuthShell>
        {isApiError(invitation.error) && invitation.error.status === 410 ? (
          <Expired title={t("authLinks.expiredTitle")} body={t("authLinks.inviteExpired")} />
        ) : (
          <div className="flex flex-col gap-4">
            <h1 className="text-xl text-fg">{t("authLinks.inviteTitle")}</h1>
            <Banner tone="danger">{t("app.errorBody")}</Banner>
            <Button variant="secondary" onClick={() => invitation.refetch()}>
              {t("app.retry")}
            </Button>
          </div>
        )}
      </AuthShell>
    );
  return <AcceptForm token={token} email={invitation.data.email} defaultName={invitation.data.name} invitedBy={invitation.data.invitedBy} signedInAs={me.data?.name ?? null} />;
}

function AcceptForm({ token, email, defaultName, invitedBy, signedInAs }: { token: string; email: string; defaultName: string; invitedBy: string | null; signedInAs: string | null }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const checkPasswords = usePasswordCheck();
  const [name, setName] = useState(defaultName);
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [expired, setExpired] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    setFailure(null);
    const local: Errors = !name.trim() ? { name: t("authLinks.nameRequired") } : checkPasswords(password, confirm);
    setErrors(local);
    if (Object.keys(local).length) return focusFirstError(form);
    setBusy(true);
    try {
      const user = await api.auth.acceptInvitation(token, { name: name.trim(), password });
      qc.clear(); // whatever another account left in the cache is not this user's
      qc.setQueryData(qk.me, user);
      toast({ tone: "success", title: t("authLinks.welcome", { name: user.name }) });
      navigate("/", { replace: true });
    } catch (err) {
      if (isApiError(err) && err.status === 410) setExpired(true);
      else if (isApiError(err) && err.fieldErrors.length) (setErrors(fieldErrorsOf(err)), focusFirstError(form));
      else setFailure(t("app.errorBody"));
      setBusy(false);
    }
  };

  if (expired)
    return (
      <AuthShell>
        <Expired title={t("authLinks.expiredTitle")} body={t("authLinks.inviteExpired")} />
      </AuthShell>
    );
  return (
    <AuthShell>
      <h1 className="text-xl text-fg">{t("authLinks.inviteTitle")}</h1>
      <p className="mt-1 text-sm text-fg-muted">{invitedBy ? t("authLinks.invitedBy", { name: invitedBy }) : t("authLinks.invitedSetup")}</p>
      {signedInAs && (
        <div className="mt-4">
          <Banner tone="info">{t("authLinks.signedInAs", { name: signedInAs })}</Banner>
        </div>
      )}
      {failure && (
        <div className="mt-4">
          <Banner tone="danger">{failure}</Banner>
        </div>
      )}
      <form className="mt-5 flex flex-col gap-4" onSubmit={submit} noValidate>
        <Field label={t("auth.email")} hint={t("authLinks.emailFixed")}>
          <Input type="email" autoComplete="username" value={email} readOnly />
        </Field>
        <Field label={t("authLinks.name")} required error={errors.name}>
          <Input autoComplete="name" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <NewPasswordFields password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} errors={errors} />
        <Button type="submit" variant="primary" size="lg" loading={busy} icon={<UserCheck />}>
          {t("authLinks.accept")}
        </Button>
      </form>
    </AuthShell>
  );
}
