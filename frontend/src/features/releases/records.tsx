// 릴리스 기록: one row per production deployment that brought commits in (newest first, 20 a page) — when, the target
// environment, the repository, how many work packages went out and how many of them with a warning, and the deployment in
// the tool. A row whose content could not be checked says so instead of counts. Opening a row (/releases/records/:id) shows
// below the table what went out: each work package as recorded (number and subject copies), its last verification on the
// source environment and the warning; work packages the viewer may not see are only counted ("볼 수 없는 작업 n개").
import { useId } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Check, History, X } from "lucide-react";
import type { ReleaseDetail, ReleaseItem, ReleaseRow, ReleaseWarning } from "@/api";
import { useReleaseRecord, useReleaseRecords } from "@/api/hooks";
import { Badge } from "@/components/ui/badges";
import { EmptyState } from "@/components/ui/feedback";
import { Pagination } from "@/components/ui/nav";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState } from "@/app/page";
import { useCurrentProject } from "@/app/project";
import { cn } from "@/lib/cn";
import { formatDateTime } from "@/lib/dates";
import { AdminTable, tbodyRow, th, theadRow } from "@/features/admin/common";
import { External, monthDay, shortWhen } from "@/features/cicd/links";

const PAGE_SIZE = 20;

/** "검증 없이 나감" · "재검증 없이 나감" · "실패인 채 나감": all warnings, told apart by the words */
function WarningBadge({ warning }: { warning: ReleaseWarning }) {
  const { t } = useTranslation();
  return <Badge tone="danger">{t(`releases.records.warnings.${warning}`)}</Badge>;
}

function When({ at }: { at: string }) {
  return (
    <time dateTime={at} title={formatDateTime(at)} className="tabular-nums">
      {shortWhen(at)}
    </time>
  );
}

/* ---------- the list ---------- */

function RecordsTable({ rows, project, openId, page }: { rows: ReleaseRow[]; project: string; openId: string | undefined; page: number }) {
  const { t } = useTranslation();
  const td = "px-3 py-2 align-top";
  return (
    <AdminTable minWidth={720}>
      <caption className="sr-only">{t("releases.records.caption")}</caption>
      <thead>
        <tr className={theadRow}>
          <th scope="col" className={th}>{t("releases.records.columns.deployedAt")}</th>
          <th scope="col" className={th}>{t("releases.records.columns.environment")}</th>
          <th scope="col" className={th}>{t("releases.records.columns.repository")}</th>
          <th scope="col" className={cn(th, "text-right")}>{t("releases.records.columns.items")}</th>
          <th scope="col" className={th}>{t("releases.records.columns.warnings")}</th>
          <th scope="col" className={th}>{t("releases.records.columns.deployment")}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => {
          const open = r.id === openId;
          return (
            <tr key={r.id} className={cn(tbodyRow, open && "bg-surface")}>
              <th scope="row" className={cn(td, "text-left font-normal whitespace-nowrap")}>
                <Link
                  to={`/projects/${project}/releases/records/${r.id}${page > 1 ? `?page=${page}` : ""}`}
                  aria-current={open ? "true" : undefined}
                  className={cn("text-accent hover:underline", open && "font-semibold")}
                >
                  <When at={r.deployedAt} />
                </Link>
              </th>
              <td className={td}>{r.environment.name}</td>
              <td className={cn(td, "font-mono text-xs")}>{r.repository}</td>
              {r.contentFailed ? (
                <td colSpan={2} className={cn(td, "text-danger")}>
                  {t("releases.records.contentFailed")}
                </td>
              ) : (
                <>
                  <td className={cn(td, "text-right tabular-nums")}>{r.itemCount}</td>
                  <td className={td}>
                    <span className="flex flex-wrap items-center gap-1.5">
                      {r.warningCount > 0 ? (
                        <Badge tone="danger">{t("releases.records.warningCount", { count: r.warningCount })}</Badge>
                      ) : (
                        <span className="text-fg-muted">{t("releases.records.noWarning")}</span>
                      )}
                      {r.partial && <Badge tone="neutral">{t("releases.records.partial")}</Badge>}
                    </span>
                  </td>
                </>
              )}
              <td className={td}>
                {r.deploymentUrl ? <External href={r.deploymentUrl}>{t("releases.records.viewDeployment")}</External> : <span className="text-fg-muted">–</span>}
              </td>
            </tr>
          );
        })}
      </tbody>
    </AdminTable>
  );
}

/* ---------- a record ---------- */

function Verification({ item, source }: { item: ReleaseItem; source: string | null }) {
  const { t } = useTranslation();
  const v = item.verification;
  if (!v) return <span className="text-fg-muted">{t("releases.records.noVerification")}</span>;
  const failed = v.result === "failed";
  return (
    <span className={cn("inline-flex items-start gap-1", failed ? "text-danger" : "text-fg")}>
      {failed ? <X className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" /> : <Check className="mt-0.5 size-3.5 shrink-0 text-success" aria-hidden="true" />}
      <span>
        {t(failed ? "releases.records.verificationFailed" : "releases.records.verificationPassed", {
          environment: v.environment || source || "",
          by: v.by?.name ?? t("releases.card.deletedUser"),
          date: monthDay(v.at),
        })}
        {failed && v.reason && <span className="block break-words text-fg-muted">{v.reason}</span>}
      </span>
    </span>
  );
}

function RecordDetail({ detail, project }: { detail: ReleaseDetail; project: string }) {
  const { t } = useTranslation();
  const headingId = useId();
  const r = detail.release;
  const td = "px-3 py-2 align-top";
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <h2 id={headingId} className="text-base font-semibold text-fg">
          {t("releases.records.detailTitle", { environment: r.environment.name, when: shortWhen(r.deployedAt) })}
        </h2>
        <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-fg-muted">
          <span className="font-mono text-xs">{r.repository}</span>
          {r.sourceEnvironment && <span>· {t("releases.records.source", { environment: r.sourceEnvironment })}</span>}
          {r.deploymentUrl && (
            <span>
              · <External href={r.deploymentUrl}>{t("releases.records.viewDeployment")}</External>
            </span>
          )}
          {r.partial && <Badge tone="neutral">{t("releases.records.partial")}</Badge>}
        </p>
      </div>
      {r.contentFailed ? (
        <p className="text-sm text-danger">{t("releases.records.contentFailed")}</p>
      ) : detail.items.length === 0 ? (
        <p className="text-sm text-fg-muted">{t("releases.records.noItems")}</p>
      ) : (
        <AdminTable minWidth={560}>
          <caption className="sr-only">{t("releases.records.itemsCaption")}</caption>
          <thead>
            <tr className={theadRow}>
              <th scope="col" className={th}>{t("releases.records.columns.workPackage")}</th>
              <th scope="col" className={th}>{t("releases.records.columns.verification")}</th>
              <th scope="col" className={th}>{t("releases.records.columns.warning")}</th>
            </tr>
          </thead>
          <tbody>
            {detail.items.map((it, i) => (
              <tr key={`${it.workPackageId ?? "deleted"}-${it.number}-${i}`} className={tbodyRow}>
                <th scope="row" className={cn(td, "text-left font-normal")}>
                  {it.workPackageId ? (
                    <Link to={`/projects/${project}/work-packages/${it.number}/activity`} className="text-accent hover:underline">
                      <span className="tabular-nums">#{it.number}</span> {it.subject}
                    </Link>
                  ) : (
                    <span className="text-fg">
                      <span className="tabular-nums">#{it.number}</span> {it.subject}
                    </span>
                  )}
                </th>
                <td className={td}>
                  <Verification item={it} source={r.sourceEnvironment} />
                </td>
                <td className={td}>{it.warning ? <WarningBadge warning={it.warning} /> : <span className="text-fg-muted">{t("releases.records.noWarning")}</span>}</td>
              </tr>
            ))}
          </tbody>
        </AdminTable>
      )}
      {r.hiddenCount > 0 && <p className="text-sm text-fg-muted">{t("releases.records.hidden", { count: r.hiddenCount })}</p>}
    </section>
  );
}

function RecordDetailLoader({ project, id }: { project: string; id: string }) {
  const q = useReleaseRecord(project, id);
  if (q.isPending) return <Skeleton className="h-32 w-full" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  return <RecordDetail detail={q.data} project={project} />;
}

export function ReleaseRecordsView({ releaseId }: { releaseId?: string }) {
  const { t } = useTranslation();
  const project = useCurrentProject().identifier;
  const [sp] = useSearchParams();
  const navigate = useNavigate();
  const page = Math.max(1, Number(sp.get("page")) || 1);
  const q = useReleaseRecords(project, page);
  const goPage = (p: number) => navigate(p > 1 ? `/projects/${project}/releases/records?page=${p}` : `/projects/${project}/releases/records`);

  let list;
  if (q.isPending) list = <Skeleton className="h-40 w-full" />;
  else if (q.isError) list = <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  else if (q.data.total === 0)
    list = (
      <div className="rounded-md border border-border">
        <EmptyState compact icon={<History />} title={t("releases.records.empty")} />
      </div>
    );
  else
    list = (
      <div className="flex flex-col gap-2">
        <RecordsTable rows={q.data.items} project={project} openId={releaseId} page={page} />
        <Pagination page={q.data.page} pageSize={q.data.pageSize || PAGE_SIZE} total={q.data.total} pageSizes={[PAGE_SIZE]} onPage={goPage} />
      </div>
    );

  return (
    <div className="flex flex-col gap-6 py-4">
      {list}
      {releaseId && <RecordDetailLoader project={project} id={releaseId} />}
    </div>
  );
}
