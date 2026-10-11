// 공수 보고서: the time logged in a project over a period, summed by person, work package, activity or day, with each
// row's share of the total and the entries as an Excel file. Only entries I may see count (time.view: everyone's).
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { BarChart3, Download } from "lucide-react";
import { TIME_GROUPS, type TimeGroupBy } from "@/api";
import { api } from "@/api";
import { useTimeReport } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Segmented } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { addDays, todayIso } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { useCurrentProject } from "@/app/project";

function monthRange(today: string): [string, string] {
  const from = `${today.slice(0, 8)}01`;
  const d = new Date(`${from}T00:00:00Z`);
  d.setUTCMonth(d.getUTCMonth() + 1);
  return [from, addDays(d.toISOString().slice(0, 10), -1)];
}
const round2 = (n: number) => Math.round(n * 100) / 100;

export function ProjectTimeReportPage() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const toast = useToast();
  useDocumentTitle(`${t("time.reportTitle")} · ${project.name}`);
  const [initialFrom, initialTo] = monthRange(todayIso());
  const [from, setFrom] = useState(initialFrom);
  const [to, setTo] = useState(initialTo);
  const [groupBy, setGroupBy] = useState<TimeGroupBy>("user");
  const okRange = !!from && !!to && from <= to;
  const report = useTimeReport({ project: project.identifier, from: okRange ? from : undefined, to: okRange ? to : undefined, groupBy });
  const [downloading, setDownloading] = useState(false);

  const download = async () => {
    setDownloading(true);
    try {
      const { blob, fileName } = await api.time.exportFile({ project: project.identifier, from, to });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = fileName;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      toast({ tone: "danger", title: e instanceof Error ? e.message : t("app.errorTitle") });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <PageBody>
      <PageHeader
        title={t("time.reportTitle")}
        breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("time.reportTitle") }]}
        actions={
          <Button icon={<Download />} onClick={() => void download()} loading={downloading} disabled={!okRange}>
            {t("time.exportXlsx")}
          </Button>
        }
      />
      <p className="mb-4 text-sm text-fg-muted">{t("time.reportHint")}</p>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Field label={t("time.from")}>
          <Input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label={t("time.to")} error={okRange ? undefined : t("time.invalid")}>
          <Input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
        </Field>
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-medium text-fg" id="time-group-label">{t("time.groupBy")}</span>
          <Segmented
            aria-label={t("time.groupBy")}
            value={groupBy}
            onValueChange={(v) => setGroupBy(v as TimeGroupBy)}
            options={TIME_GROUPS.filter((g) => g !== "project").map((g) => ({ value: g, label: t(`time.groups.${g}`) }))}
          />
        </div>
      </div>

      {report.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : report.isError ? (
        <ErrorState error={report.error} onRetry={() => report.refetch()} />
      ) : report.data.rows.length === 0 ? (
        <div className="rounded-md border border-border">
          <EmptyState icon={<BarChart3 />} title={t("time.reportEmpty")} />
        </div>
      ) : (
        <div className={cn("overflow-x-auto rounded-md border border-border", report.isPlaceholderData && "opacity-60")}>
          <table className="w-full min-w-[480px] border-collapse text-sm">
            <caption className="sr-only">{`${t("time.reportTitle")} · ${t(`time.groups.${groupBy}`)}`}</caption>
            <thead>
              <tr className="border-b border-border bg-surface-sunken text-left text-xs text-fg-muted">
                <th scope="col" className="px-3 py-2 font-medium">{t(`time.groups.${groupBy}`)}</th>
                <th scope="col" className="w-24 px-3 py-2 text-right font-medium">{t("time.hours")}</th>
                <th scope="col" className="w-1/3 px-3 py-2 font-medium">{t("time.share")}</th>
              </tr>
            </thead>
            <tbody>
              {report.data.rows.map((r) => {
                const share = report.data.total ? r.hours / report.data.total : 0;
                return (
                  <tr key={r.key} className="border-b border-border-muted">
                    <th scope="row" className="px-3 py-2 text-left font-normal">{r.label}</th>
                    <td className="px-3 py-2 text-right tabular-nums">{round2(r.hours)}</td>
                    <td className="px-3 py-2">
                      <span className="flex items-center gap-2">
                        <span className="h-2 flex-1 rounded-full bg-surface-sunken" aria-hidden="true">
                          <span className="block h-2 rounded-full bg-accent" style={{ width: `${Math.max(2, share * 100)}%` }} />
                        </span>
                        <span className="w-12 text-right text-xs tabular-nums text-fg-muted">{Math.round(share * 100)}%</span>
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr className="bg-surface-sunken font-semibold">
                <th scope="row" className="px-3 py-2 text-left">{t("time.total")}</th>
                <td className="px-3 py-2 text-right tabular-nums">{round2(report.data.total)}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </PageBody>
  );
}
