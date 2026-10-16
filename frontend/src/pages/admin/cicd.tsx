// 관리 › CI/CD 연동: the company's GitLab and GitHub servers. Administrators and people with global.manage_cicd (the
// "CI/CD 연동 관리자" global role) register a connection, get its webhook address and secret once (again only by
// rotating the secret), check that the API token works and remove a connection no project uses any more.
// The API token is never shown again (the last four characters only); a changed address clears it unless a new one comes along.
// Each connection has a list of allowed projects (only those can search and attach through it): system administrators pick
// them in the edit dialog; a delegated manager sees the projects visible to them by name and the rest as a count.
import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Copy, GitMerge, KeyRound, MoreHorizontal, Pencil, Plus, PlugZap, Trash2 } from "lucide-react";
import { isApiError, type CicdConnection, type CicdConnectionPatch, type CicdKind } from "@/api";
import { useCicdConnectionMutations, useCicdConnections } from "@/api/admin-hooks";
import { useMe, useProjects } from "@/api/hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox, Segmented } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { MultiSelect } from "@/components/ui/multi-select";
import { Banner, EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ConfirmDialog, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

const KINDS: CicdKind[] = ["gitlab", "github"];

/** The server answers with a path when it has no public address configured: make it one the code host can call. */
const absolute = (url: string) => (url.startsWith("/") ? `${location.origin}${url}` : url);

function CopyRow({ label, copyLabel, copied, value }: { label: string; copyLabel: string; copied: string; value: string }) {
  const { t } = useTranslation();
  const toast = useToast();
  const id = useId();
  return (
    <div role="group" aria-labelledby={id} className="flex flex-col gap-1">
      <span id={id} className="text-sm font-medium text-fg">
        {label}
      </span>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 rounded-md border border-border bg-surface px-3 py-2 font-mono text-sm break-all text-fg">
          {value}
        </code>
        <Button
          icon={<Copy />}
          aria-label={copyLabel}
          onClick={() => void navigator.clipboard?.writeText(value).then(() => toast({ tone: "success", title: copied }), () => undefined)}
        >
          {t("cicd.admin.copy")}
        </Button>
      </div>
    </div>
  );
}

/** "웹 플랫폼 개편, 모바일 앱 2.0 외 비공개 1개" — names the reader may see, the rest counted. */
function useProjectsText() {
  const { t } = useTranslation();
  return (c: CicdConnection) => {
    const names = c.projects.map((p) => p.name).join(", ");
    if (c.hiddenProjectCount > 0)
      return names ? t("cicd.admin.projectsWithHidden", { names, count: c.hiddenProjectCount }) : t("cicd.admin.projectsOnlyHidden", { count: c.hiddenProjectCount });
    return names || t("cicd.admin.projectsNone");
  };
}

/**
 * System administrators pick the allowed projects. The list asks the server as you type (a page holds 100 projects, so
 * every active project is reachable by its name or identifier); the projects already picked stay in the list.
 */
function ProjectsPicker({ editing, value, onChange }: { editing: CicdConnection; value: string[]; onChange: (v: string[]) => void }) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setDebounced(q.trim()), 300);
    return () => clearTimeout(id);
  }, [q]);
  const found = useProjects({ filter: "active", q: debounced || undefined, pageSize: 100 }).data?.items;
  // every name seen so far: a project picked from one search keeps its name when the search moves on
  const [names, setNames] = useState<Record<string, string>>(() => Object.fromEntries(editing.projects.map((p) => [p.id, p.name])));
  useEffect(() => {
    if (!found?.length) return;
    setNames((cur) => (found.every((p) => cur[p.id] === p.name) ? cur : { ...cur, ...Object.fromEntries(found.map((p) => [p.id, p.name])) }));
  }, [found]);
  const options = [
    ...(found ?? []).map((p) => ({ value: p.id, label: p.name })),
    ...value.filter((id) => !found?.some((p) => p.id === id)).map((id) => ({ value: id, label: names[id] ?? id })),
  ];
  return (
    <MultiSelect
      aria-label={t("cicd.admin.projects")}
      placeholder={t("cicd.admin.projectsPlaceholder")}
      options={options}
      value={value}
      onValueChange={onChange}
      onSearchChange={setQ}
    />
  );
}

/** After making a connection or rotating its secret: the webhook address and secret, this once. */
function SecretDialog({ webhookUrl, secret, onClose }: { webhookUrl: string; secret: string; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} size="md" title={t("cicd.admin.secretTitle")} footer={<Button variant="primary" onClick={onClose}>{t("apiTokens.done")}</Button>}>
      <div className="flex flex-col gap-4">
        <Banner tone="warning">{t("cicd.admin.secretOnce")}</Banner>
        <CopyRow label={t("cicd.admin.webhookUrl")} copyLabel={t("cicd.admin.copyUrl")} copied={t("cicd.admin.copiedUrl")} value={absolute(webhookUrl)} />
        <CopyRow label={t("cicd.admin.webhookSecret")} copyLabel={t("cicd.admin.copySecret")} copied={t("cicd.admin.copiedSecret")} value={secret} />
        <p className="text-xs text-fg-muted">{t("cicd.admin.secretHow")}</p>
      </div>
    </Dialog>
  );
}

function ConnectionDialog({ editing, onClose, onSecret }: { editing: CicdConnection | null; onClose: () => void; onSecret: (s: { webhookUrl: string; secret: string }) => void }) {
  const { t } = useTranslation();
  const m = useCicdConnectionMutations();
  const runToast = useRunWithToast();
  const toast = useToast();
  const isAdmin = !!useMe().data?.isAdmin;
  const projectsText = useProjectsText();
  const [projectIds, setProjectIds] = useState<string[]>(editing?.projects.map((p) => p.id) ?? []);
  const [kind, setKind] = useState<CicdKind>(editing?.kind ?? "gitlab");
  const [name, setName] = useState(editing?.name ?? "");
  const [baseUrl, setBaseUrl] = useState(editing?.baseUrl ?? "");
  const [apiToken, setApiToken] = useState("");
  const [clearToken, setClearToken] = useState(false);
  const [active, setActive] = useState(editing?.active ?? true);
  const mutation = editing ? m.update : m.create;
  const err = (f: string) => fieldErrorOf(mutation.error, f);
  const urlChanged = !!editing && baseUrl.trim().replace(/\/+$/, "") !== editing.baseUrl;
  const generalError = isApiError(mutation.error) && !mutation.error.fieldErrors.length ? mutation.error.message : undefined;
  // a 409 (a project with this connection's repositories) or a 422 from saving the list: shown here, the dialog stays open
  // the other fields may already be saved when only the list failed: say so
  const [partial, setPartial] = useState(false);
  const projectsReason = isApiError(m.setProjects.error) ? (fieldErrorOf(m.setProjects.error, "projectIds") ?? m.setProjects.error.message) : undefined;
  const projectsError = projectsReason && partial ? t("cicd.admin.projectsPartial", { error: projectsReason }) : projectsReason;
  const projectsChanged =
    !!editing && isAdmin && (projectIds.length !== editing.projects.length || projectIds.some((id) => !editing.projects.some((p) => p.id === id)));

  const save = async () => {
    if (!editing) {
      const made = await runToast(m.create.mutateAsync({ kind, name, baseUrl, apiToken: apiToken.trim() || undefined }), t("cicd.admin.created"));
      if (made) {
        // the secret leaves the mutation's memory: the dialog below is its one showing
        m.create.reset();
        onClose();
        onSecret({ webhookUrl: made.connection.webhookUrl, secret: made.webhookSecret });
      }
      return;
    }
    const patch: CicdConnectionPatch = {};
    if (name !== editing.name) patch.name = name;
    if (urlChanged) patch.baseUrl = baseUrl;
    if (apiToken.trim()) patch.apiToken = apiToken.trim();
    else if (clearToken) patch.apiToken = "";
    if (active !== editing.active) patch.active = active;
    const patched = Object.keys(patch).length > 0;
    if (!patched && !projectsChanged) return onClose();
    if (patched && !(await runToast(m.update.mutateAsync({ id: editing.id, patch })))) return;
    if (projectsChanged) {
      // its own request (PUT …/projects), after the other changes
      try {
        await m.setProjects.mutateAsync({ id: editing.id, projectIds });
      } catch {
        setPartial(patched);
        return;
      }
    }
    toast({ tone: "success", title: t("cicd.admin.saved") });
    onClose();
  };

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={editing ? t("cicd.admin.edit") : t("cicd.admin.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={mutation.isPending} onClick={() => void save()}>
            {editing ? t("app.save") : t("apiTokens.make")}
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        {generalError && <Banner tone="danger">{generalError}</Banner>}
        {!editing ? (
          <fieldset className="flex flex-col gap-1.5">
            <legend className="mb-1 text-sm font-medium text-fg">{t("cicd.admin.kind")}</legend>
            <Segmented aria-label={t("cicd.admin.kind")} value={kind} onValueChange={(v) => setKind(v as CicdKind)} options={KINDS.map((k) => ({ value: k, label: t(`cicd.kinds.${k}`) }))} />
            {err("kind") && <p className="text-xs text-danger">{err("kind")}</p>}
          </fieldset>
        ) : (
          <Field label={t("cicd.admin.kind")}>
            <Input readOnly value={t(`cicd.kinds.${editing.kind}`)} />
          </Field>
        )}
        <Field label={t("cicd.admin.name")} required error={err("name")}>
          <Input data-autofocus value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label={t("cicd.admin.baseUrl")} required hint={t("cicd.admin.baseUrlHint")} error={err("baseUrl")}>
          <Input type="url" value={baseUrl} placeholder="https://" spellCheck={false} onChange={(e) => setBaseUrl(e.target.value)} />
        </Field>
        {urlChanged && editing?.tokenHint && !apiToken.trim() && <Banner tone="warning">{t("cicd.admin.baseUrlChanged")}</Banner>}
        <Field
          label={t("cicd.admin.apiToken")}
          error={err("apiToken")}
          hint={
            <>
              {t("cicd.admin.tokenHint")}.{" "}
              <br />
              {editing ? (editing.tokenHint ? t("cicd.admin.tokenKeep", { hint: editing.tokenHint }) : t("cicd.admin.tokenNoneStored")) : t("cicd.admin.tokenOptional")}
            </>
          }
        >
          <Input type="password" autoComplete="off" spellCheck={false} value={apiToken} onChange={(e) => setApiToken(e.target.value)} />
        </Field>
        {editing?.tokenHint && !apiToken.trim() && !urlChanged && (
          <Checkbox label={t("cicd.admin.clearToken")} checked={clearToken} onCheckedChange={(c) => setClearToken(c === true)} />
        )}
        {editing && <Checkbox label={t("cicd.admin.active")} description={t("cicd.admin.activeHint")} checked={active} onCheckedChange={(c) => setActive(c === true)} />}
        {!editing ? (
          <p className="text-xs text-fg-muted">{t("cicd.admin.projectsNewHint")}</p>
        ) : (
          <fieldset className="flex flex-col gap-1.5">
            <legend className="mb-1 text-sm font-medium text-fg">{t("cicd.admin.projects")}</legend>
            {isAdmin ? (
              <>
                <ProjectsPicker editing={editing} value={projectIds} onChange={setProjectIds} />
                <p className="text-xs text-fg-muted">{t("cicd.admin.projectsHint")}</p>
              </>
            ) : (
              <>
                <p className="text-sm text-fg">{projectsText(editing)}</p>
                <p className="text-xs text-fg-muted">{t("cicd.admin.projectsAdminOnly")}</p>
              </>
            )}
            {projectsError && (
              <p role="alert" className="text-xs text-danger">
                {projectsError}
              </p>
            )}
          </fieldset>
        )}
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}

export function AdminCicdPage() {
  const { t } = useTranslation();
  const title = t("admin.sections.cicd");
  useDocumentTitle(title);
  const me = useMe().data;
  const allowed = !!me && (me.isAdmin || me.globalPermissions.includes("global.manage_cicd"));
  const list = useCicdConnections(allowed);
  const m = useCicdConnectionMutations();
  const toast = useToast();
  const runToast = useRunWithToast();
  const [dialog, setDialog] = useState<{ editing: CicdConnection | null } | null>(null);
  const [secret, setSecret] = useState<{ webhookUrl: string; secret: string } | null>(null);
  const [rotating, setRotating] = useState<CicdConnection | null>(null);
  const [removing, setRemoving] = useState<CicdConnection | null>(null);
  const [checks, setChecks] = useState<Record<string, { ok: boolean; status: number }>>({});
  const [checking, setChecking] = useState<string | null>(null);
  const projectsText = useProjectsText();

  // a result belongs to the address and token it was made with: a change of either drops it
  const checkKey = (c: CicdConnection) => `${c.id} ${c.baseUrl} ${c.tokenHint ?? ""}`;
  const checkText = (r: { ok: boolean; status: number }) =>
    r.ok ? t("cicd.admin.checkOk", { status: r.status }) : r.status === 0 ? t("cicd.admin.checkNoAnswer") : t("cicd.admin.checkFail", { status: r.status });
  const check = async (c: CicdConnection) => {
    setChecking(c.id);
    try {
      const r = await m.check.mutateAsync(c.id);
      setChecks((cur) => ({ ...cur, [checkKey(c)]: r }));
      toast({ tone: r.ok ? "success" : "danger", title: t("cicd.admin.checkResult", { result: checkText(r) }) });
    } catch (e) {
      toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle") });
    } finally {
      setChecking(null);
    }
  };

  return (
    <PageBody>
      <PageHeader
        title={title}
        // a CI/CD 연동 관리자 who is not an administrator cannot open the 관리 landing page
        breadcrumbs={[{ label: t("nav.admin"), href: me?.isAdmin ? "/admin" : undefined }, { label: title }]}
        actions={
          allowed && (
            <Button variant="primary" icon={<Plus />} onClick={() => setDialog({ editing: null })}>
              {t("cicd.admin.new")}
            </Button>
          )
        }
      />
      <AdminGuard cicdManagers>
        <p className="mb-4 max-w-3xl text-sm text-fg-muted">{t("cicd.admin.hint")}</p>
        {list.isPending ? (
          <Skeleton className="h-32 w-full" />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => list.refetch()} />
        ) : list.data.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState compact icon={<GitMerge />} title={t("cicd.admin.none")}>
              {t("cicd.admin.noneHint")}
            </EmptyState>
          </div>
        ) : (
          <AdminTable minWidth={1040}>
            <caption className="sr-only">{t("cicd.admin.list")}</caption>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("cicd.admin.cols.name")}</th>
                <th scope="col" className={th}>{t("cicd.admin.cols.kind")}</th>
                <th scope="col" className={th}>{t("cicd.admin.cols.baseUrl")}</th>
                <th scope="col" className={th}>{t("cicd.admin.cols.token")}</th>
                <th scope="col" className={th}>{t("cicd.admin.cols.repositories")}</th>
                <th scope="col" className={th}>{t("cicd.admin.cols.projects")}</th>
                <th scope="col" className={th}>{t("cicd.admin.cols.status")}</th>
                <th scope="col" className={`${th} w-40`}>
                  <span className="sr-only">{t("app.more")}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((c) => {
                const result = checks[checkKey(c)];
                return (
                  <tr key={c.id} className={tbodyRow}>
                    <td className="px-3 py-1.5">
                      <span className="font-medium text-fg">{c.name}</span>
                      {c.secretHint && <span className="block text-xs text-fg-muted">{t("cicd.admin.secretEnd", { hint: c.secretHint })}</span>}
                    </td>
                    <td className="px-3">{t(`cicd.kinds.${c.kind}`)}</td>
                    <td className="max-w-64 truncate px-3 font-mono text-xs text-fg-muted" title={c.baseUrl}>
                      {c.baseUrl}
                    </td>
                    <td className="px-3 text-fg-muted">
                      {c.tokenHint ? <span className="font-mono text-xs">{t("cicd.admin.tokenEnd", { hint: c.tokenHint })}</span> : t("cicd.admin.tokenNone")}
                    </td>
                    <td className="px-3 tabular-nums">{t("cicd.admin.repositoryCount", { count: c.repositoryCount })}</td>
                    <td className={c.projects.length || c.hiddenProjectCount ? "max-w-64 px-3 py-1.5 text-fg" : "px-3 text-fg-muted"}>{projectsText(c)}</td>
                    <td className="px-3 py-1.5">
                      <span className="flex flex-wrap items-center gap-1.5">
                        <Badge tone={c.active ? "accent" : "neutral"}>{c.active ? t("cicd.admin.statuses.active") : t("cicd.admin.statuses.off")}</Badge>
                        {c.active && !c.tokenHint && <Badge>{t("cicd.admin.webhookOnly")}</Badge>}
                        {!c.secretHint && <Badge tone="warning">{t("cicd.admin.noSecret")}</Badge>}
                        {result && (
                          <span className={result.ok ? "text-xs text-success" : "text-xs text-danger"}>{t("cicd.admin.checkResult", { result: checkText(result) })}</span>
                        )}
                      </span>
                    </td>
                    <td className="px-2">
                      <span className="flex items-center justify-end gap-1">
                        <Button size="sm" icon={<PlugZap />} aria-label={t("cicd.admin.checkFor", { name: c.name })} loading={checking === c.id} onClick={() => void check(c)}>
                          {t("cicd.admin.check")}
                        </Button>
                        <Menu
                          align="end"
                          trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: c.name })} icon={<MoreHorizontal />} />}
                          items={[
                            { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: c }) },
                            { label: t("cicd.admin.rotate"), icon: <KeyRound />, onSelect: () => setRotating(c) },
                            { kind: "separator" as const },
                            { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(c) },
                          ]}
                        />
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {dialog && <ConnectionDialog key={dialog.editing?.id ?? "new"} editing={dialog.editing} onClose={() => setDialog(null)} onSecret={setSecret} />}
      {secret && <SecretDialog webhookUrl={secret.webhookUrl} secret={secret.secret} onClose={() => (setSecret(null), m.rotateSecret.reset())} />}
      <ConfirmDialog
        open={!!rotating}
        onOpenChange={(o) => !o && setRotating(null)}
        title={t("cicd.admin.rotateTitle", { name: rotating?.name ?? "" })}
        body={t("cicd.admin.rotateBody")}
        confirm={t("cicd.admin.rotate")}
        pending={m.rotateSecret.isPending}
        onConfirm={async () => {
          const c = rotating;
          const r = c ? await runToast(m.rotateSecret.mutateAsync(c.id)) : null;
          setRotating(null);
          if (c && r) setSecret({ webhookUrl: c.webhookUrl, secret: r.webhookSecret });
        }}
      />
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("cicd.admin.deleteTitle", { name: removing?.name ?? "" })}
        body={t("cicd.admin.deleteBody")}
        confirm={t("app.delete")}
        pending={m.remove.isPending}
        onConfirm={async () => {
          if (removing) await runToast(m.remove.mutateAsync(removing.id), t("admin.deleted"));
          setRemoving(null);
        }}
      />
    </PageBody>
  );
}
