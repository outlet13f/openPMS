// 프로젝트 설정 › 환경: the project's deployment environments in order (개발 → 스테이징 → 운영) with the names the tools
// send (aliases, case ignored), and the deployments no environment took. cicd.view sees both lists; cicd.manage adds,
// edits, reorders (up/down buttons), deletes (an environment with deployment history is hidden instead), shows a hidden
// one again and adds an unassigned name as an alias of the environment picked (its deployments then join it).
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDown, ArrowUp, Eye, Layers, MoreHorizontal, Pencil, Plus, Tag, Trash2 } from "lucide-react";
import { isApiError, type CicdEnvironment, type CicdUnassigned } from "@/api";
import { useCicdEnvironmentMutations, useCicdEnvironments } from "@/api/admin-hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState } from "@/app/page";
import { useCurrentProject } from "@/app/project";
import { AdminTable, ConfirmDialog, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast, YesNo } from "@/features/admin/common";
import { formatDateTime, relativeTime } from "@/lib/dates";

/** "prod, Production ,," → ["prod", "Production"] (the server lower-cases and checks) */
const splitAliases = (s: string) =>
  s
    .split(",")
    .map((a) => a.trim())
    .filter(Boolean);

function EnvironmentDialog({ project, editing, onClose }: { project: string; editing: CicdEnvironment | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useCicdEnvironmentMutations(project);
  const runToast = useRunWithToast();
  const [name, setName] = useState(editing?.name ?? "");
  const [production, setProduction] = useState(editing?.production ?? false);
  const [aliases, setAliases] = useState(editing?.aliases.join(", ") ?? "");
  const mutation = editing ? m.update : m.create;
  const save = async () => {
    const input = { name: name.trim(), production, aliases: splitAliases(aliases) };
    const done = editing
      ? await runToast(m.update.mutateAsync({ id: editing.id, patch: input }), t("cicd.environments.saved"))
      : await runToast(m.create.mutateAsync(input), t("cicd.environments.created"));
    if (done) onClose();
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={editing ? t("cicd.environments.edit") : t("cicd.environments.add")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={mutation.isPending} disabled={!name.trim()} onClick={() => void save()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) void save();
        }}
      >
        <Field label={t("cicd.environments.name")} required error={fieldErrorOf(mutation.error, "name")}>
          <Input data-autofocus value={name} maxLength={50} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Checkbox label={t("cicd.environments.production")} description={t("cicd.environments.productionHint")} checked={production} onCheckedChange={(c) => setProduction(c === true)} />
        <Field label={t("cicd.environments.aliases")} hint={t("cicd.environments.aliasesHint")} error={fieldErrorOf(mutation.error, "aliases")}>
          <Input value={aliases} maxLength={2100} spellCheck={false} onChange={(e) => setAliases(e.target.value)} />
        </Field>
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}

/** One unassigned name: pick an environment, add the name to its aliases. */
function UnassignedRow({ project, row, environments, canManage }: { project: string; row: CicdUnassigned; environments: CicdEnvironment[]; canManage: boolean }) {
  const { t } = useTranslation();
  const m = useCicdEnvironmentMutations(project);
  const runToast = useRunWithToast();
  const [envId, setEnvId] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  // a 422 (the name is another environment's, too many aliases, …) shows under this row's picker; runToast leaves field errors to the form
  const [error, setError] = useState<string | null>(null);
  const errorId = useId();
  const env = environments.find((e) => e.id === envId);
  const add = async () => {
    if (!env) return;
    setPending(true);
    setError(null);
    const saving = m.update.mutateAsync({ id: env.id, patch: { aliases: [...env.aliases, row.name] } }).catch((e: unknown) => {
      const message = isApiError(e) ? e.fieldErrors[0]?.message : undefined;
      if (message) setError(message);
      throw e;
    });
    await runToast(saving, t("cicd.environments.aliasAdded", { name: row.name, env: env.name }));
    setPending(false);
  };
  return (
    <tr className={tbodyRow}>
      <td className="px-3 py-1.5 font-mono text-fg">{row.name}</td>
      <td className="px-3 text-right tabular-nums">{t("cicd.environments.countValue", { count: row.count })}</td>
      <td className="px-3 text-fg-muted">
        {row.lastAt && (
          <time dateTime={row.lastAt} title={formatDateTime(row.lastAt)}>
            {relativeTime(row.lastAt)}
          </time>
        )}
      </td>
      {canManage && (
        <td className="px-2 py-1.5">
          <div className="flex flex-wrap items-center justify-end gap-2">
            <Select
              size="sm"
              className="w-40"
              aria-label={t("cicd.environments.pickFor", { name: row.name })}
              value={envId ?? ""}
              placeholder={t("cicd.environments.pick")}
              aria-invalid={!!error}
              aria-describedby={error ? errorId : undefined}
              onValueChange={(v) => (setEnvId(v || null), setError(null))}
              options={environments.filter((e) => !e.hidden).map((e) => ({ value: e.id, label: e.name, textValue: e.name }))}
            />
            <Button size="sm" icon={<Tag />} disabled={!env} loading={pending} aria-label={t("cicd.environments.addAliasName", { name: row.name })} onClick={() => void add()}>
              {t("cicd.environments.addAlias")}
            </Button>
          </div>
          {error && (
            <p id={errorId} role="alert" className="mt-1 text-right text-xs text-danger">
              {error}
            </p>
          )}
        </td>
      )}
    </tr>
  );
}

export function EnvironmentsSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const q = useCicdEnvironments(project.identifier);
  const m = useCicdEnvironmentMutations(project.identifier);
  const runToast = useRunWithToast();
  const toast = useToast();
  const [dialog, setDialog] = useState<{ editing: CicdEnvironment | null } | null>(null);
  const [removing, setRemoving] = useState<CicdEnvironment | null>(null);
  // the edit controls follow the server's answer (cicd.manage)
  const canManage = q.data?.canManage === true;
  const title = t("settings.sections.environments");

  const move = (envs: CicdEnvironment[], i: number, to: number) => {
    const ids = envs.map((e) => e.id);
    const [id] = ids.splice(i, 1);
    ids.splice(to, 0, id);
    void runToast(m.reorder.mutateAsync(ids), t("cicd.environments.reordered"));
  };

  return (
    <section aria-labelledby="envs-h" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-3xl">
          <h2 id="envs-h" className="text-lg text-fg">
            {title}
          </h2>
          <p className="mt-1 text-sm text-fg-muted">{t("cicd.environments.hint")}</p>
        </div>
        {canManage && (
          <Button variant="primary" icon={<Plus />} onClick={() => setDialog({ editing: null })}>
            {t("cicd.environments.add")}
          </Button>
        )}
      </div>
      {q.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : (
        <>
          {q.data.environments.length === 0 ? (
            <div className="rounded-md border border-border">
              <EmptyState compact icon={<Layers />} title={t("cicd.environments.none")} />
            </div>
          ) : (
            <AdminTable minWidth={620}>
              <caption className="sr-only">{t("cicd.environments.list")}</caption>
              <thead>
                <tr className={theadRow}>
                  <th scope="col" className={th}>{t("cicd.environments.cols.name")}</th>
                  {canManage && <th scope="col" className={`${th} w-24`}>{t("cicd.environments.cols.order")}</th>}
                  <th scope="col" className={th}>{t("cicd.environments.cols.production")}</th>
                  <th scope="col" className={th}>{t("cicd.environments.cols.aliases")}</th>
                  {canManage && (
                    <th scope="col" className={`${th} w-48`}>
                      <span className="sr-only">{t("app.more")}</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {q.data.environments.map((e, i, all) => (
                  <tr key={e.id} className={tbodyRow}>
                    <td className="px-3 py-1.5">
                      <span className={e.hidden ? "font-medium text-fg-muted" : "font-medium text-fg"}>{e.name}</span>
                      {e.hidden && (
                        <Badge tone="neutral" className="ml-2">
                          {t("cicd.environments.hidden")}
                        </Badge>
                      )}
                    </td>
                    {canManage && (
                      <td className="px-2">
                        <div className="flex gap-0.5">
                          <Button
                            variant="ghost"
                            size="sm"
                            iconOnly
                            icon={<ArrowUp />}
                            aria-label={t("cicd.environments.moveUp", { name: e.name })}
                            disabled={i === 0 || m.reorder.isPending}
                            onClick={() => move(all, i, i - 1)}
                          />
                          <Button
                            variant="ghost"
                            size="sm"
                            iconOnly
                            icon={<ArrowDown />}
                            aria-label={t("cicd.environments.moveDown", { name: e.name })}
                            disabled={i === all.length - 1 || m.reorder.isPending}
                            onClick={() => move(all, i, i + 1)}
                          />
                        </div>
                      </td>
                    )}
                    <td className="px-3">
                      <YesNo value={e.production} />
                    </td>
                    <td className="px-3 font-mono text-xs text-fg-muted">{e.aliases.join(", ")}</td>
                    {canManage && (
                      <td className="px-2">
                        <div className="flex items-center justify-end gap-1">
                          {e.hidden && (
                            <Button
                              size="sm"
                              variant="ghost"
                              icon={<Eye />}
                              aria-label={t("cicd.environments.showName", { name: e.name })}
                              onClick={() => void runToast(m.update.mutateAsync({ id: e.id, patch: { hidden: false } }), t("cicd.environments.shown"))}
                            >
                              {t("cicd.environments.show")}
                            </Button>
                          )}
                          <Menu
                            align="end"
                            trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: e.name })} icon={<MoreHorizontal />} />}
                            items={[
                              { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: e }) },
                              { kind: "separator" },
                              { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(e) },
                            ]}
                          />
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </AdminTable>
          )}
          <section aria-labelledby="envs-unassigned-h" className="flex flex-col gap-2">
            <div>
              <h3 id="envs-unassigned-h" className="text-sm font-semibold text-fg">
                {t("cicd.environments.unassigned")}
              </h3>
              <p className="mt-1 text-sm text-fg-muted">{t("cicd.environments.unassignedHint")}</p>
            </div>
            {q.data.unassigned.length === 0 ? (
              <p className="text-sm text-fg-muted">{t("cicd.environments.unassignedNone")}</p>
            ) : (
              <AdminTable minWidth={canManage ? 680 : 480}>
                <caption className="sr-only">{t("cicd.environments.unassigned")}</caption>
                <thead>
                  <tr className={theadRow}>
                    <th scope="col" className={th}>{t("cicd.environments.unassignedCols.name")}</th>
                    <th scope="col" className={`${th} text-right`}>{t("cicd.environments.unassignedCols.count")}</th>
                    <th scope="col" className={th}>{t("cicd.environments.unassignedCols.lastAt")}</th>
                    {canManage && <th scope="col" className={`${th} text-right`}>{t("cicd.environments.unassignedCols.assign")}</th>}
                  </tr>
                </thead>
                <tbody>
                  {q.data.unassigned.map((u) => (
                    <UnassignedRow key={u.name} project={project.identifier} row={u} environments={q.data.environments} canManage={canManage} />
                  ))}
                </tbody>
              </AdminTable>
            )}
          </section>
        </>
      )}
      {dialog && <EnvironmentDialog project={project.identifier} editing={dialog.editing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("cicd.environments.deleteTitle", { name: removing?.name ?? "" })}
        body={t("cicd.environments.deleteBody")}
        confirm={t("app.delete")}
        pending={m.remove.isPending}
        onConfirm={async () => {
          if (removing) {
            const r = await runToast(m.remove.mutateAsync(removing.id));
            if (r) toast({ tone: "success", title: r.deleted ? t("cicd.environments.deleted") : t("cicd.environments.hiddenInstead") });
          }
          setRemoving(null);
        }}
      />
    </section>
  );
}
