// 개발 tab of a work package: the merge requests (GitLab MR, GitHub PR) and commits linked to it through "#<번호>" in a
// commit message, branch name or MR title, or by hand. People with cicd.link can link an MR/PR by its address and cut a
// wrong link (no confirmation: linking the address again brings an MR back). The caller shows the tab only with the
// module on and cicd.view (the API answers 404 otherwise).
import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink, GitCommitHorizontal, GitMerge, GitPullRequest, GitPullRequestClosed, GitPullRequestDraft, Link2, Unlink } from "lucide-react";
import { isApiError, type CicdCommit, type CicdMergeRequest, type WorkPackage } from "@/api";
import { useDevInfo, useDevLinkMutations } from "@/api/hooks";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState } from "@/app/page";
import { useRunWithToast } from "@/features/admin/common";
import { formatDateTime, relativeTime } from "@/lib/dates";

/** state → badge tone and icon; the badge always carries the state in words too */
const STATE: Record<CicdMergeRequest["state"], { tone: "accent" | "neutral" | "success" | "danger"; icon: ReactNode }> = {
  open: { tone: "accent", icon: <GitPullRequest /> },
  draft: { tone: "neutral", icon: <GitPullRequestDraft /> },
  merged: { tone: "success", icon: <GitMerge /> },
  closed: { tone: "danger", icon: <GitPullRequestClosed /> },
};

/** A link to the code host, in a new window, saying so to screen readers. */
function External({ href, children, className }: { href: string; children: ReactNode; className?: string }) {
  const { t } = useTranslation();
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" className={className ?? "font-medium text-accent hover:underline"}>
      {children}
      <ExternalLink className="ml-1 inline size-3 align-[-1px]" aria-hidden="true" />
      <span className="sr-only"> ({t("cicd.opensInNew")})</span>
    </a>
  );
}

function Who({ name, user }: { name: string; user: CicdCommit["user"] }) {
  return user ? (
    <span className="inline-flex items-center gap-1">
      <Avatar id={user.id} name={user.name} size="xs" />
      {user.name}
    </span>
  ) : (
    <span>{name}</span>
  );
}

function When({ at }: { at: string }) {
  return (
    <time dateTime={at} title={formatDateTime(at)}>
      {relativeTime(at)}
    </time>
  );
}

function UnlinkButton({ label, pending, onClick }: { label: string; pending: boolean; onClick: () => void }) {
  const { t } = useTranslation();
  return (
    <Button size="sm" variant="ghost" icon={<Unlink />} aria-label={label} loading={pending} onClick={onClick}>
      {t("cicd.dev.unlink")}
    </Button>
  );
}

function LinkForm({ number }: { number: number }) {
  const { t } = useTranslation();
  const { link } = useDevLinkMutations(number);
  const toast = useToast();
  const [url, setUrl] = useState("");
  // the server's 422 ("…아직 받지 않은 MR/PR입니다.") stays under the field until the address changes
  const error = isApiError(link.error) ? (link.error.fieldErrors[0]?.message ?? link.error.message ?? link.error.title) : undefined;
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        const v = url.trim();
        if (!v) return;
        link.mutate(v, {
          onSuccess: () => (setUrl(""), toast({ tone: "success", title: t("cicd.dev.linked") })),
          onError: (e) => !isApiError(e) && toast({ tone: "danger", title: t("app.errorTitle") }),
        });
      }}
    >
      <div className="min-w-0 flex-1">
        <Field label={t("cicd.dev.link")} hint={t("cicd.dev.linkHint")} error={error}>
          <Input
            type="url"
            value={url}
            placeholder={t("cicd.dev.linkPlaceholder")}
            onChange={(e) => {
              setUrl(e.target.value);
              if (link.error) link.reset();
            }}
          />
        </Field>
      </div>
      <Button type="submit" icon={<Link2 />} loading={link.isPending} disabled={!url.trim()} className={error ? "mb-6" : "mb-5"}>
        {t("cicd.dev.linkAction")}
      </Button>
    </form>
  );
}

export function DevTab({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const q = useDevInfo(wp.number);
  const { unlink } = useDevLinkMutations(wp.number);
  const runToast = useRunWithToast();
  const [cutting, setCutting] = useState<string | null>(null);
  if (q.isPending)
    return (
      <div className="flex flex-col gap-3 py-4">
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const { mergeRequests, commits, canLink } = q.data;
  const cut = (linkId: string) => {
    setCutting(linkId);
    void runToast(unlink.mutateAsync(linkId), t("cicd.dev.unlinked")).finally(() => setCutting(null));
  };
  const via = (s: CicdCommit["source"]) => t(`cicd.dev.sources.${s}`);

  return (
    <div className="flex flex-col gap-6 py-4">
      {canLink && <LinkForm number={wp.number} />}
      {mergeRequests.length === 0 && commits.length === 0 ? (
        <div className="rounded-md border border-border">
          <EmptyState compact icon={<GitCommitHorizontal />} title={t("cicd.dev.empty")} />
        </div>
      ) : (
        <>
          <section aria-labelledby="dev-mr-h" className="flex flex-col gap-2">
            <h3 id="dev-mr-h" className="text-sm font-semibold text-fg">
              {t("cicd.dev.mergeRequests")} <span className="font-normal text-fg-muted tabular-nums">{mergeRequests.length}</span>
            </h3>
            {mergeRequests.length === 0 ? (
              <p className="text-sm text-fg-muted">{t("cicd.dev.noMergeRequests")}</p>
            ) : (
              <ul className="flex flex-col divide-y divide-border-muted rounded-md border border-border">
                {mergeRequests.map((m) => (
                  <li key={m.linkId} className="flex flex-wrap items-start gap-x-3 gap-y-1 px-3 py-2">
                    <Badge tone={STATE[m.state].tone} icon={STATE[m.state].icon} className="mt-0.5">
                      {t(`cicd.dev.states.${m.state}`)}
                    </Badge>
                    <div className="min-w-0 flex-1">
                      <External href={m.webUrl} className="text-sm font-medium break-words text-accent hover:underline">
                        {m.title}
                      </External>
                      <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-fg-muted">
                        <span className="font-mono">{m.repository}</span>
                        <span>{t("cicd.dev.mrNumber", { iid: m.iid })}</span>
                        <span>
                          <code className="font-mono">{m.sourceBranch}</code> → <code className="font-mono">{m.targetBranch}</code>
                        </span>
                        <Who name={m.author} user={m.user} />
                        <span>{m.mergedAt ? t("cicd.dev.merged", { when: relativeTime(m.mergedAt) }) : t("cicd.dev.updated", { when: relativeTime(m.updatedAt) })}</span>
                        <span>· {via(m.source)}</span>
                      </p>
                    </div>
                    {canLink && <UnlinkButton label={t("cicd.dev.unlinkName", { title: m.title })} pending={cutting === m.linkId} onClick={() => cut(m.linkId)} />}
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section aria-labelledby="dev-commits-h" className="flex flex-col gap-2">
            <h3 id="dev-commits-h" className="text-sm font-semibold text-fg">
              {t("cicd.dev.commits")} <span className="font-normal text-fg-muted tabular-nums">{commits.length}</span>
            </h3>
            {commits.length === 0 ? (
              <p className="text-sm text-fg-muted">{t("cicd.dev.noCommits")}</p>
            ) : (
              <ul className="flex flex-col divide-y divide-border-muted rounded-md border border-border">
                {commits.map((c) => (
                  <li key={c.linkId} className="flex flex-wrap items-start gap-x-3 gap-y-1 px-3 py-2">
                    <code className="mt-0.5 rounded-sm bg-surface px-1 font-mono text-xs text-fg">{c.shortSha}</code>
                    <div className="min-w-0 flex-1">
                      <External href={c.webUrl} className="text-sm break-words text-accent hover:underline">
                        {c.title}
                      </External>
                      <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-fg-muted">
                        <span className="font-mono">{c.repository}</span>
                        <Who name={c.author} user={c.user} />
                        <When at={c.committedAt} />
                        <span>· {via(c.source)}</span>
                      </p>
                    </div>
                    {canLink && <UnlinkButton label={t("cicd.dev.unlinkName", { title: `${c.shortSha} ${c.title}` })} pending={cutting === c.linkId} onClick={() => cut(c.linkId)} />}
                  </li>
                ))}
              </ul>
            )}
            {commits.length >= 50 && <p className="text-xs text-fg-muted">{t("cicd.dev.commitLimit", { count: 50 })}</p>}
          </section>
        </>
      )}
    </div>
  );
}
