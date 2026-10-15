// 프로젝트 설정 › 서비스 데스크: SLA policies (type × priority → calendar and targets) and the 3×3 priority matrix
// (impact × urgency → priority), saved together. Read-only without service.manage.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Plus, Save, Trash2 } from "lucide-react";
import type { Level, MatrixCell, Project, ServiceDeskSettings } from "@/api";
import { useFilterOptions, useServiceCalendars, useServiceDeskMutations, useServiceDeskSettings } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Banner } from "@/components/ui/feedback";
import { Input } from "@/components/ui/field";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { AdminTable, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";

/** 장애 and 서비스 요청, the only types a policy may name (server ItsmIds; the fake data layer uses readable ids). */
export const TICKET_TYPE_IDS = new Set(["2dcd4691-d4a9-5bf0-8908-e4740b42616e", "a3af2ccf-05c6-5b1c-84bc-4d815791e4eb", "t-incident", "t-service-request"]);
const LEVELS: Level[] = [1, 2, 3];

type Row = { key: number; typeId: string; priorityId: string; calendarId: string; firstResponseMinutes: string; resolveMinutes: string };

/** A message under a table cell, tied to its control by id. */
function CellError({ id, message }: { id: string; message?: string }) {
  return message ? (
    <p id={id} className="mt-1 text-xs text-danger">
      {message}
    </p>
  ) : null;
}

function Editor({ project, settings }: { project: Project; settings: ServiceDeskSettings }) {
  const { t } = useTranslation();
  const options = useFilterOptions(project.id).data;
  const calendars = useServiceCalendars().data ?? [];
  const m = useServiceDeskMutations(project.identifier);
  const runToast = useRunWithToast();
  const editable = can(project, "service.manage");
  const [rows, setRows] = useState<Row[]>(() =>
    settings.policies.map((p, i) => ({
      key: i,
      typeId: p.typeId,
      priorityId: p.priorityId,
      calendarId: p.calendarId,
      firstResponseMinutes: String(p.firstResponseMinutes),
      resolveMinutes: String(p.resolveMinutes),
    })),
  );
  const [nextKey, setNextKey] = useState(settings.policies.length);
  const [matrix, setMatrix] = useState<Record<string, string>>(() => Object.fromEntries(settings.matrix.map((c) => [`${c.impact}/${c.urgency}`, c.priorityId])));

  const types = (options?.types ?? []).filter((x) => TICKET_TYPE_IDS.has(x.id));
  const priorities = options?.priorities ?? [];
  const typeOptions = types.map((x) => ({ value: x.id, label: x.name }));
  const priorityOptions = priorities.map((x) => ({ value: x.id, label: x.name }));
  const calendarOptions = calendars.map((c) => ({ value: c.id, label: c.name }));
  const err = (field: string) => fieldErrorOf(m.saveSettings.error, field);
  const level = (l: Level) => t(`serviceDesk.levels.${l}`);
  const cells: [Level, Level][] = LEVELS.flatMap((i) => LEVELS.map((u) => [i, u] as [Level, Level]));

  const setRow = (key: number, patch: Partial<Row>) => setRows((cur) => cur.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  const add = () => {
    setRows((cur) => [
      ...cur,
      { key: nextKey, typeId: types[0]?.id ?? "", priorityId: priorities[0]?.id ?? "", calendarId: calendars[0]?.id ?? "", firstResponseMinutes: "", resolveMinutes: "" },
    ]);
    setNextKey((k) => k + 1);
  };
  const minutes = (v: string) => (v.trim() === "" ? null : Number(v)) as number;
  const save = () => {
    const matrixCells: MatrixCell[] = cells.filter(([i, u]) => matrix[`${i}/${u}`]).map(([i, u]) => ({ impact: i, urgency: u, priorityId: matrix[`${i}/${u}`] }));
    void runToast(
      m.saveSettings.mutateAsync({
        policies: rows.map((r) => ({
          typeId: r.typeId,
          priorityId: r.priorityId,
          calendarId: r.calendarId,
          firstResponseMinutes: minutes(r.firstResponseMinutes),
          resolveMinutes: minutes(r.resolveMinutes),
        })),
        matrix: matrixCells,
      }),
      t("serviceDesk.settings.saved"),
    );
  };
  // matrix errors come back by the index of the cell sent, which skips empty cells
  const sentIndex = (i: Level, u: Level) => cells.filter(([a, b]) => matrix[`${a}/${b}`]).findIndex(([a, b]) => a === i && b === u);

  return (
    <div className="flex flex-col gap-6">
      {!editable && <Banner tone="info">{t("serviceDesk.settings.readOnly")}</Banner>}

      <section aria-labelledby="sd-policies-h" className="flex flex-col gap-3">
        <div>
          <h3 id="sd-policies-h" className="text-base font-semibold text-fg">
            {t("serviceDesk.settings.policies")}
          </h3>
          <p className="mt-1 text-sm text-fg-muted">{t("serviceDesk.settings.hint")}</p>
        </div>
        {rows.length === 0 ? (
          <p className="text-sm text-fg-muted">{t("serviceDesk.settings.noPolicies")}</p>
        ) : (
          <AdminTable minWidth={760}>
            <thead>
              <tr className={theadRow}>
                <th className={th}>{t("serviceDesk.settings.type")}</th>
                <th className={th}>{t("serviceDesk.settings.priority")}</th>
                <th className={th}>{t("serviceDesk.settings.calendar")}</th>
                <th className={th}>{t("serviceDesk.settings.firstResponseMinutes")}</th>
                <th className={th}>{t("serviceDesk.settings.resolveMinutes")}</th>
                {editable && (
                  <th className={th}>
                    <span className="sr-only">{t("app.delete")}</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => {
                const n = i + 1;
                const at = `policies[${i}]`;
                const label = (col: string) => `${t("serviceDesk.settings.policyRow", { n })} ${col}`;
                const e = {
                  typeId: err(`${at}.typeId`),
                  priorityId: err(`${at}.priorityId`),
                  calendarId: err(`${at}.calendarId`),
                  firstResponseMinutes: err(`${at}.firstResponseMinutes`),
                  resolveMinutes: err(`${at}.resolveMinutes`),
                };
                const eid = (f: string) => `sd-${r.key}-${f}-err`;
                return (
                  <tr key={r.key} className={`${tbodyRow} align-top`}>
                    <td className="px-2 py-1.5">
                      <Select size="sm" disabled={!editable} aria-label={label(t("serviceDesk.settings.type"))} aria-invalid={!!e.typeId || undefined} aria-describedby={e.typeId ? eid("typeId") : undefined} value={r.typeId} onValueChange={(v) => setRow(r.key, { typeId: v })} options={typeOptions} />
                      <CellError id={eid("typeId")} message={e.typeId} />
                    </td>
                    <td className="px-2 py-1.5">
                      <Select size="sm" disabled={!editable} aria-label={label(t("serviceDesk.settings.priority"))} aria-invalid={!!e.priorityId || undefined} aria-describedby={e.priorityId ? eid("priorityId") : undefined} value={r.priorityId} onValueChange={(v) => setRow(r.key, { priorityId: v })} options={priorityOptions} />
                      <CellError id={eid("priorityId")} message={e.priorityId} />
                    </td>
                    <td className="px-2 py-1.5">
                      <Select size="sm" disabled={!editable} aria-label={label(t("serviceDesk.settings.calendar"))} aria-invalid={!!e.calendarId || undefined} aria-describedby={e.calendarId ? eid("calendarId") : undefined} value={r.calendarId} onValueChange={(v) => setRow(r.key, { calendarId: v })} options={calendarOptions} />
                      <CellError id={eid("calendarId")} message={e.calendarId} />
                    </td>
                    <td className="px-2 py-1.5">
                      <Input
                        size="sm"
                        type="number"
                        min={1}
                        className="w-24"
                        disabled={!editable}
                        aria-label={label(t("serviceDesk.settings.firstResponseMinutes"))}
                        aria-invalid={!!e.firstResponseMinutes || undefined}
                        aria-describedby={e.firstResponseMinutes ? eid("firstResponseMinutes") : undefined}
                        value={r.firstResponseMinutes}
                        onChange={(ev) => setRow(r.key, { firstResponseMinutes: ev.target.value })}
                      />
                      <CellError id={eid("firstResponseMinutes")} message={e.firstResponseMinutes} />
                    </td>
                    <td className="px-2 py-1.5">
                      <Input
                        size="sm"
                        type="number"
                        min={1}
                        className="w-24"
                        disabled={!editable}
                        aria-label={label(t("serviceDesk.settings.resolveMinutes"))}
                        aria-invalid={!!e.resolveMinutes || undefined}
                        aria-describedby={e.resolveMinutes ? eid("resolveMinutes") : undefined}
                        value={r.resolveMinutes}
                        onChange={(ev) => setRow(r.key, { resolveMinutes: ev.target.value })}
                      />
                      <CellError id={eid("resolveMinutes")} message={e.resolveMinutes} />
                    </td>
                    {editable && (
                      <td className="px-2 py-1.5">
                        <Button
                          variant="ghost"
                          size="sm"
                          iconOnly
                          icon={<Trash2 />}
                          aria-label={t("serviceDesk.settings.removePolicy", { n })}
                          onClick={() => setRows((cur) => cur.filter((x) => x.key !== r.key))}
                        />
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </AdminTable>
        )}
        {editable && (
          <div>
            <Button size="sm" icon={<Plus />} onClick={add} disabled={!types.length || !calendars.length}>
              {t("serviceDesk.settings.addPolicy")}
            </Button>
          </div>
        )}
      </section>

      <section aria-labelledby="sd-matrix-h" className="flex flex-col gap-3">
        <div>
          <h3 id="sd-matrix-h" className="text-base font-semibold text-fg">
            {t("serviceDesk.settings.matrix")}
          </h3>
          <p className="mt-1 text-sm text-fg-muted">{t("serviceDesk.settings.matrixHint")}</p>
        </div>
        <AdminTable minWidth={520}>
          <thead>
            <tr className={theadRow}>
              <th className={th}>
                <span className="sr-only">{t("serviceDesk.fields.impact")}</span>
              </th>
              {LEVELS.map((u) => (
                <th key={u} scope="col" className={th}>
                  {t("serviceDesk.settings.urgency", { level: level(u) })}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {LEVELS.map((i) => (
              <tr key={i} className={`${tbodyRow} align-top`}>
                <th scope="row" className={`${th} py-1.5`}>
                  {t("serviceDesk.settings.impact", { level: level(i) })}
                </th>
                {LEVELS.map((u) => {
                  const idx = sentIndex(i, u);
                  const message = idx >= 0 ? err(`matrix[${idx}].priorityId`) : undefined;
                  const id = `sd-matrix-${i}-${u}-err`;
                  return (
                    <td key={u} className="px-2 py-1.5">
                      <Select
                        size="sm"
                        disabled={!editable}
                        aria-label={t("serviceDesk.settings.cell", { impact: level(i), urgency: level(u) })}
                        aria-invalid={!!message || undefined}
                        aria-describedby={message ? id : undefined}
                        value={matrix[`${i}/${u}`] ?? ""}
                        placeholder={t("app.select")}
                        onValueChange={(v) => setMatrix((cur) => ({ ...cur, [`${i}/${u}`]: v }))}
                        options={priorityOptions}
                      />
                      <CellError id={id} message={message} />
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </AdminTable>
      </section>

      {editable && (
        <div>
          <Button variant="primary" icon={<Save />} loading={m.saveSettings.isPending} onClick={save}>
            {t("serviceDesk.settings.save")}
          </Button>
        </div>
      )}
    </div>
  );
}

export function ServiceDeskSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const q = useServiceDeskSettings(project.identifier);
  return (
    <section aria-label={t("serviceDesk.settings.title")} className="flex flex-col gap-4">
      <h2 className="text-lg text-fg">{t("serviceDesk.settings.title")}</h2>
      {q.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : (
        // a save (or someone else's) starts the editor again from the server's lists; a refetch of the same lists does not
        <Editor key={JSON.stringify(q.data)} project={project} settings={q.data} />
      )}
    </section>
  );
}
