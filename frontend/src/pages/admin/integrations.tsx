// 관리 · API와 웹훅: whether people may make API tokens, everyone's tokens (revocable), and the webhooks.
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Trash2 } from "lucide-react";
import { api, type AdminApiToken } from "@/api";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Skeleton } from "@/components/ui/spinner";
import { formatDate, relativeTime } from "@/lib/dates";
import { PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ConfirmDialog, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";
import { WebhooksSection } from "./webhooks";

function ApiSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const runToast = useRunWithToast();
  const settings = useQuery({ queryKey: ["admin", "api-settings"], queryFn: () => api.admin.api.settings() });
  const tokens = useQuery({ queryKey: ["admin", "api-tokens"], queryFn: () => api.admin.api.tokens() });
  const [revoking, setRevoking] = useState<AdminApiToken | null>(null);
  return (
    <section aria-labelledby="api-h" className="flex flex-col gap-4">
      <div>
        <h2 id="api-h" className="text-base font-semibold text-fg">{t("integrations.api")}</h2>
        <p className="mt-1 text-sm text-fg-muted">{t("integrations.apiHint")}</p>
      </div>
      {settings.data && (
        <Checkbox
          label={t("integrations.tokensEnabled")}
          description={t("integrations.tokensEnabledHint")}
          checked={settings.data.tokensEnabled}
          onCheckedChange={(c) =>
            void runToast(api.admin.api.saveSettings({ tokensEnabled: c === true }), t("app.saved")).then(() => qc.invalidateQueries({ queryKey: ["admin", "api-settings"] }))
          }
        />
      )}
      {tokens.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : !tokens.data?.length ? (
        <p className="text-sm text-fg-muted">{t("integrations.noTokens")}</p>
      ) : (
        <AdminTable minWidth={720}>
          <thead>
            <tr className={theadRow}>
              <th scope="col" className={th}>{t("integrations.owner")}</th>
              <th scope="col" className={th}>{t("apiTokens.name")}</th>
              <th scope="col" className={th}>{t("apiTokens.scope")}</th>
              <th scope="col" className={th}>{t("integrations.lastUsed")}</th>
              <th scope="col" className={th}>{t("apiTokens.expires")}</th>
              <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>
            </tr>
          </thead>
          <tbody>
            {tokens.data.map((tk) => (
              <tr key={tk.id} className={tbodyRow}>
                <td className="px-3">{tk.userName}</td>
                <td className="px-3">{tk.name}</td>
                <td className="px-3"><Badge tone={tk.scope === "write" ? "warning" : "neutral"}>{t(`apiTokens.scopes.${tk.scope}`)}</Badge></td>
                <td className="px-3 text-fg-muted">{tk.lastUsedAt ? relativeTime(tk.lastUsedAt) : t("apiTokens.neverUsed")}</td>
                <td className="px-3 text-fg-muted">{tk.expiresAt ? formatDate(tk.expiresAt.slice(0, 10)) : t("apiTokens.never")}</td>
                <td className="px-2">
                  <Button size="sm" variant="ghost" iconOnly icon={<Trash2 />} aria-label={t("apiTokens.revokeName", { name: `${tk.userName} · ${tk.name}` })} onClick={() => setRevoking(tk)} />
                </td>
              </tr>
            ))}
          </tbody>
        </AdminTable>
      )}
      <ConfirmDialog
        open={!!revoking}
        onOpenChange={(o) => !o && setRevoking(null)}
        title={t("apiTokens.revokeTitle", { name: revoking ? `${revoking.userName} · ${revoking.name}` : "" })}
        body={t("apiTokens.revokeBody")}
        confirm={t("apiTokens.revoke")}
        onConfirm={async () => {
          if (revoking) await runToast(api.admin.api.revokeToken(revoking.id), t("apiTokens.revoked"));
          void qc.invalidateQueries({ queryKey: ["admin", "api-tokens"] });
          setRevoking(null);
        }}
      />
    </section>
  );
}

export function AdminIntegrationsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.integrations"));
  return (
    <PageBody>
      <PageHeader title={t("admin.sections.integrations")} breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.integrations") }]} />
      <AdminGuard>
        <div className="flex max-w-5xl flex-col gap-10">
          <ApiSection />
          <WebhooksSection />
        </div>
      </AdminGuard>
    </PageBody>
  );
}
