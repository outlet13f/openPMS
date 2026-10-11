// 계정 설정 · API 토큰: tokens for the company's own integrations. The secret is shown once, right after making it;
// afterwards only its name, scope and dates are listed. A token acts with my rights (read-only or read-write).
import { useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { Copy, KeyRound, Plus, Trash2 } from "lucide-react";
import { isApiError, type ApiToken, type ApiTokenInput } from "@/api";
import { useApiTokenMutations, useApiTokens } from "@/api/hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { RadioCards } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner, useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { formatDate, relativeTime } from "@/lib/dates";
import { ConfirmDialog } from "@/features/admin/common";

function NewTokenDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const m = useApiTokenMutations();
  const [v, setV] = useState<ApiTokenInput>({ name: "", scope: "read", expiresInDays: 90 });
  const [secret, setSecret] = useState<string | null>(null);
  const err = isApiError(m.create.error) ? m.create.error : null;
  if (secret)
    return (
      <Dialog open onOpenChange={(o) => !o && onClose()} size="md" title={t("apiTokens.madeTitle")} footer={<Button variant="primary" onClick={onClose}>{t("apiTokens.done")}</Button>}>
        <div className="flex flex-col gap-3">
          <Banner tone="warning">{t("apiTokens.onlyOnce")}</Banner>
          <div className="flex items-center gap-2">
            <code className="min-w-0 flex-1 overflow-x-auto rounded-md border border-border bg-surface px-3 py-2 font-mono text-sm break-all text-fg" aria-label={t("apiTokens.secret")}>
              {secret}
            </code>
            <Button
              icon={<Copy />}
              onClick={() => void navigator.clipboard?.writeText(secret).then(() => toast({ tone: "success", title: t("apiTokens.copied") }), () => undefined)}
            >
              {t("apiTokens.copy")}
            </Button>
          </div>
          <p className="text-xs text-fg-muted">{t("apiTokens.howToUse")}</p>
        </div>
      </Dialog>
    );
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={t("apiTokens.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" icon={<KeyRound />} loading={m.create.isPending} onClick={() => m.create.mutate(v, { onSuccess: (r) => setSecret(r.secret) })}>
            {t("apiTokens.make")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {err && !err.fieldErrors.length && <Banner tone="danger">{err.title}</Banner>}
        <Field label={t("apiTokens.name")} required hint={t("apiTokens.nameHint")} error={err?.fieldErrors.find((e) => e.field === "name")?.message}>
          <Input data-autofocus value={v.name} maxLength={60} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </Field>
        <RadioCards
          aria-label={t("apiTokens.scope")}
          columns={2}
          value={v.scope}
          onValueChange={(s) => setV({ ...v, scope: s as ApiTokenInput["scope"] })}
          options={(["read", "write"] as const).map((s) => ({ value: s, title: t(`apiTokens.scopes.${s}`), description: t(`apiTokens.scopeHints.${s}`) }))}
        />
        <Field label={t("apiTokens.expires")}>
          <Select
            value={String(v.expiresInDays ?? "never")}
            onValueChange={(d) => setV({ ...v, expiresInDays: d === "never" ? null : (Number(d) as 30 | 90 | 365) })}
            options={[
              { value: "30", label: t("apiTokens.days", { count: 30 }) },
              { value: "90", label: t("apiTokens.days", { count: 90 }) },
              { value: "365", label: t("apiTokens.days", { count: 365 }) },
              { value: "never", label: t("apiTokens.never") },
            ]}
          />
        </Field>
      </div>
    </Dialog>
  );
}

export function ApiTokensSection({ Section }: { Section: (p: { title: string; hint?: string; children: React.ReactNode }) => React.ReactElement }) {
  const { t } = useTranslation();
  const toast = useToast();
  const tokens = useApiTokens();
  const m = useApiTokenMutations();
  const [making, setMaking] = useState(false);
  const [revoking, setRevoking] = useState<ApiToken | null>(null);
  return (
    <Section title={t("apiTokens.title")} hint={t("apiTokens.hint")}>
      <div className="flex flex-col gap-3">
        {tokens.isPending ? (
          <Skeleton className="h-16 w-full" />
        ) : tokens.data && tokens.data.length > 0 ? (
          <ul className="flex flex-col divide-y divide-border-muted rounded-md border border-border">
            {tokens.data.map((tk) => (
              <li key={tk.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm">
                <span className="font-medium text-fg">{tk.name}</span>
                <Badge tone={tk.scope === "write" ? "warning" : "neutral"}>{t(`apiTokens.scopes.${tk.scope}`)}</Badge>
                <span className="text-xs text-fg-muted">
                  {t("apiTokens.made", { date: formatDate(tk.createdAt.slice(0, 10)) })} · {tk.lastUsedAt ? t("apiTokens.used", { when: relativeTime(tk.lastUsedAt) }) : t("apiTokens.neverUsed")} ·{" "}
                  {tk.expiresAt ? t("apiTokens.until", { date: formatDate(tk.expiresAt.slice(0, 10)) }) : t("apiTokens.never")}
                </span>
                <Button className="ml-auto" size="sm" variant="ghost" icon={<Trash2 />} aria-label={t("apiTokens.revokeName", { name: tk.name })} onClick={() => setRevoking(tk)}>
                  {t("apiTokens.revoke")}
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-fg-muted">{t("apiTokens.none")}</p>
        )}
        <div className="flex flex-wrap items-center gap-3">
          <Button icon={<Plus />} onClick={() => setMaking(true)}>
            {t("apiTokens.new")}
          </Button>
          <Link to="/help/api" className="text-sm text-accent hover:underline">
            {t("apiTokens.docs")}
          </Link>
        </div>
      </div>
      {making && <NewTokenDialog onClose={() => setMaking(false)} />}
      <ConfirmDialog
        open={!!revoking}
        onOpenChange={(o) => !o && setRevoking(null)}
        title={t("apiTokens.revokeTitle", { name: revoking?.name ?? "" })}
        body={t("apiTokens.revokeBody")}
        confirm={t("apiTokens.revoke")}
        pending={m.revoke.isPending}
        onConfirm={async () => {
          if (revoking) await m.revoke.mutateAsync(revoking.id).then(() => toast({ tone: "success", title: t("apiTokens.revoked") }));
          setRevoking(null);
        }}
      />
    </Section>
  );
}
