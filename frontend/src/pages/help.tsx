// 도움말: the user manual (/help) or the operator manual (/help/admin) inside the app — what openPMS can do, chapter
// by chapter, a search over every section, and the text itself. "#…" in the address goes to that section, which is
// where 이 화면 도움말 and the manuals' own cross references lead.
import { useEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { BookOpen, Search } from "lucide-react";
import { useMe } from "@/api/hooks";
import { Input } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { cn } from "@/lib/cn";
import { MarkdownView, type LinkHandler } from "@/features/help/markdown-view";
import { MANUAL_PATH, manualOfFile, useManual, type ManualId } from "@/features/help/manuals";
import type { Section } from "@/features/help/markdown";

/** Sections whose title or words contain every word of the query, titles first. */
export function searchSections(sections: Section[], query: string): Section[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  const hit = (s: string) => words.every((w) => s.toLowerCase().includes(w));
  const inTitle = sections.filter((s) => hit(s.title));
  const inText = sections.filter((s) => !inTitle.includes(s) && hit(`${s.title} ${s.text}`));
  return [...inTitle, ...inText];
}

function snippet(text: string, query: string) {
  const w = query.toLowerCase().split(/\s+/).find(Boolean) ?? "";
  const at = text.toLowerCase().indexOf(w);
  if (at < 0) return text.slice(0, 120);
  const from = Math.max(0, at - 40);
  return `${from > 0 ? "…" : ""}${text.slice(from, at + 80)}…`;
}

export function HelpPage({ manual }: { manual: ManualId }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const m = useManual(manual);
  const location = useLocation();
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const title = manual === "admin" ? t("help.adminManual") : t("help.userManual");
  useDocumentTitle(title);
  const target = decodeURIComponent(location.hash.replace(/^#/, ""));
  const path = MANUAL_PATH[manual];

  // go to the section in the address once the text is there (and whenever the address changes)
  useEffect(() => {
    if (!m.data || !target) return;
    const el = document.getElementById(target);
    if (el) {
      el.scrollIntoView({ block: "start" });
      el.focus({ preventScroll: true });
    }
  }, [m.data, target]);

  const links: LinkHandler = {
    anchorHref: (id) => `${path}#${encodeURIComponent(id)}`,
    onAnchor: (id) => navigate({ hash: encodeURIComponent(id) }),
    docHref: (file, id) => {
      const other = manualOfFile(file);
      return other ? `${MANUAL_PATH[other]}${id ? `#${encodeURIComponent(id)}` : ""}` : null;
    },
  };
  const results = useMemo(() => (m.data ? searchSections(m.data.sections, query) : []), [m.data, query]);
  const chapters = useMemo(() => {
    const out: { chapter: Section; children: Section[] }[] = [];
    m.data?.sections.forEach((s) => {
      if (s.level === 2) out.push({ chapter: s, children: [] });
      else if (s.level === 3 && out.length) out[out.length - 1].children.push(s);
    });
    return out;
  }, [m.data]);

  return (
    <PageBody>
      <PageHeader
        title={title}
        breadcrumbs={[{ label: t("help.title") }, { label: title }]}
        actions={
          me?.isAdmin && (
            <Link to={manual === "admin" ? MANUAL_PATH.user : MANUAL_PATH.admin} className="inline-flex items-center gap-1.5 text-sm text-accent underline underline-offset-2">
              <BookOpen aria-hidden="true" className="size-4" />
              {manual === "admin" ? t("help.userManual") : t("help.adminManual")}
            </Link>
          )
        }
      />
      {m.isPending ? (
        <Skeleton className="h-96 w-full" />
      ) : m.isError ? (
        <ErrorState error={m.error} onRetry={() => m.refetch()} />
      ) : (
        <div className="grid grid-cols-1 gap-8 lg:grid-cols-[18rem_minmax(0,1fr)]">
          <aside className="lg:sticky lg:top-[calc(var(--pms-layout-header-h)+16px)] lg:max-h-[calc(100dvh-var(--pms-layout-header-h)-32px)] lg:overflow-y-auto">
            <div role="search" className="relative mb-3">
              <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-fg-muted" />
              <Input type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t("help.searchPlaceholder")} aria-label={t("help.search")} className="pl-8" />
            </div>
            {query.trim() ? (
              <section aria-label={t("help.results")}>
                <p aria-live="polite" className="mb-2 text-xs text-fg-muted">{results.length ? t("help.resultCount", { count: results.length }) : t("help.noResults")}</p>
                <ul className="flex flex-col gap-1">
                  {results.slice(0, 40).map((s) => (
                    <li key={s.id}>
                      <Link to={{ hash: encodeURIComponent(s.id) }} className="block rounded-md px-2 py-1.5 hover:bg-surface-hover">
                        <span className="block text-sm font-medium text-fg">{s.title}</span>
                        <span className="block text-xs text-fg-muted">{snippet(s.text, query)}</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ) : (
              <nav aria-label={t("help.toc")}>
                <ul className="flex flex-col gap-0.5 text-sm">
                  {chapters.map(({ chapter, children }) => (
                    <li key={chapter.id}>
                      <Link to={{ hash: encodeURIComponent(chapter.id) }} className={cn("block rounded-md px-2 py-1 font-medium text-fg hover:bg-surface-hover", target === chapter.id && "bg-surface-selected")}>
                        {chapter.title}
                      </Link>
                      {children.length > 0 && (
                        <ul className="mb-1 ml-3 border-l border-border-muted pl-2">
                          {children.map((s) => (
                            <li key={s.id}>
                              <Link
                                to={{ hash: encodeURIComponent(s.id) }}
                                aria-current={target === s.id ? "location" : undefined}
                                className={cn("block rounded-md px-2 py-0.5 text-fg-muted hover:bg-surface-hover hover:text-fg", target === s.id && "bg-surface-selected text-fg")}
                              >
                                {s.title}
                              </Link>
                            </li>
                          ))}
                        </ul>
                      )}
                    </li>
                  ))}
                </ul>
              </nav>
            )}
          </aside>
          <article aria-label={title} className="min-w-0 max-w-3xl">
            <MarkdownView blocks={m.data.blocks} links={links} focusId={target} />
          </article>
        </div>
      )}
    </PageBody>
  );
}
