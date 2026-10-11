// 관리 · 시스템 정보: what is running (version, start, Java, jobs), the database (PostgreSQL version, size, migrations), the
// data held, and the backups — the automatic one before an upgrade and 지금 백업 — so an operator sees at a glance whether
// the last upgrade went through and whether there is a dump to go back to.
import { useTranslation } from "react-i18next";
import { DatabaseBackup, Server } from "lucide-react";
import type { ReactNode } from "react";
import { useBackupNow, useSystemInfo } from "@/api/admin-hooks";
import { Button } from "@/components/ui/button";
import { Banner, EmptyState } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { formatDateTime } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) (v /= 1024), i++;
  return `${v >= 10 ? Math.round(v) : Math.round(v * 10) / 10} ${units[i]}`;
}

function Card({ title, children, id }: { title: string; children: ReactNode; id: string }) {
  return (
    <section aria-labelledby={id} className="rounded-md border border-border p-4">
      <h2 id={id} className="mb-3 text-base font-semibold text-fg">{title}</h2>
      {children}
    </section>
  );
}

function Facts({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-[minmax(0,10rem)_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm">
      {rows.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-fg-muted">{k}</dt>
          <dd className="min-w-0 break-words text-fg">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function AdminSystemPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.system.title"));
  const q = useSystemInfo();
  const backup = useBackupNow();
  const runToast = useRunWithToast();
  const when = (d: string | null) => (d ? formatDateTime(d) : "-");

  return (
    <PageBody>
      <PageHeader
        title={t("admin.system.title")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.system.title") }]}
        actions={
          q.data && (
            <Button
              icon={<DatabaseBackup />}
              loading={backup.isPending || q.data.backups.running}
              disabled={!q.data.backups.pgDump}
              onClick={async () => {
                const made = await runToast(backup.mutateAsync());
                if (made) await runToast(Promise.resolve(made), t("admin.system.backedUp", { name: made.name }));
              }}
            >
              {backup.isPending ? t("admin.system.backingUp") : t("admin.system.backupNow")}
            </Button>
          )
        }
      />
      <AdminGuard>
        <p className="mb-4 max-w-prose text-sm text-fg-muted">{t("admin.system.hint")}</p>
        {q.isPending ? (
          <Skeleton className="h-64 w-full" />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        ) : (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Card id="sys-app" title={t("admin.system.app")}>
              <Facts
                rows={[
                  [t("admin.system.version"), q.data.app.version],
                  [t("admin.system.builtAt"), when(q.data.app.builtAt)],
                  [t("admin.system.startedAt"), when(q.data.app.startedAt)],
                  [t("admin.system.java"), q.data.app.java],
                  [t("admin.system.jobs"), q.data.app.jobsEnabled ? t("admin.system.jobsOn") : t("admin.system.jobsOff")],
                ]}
              />
            </Card>
            <Card id="sys-db" title={t("admin.system.database")}>
              <Facts
                rows={[
                  [t("admin.system.dbVersion"), q.data.database.version],
                  [t("admin.system.dbSize"), formatBytes(q.data.database.sizeBytes)],
                  [t("admin.system.current"), q.data.migrations.current ?? "-"],
                  [t("admin.system.applied"), String(q.data.migrations.applied)],
                  [t("admin.system.pending"), String(q.data.migrations.pending)],
                ]}
              />
              {q.data.migrations.pending > 0 && <div className="mt-3"><Banner tone="warning">{t("admin.system.pending")}: {q.data.migrations.pending}</Banner></div>}
            </Card>
            <Card id="sys-counts" title={t("admin.system.counts")}>
              <Facts
                rows={[
                  [t("admin.system.activeUsers"), String(q.data.counts.activeUsers)],
                  [t("admin.system.projects"), String(q.data.counts.projects)],
                  [t("admin.system.workPackages"), String(q.data.counts.workPackages)],
                  [t("admin.system.attachments"), formatBytes(q.data.counts.attachmentBytes)],
                ]}
              />
            </Card>
            <Card id="sys-backups" title={t("admin.system.backups")}>
              <Facts
                rows={[
                  [t("admin.system.beforeUpgrade"), q.data.backups.beforeUpgrade ? t("admin.system.on") : t("admin.system.off")],
                  [t("admin.system.backupDir"), <code key="dir" className="text-xs">{q.data.backups.dir}</code>],
                  [t("admin.system.keep"), String(q.data.backups.keep)],
                  [t("admin.system.pgDump"), q.data.backups.pgDump ?? <span key="none" className="text-danger">{t("admin.system.noPgDump")}</span>],
                ]}
              />
              <p className="mt-3 text-xs text-fg-muted">{t("admin.system.restoreHint")}</p>
            </Card>
            <div className="lg:col-span-2">
              <Card id="sys-files" title={t("admin.system.files")}>
                {q.data.backups.files.length === 0 ? (
                  <EmptyState compact icon={<Server />} title={t("admin.system.noFiles")} />
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full min-w-[520px] text-sm">
                      <thead>
                        <tr className="border-b border-border text-left text-xs text-fg-muted">
                          <th scope="col" className="py-2 pr-3 font-medium">{t("admin.system.fileName")}</th>
                          <th scope="col" className="py-2 pr-3 font-medium">{t("admin.system.kind")}</th>
                          <th scope="col" className="py-2 pr-3 text-right font-medium">{t("admin.system.fileSize")}</th>
                          <th scope="col" className="py-2 font-medium">{t("admin.system.fileAt")}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {q.data.backups.files.map((f) => (
                          <tr key={f.name} className="border-b border-border-muted">
                            <td className="py-2 pr-3 font-mono text-xs break-all">{f.name}</td>
                            <td className="py-2 pr-3">{t(`admin.system.kinds.${f.kind}`)}</td>
                            <td className="py-2 pr-3 text-right tabular-nums">{formatBytes(f.bytes)}</td>
                            <td className="py-2 tabular-nums">{when(f.createdAt)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </Card>
            </div>
            <div className="lg:col-span-2">
              <Card id="sys-migrations" title={t("admin.system.recent")}>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[480px] text-sm">
                    <thead>
                      <tr className="border-b border-border text-left text-xs text-fg-muted">
                        <th scope="col" className="py-2 pr-3 font-medium">{t("admin.system.migrationVersion")}</th>
                        <th scope="col" className="py-2 pr-3 font-medium">{t("admin.system.migrationName")}</th>
                        <th scope="col" className="py-2 font-medium">{t("admin.system.installedAt")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {q.data.migrations.recent.map((m) => (
                        <tr key={m.version + m.description} className="border-b border-border-muted">
                          <td className="py-2 pr-3 tabular-nums">v{m.version}</td>
                          <td className="py-2 pr-3">
                            {m.description}
                            {!m.success && <span className="ml-2 text-danger">{t("admin.system.failed")}</span>}
                          </td>
                          <td className="py-2 tabular-nums">{when(m.installedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Card>
            </div>
          </div>
        )}
      </AdminGuard>
    </PageBody>
  );
}
