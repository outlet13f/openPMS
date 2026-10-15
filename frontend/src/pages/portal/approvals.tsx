// 내 승인함: the approvals whose turn has come for me (only my pending rows). A row opens the approval page.
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { ClipboardCheck } from "lucide-react";
import { usePortalApprovals } from "@/api/hooks";
import { EmptyState } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { th, theadRow } from "@/features/admin/common";
import { formatDateTime, relativeTime } from "@/lib/dates";

export function PortalApprovalsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const q = usePortalApprovals();
  useDocumentTitle(t("portal.inbox.title"));
  let body;
  if (q.isPending)
    body = (
      <div className="flex flex-col gap-2">
        {Array.from({ length: 3 }, (_, i) => (
          <Skeleton key={i} className="h-8 w-full" />
        ))}
      </div>
    );
  else if (q.isError) body = <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  else if (q.data.length === 0) body = <EmptyState compact icon={<ClipboardCheck />} title={t("portal.inbox.empty")}>{t("portal.inbox.emptyHint")}</EmptyState>;
  else
    body = (
      <div className="relative overflow-x-auto rounded-md border border-border">
        <table className="w-full min-w-[40rem] border-collapse text-sm">
          <caption className="sr-only">{t("portal.inbox.caption")}</caption>
          <thead>
            <tr className={theadRow}>
              <th scope="col" className={th}>{t("portal.inbox.columns.request")}</th>
              <th scope="col" className={th}>{t("portal.inbox.columns.requester")}</th>
              <th scope="col" className={th}>{t("portal.inbox.columns.item")}</th>
              <th scope="col" className={th}>{t("portal.inbox.columns.desk")}</th>
              <th scope="col" className={th}>{t("portal.inbox.columns.requestedAt")}</th>
            </tr>
          </thead>
          <tbody>
            {q.data.map((r) => (
              <tr
                key={r.id}
                className="cursor-pointer border-b border-border-muted last:border-b-0 hover:bg-surface"
                onClick={(e) => {
                  if ((e.target as HTMLElement).closest("a")) return; // the link navigates itself
                  navigate(`/portal/approvals/${r.id}`);
                }}
              >
                <td className="max-w-80 px-3 py-2">
                  <Link to={`/portal/approvals/${r.id}`} className="line-clamp-2 font-semibold text-fg hover:underline">
                    {r.subject}
                  </Link>
                  <span className="text-xs text-fg-muted tabular-nums">#{r.number}</span>
                </td>
                <td className="px-3 py-2 text-fg">{r.requester}</td>
                <td className="px-3 py-2 text-fg-muted">{r.item ?? "-"}</td>
                <td className="max-w-48 truncate px-3 py-2 text-fg-muted">{r.desk}</td>
                <td className="px-3 py-2 whitespace-nowrap text-fg-muted">
                  <time dateTime={r.requestedAt} title={formatDateTime(r.requestedAt)}>
                    {relativeTime(r.requestedAt)}
                  </time>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  return (
    <PageBody className="max-w-6xl">
      <PageHeader title={t("portal.inbox.title")} breadcrumbs={[{ label: t("portal.title"), href: "/portal" }, { label: t("portal.inbox.title") }]} />
      {body}
    </PageBody>
  );
}
