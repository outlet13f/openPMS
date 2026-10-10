// S32 관리: 진행률 추적 (architecture.md §3.4) — how % complete is worked out, whether a closed status means done,
// and how a parent's total % is made. Saving recalculates every work package.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Save } from "lucide-react";
import type { ProgressSettings } from "@/api";
import { useProgressSettings, useSaveProgressSettings } from "@/api/admin-hooks";
import { Button } from "@/components/ui/button";
import { Checkbox, RadioCards } from "@/components/ui/choice";
import { Banner, useToast } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminGuard } from "./index";

function Form({ saved }: { saved: ProgressSettings }) {
  const { t } = useTranslation();
  const toast = useToast();
  const save = useSaveProgressSettings();
  const [v, setV] = useState(saved);
  const dirty = JSON.stringify(v) !== JSON.stringify(saved);
  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <section aria-labelledby="pg-mode" className="flex flex-col gap-3">
        <h2 id="pg-mode" className="text-lg text-fg">{t("progressSettings.mode")}</h2>
        <RadioCards
          aria-label={t("progressSettings.mode")}
          value={v.mode}
          onValueChange={(m) => setV({ ...v, mode: m as ProgressSettings["mode"] })}
          options={(["work", "status"] as const).map((m) => ({ value: m, title: t(`progressSettings.modes.${m}`), description: t(`progressSettings.modeHints.${m}`) }))}
        />
        <Checkbox label={t("progressSettings.closed")} description={t("progressSettings.closedHint")} checked={v.closedSets100} onCheckedChange={(c) => setV({ ...v, closedSets100: c === true })} />
      </section>
      <section aria-labelledby="pg-totals" className="flex flex-col gap-3">
        <h2 id="pg-totals" className="text-lg text-fg">{t("progressSettings.totals")}</h2>
        <RadioCards
          aria-label={t("progressSettings.totals")}
          value={v.totals}
          onValueChange={(m) => setV({ ...v, totals: m as ProgressSettings["totals"] })}
          options={(["weighted", "simple_average"] as const).map((m) => ({ value: m, title: t(`progressSettings.totalsModes.${m}`), description: t(`progressSettings.totalsHints.${m}`) }))}
        />
      </section>
      <Banner tone="info">{t("progressSettings.recalcNote")}</Banner>
      <div>
        <Button
          variant="primary"
          icon={<Save />}
          disabled={!dirty}
          loading={save.isPending}
          onClick={() => save.mutate(v, { onSuccess: (r) => toast({ tone: "success", title: t("progressSettings.saved", { count: r.recalculated }) }) })}
        >
          {t("app.save")}
        </Button>
      </div>
    </div>
  );
}

export function AdminProgressPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.progress"));
  const q = useProgressSettings();
  return (
    <PageBody>
      <PageHeader title={t("admin.sections.progress")} breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.progress") }]} />
      <AdminGuard>
        {q.isPending ? <Skeleton className="h-64 w-full" /> : q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : <Form key={JSON.stringify(q.data)} saved={q.data} />}
      </AdminGuard>
    </PageBody>
  );
}
