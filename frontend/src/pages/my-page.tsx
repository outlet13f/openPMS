// S21 내 페이지 (/my/page): my own dashboard of work package widgets. Each widget is a saved-view-like query
// (assigned to me, created by me, watched, due soon …) shown as a short table with a link to the full list.
// Which widgets show, and in what order, is a preference (myPageWidgets).
import { useRef, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { ArrowDown, ArrowUp, Check, LayoutGrid, ListTodo, Settings2 } from "lucide-react";
import { MY_PAGE_WIDGETS, isApiError, type MyPageWidget, type QuerySpec, type QueryFilter } from "@/api";
import { useAccountMutations, useMe, useWpQuery } from "@/api/hooks";
import { StatusPill, TypeLabel } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/dates";
import { encodeSpec } from "@/features/work-packages/view-state";
import { useLatestSent } from "@/lib/use-latest-sent";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";

const open: QueryFilter = { field: "status", op: "open" };

function spec(filters: QueryFilter[], sort: QuerySpec["sort"]): QuerySpec {
  return {
    v: 1,
    filters,
    columns: ["number", "project", "type", "subject", "status", "dueDate"],
    sort,
    groupBy: null,
    hierarchy: false,
    sums: false,
    includeSubprojects: true,
  };
}

const SPECS: Record<MyPageWidget, QuerySpec> = {
  assigned: spec([{ field: "assignee", op: "eq", values: ["me"] }, open], [{ field: "dueDate", dir: "asc" }]),
  created: spec([{ field: "author", op: "eq", values: ["me"] }, open], [{ field: "updatedAt", dir: "desc" }]),
  watched: spec([{ field: "watcher", op: "eq", values: ["me"] }, open], [{ field: "updatedAt", dir: "desc" }]),
  responsible: spec([{ field: "responsible", op: "eq", values: ["me"] }, open], [{ field: "dueDate", dir: "asc" }]),
  dueSoon: spec([{ field: "assignee", op: "eq", values: ["me"] }, open, { field: "dueDate", op: "next_days", values: ["14"] }], [{ field: "dueDate", dir: "asc" }]),
  recent: spec([{ field: "updatedAt", op: "last_days", values: ["7"] }], [{ field: "updatedAt", dir: "desc" }]),
};

const ROWS = 10;

function Widget({ kind }: { kind: MyPageWidget }) {
  const { t } = useTranslation();
  const q = useWpQuery(SPECS[kind], undefined, 1, ROWS);
  const title = t(`myPage.widgets.${kind}`);
  const th = "px-3 py-2 text-left text-xs font-semibold whitespace-nowrap text-fg-muted";
  return (
    <section aria-label={title} className="flex min-w-0 flex-col rounded-lg border border-border bg-bg">
      <header className="flex items-center justify-between gap-2 border-b border-border-muted px-4 py-3">
        <h2 className="text-sm font-semibold text-fg">{title}</h2>
        {q.data && q.data.total > 0 && (
          <Link to={`/work-packages?q=${encodeSpec(SPECS[kind])}`} className="text-sm whitespace-nowrap text-accent hover:underline">
            {t("myPage.seeAll", { count: q.data.total })}
          </Link>
        )}
      </header>
      {q.isPending ? (
        <div className="flex flex-col gap-3 p-4">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-5 w-full" />
          ))}
        </div>
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : q.data.rows.length === 0 ? (
        <EmptyState compact icon={<ListTodo />} title={t(`myPage.empty.${kind}`)} />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[34rem] border-collapse text-sm">
            <thead>
              <tr className="border-b border-border-muted">
                <th scope="col" className={cn(th, "w-14")}>{t("myPage.columns.number")}</th>
                <th scope="col" className={th}>{t("myPage.columns.project")}</th>
                <th scope="col" className={th}>{t("myPage.columns.type")}</th>
                <th scope="col" className={th}>{t("myPage.columns.subject")}</th>
                <th scope="col" className={th}>{t("myPage.columns.status")}</th>
                <th scope="col" className={th}>{t("myPage.columns.due")}</th>
              </tr>
            </thead>
            <tbody>
              {q.data.rows.map(({ wp }) => (
                <tr key={wp.id} className="border-b border-border-muted last:border-b-0 hover:bg-surface">
                  <td className="px-3 py-2 tabular-nums">
                    <Link to={`/projects/${wp.project.identifier}/work-packages/${wp.number}/activity`} className="text-accent hover:underline" aria-label={`#${wp.number} ${wp.subject}`}>
                      {wp.number}
                    </Link>
                  </td>
                  <td className="max-w-32 truncate px-3 py-2 text-fg-muted">{wp.project.name}</td>
                  <td className="px-3 py-2">
                    <TypeLabel name={wp.type.name} color={wp.type.color} />
                  </td>
                  <td className="max-w-64 px-3 py-2">
                    <Link to={`/projects/${wp.project.identifier}/work-packages/${wp.number}/activity`} className="line-clamp-2 text-fg hover:underline">
                      {wp.subject}
                    </Link>
                  </td>
                  <td className="px-3 py-2">
                    <StatusPill size="sm" name={wp.status.name} color={wp.status.color} />
                  </td>
                  <td className={cn("px-3 py-2 whitespace-nowrap tabular-nums", wp._meta.overdue ? "font-semibold text-danger" : "text-fg-muted")}>
                    {formatDate(wp.dueDate) || "-"}
                    {wp._meta.overdue && <span className="sr-only"> {t("wp.overdueSr")}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** Choose and order the widgets; every change is saved right away. */
function Editor({ shown, onChange, saving }: { shown: MyPageWidget[]; onChange: (w: MyPageWidget[]) => void; saving: boolean }) {
  const { t } = useTranslation();
  // shown ones in their order, then the hidden ones
  const all = [...shown, ...MY_PAGE_WIDGETS.filter((w) => !shown.includes(w))];
  const move = (w: MyPageWidget, by: -1 | 1) => {
    const i = shown.indexOf(w);
    const next = [...shown];
    [next[i], next[i + by]] = [next[i + by], next[i]];
    onChange(next);
  };
  return (
    <section aria-label={t("myPage.edit")} className="mb-6 rounded-lg border border-border bg-surface p-4">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-sm text-fg-muted">{t("myPage.editHint")}</p>
        <p className="text-xs text-fg-muted" aria-live="polite">
          {saving ? t("myPage.saving") : t("myPage.saved")}
        </p>
      </div>
      <ul className="flex flex-col gap-1">
        {all.map((w) => {
          const name = t(`myPage.widgets.${w}`);
          const i = shown.indexOf(w);
          return (
            <li key={w} className="flex items-center gap-2 rounded-md px-2 py-1 hover:bg-surface-hover">
              <div className="flex-1">
                <Checkbox label={name} checked={i >= 0} onCheckedChange={(c) => onChange(c === true ? [...shown, w] : shown.filter((x) => x !== w))} />
              </div>
              <Button variant="ghost" size="sm" iconOnly icon={<ArrowUp />} aria-label={t("myPage.moveUp", { name })} disabled={i <= 0} onClick={() => move(w, -1)} />
              <Button variant="ghost" size="sm" iconOnly icon={<ArrowDown />} aria-label={t("myPage.moveDown", { name })} disabled={i < 0 || i >= shown.length - 1} onClick={() => move(w, 1)} />
            </li>
          );
        })}
      </ul>
    </section>
  );
}

export function MyPage() {
  const { t } = useTranslation();
  const toast = useToast();
  useDocumentTitle(t("nav.myPage"));
  const me = useMe().data;
  const m = useAccountMutations();
  const [editing, setEditing] = useState(false);
  // quick edits (two "위로" in a row) build on each other, not on preferences still on their way back
  const { latest, send } = useLatestSent<MyPageWidget[]>(me?.preferences.myPageWidgets ?? [], (w) => w.join(","));
  const shown = latest.current;
  // saves go out one after another, so the last click is also the last write
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  const [pending, setPending] = useState(0);
  const save = (w: MyPageWidget[]) => {
    send(w);
    setPending((n) => n + 1);
    queue.current = queue.current
      .then(() => m.preferences.mutateAsync({ myPageWidgets: w }))
      .catch((e) => toast({ tone: "danger", title: isApiError(e) ? e.message : t("app.errorTitle") }))
      .finally(() => setPending((n) => n - 1));
  };
  return (
    <PageBody>
      <PageHeader
        title={t("nav.myPage")}
        actions={
          <Button icon={editing ? <Check /> : <Settings2 />} aria-pressed={editing} onClick={() => setEditing((e) => !e)}>
            {editing ? t("myPage.done") : t("myPage.edit")}
          </Button>
        }
      />
      {!me ? (
        <Skeleton className="h-64 w-full" />
      ) : (
        <>
          {editing && <Editor shown={shown} onChange={save} saving={pending > 0} />}
          {shown.length === 0 ? (
            <EmptyState icon={<LayoutGrid />} title={t("myPage.noWidgets")}>
              {t("myPage.noWidgetsBody")}
            </EmptyState>
          ) : (
            <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
              {shown.map((w) => (
                <Widget key={w} kind={w} />
              ))}
            </div>
          )}
        </>
      )}
    </PageBody>
  );
}
