// 알림 설정 예시: the Jenkinsfile snippet or the Argo CD Notifications settings of one connection, read-only with a copy
// button. The server never puts the webhook secret into them (a placeholder stands in its place).
import { useTranslation } from "react-i18next";
import { Copy } from "lucide-react";
import type { CicdConnection } from "@/api";
import { useCicdTemplates } from "@/api/admin-hooks";
import { Banner, useToast } from "@/components/ui/feedback";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";

function CodeBlock({ id, label, code, copyLabel, copied }: { id: string; label: string; code: string; copyLabel: string; copied: string }) {
  const { t } = useTranslation();
  const toast = useToast();
  return (
    <section aria-labelledby={id} className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between gap-2">
        <h3 id={id} className="text-sm font-medium text-fg">
          {label}
        </h3>
        <Button
          size="sm"
          icon={<Copy />}
          aria-label={copyLabel}
          onClick={() => void navigator.clipboard?.writeText(code).then(() => toast({ tone: "success", title: copied }), () => undefined)}
        >
          {t("cicd.admin.copy")}
        </Button>
      </div>
      {/* tabIndex: a long line scrolls sideways, so the block must be reachable by keyboard */}
      <pre tabIndex={0} aria-labelledby={id} className="max-h-96 overflow-auto rounded-md border border-border bg-surface px-3 py-2 text-xs text-fg">
        <code className="font-mono">{code}</code>
      </pre>
    </section>
  );
}

export function TemplatesDialog({ connection, onClose }: { connection: CicdConnection; onClose: () => void }) {
  const { t } = useTranslation();
  const q = useCicdTemplates(connection.id);
  const jenkins = connection.kind === "jenkins";
  const code = jenkins ? q.data?.jenkinsfile : q.data?.argocd;
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="lg"
      title={t("cicd.templates.title", { name: connection.name })}
      footer={<Button variant="primary" onClick={onClose}>{t("apiTokens.done")}</Button>}
    >
      <div className="flex flex-col gap-4">
        <p className="text-sm text-fg">{jenkins ? t("cicd.templates.secretNoteJenkins") : t("cicd.templates.secretNoteArgocd")}</p>
        <p className="text-xs text-fg-muted">{jenkins ? t("cicd.templates.jenkinsHow") : t("cicd.templates.argocdHow")}</p>
        {q.isPending ? (
          <Skeleton className="h-40 w-full" />
        ) : q.isError || !code ? (
          <Banner tone="danger">{t("cicd.templates.failed")}</Banner>
        ) : (
          <CodeBlock
            id={`tpl-${connection.id}`}
            label={jenkins ? t("cicd.templates.jenkinsfile") : t("cicd.templates.argocd")}
            code={code}
            copyLabel={jenkins ? t("cicd.templates.copyJenkinsfile") : t("cicd.templates.copyArgocd")}
            copied={t("cicd.templates.copied")}
          />
        )}
      </div>
    </Dialog>
  );
}
