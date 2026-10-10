// S12 내보내기: the current list (its filters and sort, every page) as CSV or Excel. Columns start as the
// list's own; they can be reordered, removed and added. Needs wp.export. Before downloading, the server says how many
// rows the file will have and how many matching items it leaves out (no export right there, or seen only through a share).
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { ArrowDown, ArrowUp, Download, Plus, X } from "lucide-react";
import { api, isApiError, type ColumnKey, type ExportFormat, type QuerySpec } from "@/api";
import { Button } from "@/components/ui/button";
import { Checkbox, RadioCards } from "@/components/ui/choice";
import { Banner, useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";

export function ExportDialog({
  open,
  onOpenChange,
  spec,
  projectId,
  title,
  allColumns,
  columnLabel,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  spec: QuerySpec;
  projectId?: string;
  title: string;
  allColumns: ColumnKey[];
  columnLabel: (c: ColumnKey) => string;
}) {
  const { t } = useTranslation();
  const toast = useToast();
  const [format, setFormat] = useState<ExportFormat>("xlsx");
  const [columns, setColumns] = useState<ColumnKey[]>(spec.columns);
  const [adding, setAdding] = useState("");
  const [description, setDescription] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const counted = useQuery({ queryKey: ["export-count", projectId ?? null, spec], queryFn: () => api.workPackages.exportCount({ spec, projectId }), staleTime: 0 });
  const c = counted.data;
  const tooMany = !!c && c.total > c.limit;
  const nothing = !!c && c.total > 0 && c.count === 0 && !tooMany;
  const addable = allColumns.filter((c) => !columns.includes(c));
  const move = (i: number, by: -1 | 1) => setColumns((cs) => {
    const next = [...cs];
    [next[i], next[i + by]] = [next[i + by], next[i]];
    return next;
  });

  const run = async () => {
    if (!columns.length) return setError(t("export.noColumns"));
    setBusy(true);
    setError(null);
    try {
      const { blob, fileName } = await api.workPackages.export({ spec, projectId, format, columns, includeDescription: description, title });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = fileName;
      document.body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast({ tone: "success", title: t("export.done", { name: fileName }) });
      onOpenChange(false);
    } catch (e) {
      setError(isApiError(e) ? e.message : t("app.errorBody"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="lg"
      title={t("export.title")}
      description={title}
      footer={
        <>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button variant="primary" icon={<Download />} loading={busy} disabled={!c || tooMany || nothing} onClick={() => void run()}>
            {t("export.submit")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-5">
        {counted.isPending ? (
          <Skeleton className="h-5 w-48" />
        ) : counted.isError ? (
          <Banner tone="danger">{isApiError(counted.error) ? counted.error.message : t("app.errorBody")}</Banner>
        ) : tooMany ? (
          <Banner tone="warning">{t("export.tooMany", { count: c!.total, limit: c!.limit.toLocaleString() })}</Banner>
        ) : nothing ? (
          <Banner tone="warning" title={t("export.nothingTitle")}>{t("export.nothingBody", { count: c!.total })}</Banner>
        ) : (
          <div className="flex flex-col gap-1">
            <p className="text-sm text-fg-muted">{t("export.count", { count: c!.count })}</p>
            {c!.excluded > 0 && <Banner tone="info">{t("export.excluded", { count: c!.excluded })}</Banner>}
          </div>
        )}
        {error && <Banner tone="danger">{error}</Banner>}
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-fg">{t("export.format")}</h3>
          <RadioCards
            aria-label={t("export.format")}
            value={format}
            onValueChange={(v) => setFormat(v as ExportFormat)}
            options={(["xlsx", "csv"] as const).map((f) => ({ value: f, title: t(`export.formats.${f}`), description: t(`export.formatHints.${f}`) }))}
          />
        </div>
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-fg">{t("export.columns")}</h3>
          <p className="text-xs text-fg-muted">{t("export.columnsHint")}</p>
          <ol aria-label={t("export.columns")} className="flex flex-col rounded-md border border-border">
            {columns.map((c, i) => {
              const name = columnLabel(c);
              return (
                <li key={c} className="flex items-center gap-2 border-b border-border-muted px-3 py-1.5 last:border-b-0">
                  <span className="w-6 text-xs text-fg-muted tabular-nums">{i + 1}</span>
                  <span className="min-w-0 flex-1 truncate text-sm text-fg">{name}</span>
                  <Button variant="ghost" size="sm" iconOnly icon={<ArrowUp />} aria-label={t("export.moveUp", { name })} disabled={i === 0} onClick={() => move(i, -1)} />
                  <Button variant="ghost" size="sm" iconOnly icon={<ArrowDown />} aria-label={t("export.moveDown", { name })} disabled={i === columns.length - 1} onClick={() => move(i, 1)} />
                  <Button variant="ghost" size="sm" iconOnly icon={<X />} aria-label={t("export.remove", { name })} onClick={() => setColumns((cs) => cs.filter((x) => x !== c))} />
                </li>
              );
            })}
          </ol>
          {addable.length > 0 && (
            <div className="flex items-center gap-2">
              <Select size="sm" className="w-56" aria-label={t("export.addColumn")} placeholder={t("export.addColumn")} value={adding} onValueChange={setAdding} options={addable.map((c) => ({ value: c, label: columnLabel(c) }))} />
              <Button size="sm" icon={<Plus />} disabled={!adding} onClick={() => (setColumns((cs) => [...cs, adding as ColumnKey]), setAdding(""))}>
                {t("export.add")}
              </Button>
            </div>
          )}
        </div>
        <Checkbox label={t("export.includeDescription")} checked={description} onCheckedChange={(c) => setDescription(c === true)} />
      </div>
    </Dialog>
  );
}
