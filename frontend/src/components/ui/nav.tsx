import type { ReactNode } from "react";
import { Link } from "react-router";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/cn";
import { Button } from "./button";
import { Select } from "./select";
import { useTranslation } from "react-i18next";

/* ---------- Breadcrumbs ---------- */

export function Breadcrumbs({ items }: { items: { label: ReactNode; href?: string }[] }) {
  const { t } = useTranslation();
  return (
    <nav aria-label={t("app.breadcrumb")}>
      <ol className="flex flex-wrap items-center gap-1 text-sm text-fg-muted">
        {items.map((it, i) => {
          const last = i === items.length - 1;
          return (
            <li key={i} className="inline-flex items-center gap-1">
              {last || !it.href ? (
                <span aria-current={last ? "page" : undefined} className={cn(last && "font-semibold text-fg")}>{it.label}</span>
              ) : (
                <Link to={it.href} className="text-accent hover:underline">{it.label}</Link>
              )}
              {!last && <span aria-hidden="true">/</span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/* ---------- Pagination: "1–20 / 134건" + page size ---------- */

export function Pagination({
  page,
  pageSize,
  total,
  pageSizes = [20, 100],
  onPage,
  onPageSize,
}: {
  page: number;
  pageSize: number;
  total: number;
  pageSizes?: number[];
  onPage?: (p: number) => void;
  onPageSize?: (s: number) => void;
}) {
  const { t } = useTranslation();
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-fg-muted">
      <p aria-live="polite">{t("pagination.range", { from, to, total })}</p>
      <div className="flex items-center gap-2">
        <span aria-hidden="true">{t("pagination.perPage")}</span>
        <Select
          size="sm"
          aria-label={t("pagination.perPageLabel")}
          className="w-20"
          value={String(pageSize)}
          onValueChange={(v) => onPageSize?.(Number(v))}
          options={pageSizes.map((s) => ({ value: String(s), label: t("pagination.perPageOption", { count: s }) }))}
        />
        <nav aria-label={t("pagination.nav")} className="flex items-center gap-1">
          <Button size="sm" variant="ghost" iconOnly aria-label={t("pagination.prev")} icon={<ChevronLeft />} disabled={page <= 1} onClick={() => onPage?.(page - 1)} />
          <span className="min-w-14 text-center tabular-nums">
            {page} / {pages}
          </span>
          <Button size="sm" variant="ghost" iconOnly aria-label={t("pagination.next")} icon={<ChevronRight />} disabled={page >= pages} onClick={() => onPage?.(page + 1)} />
        </nav>
      </div>
    </div>
  );
}
