// 내 공수: one person's week as a grid — work packages down, days across — typed in with the keyboard and saved at once.
// Arrow keys move between cells (left/right at the ends of the text), Enter moves down, Ctrl+S saves; "1:30" means 1.5.
// A day past the instance's hours per day is flagged, 24 is refused by the server. Cells the sheet cannot set (several
// entries, or no right to log there any more) are read-only and say why.
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { AlertTriangle, ChevronLeft, ChevronRight, Clock, ListPlus, Plus, RotateCcw, Save, Search } from "lucide-react";
import { api, isApiError, type QuerySpec, type TimeWeek, type WorkPackageRef } from "@/api";
import { useTimeMutations, useTimeWeek } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/field";
import { Banner, EmptyState, useToast } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { TypeLabel } from "@/components/ui/badges";
import { cn } from "@/lib/cn";
import { addDays, formatDate, parseIso, todayIso } from "@/lib/dates";
import { useUnsavedWarning } from "@/lib/use-unsaved-warning";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";

const key = (number: number, date: string) => `${number}|${date}`;

/** "1.5", "1,5", "1:30" → hours; "" → 0; anything else → NaN. */
export function parseHours(text: string): number {
  const t = text.trim();
  if (!t) return 0;
  const hm = /^(\d{1,2}):([0-5]\d)$/.exec(t);
  if (hm) return Number(hm[1]) + Number(hm[2]) / 60;
  const n = Number(t.replace(",", "."));
  return Number.isFinite(n) ? n : NaN;
}
const round2 = (n: number) => Math.round(n * 100) / 100;
const show = (n: number) => (n ? String(round2(n)) : "");
const valid = (n: number) => Number.isFinite(n) && n >= 0 && n <= 24;
const weekday = (d: string) => parseIso(d).toLocaleDateString("ko-KR", { weekday: "short", timeZone: "UTC" });

const MINE_SPEC: QuerySpec = {
  v: 1, filters: [{ field: "assignee", op: "eq", values: ["me"] }, { field: "status", op: "open" }], columns: ["subject"],
  sort: [{ field: "updatedAt", dir: "desc" }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false,
};
const toRef = (w: WorkPackageRef): WorkPackageRef => ({ id: w.id, number: w.number, subject: w.subject, type: w.type, status: w.status, project: w.project });

function AddRow({ onAdd, taken }: { onAdd: (rows: WorkPackageRef[]) => void; taken: Set<number> }) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<WorkPackageRef[]>([]);
  useEffect(() => {
    const text = q.trim();
    if (!text) return setHits([]);
    const id = setTimeout(async () => {
      const r = await api.workPackages.query({ ...MINE_SPEC, filters: [], text }, { page: 1, pageSize: 8 }).catch(() => null);
      setHits((r?.rows ?? []).map((x) => toRef(x.wp)).filter((w) => !taken.has(w.number)));
    }, 200);
    return () => clearTimeout(id);
  }, [q, taken]);
  const loadMine = async () => {
    const r = await api.workPackages.query(MINE_SPEC, { page: 1, pageSize: 50 });
    onAdd(r.rows.map((x) => toRef(x.wp)).filter((w) => !taken.has(w.number)));
  };
  return (
    <div className="mt-3 flex flex-wrap items-start gap-2">
      <div className="relative w-full max-w-sm">
        <label className="block">
          <span className="sr-only">{t("time.addRow")}</span>
          <Input
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={t("time.addRowPlaceholder")}
            aria-label={t("time.addRow")}
            onKeyDown={(e) => {
              if (e.key === "Enter" && hits[0]) (onAdd([hits[0]]), setQ(""));
            }}
          />
        </label>
        <Search className="pointer-events-none absolute top-2 right-2.5 size-4 text-fg-muted" aria-hidden="true" />
        {hits.length > 0 && (
          <ul className="absolute z-10 mt-1 w-full overflow-hidden rounded-md border border-border bg-bg shadow-card" aria-label={t("time.addRow")}>
            {hits.map((w) => (
              <li key={w.id}>
                <button type="button" className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-surface-hover" onClick={() => (onAdd([w]), setQ(""))}>
                  <Plus className="size-3.5 shrink-0 text-fg-muted" aria-hidden="true" />
                  <span className="tabular-nums text-fg-muted">#{w.number}</span>
                  <span className="truncate">{w.subject}</span>
                  <span className="ml-auto shrink-0 text-xs text-fg-muted">{w.project.name}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <Button icon={<ListPlus />} onClick={() => void loadMine()}>
        {t("time.addMine")}
      </Button>
    </div>
  );
}

function Sheet({ week, onWeek }: { week: TimeWeek; onWeek: (start: string) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const m = useTimeMutations();
  const [added, setAdded] = useState<WorkPackageRef[]>([]);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const inputs = useRef(new Map<string, HTMLInputElement>());
  // a new week starts clean
  useEffect(() => (setAdded([]), setDraft({}), setErrors({})), [week.start]);

  const server = useMemo(() => {
    const out = new Map<string, { hours: number; entries: number }>();
    for (const r of week.rows) for (const c of r.cells) out.set(key(r.workPackage.number, c.date), { hours: c.hours, entries: c.entries });
    return out;
  }, [week]);
  const rows = useMemo(() => {
    const known = new Set(week.rows.map((r) => r.workPackage.number));
    return [...week.rows.map((r) => ({ wp: r.workPackage, canLog: r.canLog })), ...added.filter((w) => !known.has(w.number)).map((w) => ({ wp: w, canLog: true }))];
  }, [week.rows, added]);
  const taken = useMemo(() => new Set(rows.map((r) => r.wp.number)), [rows]);
  const dates = week.days.map((d) => d.date);

  const valueOf = (n: number, d: string) => (key(n, d) in draft ? parseHours(draft[key(n, d)]) : (server.get(key(n, d))?.hours ?? 0));
  const textOf = (n: number, d: string) => (key(n, d) in draft ? draft[key(n, d)] : show(server.get(key(n, d))?.hours ?? 0));
  const changed = Object.entries(draft).filter(([k, v]) => {
    const p = parseHours(v);
    return !Number.isFinite(p) || round2(p) !== round2(server.get(k)?.hours ?? 0);
  });
  const invalid = changed.some(([, v]) => !valid(parseHours(v)));
  const dirty = changed.length > 0;
  useUnsavedWarning(dirty);

  const dayTotal = (d: string) => rows.reduce((s, r) => s + (valid(valueOf(r.wp.number, d)) ? valueOf(r.wp.number, d) : 0), 0);
  const rowTotal = (n: number) => dates.reduce((s, d) => s + (valid(valueOf(n, d)) ? valueOf(n, d) : 0), 0);
  const weekTotal = dates.reduce((s, d) => s + dayTotal(d), 0);

  const save = async () => {
    if (!dirty || invalid || m.saveWeek.isPending) return;
    const cells = changed.map(([k, v]) => {
      const [n, date] = k.split("|");
      return { workPackageNumber: Number(n), date, hours: round2(parseHours(v)) };
    });
    try {
      await m.saveWeek.mutateAsync({ start: week.start, cells });
      setDraft({});
      setErrors({});
      toast({ tone: "success", title: t("time.saved") });
    } catch (e) {
      if (!isApiError(e)) throw e;
      const next: Record<string, string> = {};
      for (const f of e.fieldErrors) {
        const cell = /^#(\d+) (\d{4}-\d{2}-\d{2})$/.exec(f.field);
        next[cell ? key(Number(cell[1]), cell[2]) : f.field] = f.message;
      }
      setErrors(next);
      toast({ tone: "danger", title: e.title, description: e.message });
    }
  };

  const focus = (row: number, col: number) => {
    const r = rows[Math.max(0, Math.min(rows.length - 1, row))];
    const d = dates[Math.max(0, Math.min(dates.length - 1, col))];
    const el = r && inputs.current.get(key(r.wp.number, d));
    if (el) (el.focus(), el.select());
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>, row: number, col: number) => {
    const el = e.currentTarget;
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") return (e.preventDefault(), void save());
    if (e.key === "ArrowUp") return (e.preventDefault(), focus(row - 1, col));
    if (e.key === "ArrowDown" || e.key === "Enter") return (e.preventDefault(), focus(row + 1, col));
    if (e.key === "ArrowLeft" && el.selectionStart === 0 && el.selectionEnd === 0) return (e.preventDefault(), focus(row, col - 1));
    if (e.key === "ArrowRight" && el.selectionStart === el.value.length) return (e.preventDefault(), focus(row, col + 1));
  };

  const go = (start: string) => {
    if (dirty && !window.confirm(t("time.leaveWarning"))) return;
    onWeek(start);
  };
  const thisWeek = todayIso();
  const end = addDays(week.start, 6);
  const th = "px-2 py-2 text-xs font-medium text-fg-muted";

  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div className="inline-flex items-center gap-1">
          <Button iconOnly aria-label={t("time.prevWeek")} icon={<ChevronLeft />} onClick={() => go(addDays(week.start, -7))} />
          <Button iconOnly aria-label={t("time.nextWeek")} icon={<ChevronRight />} onClick={() => go(addDays(week.start, 7))} />
        </div>
        <Button variant="ghost" onClick={() => go(thisWeek)} disabled={thisWeek >= week.start && thisWeek <= end}>
          {t("time.thisWeek")}
        </Button>
        <h2 className="text-base font-semibold tabular-nums" aria-live="polite">
          {t("time.weekOf", { from: formatDate(week.start), to: formatDate(end) })}
        </h2>
      </div>
      <p className="mb-3 text-sm text-fg-muted">{t("time.sheetHint")}</p>

      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full min-w-[720px] border-collapse text-sm">
          <caption className="sr-only">{t("time.weekOf", { from: formatDate(week.start), to: formatDate(end) })}</caption>
          <thead>
            <tr className="border-b border-border bg-surface-sunken text-left">
              <th scope="col" className={cn(th, "w-[34%] pl-3")}>{t("time.workPackage")}</th>
              {week.days.map((d) => (
                <th key={d.date} scope="col" className={cn(th, "text-center", !d.working && "bg-surface-hover")} title={d.holiday ?? undefined}>
                  <span className="block">{weekday(d.date)}</span>
                  <span className="block tabular-nums">{formatDate(d.date).slice(5)}</span>
                  {d.holiday && <span className="block truncate text-[11px] text-danger">{d.holiday}</span>}
                </th>
              ))}
              <th scope="col" className={cn(th, "w-14 whitespace-nowrap pr-3 text-right")}>{t("time.total")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={9} className="px-3 py-6 text-center text-fg-muted">{t("time.noRows")}</td>
              </tr>
            )}
            {rows.map((r, ri) => (
              <tr key={r.wp.id} className="border-b border-border-muted">
                <th scope="row" className="py-1.5 pr-2 pl-3 text-left font-normal">
                  <span className="flex min-w-0 items-center gap-2">
                    <TypeLabel name={r.wp.type.name} color={r.wp.type.color} />
                    <Link to={`/projects/${r.wp.project.identifier}/work-packages/${r.wp.number}/activity`} className="shrink-0 tabular-nums text-accent hover:underline">
                      #{r.wp.number}
                    </Link>
                    <span className="truncate" title={r.wp.subject}>{r.wp.subject}</span>
                  </span>
                  <span className="block truncate pl-0.5 text-xs text-fg-muted">{r.wp.project.name}</span>
                </th>
                {week.days.map((d, ci) => {
                  const k = key(r.wp.number, d.date);
                  const many = (server.get(k)?.entries ?? 0) > 1;
                  const locked = !r.canLog;
                  const text = textOf(r.wp.number, d.date);
                  const bad = (k in draft && !valid(parseHours(draft[k]))) || !!errors[k];
                  const why = errors[k] ?? (bad ? t("time.invalid") : many ? t("time.cellMany", { count: server.get(k)!.entries }) : locked ? t("time.cellLocked") : undefined);
                  return (
                    <td key={d.date} className={cn("px-1 py-1", !d.working && "bg-surface-sunken/60")}>
                      <input
                        ref={(el) => void (el ? inputs.current.set(k, el) : inputs.current.delete(k))}
                        inputMode="decimal"
                        autoComplete="off"
                        value={text}
                        readOnly={many || locked}
                        aria-invalid={bad || undefined}
                        aria-label={t("time.cellLabel", { number: r.wp.number, subject: r.wp.subject, day: `${weekday(d.date)} ${formatDate(d.date)}` })}
                        title={why}
                        onChange={(e) => setDraft((x) => ({ ...x, [k]: e.target.value }))}
                        onFocus={(e) => e.currentTarget.select()}
                        onKeyDown={(e) => onKey(e, ri, ci)}
                        className={cn(
                          "h-8 w-full min-w-12 rounded-sm border border-border-input bg-bg px-1.5 text-center tabular-nums text-fg focus-visible:outline-2 focus-visible:outline-focus-ring",
                          (many || locked) && "cursor-not-allowed bg-surface-sunken text-fg-muted",
                          k in draft && !bad && "border-accent",
                          bad && "border-danger",
                        )}
                      />
                    </td>
                  );
                })}
                <td className="pr-3 text-right tabular-nums font-medium">{show(rowTotal(r.wp.number)) || "0"}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="bg-surface-sunken">
              <th scope="row" className="py-2 pl-3 text-left text-xs font-semibold">{t("time.total")}</th>
              {week.days.map((d) => {
                const total = dayTotal(d.date);
                const over = total > week.hoursPerDay;
                const err = errors[d.date];
                return (
                  <td key={d.date} className={cn("px-1 py-2 text-center tabular-nums text-xs font-semibold", (over || err) && "text-danger")}>
                    <span aria-label={t("time.dayTotal", { day: `${weekday(d.date)} ${formatDate(d.date)}` }) + ` ${round2(total)}`}>{round2(total)}</span>
                    {(over || err) && (
                      <span className="mt-0.5 flex items-center justify-center gap-0.5 font-normal" title={err ?? t("time.overDay", { hours: week.hoursPerDay })}>
                        <AlertTriangle className="size-3" aria-hidden="true" />
                        <span className="sr-only">{err ?? t("time.overDay", { hours: week.hoursPerDay })}</span>
                      </span>
                    )}
                  </td>
                );
              })}
              <td className="pr-3 text-right tabular-nums text-sm font-semibold">{round2(weekTotal)}</td>
            </tr>
          </tfoot>
        </table>
      </div>

      <AddRow taken={taken} onAdd={(ws) => setAdded((a) => [...a, ...ws.filter((w) => !a.some((x) => x.number === w.number))])} />

      {Object.keys(errors).length > 0 && (
        <div className="mt-3">
          <Banner tone="danger">
            <ul className="list-disc pl-5">
              {Object.entries(errors).map(([k, msg]) => (
                <li key={k}>{msg}</li>
              ))}
            </ul>
          </Banner>
        </div>
      )}

      <div className={cn("sticky bottom-0 mt-4 flex items-center justify-end gap-2 border-t border-border bg-bg py-3", !dirty && "invisible")} aria-hidden={!dirty}>
        <span className="mr-auto text-sm text-fg-muted">{t("time.unsaved")}</span>
        <Button icon={<RotateCcw />} onClick={() => (setDraft({}), setErrors({}))} disabled={!dirty}>
          {t("time.discard")}
        </Button>
        <Button variant="primary" icon={<Save />} onClick={() => void save()} disabled={!dirty || invalid} loading={m.saveWeek.isPending}>
          {t("time.save")}
        </Button>
      </div>
    </>
  );
}

export function TimesheetPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("time.sheetTitle"));
  const [params, setParams] = useSearchParams();
  const start = params.get("week") ?? undefined;
  const week = useTimeWeek(start);
  return (
    <PageBody>
      <PageHeader title={t("time.sheetTitle")} />
      {week.isPending ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-9 w-full" />
          ))}
        </div>
      ) : week.isError ? (
        <ErrorState error={week.error} onRetry={() => week.refetch()} />
      ) : week.data ? (
        <Sheet week={week.data} onWeek={(s) => setParams((p) => (p.set("week", s), p))} />
      ) : (
        <EmptyState icon={<Clock />} title={t("time.noRows")} />
      )}
    </PageBody>
  );
}
