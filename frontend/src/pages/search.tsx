// S22 검색 결과 (/search?q=&scope=&page=): work packages by #number, title, description or comment, and projects,
// in tabs with counts. Matches are highlighted; snippets show where in a description or comment the word is.
import { useEffect, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { FolderKanban, Search } from "lucide-react";
import { api, type SearchResult, type SearchScope } from "@/api";
import { StatusPill, TypeLabel } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/field";
import { EmptyState } from "@/components/ui/feedback";
import { Pagination } from "@/components/ui/nav";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { relativeTime } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";

const SCOPES: SearchScope[] = ["all", "work_packages", "projects"];

/** The text with every occurrence of the query marked (plain text in, React nodes out; no HTML). */
function Highlight({ text, q }: { text: string; q: string }) {
  const needle = q.trim().toLowerCase();
  if (!needle) return <>{text}</>;
  const out: (string | { mark: string })[] = [];
  let rest = text;
  for (let i = rest.toLowerCase().indexOf(needle); i >= 0; i = rest.toLowerCase().indexOf(needle)) {
    if (i > 0) out.push(rest.slice(0, i));
    out.push({ mark: rest.slice(i, i + needle.length) });
    rest = rest.slice(i + needle.length);
  }
  if (rest) out.push(rest);
  return (
    <>
      {out.map((x, i) =>
        typeof x === "string" ? (
          x
        ) : (
          <mark key={i} className="rounded-sm bg-warning-subtle px-0.5 text-fg">
            {x.mark}
          </mark>
        ),
      )}
    </>
  );
}

function Result({ r, q }: { r: SearchResult; q: string }) {
  const { t } = useTranslation();
  if (r.kind === "project")
    return (
      <li className="flex gap-3 border-b border-border-muted py-3 last:border-b-0">
        <FolderKanban className="mt-0.5 size-4 shrink-0 text-fg-muted" aria-hidden="true" />
        <div className="min-w-0">
          <Link to={`/projects/${r.project.identifier}`} className="font-medium text-accent hover:underline">
            <Highlight text={r.project.name} q={q} />
          </Link>
          <p className="text-xs text-fg-muted">{t("search.tabs.projects")} · {r.project.identifier}</p>
          {r.snippet && (
            <p className="mt-1 text-sm text-fg-muted">
              <Highlight text={r.snippet} q={q} />
            </p>
          )}
        </div>
      </li>
    );
  return (
    <li className="flex flex-col gap-1 border-b border-border-muted py-3 last:border-b-0">
      <div className="flex flex-wrap items-center gap-2 text-xs text-fg-muted">
        <TypeLabel name={r.type.name} color={r.type.color} />
        <span className="tabular-nums">#{r.number}</span>
        <StatusPill size="sm" name={r.status.name} color={r.status.color} />
        <span>{r.project.name}</span>
        <span aria-hidden="true">·</span>
        <time dateTime={r.updatedAt}>{relativeTime(r.updatedAt)}</time>
      </div>
      <Link to={`/projects/${r.project.identifier}/work-packages/${r.number}/activity`} className="font-medium text-accent hover:underline">
        <Highlight text={r.subject} q={q} />
      </Link>
      {r.snippet && (
        <p className="text-sm text-fg-muted">
          <span className="mr-1 text-xs font-medium text-fg">{t("search.matched", { where: t(`search.matchedIn.${r.matchedIn}`) })}</span>
          <Highlight text={r.snippet} q={q} />
        </p>
      )}
    </li>
  );
}

export function SearchPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const scope = (SCOPES.includes(params.get("scope") as SearchScope) ? params.get("scope") : "all") as SearchScope;
  const page = Math.max(1, Number(params.get("page")) || 1);
  const [text, setText] = useState(q);
  useEffect(() => setText(q), [q]);
  useDocumentTitle(q ? `${q} · ${t("search.title")}` : t("search.title"));
  const results = useQuery({ queryKey: ["search", q, scope, page], queryFn: () => api.search.results(q, scope, page), enabled: !!q.trim(), placeholderData: keepPreviousData });
  const go = (next: { q?: string; scope?: SearchScope; page?: number }) => {
    const p = new URLSearchParams();
    p.set("q", next.q ?? q);
    if ((next.scope ?? scope) !== "all") p.set("scope", next.scope ?? scope);
    if ((next.page ?? 1) > 1) p.set("page", String(next.page));
    setParams(p);
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (text.trim()) go({ q: text.trim(), page: 1 });
  };
  const counts = results.data?.counts;
  const countOf = (s: SearchScope) => (!counts ? undefined : s === "all" ? counts.workPackages + counts.projects : s === "work_packages" ? counts.workPackages : counts.projects);

  return (
    <PageBody>
      <PageHeader title={t("search.title")} />
      <form role="search" onSubmit={submit} className="mb-4 flex max-w-xl items-center gap-2">
        <label className="relative flex-1">
          <span className="sr-only">{t("search.input")}</span>
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-fg-muted" />
          <Input type="search" className="pl-8" value={text} placeholder={t("nav.searchPlaceholder")} onChange={(e) => setText(e.target.value)} />
        </label>
        <Button type="submit" variant="primary">
          {t("search.submit")}
        </Button>
      </form>

      {!q.trim() ? (
        <EmptyState icon={<Search />} title={t("search.prompt")} />
      ) : (
        <>
          <nav aria-label={t("search.title")} className="mb-2 flex gap-1 overflow-x-auto border-b border-border">
            {SCOPES.map((s) => (
              <button
                key={s}
                type="button"
                aria-current={s === scope ? "page" : undefined}
                onClick={() => go({ scope: s, page: 1 })}
                className={cn(
                  "-mb-px inline-flex h-10 shrink-0 items-center gap-1.5 border-b-2 border-transparent px-3 text-sm text-fg-muted hover:text-fg",
                  s === scope && "border-accent font-semibold text-fg",
                )}
              >
                {t(`search.tabs.${s}`)}
                {countOf(s) !== undefined && <span className="rounded-full bg-surface-selected px-1.5 text-xs tabular-nums">{countOf(s)}</span>}
              </button>
            ))}
          </nav>
          {results.isPending ? (
            <div className="flex flex-col gap-3 py-3">
              {Array.from({ length: 4 }, (_, i) => (
                <Skeleton key={i} className="h-12 w-full" />
              ))}
            </div>
          ) : results.isError ? (
            <ErrorState error={results.error} onRetry={() => results.refetch()} />
          ) : results.data.total === 0 ? (
            <EmptyState icon={<Search />} title={t("search.empty", { q })}>
              {t("search.emptyBody")}
            </EmptyState>
          ) : (
            <>
              <p className="sr-only" aria-live="polite">
                {t("search.summary", { q, total: results.data.total })}
              </p>
              <ul aria-label={t("search.results")} className={cn(results.isPlaceholderData && "opacity-60")}>
                {results.data.items.map((r) => (
                  <Result key={r.kind === "project" ? `p-${r.project.id}` : `w-${r.number}`} r={r} q={q} />
                ))}
              </ul>
              <div className="mt-4">
                <Pagination page={results.data.page} pageSize={results.data.pageSize} total={results.data.total} pageSizes={[results.data.pageSize]} onPage={(p) => go({ page: p })} />
              </div>
            </>
          )}
        </>
      )}
    </PageBody>
  );
}
