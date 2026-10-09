// Header search: an ARIA combobox over GET /search/suggest (work packages by #number or title, projects by name).
import { useEffect, useId, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { FolderKanban, ListTodo, Search } from "lucide-react";
import { api } from "@/api";
import { cn } from "@/lib/cn";
import { Spinner } from "@/components/ui/spinner";

function useDebounced<T>(value: T, ms: number) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

export function SearchBox() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const id = useId();
  const [text, setText] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const q = useDebounced(text.trim(), 250);
  const inputRef = useRef<HTMLInputElement>(null);
  const hits = useQuery({ queryKey: ["suggest", q], queryFn: () => api.search.suggest(q), enabled: q.length > 0 });
  const items = hits.data ?? [];
  const show = open && q.length > 0;

  const go = (href: string) => {
    setOpen(false);
    setText("");
    inputRef.current?.blur();
    navigate(href);
  };

  return (
    <div className="relative">
      <label htmlFor={`${id}-input`} className="sr-only">
        {t("nav.search")}
      </label>
      <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-header-fg-muted" />
      <input
        ref={inputRef}
        id={`${id}-input`}
        type="search"
        role="combobox"
        autoComplete="off"
        aria-expanded={show}
        aria-controls={`${id}-list`}
        aria-autocomplete="list"
        aria-activedescendant={show && items[active] ? `${id}-opt-${active}` : undefined}
        placeholder={t("nav.searchPlaceholder")}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          setActive(0);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setActive((a) => Math.min(items.length - 1, a + 1));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setActive((a) => Math.max(0, a - 1));
          } else if (e.key === "Enter" && items[active]) {
            e.preventDefault();
            go(items[active].href);
          } else if (e.key === "Escape") {
            if (open) setOpen(false);
            else setText("");
          }
        }}
        className="h-8 w-full rounded-md border border-header-fg-muted/40 bg-header-hover pr-8 pl-8 text-sm text-header-fg placeholder:text-header-fg-muted focus-visible:outline-header-fg"
      />
      {hits.isFetching && <Spinner size={14} className="absolute top-1/2 right-2.5 -translate-y-1/2 text-header-fg-muted" />}
      <div
        id={`${id}-list`}
        role="listbox"
        aria-label={t("nav.search")}
        hidden={!show}
        className="absolute inset-x-0 top-full z-(--pms-z-dropdown) mt-1 overflow-hidden rounded-lg border border-border bg-bg p-1 text-fg shadow-pop"
      >
        {items.map((h, i) => (
          <div
            key={h.href}
            id={`${id}-opt-${i}`}
            role="option"
            aria-selected={i === active}
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => go(h.href)}
            onMouseEnter={() => setActive(i)}
            className={cn("flex cursor-pointer items-start gap-2 rounded-sm px-2 py-1.5 text-sm", i === active && "bg-surface-hover")}
          >
            {h.kind === "project" ? <FolderKanban className="mt-0.5 size-4 shrink-0 text-fg-muted" aria-hidden="true" /> : <ListTodo className="mt-0.5 size-4 shrink-0 text-fg-muted" aria-hidden="true" />}
            <span className="min-w-0">
              <span className="block truncate">{h.title}</span>
              <span className="block truncate text-xs text-fg-muted">{h.subtitle}</span>
            </span>
          </div>
        ))}
        {!hits.isFetching && hits.isSuccess && items.length === 0 && <p className="px-2 py-2 text-sm text-fg-muted">{t("nav.searchEmpty")}</p>}
      </div>
      <p className="sr-only" aria-live="polite">
        {show && hits.isSuccess ? `${items.length}` : ""}
      </p>
    </div>
  );
}
