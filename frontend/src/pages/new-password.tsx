// New password fields shared by the reset page, the invitation page and the first sign-in with a temporary password (S01).
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { KeyRound } from "lucide-react";
import { api, isApiError, type Me } from "@/api";
import { usePasswordRules } from "@/api/hooks";
import { DEFAULT_AUTH_SETTINGS, describeRules, passwordProblem } from "@/api/password-policy";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { Banner } from "@/components/ui/feedback";

export type Errors = Partial<Record<"email" | "name" | "password" | "confirm", string>>;

/** Server field errors (newPassword is the reset's name for it) onto this form's fields. */
export function fieldErrorsOf(err: unknown): Errors {
  if (!isApiError(err)) return {};
  const out: Errors = {};
  for (const f of err.fieldErrors) {
    const k = f.field === "newPassword" ? "password" : f.field;
    if (k === "email" || k === "name" || k === "password") out[k] = f.message;
  }
  return out;
}

/** New password twice; checked here first so a typo does not cost a round trip. */
export function NewPasswordFields({ password, confirm, onPassword, onConfirm, errors }: { password: string; confirm: string; onPassword: (v: string) => void; onConfirm: (v: string) => void; errors: Errors }) {
  const { t } = useTranslation();
  const rules = usePasswordRules();
  return (
    <>
      <Field label={t("authLinks.newPassword")} required hint={describeRules(rules.data ?? DEFAULT_AUTH_SETTINGS)} error={errors.password}>
        <Input type="password" autoComplete="new-password" maxLength={128} value={password} onChange={(e) => onPassword(e.target.value)} />
      </Field>
      <Field label={t("authLinks.confirm")} required error={errors.confirm}>
        <Input type="password" autoComplete="new-password" maxLength={128} value={confirm} onChange={(e) => onConfirm(e.target.value)} />
      </Field>
    </>
  );
}

export function usePasswordCheck() {
  const { t } = useTranslation();
  const rules = usePasswordRules().data ?? DEFAULT_AUTH_SETTINGS;
  return (password: string, confirm: string): Errors => {
    const problem = passwordProblem(rules, password);
    if (problem) return { password: problem };
    if (password !== confirm) return { confirm: t("authLinks.mismatch") };
    return {};
  };
}

/** Puts focus on the first field with an error (by its label), so keyboard and screen reader users land on it. */
export function focusFirstError(form: HTMLFormElement | null) {
  requestAnimationFrame(() => form?.querySelector<HTMLInputElement>("[aria-invalid='true']")?.focus());
}

/** 임시 비밀번호로 로그인한 뒤: a password of one's own before anything else (the token stays in memory, never in the address). */
export function PasswordChangeForm({ token, onSignedIn, onExpired }: { token: string; onSignedIn: (me: Me) => void; onExpired: () => void }) {
  const { t } = useTranslation();
  const checkPasswords = usePasswordCheck();
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // the sign-in form was replaced: tell screen reader users where they are, as the layout does on a page change
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
  }, []);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    setFailure(null);
    const local = checkPasswords(password, confirm);
    setErrors(local);
    if (Object.keys(local).length) return focusFirstError(form);
    setBusy(true);
    try {
      onSignedIn(await api.auth.changeTemporaryPassword(token, password));
    } catch (err) {
      if (isApiError(err) && err.status === 410) onExpired();
      else if (isApiError(err) && err.fieldErrors.length) (setErrors(fieldErrorsOf(err)), focusFirstError(form));
      else setFailure(t("app.errorBody"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h1 ref={headingRef} tabIndex={-1} className="text-xl text-fg outline-none">
        {t("authLinks.newPasswordTitle")}
      </h1>
      <p className="mt-1 text-sm text-fg-muted">{t("auth.temporary.subtitle")}</p>
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
      </form>
    </>
  );
}
