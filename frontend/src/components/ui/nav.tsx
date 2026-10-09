import type { ReactNode } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/cn";
import { Button } from "./button";
import { Select } from "./select";

/* ---------- Breadcrumbs ---------- */

export function Breadcrumbs({ items }: { items: { label: ReactNode; href?: string }[] }) {
  return (
    <nav aria-label="현재 위치">
      <ol className="flex flex-wrap items-center gap-1 text-sm text-fg-muted">
        {items.map((it, i) => {
          const last = i === items.length - 1;
          return (
            <li key={i} className="inline-flex items-center gap-1">
              {last || !it.href ? (
                <span aria-current={last ? "page" : undefined} className={cn(last && "font-semibold text-fg")}>{it.label}</span>
              ) : (
                <a href={it.href} className="text-accent hover:underline">{it.label}</a>
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
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-fg-muted">
      <p aria-live="polite">
        {from}–{to} / 총 {total}건
      </p>
      <div className="flex items-center gap-2">
        <span id="page-size-label">페이지당</span>
        <Select
          size="sm"
          aria-label="페이지당 항목 수"
          className="w-20"
          value={String(pageSize)}
          onValueChange={(v) => onPageSize?.(Number(v))}
          options={pageSizes.map((s) => ({ value: String(s), label: `${s}개` }))}
        />
        <nav aria-label="페이지" className="flex items-center gap-1">
          <Button size="sm" variant="ghost" iconOnly aria-label="이전 페이지" icon={<ChevronLeft />} disabled={page <= 1} onClick={() => onPage?.(page - 1)} />
          <span className="min-w-14 text-center tabular-nums">
            {page} / {pages}
          </span>
          <Button size="sm" variant="ghost" iconOnly aria-label="다음 페이지" icon={<ChevronRight />} disabled={page >= pages} onClick={() => onPage?.(page + 1)} />
        </nav>
      </div>
    </div>
  );
}
