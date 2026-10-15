// 프로젝트 설정 › 저장소: the GitLab·GitHub repositories attached to the project (cicd.view sees the list). With
// cicd.manage: attach one (pick a connection → search the code host → attach) and detach (its commits, merge requests
// and links go with it). How to set up the webhook is the connection administrator's business: a hint says whom to ask.
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink, FolderGit2, Link2, Plus, Unlink } from "lucide-react";
import { isApiError, type CicdRepository } from "@/api";
import { useProjectCicdConnections, useProjectRepositories, useRepositoryMutations, useRepositorySearch } from "@/api/admin-hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Banner, EmptyState } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Dialog } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { AdminTable, ConfirmDialog, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { formatDateTime, relativeTime } from "@/lib/dates";

function AttachDialog({ project, onClose }: { project: string; onClose: () => void }) {
  const { t } = useTranslation();
  const connections = useProjectCicdConnections(project);
  const m = useRepositoryMutations(project);
  const runToast = useRunWithToast();
  const [connectionId, setConnectionId] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  const [attaching, setAttaching] = useState<string | null>(null);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(q.trim()), 300);
    return () => clearTimeout(id);
  }, [q]);
  // the only connection is picked for you
  const only = connections.data?.length === 1 ? connections.data[0].id : null;
  const chosen = connectionId ?? only;
  const search = useRepositorySearch(project, chosen, debounced);
  const attach = async (externalId: string) => {
    if (!chosen) return;
    setAttaching(externalId);
    const done = await runToast(m.attach.mutateAsync({ connectionId: chosen, externalId }), t("cicd.repos.attached"));
    setAttaching(null);
    if (done) onClose();
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} size="md" title={t("cicd.repos.attach")} footer={<Button onClick={onClose}>{t("app.cancel")}</Button>}>
      <div className="flex flex-col gap-4">
        {connections.isPending ? (
          <Skeleton className="h-10 w-full" />
        ) : connections.isError ? (
          <ErrorState error={connections.error} onRetry={() => connections.refetch()} />
        ) : connections.data.length === 0 ? (
          <Banner tone="info">{t("cicd.repos.noConnections")}</Banner>
        ) : (
          <>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label={t("cicd.repos.connection")} required>
                <Select
                  value={chosen ?? undefined}
                  onValueChange={setConnectionId}
                  options={connections.data.map((c) => ({ value: c.id, label: `${c.name} (${t(`cicd.kinds.${c.kind}`)})`, textValue: c.name }))}
                />
              </Field>
              <Field label={t("cicd.repos.search")}>
                <Input data-autofocus type="search" value={q} maxLength={100} placeholder={t("cicd.repos.searchPlaceholder")} onChange={(e) => setQ(e.target.value)} />
              </Field>
            </div>
            <section aria-label={t("cicd.repos.results")} aria-busy={search.isFetching}>
              {!chosen ? (
                <p className="text-sm text-fg-muted">{t("cicd.repos.pickConnection")}</p>
              ) : search.isPending ? (
                <Skeleton className="h-24 w-full" />
              ) : search.isError ? (
                <Banner tone="danger">{isApiError(search.error) ? search.error.message : t("app.errorTitle")}</Banner>
              ) : search.data.length === 0 ? (
                <p className="text-sm text-fg-muted">{t("cicd.repos.noResults")}</p>
              ) : (
                <ul className="flex max-h-72 flex-col divide-y divide-border-muted overflow-y-auto rounded-md border border-border">
                  {search.data.map((r) => (
                    <li key={r.externalId} className="flex items-center gap-2 px-3 py-1.5">
                      <span className="min-w-0 flex-1 truncate font-mono text-sm text-fg" title={r.fullPath}>
                        {r.fullPath}
                      </span>
                      {r.attachedProject ? (
                        <Badge>{t("cicd.repos.attachedElsewhere")}</Badge>
                      ) : (
                        <Button
                          size="sm"
                          icon={<Link2 />}
                          aria-label={t("cicd.repos.attachName", { path: r.fullPath })}
                          loading={attaching === r.externalId}
                          disabled={!!attaching && attaching !== r.externalId}
                          onClick={() => void attach(r.externalId)}
                        >
                          {t("cicd.repos.attachOne")}
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </>
        )}
      </div>
    </Dialog>
  );
}

export function RepositoriesSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const manage = can(project, "cicd.manage");
  const list = useProjectRepositories(project.identifier);
  const m = useRepositoryMutations(project.identifier);
  const runToast = useRunWithToast();
  const [attaching, setAttaching] = useState(false);
  const [removing, setRemoving] = useState<CicdRepository | null>(null);
  const title = t("settings.sections.repositories");

  return (
    <section aria-labelledby="repos-h" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-3xl">
          <h2 id="repos-h" className="text-lg text-fg">
            {title}
          </h2>
          <p className="mt-1 text-sm text-fg-muted">{t("cicd.repos.hint")}</p>
          <p className="mt-1 text-sm text-fg-muted">{t("cicd.repos.webhookHint")}</p>
        </div>
        {manage && (
          <Button variant="primary" icon={<Plus />} onClick={() => setAttaching(true)}>
            {t("cicd.repos.attach")}
          </Button>
        )}
      </div>
      {list.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : list.isError ? (
        <ErrorState error={list.error} onRetry={() => list.refetch()} />
      ) : list.data.length === 0 ? (
        <div className="rounded-md border border-border">
          <EmptyState compact icon={<FolderGit2 />} title={t("cicd.repos.none")} />
        </div>
      ) : (
        <AdminTable minWidth={620}>
          <caption className="sr-only">{t("cicd.repos.list")}</caption>
          <thead>
            <tr className={theadRow}>
              <th scope="col" className={th}>{t("cicd.repos.cols.path")}</th>
              <th scope="col" className={th}>{t("cicd.repos.cols.connection")}</th>
              <th scope="col" className={th}>{t("cicd.repos.cols.lastPolled")}</th>
              {manage && (
                <th scope="col" className={`${th} w-24`}>
                  <span className="sr-only">{t("cicd.repos.detach")}</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {list.data.map((r) => (
              <tr key={r.id} className={tbodyRow}>
                <td className="px-3 py-1.5">
                  <a href={r.webUrl} target="_blank" rel="noopener noreferrer" className="font-mono text-accent hover:underline">
                    {r.fullPath}
                    <ExternalLink className="ml-1 inline size-3 align-[-1px]" aria-hidden="true" />
                    <span className="sr-only"> ({t("cicd.opensInNew")})</span>
                  </a>
                </td>
                <td className="px-3 text-fg-muted">
                  {r.connectionName} <span className="text-xs">({t(`cicd.kinds.${r.kind}`)})</span>
                </td>
                <td className="px-3 text-fg-muted">
                  {r.lastPolledAt ? (
                    <time dateTime={r.lastPolledAt} title={formatDateTime(r.lastPolledAt)}>
                      {relativeTime(r.lastPolledAt)}
                    </time>
                  ) : (
                    t("cicd.repos.neverPolled")
                  )}
                </td>
                {manage && (
                  <td className="px-2 text-right">
                    <Button size="sm" variant="ghost" icon={<Unlink />} aria-label={t("cicd.repos.detachName", { path: r.fullPath })} onClick={() => setRemoving(r)}>
                      {t("cicd.repos.detach")}
                    </Button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </AdminTable>
      )}
      {attaching && <AttachDialog project={project.identifier} onClose={() => setAttaching(false)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("cicd.repos.detachTitle", { path: removing?.fullPath ?? "" })}
        body={t("cicd.repos.detachBody")}
        confirm={t("cicd.repos.detach")}
        pending={m.detach.isPending}
        onConfirm={async () => {
          if (removing) await runToast(m.detach.mutateAsync(removing.id), t("cicd.repos.detached"));
          setRemoving(null);
        }}
      />
    </section>
  );
}
