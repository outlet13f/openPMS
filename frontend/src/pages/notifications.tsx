// S19 알림 센터: grouped by work package; picking one opens it beside the list and marks it read.
import { useEffect } from "react";
import { Link, Navigate, Outlet, useMatch, useParams, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { CheckCheck, Inbox, Settings } from "lucide-react";
import type { NotificationGroup } from "@/api";
import { useMarkRead, useNotifications } from "@/api/hooks";
import { Avatar } from "@/components/ui/avatar";
import { Badge, Counter, StatusPill, TypeLabel } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { formatDateTime, relativeTime } from "@/lib/dates";
import { ErrorState, PageHeader, useDocumentTitle } from "@/app/page";
import { WorkPackageDetail, type DetailTab } from "@/features/work-packages/detail";

function GroupCard({ g, selected, href }: { g: NotificationGroup; selected: boolean; href: string }) {
  const { t } = useTranslation();
  const latest = g.items[0];
  return (
    <li>
      <Link
        to={href}
        aria-current={selected ? "true" : undefined}
        className={cn(
          "block rounded-md border px-3 py-2.5 hover:bg-surface",
          selected ? "border-accent bg-accent-subtle hover:bg-accent-subtle" : "border-border",
          g.unread === 0 && !selected && "bg-surface-sunken/40",
        )}
      >
        <div className="flex items-center gap-2 text-xs text-fg-muted">
          {g.unread > 0 && <span className="size-2 shrink-0 rounded-full bg-accent" aria-hidden="true" />}
          <span className="truncate">{g.workPackage.project.name}</span>
          <span aria-hidden="true">·</span>
          <span className="tabular-nums">#{g.workPackage.number}</span>
          <StatusPill size="sm" name={g.workPackage.status.name} color={g.workPackage.status.color} />
          {g.unread > 0 && (
            <span className="ml-auto">
              <Counter tone="accent">{g.unread}</Counter>
              <span className="sr-only">{t("notifications.unread")}</span>
            </span>
          )}
        </div>
        <p className={cn("mt-1 text-sm text-fg", g.unread > 0 && "font-semibold")}>
          <TypeLabel name={g.workPackage.type.name} color={g.workPackage.type.color} className="mr-1.5" />
          {g.workPackage.subject}
        </p>
        <div className="mt-1.5 flex items-center gap-2 text-xs text-fg-muted">
          {latest.actor && <Avatar id={latest.actor.id} name={latest.actor.name} size="xs" />}
          <span className="min-w-0 flex-1 truncate">{latest.summary}</span>
          <Badge>{t(`notifications.reasons.${latest.reason}`)}</Badge>
          <time dateTime={latest.createdAt} title={formatDateTime(latest.createdAt)} className="shrink-0">
            {relativeTime(latest.createdAt)}
          </time>
        </div>
      </Link>
    </li>
  );
}

export function NotificationsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("notifications.title"));
  const [params, setParams] = useSearchParams();
  const filter = params.get("filter") === "all" ? "all" : "unread";
  const list = useNotifications(filter);
  const markRead = useMarkRead();
  const details = useMatch("/notifications/details/:number/*");
  const selected = details ? Number(details.params.number) : undefined;
  const search = filter === "all" ? "?filter=all" : "";

  return (
    <div className="flex min-h-full">
      <div className={cn("min-w-0 flex-1 px-content pb-16 md:px-8", selected && "hidden md:block")}>
        <PageHeader
          title={t("notifications.title")}
          actions={
            <>
              <Button icon={<CheckCheck />} onClick={() => markRead.mutate({ all: true })} disabled={!list.data?.some((g) => g.unread > 0)}>
                {t("notifications.markAllRead")}
              </Button>
              <Button asChild variant="ghost" iconOnly aria-label={t("notifications.settings")} icon={<Settings />}>
                <Link to="/my/notifications" />
              </Button>
            </>
          }
        />
        <div role="group" aria-label={t("notifications.title")} className="mb-3 inline-flex rounded-md border border-border bg-surface p-0.5">
          {(["unread", "all"] as const).map((f) => (
            <button
              key={f}
              type="button"
              aria-pressed={filter === f}
              onClick={() => setParams((p) => (f === "all" ? p.set("filter", "all") : p.delete("filter"), p))}
              className={cn("h-7 rounded-sm px-3 text-sm text-fg-muted hover:text-fg", filter === f && "bg-bg font-medium text-fg shadow-card")}
            >
              {t(`notifications.${f}`)}
            </button>
          ))}
        </div>
        {list.isPending ? (
          <div className="flex flex-col gap-2">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-20 w-full" />
            ))}
          </div>
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => list.refetch()} />
        ) : list.data.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState
              icon={<Inbox />}
              title={t("notifications.empty")}
              action={
                <Button asChild>
                  <Link to="/my/notifications">{t("notifications.settings")}</Link>
                </Button>
              }
            >
              {t("notifications.emptyBody")}
            </EmptyState>
          </div>
        ) : (
          <ul className="flex flex-col gap-2" aria-label={t("notifications.title")}>
            {list.data.map((g) => (
              <GroupCard key={g.workPackage.id} g={g} selected={selected === g.workPackage.number} href={`/notifications/details/${g.workPackage.number}/activity${search}`} />
            ))}
          </ul>
        )}
      </div>
      {selected ? (
        <aside aria-label={t("wp.detailsPanel")} className="flex w-full flex-col border-border-muted bg-bg md:sticky md:top-header md:h-[calc(100dvh-var(--pms-layout-header-h))] md:w-split md:shrink-0 md:border-l lg:w-[520px]">
          <Outlet />
        </aside>
      ) : (
        <aside className="hidden w-split shrink-0 items-center justify-center border-l border-border-muted p-8 text-center text-sm text-fg-muted lg:flex">{t("notifications.selectPrompt")}</aside>
      )}
    </div>
  );
}

export function NotificationDetailRoute() {
  const { number, tab } = useParams();
  const [params] = useSearchParams();
  const markRead = useMarkRead({ keepList: true });
  const n = Number(number);
  const search = params.get("filter") === "all" ? "?filter=all" : "";
  const { mutate } = markRead;
  useEffect(() => {
    if (Number.isInteger(n)) mutate({ workPackageNumber: n }); // opening an item marks its notifications read
  }, [n, mutate]);
  if (!Number.isInteger(n)) return <Navigate to="/notifications" replace />;
  return (
    <WorkPackageDetail
      key={n}
      number={n}
      mode="panel"
      tab={(["overview", "activity", "relations"].includes(tab ?? "") ? tab : "activity") as DetailTab}
      tabHref={(t) => `/notifications/details/${n}/${t}${search}`}
      closeHref={`/notifications${search}`}
      afterDeleteHref={`/notifications${search}`}
    />
  );
}
