// 서비스 요청 홈: the service desks (one button per ticket type) and 내 요청 (진행 중 / 완료 / 전체, newest update
// first). A ticket with a public answer or status change the requester has not opened yet carries "새 답변".
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { Inbox, LifeBuoy, TriangleAlert, Wrench } from "lucide-react";
import type { IdName, PortalSummary } from "@/api";
import { usePortalDesks, usePortalTickets } from "@/api/hooks";
import { Badge, StatusPill } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { Tabs } from "@/components/ui/tabs";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { th, theadRow } from "@/features/admin/common";
import { formatDateTime, relativeTime } from "@/lib/dates";
import { cn } from "@/lib/cn";

// the ticket types by their fixed ids (real API, demo) with the name as a fallback
const INCIDENT = new Set(["2dcd4691-d4a9-5bf0-8908-e4740b42616e", "t-incident"]);
export const isIncident = (type: IdName) => INCIDENT.has(type.id) || type.name === "장애";

/** "장애 신고" for incidents, "서비스 요청" for service requests, the type's own name for anything else. */
export function useTypeAction() {
  const { t } = useTranslation();
  return (type: IdName) => (isIncident(type) ? t("portal.reportIncident") : type.name === "서비스 요청" || type.id === "a3af2ccf-05c6-5b1c-84bc-4d815791e4eb" || type.id === "t-service-request" ? t("portal.requestService") : type.name);
}

function Desks() {
  const { t } = useTranslation();
  const desks = usePortalDesks();
  const action = useTypeAction();
  if (desks.isPending)
    return (
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Skeleton className="h-36" />
        <Skeleton className="h-36" />
      </div>
    );
  if (desks.isError) return <ErrorState error={desks.error} onRetry={() => desks.refetch()} />;
  if (!desks.data.length) return <EmptyState compact icon={<LifeBuoy />} title={t("portal.noDesks")} />;
  return (
    <ul className="grid grid-cols-1 gap-4 md:grid-cols-2" aria-label={t("portal.desks")}>
      {desks.data.map((d) => (
        <li key={d.identifier} className="flex flex-col gap-3 rounded-lg border border-border bg-bg p-4 sm:p-5">
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-fg">{d.name}</h3>
            {d.descriptionText && <p className="mt-1 line-clamp-3 text-sm text-fg-muted">{d.descriptionText}</p>}
          </div>
          <div role="group" aria-label={`${d.name}: ${t("portal.other")}`} className="mt-auto flex flex-col gap-2">
            <p className="text-xs font-medium text-fg-muted" aria-hidden="true">
              {t("portal.other")}
            </p>
            <div className="flex flex-wrap gap-2">
            {d.types.map((type) => (
              <Button
                key={type.id}
                asChild
                variant={isIncident(type) ? "primary" : "secondary"}
                icon={isIncident(type) ? <TriangleAlert aria-hidden="true" /> : <Wrench aria-hidden="true" />}
              >
                <Link to={`/portal/new/${encodeURIComponent(d.identifier)}?type=${encodeURIComponent(type.id)}`} aria-label={t("portal.typeAction", { desk: d.name, action: action(type) })}>
                  {action(type)}
                </Link>
              </Button>
            ))}
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}

/** A catalog item's description on one line (the description is HTML: only its text is used). */
const oneLine = (html: string) => new DOMParser().parseFromString(html, "text/html").body.textContent?.trim() ?? "";

/** The desks' catalogs: items by category, each a card that opens its request form. */
function Catalog() {
  const { t } = useTranslation();
  const desks = usePortalDesks();
  const shown = (desks.data ?? []).filter((d) => d.catalog.some((c) => c.items.length));
  if (!shown.length) return null;
  return (
    <section aria-labelledby="portal-catalog" className="mb-8">
      <h2 id="portal-catalog" className="mb-3 text-lg font-semibold text-fg">
        {t("portal.catalog")}
      </h2>
      <div className="flex flex-col gap-6">
        {shown.map((d) => (
          <div key={d.identifier} className="flex flex-col gap-4">
            <h3 className="text-base font-semibold text-fg">{d.name}</h3>
            {d.catalog
              .filter((c) => c.items.length)
              .map((c) => (
                <section key={c.id ?? "other"} aria-label={`${d.name} ${t("portal.categoryItems", { name: c.name })}`}>
                  <h4 className="mb-2 text-sm font-semibold text-fg-muted">{c.name}</h4>
                  <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {c.items.map((i) => (
                      <li key={i.id}>
                        <Link
                          to={`/portal/items/${encodeURIComponent(i.id)}`}
                          className="flex h-full flex-col gap-1 rounded-lg border border-border bg-bg p-4 hover:border-accent hover:bg-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-ring"
                        >
                          <span className="text-sm font-semibold text-fg">{i.name}</span>
                          {oneLine(i.descriptionHtml) && <span className="line-clamp-1 text-xs text-fg-muted">{oneLine(i.descriptionHtml)}</span>}
                        </Link>
                      </li>
                    ))}
                  </ul>
                </section>
              ))}
          </div>
        ))}
      </div>
    </section>
  );
}

type State = "open" | "closed" | "all";

function TicketTable({ rows }: { rows: PortalSummary[] }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <div className="relative overflow-x-auto rounded-md border border-border">
      <table className="w-full min-w-[40rem] border-collapse text-sm">
        <caption className="sr-only">{t("portal.mine")}</caption>
        <thead>
          <tr className={theadRow}>
            <th scope="col" className={cn(th, "w-16")}>{t("portal.columns.number")}</th>
            <th scope="col" className={th}>{t("portal.columns.subject")}</th>
            <th scope="col" className={th}>{t("portal.columns.desk")}</th>
            <th scope="col" className={th}>{t("portal.columns.status")}</th>
            <th scope="col" className={th}>{t("portal.columns.updated")}</th>
            <th scope="col" className={cn(th, "w-24")}>
              <span className="sr-only">{t("portal.columns.news")}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr
              key={r.number}
              className="cursor-pointer border-b border-border-muted last:border-b-0 hover:bg-surface"
              onClick={(e) => {
                if ((e.target as HTMLElement).closest("a")) return; // the link navigates itself
                navigate(`/portal/tickets/${r.number}`);
              }}
            >
              <td className="px-3 py-2 text-fg-muted tabular-nums">{r.number}</td>
              <td className="max-w-80 px-3 py-2">
                <Link to={`/portal/tickets/${r.number}`} className={cn("line-clamp-2 text-fg hover:underline", r.hasNews && "font-semibold")}>
                  {r.subject}
                </Link>
                <span className="text-xs text-fg-muted">{r.type}</span>
              </td>
              <td className="max-w-48 truncate px-3 py-2 text-fg-muted">{r.desk}</td>
              <td className="px-3 py-2">
                <StatusPill size="sm" name={r.status.name} color={r.status.color} />
              </td>
              <td className="px-3 py-2 whitespace-nowrap text-fg-muted">
                <time dateTime={r.updatedAt} title={formatDateTime(r.updatedAt)}>
                  {relativeTime(r.updatedAt)}
                </time>
              </td>
              <td className="px-3 py-2">{r.hasNews && <Badge tone="accent">{t("portal.news")}</Badge>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function MyTickets() {
  const { t } = useTranslation();
  const [state, setState] = useState<State>("open");
  const q = usePortalTickets(state);
  const body = q.isPending ? (
    <div className="flex flex-col gap-2">
      {Array.from({ length: 3 }, (_, i) => (
        <Skeleton key={i} className="h-8 w-full" />
      ))}
    </div>
  ) : q.isError ? (
    <ErrorState error={q.error} onRetry={() => q.refetch()} />
  ) : q.data.length === 0 ? (
    <EmptyState compact icon={<Inbox />} title={t("portal.empty")} />
  ) : (
    <TicketTable rows={q.data} />
  );
  const states: State[] = ["open", "closed", "all"];
  return (
    <Tabs
      aria-label={t("portal.mine")}
      value={state}
      onValueChange={(v) => setState(v as State)}
      items={states.map((s) => ({ value: s, label: t(`portal.tabs.${s}`), content: s === state ? body : null }))}
    />
  );
}

export function PortalHomePage() {
  const { t } = useTranslation();
  useDocumentTitle(t("portal.title"));
  return (
    <PageBody className="max-w-6xl">
      <PageHeader title={t("portal.title")} />
      <section aria-labelledby="portal-welcome" className="mb-8">
        <h2 id="portal-welcome" className="text-lg font-semibold text-fg">
          {t("portal.welcome")}
        </h2>
        <p className="mt-1 mb-4 max-w-2xl text-sm text-fg-muted">{t("portal.welcomeHint")}</p>
        <Desks />
      </section>
      <Catalog />
      <section aria-labelledby="portal-mine">
        <h2 id="portal-mine" className="mb-2 text-lg font-semibold text-fg">
          {t("portal.mine")}
        </h2>
        <MyTickets />
      </section>
    </PageBody>
  );
}
