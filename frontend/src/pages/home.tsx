// S02 홈: 즐겨찾는 프로젝트, 내게 할당된 작업, 읽지 않은 알림
import type { ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { Bell, CheckCircle2, FolderKanban, Star } from "lucide-react";
import type { Me, QuerySpec } from "@/api";
import { useProjects, useUnreadCount, useWpQuery } from "@/api/hooks";
import { Badge, Counter, StatusPill, TypeLabel } from "@/components/ui/badges";
import { EmptyState } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";

const MY_WORK: QuerySpec = {
  v: 1,
  filters: [
    { field: "assignee", op: "eq", values: ["me"] },
    { field: "status", op: "open" },
  ],
  columns: ["number", "subject", "status", "dueDate"],
  sort: [{ field: "dueDate", dir: "asc" }],
  groupBy: null,
  hierarchy: false,
  sums: false,
  includeSubprojects: true,
};

function Card({ title, icon, action, children }: { title: string; icon: ReactNode; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col rounded-lg border border-border bg-bg" aria-label={title}>
      <header className="flex items-center justify-between gap-2 border-b border-border-muted px-4 py-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-fg [&_svg]:size-4 [&_svg]:text-fg-muted">
          {icon}
          {title}
        </h2>
        {action}
      </header>
      <div className="flex-1">{children}</div>
    </section>
  );
}

export function HomePage({ me }: { me: Me }) {
  const { t } = useTranslation();
  useDocumentTitle(t("nav.home"));
  const favorites = useProjects({ filter: "favorite", pageSize: 12 });
  const work = useWpQuery(MY_WORK, undefined, 1, 8);
  const unread = useUnreadCount(true);

  return (
    <PageBody>
      <PageHeader title={t("home.greeting", { name: me.name })} />
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <div className="xl:col-span-2">
          <Card
            title={t("home.myWork")}
            icon={<CheckCircle2 />}
            action={
              <Link to="/work-packages?view=default%3Aassigned" className="text-sm text-accent hover:underline">
                {t("home.allMyWork")}
              </Link>
            }
          >
            {work.isPending ? (
              <div className="flex flex-col gap-3 p-4">
                {Array.from({ length: 4 }, (_, i) => (
                  <Skeleton key={i} className="h-5 w-full" />
                ))}
              </div>
            ) : work.isError ? (
              <ErrorState error={work.error} onRetry={() => work.refetch()} />
            ) : work.data.rows.length === 0 ? (
              <EmptyState compact icon={<CheckCircle2 />} title={t("home.myWorkEmpty")} />
            ) : (
              <ul className="divide-y divide-border-muted">
                {work.data.rows.map(({ wp }) => (
                  <li key={wp.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5">
                    <StatusPill size="sm" name={wp.status.name} color={wp.status.color} />
                    <Link to={`/projects/${wp.project.identifier}/work-packages/${wp.number}/activity`} className="min-w-0 flex-1 text-sm text-fg hover:underline">
                      <span className="mr-1 text-fg-muted tabular-nums">#{wp.number}</span>
                      <TypeLabel name={wp.type.name} color={wp.type.color} className="mr-1" />
                      {wp.subject}
                    </Link>
                    <span className="text-xs text-fg-muted">{wp.project.name}</span>
                    {wp.dueDate && (
                      <span className={cn("text-xs tabular-nums", wp._meta.overdue ? "font-semibold text-danger" : "text-fg-muted")}>
                        {formatDate(wp.dueDate)}
                        {wp._meta.overdue && <span className="sr-only">{t("wp.overdueSr")}</span>}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>

        <div className="flex flex-col gap-4">
          <Card
            title={t("home.inbox")}
            icon={<Bell />}
            action={
              <Link to="/notifications" className="text-sm text-accent hover:underline">
                {t("notifications.title")}
              </Link>
            }
          >
            <div className="flex items-center gap-3 px-4 py-4">
              {unread.isPending ? <Skeleton className="h-6 w-12" /> : <Counter tone={unread.data ? "accent" : "neutral"}>{unread.data ?? 0}</Counter>}
              <span className="text-sm text-fg-muted">{unread.data ? t("nav.unread", { count: unread.data }) : t("notifications.empty")}</span>
            </div>
          </Card>

          <Card title={t("home.favorites")} icon={<Star />}>
            {favorites.isPending ? (
              <div className="flex flex-col gap-3 p-4">
                <Skeleton className="h-5 w-3/4" />
                <Skeleton className="h-5 w-1/2" />
              </div>
            ) : favorites.isError ? (
              <ErrorState error={favorites.error} onRetry={() => favorites.refetch()} />
            ) : favorites.data.items.length === 0 ? (
              <EmptyState compact icon={<FolderKanban />} title={t("home.favoritesEmpty")}>
                {t("home.favoritesEmptyBody")}
              </EmptyState>
            ) : (
              <ul className="divide-y divide-border-muted">
                {favorites.data.items.map((p) => (
                  <li key={p.id} className="flex items-center justify-between gap-2 px-4 py-2.5">
                    <Link to={`/projects/${p.identifier}`} className="min-w-0 truncate text-sm font-medium text-fg hover:underline">
                      {p.name}
                    </Link>
                    {p.status && <Badge tone={p.status === "on_track" ? "success" : p.status === "at_risk" ? "warning" : "danger"}>{t(`projects.status.${p.status}`)}</Badge>}
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </PageBody>
  );
}
