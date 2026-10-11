// 관리 · API와 웹훅 · 웹훅: tell other systems when work packages are made, changed, deleted or commented on. Each request
// is signed with the webhook's secret (X-OpenPMS-Signature: sha256=…), shown once when made or renewed.
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Copy, KeyRound, MoreHorizontal, Pencil, Plus, RotateCcw, Send, Trash2, Webhook as WebhookIcon } from "lucide-react";
import { api, isApiError, WEBHOOK_EVENTS, type Webhook, type WebhookInput } from "@/api";
import { useProjects } from "@/api/hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner, EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { formatDateTime } from "@/lib/dates";
import { ConfirmDialog, useRunWithToast } from "@/features/admin/common";

const KEY = ["admin", "webhooks"];

function SecretDialog({ secret, onClose }: { secret: string; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} size="md" title={t("webhooks.secretTitle")} footer={<Button variant="primary" onClick={onClose}>{t("apiTokens.done")}</Button>}>
      <div className="flex flex-col gap-3">
        <Banner tone="warning">{t("webhooks.secretOnce")}</Banner>
        <div className="flex items-center gap-2">
          <code className="min-w-0 flex-1 rounded-md border border-border bg-surface px-3 py-2 font-mono text-sm break-all text-fg" aria-label={t("webhooks.secret")}>
            {secret}
          </code>
          <Button icon={<Copy />} onClick={() => void navigator.clipboard?.writeText(secret).then(() => toast({ tone: "success", title: t("apiTokens.copied") }), () => undefined)}>
            {t("apiTokens.copy")}
          </Button>
        </div>
        <p className="text-xs text-fg-muted">{t("webhooks.verifyHint")}</p>
      </div>
    </Dialog>
  );
}

function WebhookDialog({ editing, onClose, onSecret }: { editing: Webhook | null; onClose: () => void; onSecret: (s: string) => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const projects = useProjects({ filter: "active", pageSize: 100 }).data?.items ?? [];
  const [v, setV] = useState<WebhookInput>(
    editing ? { name: editing.name, url: editing.url, events: editing.events, projectIds: editing.projectIds, enabled: editing.enabled } : { name: "", url: "", events: [...WEBHOOK_EVENTS], projectIds: [], enabled: true },
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const fieldErr = (f: string) => (isApiError(error) ? error.fieldErrors.find((e) => e.field === f)?.message : undefined);
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const saved = editing ? await api.admin.webhooks.update(editing.id, v) : await api.admin.webhooks.create(v);
      void qc.invalidateQueries({ queryKey: KEY });
      onClose();
      if (saved.secret) onSecret(saved.secret);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={editing ? t("webhooks.edit") : t("webhooks.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={busy} onClick={() => void save()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {isApiError(error) && !error.fieldErrors.length && <Banner tone="danger">{error.title}</Banner>}
        <Field label={t("webhooks.name")} required error={fieldErr("name")}>
          <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </Field>
        <Field label={t("webhooks.url")} required hint={t("webhooks.urlHint")} error={fieldErr("url")}>
          <Input type="url" value={v.url} placeholder="https://" onChange={(e) => setV({ ...v, url: e.target.value })} />
        </Field>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium text-fg">{t("webhooks.events")}</legend>
          {fieldErr("events") && <p className="text-xs text-danger">{fieldErr("events")}</p>}
          {WEBHOOK_EVENTS.map((e) => (
            <Checkbox
              key={e}
              label={t(`webhooks.eventNames.${e.replace(".", "_")}` as "webhooks.eventNames.work_package_created")}
              description={e}
              checked={v.events.includes(e)}
              onCheckedChange={(c) => setV({ ...v, events: c === true ? [...v.events, e] : v.events.filter((x) => x !== e) })}
            />
          ))}
        </fieldset>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium text-fg">{t("webhooks.projects")}</legend>
          <p className="text-xs text-fg-muted">{t("webhooks.projectsHint")}</p>
          <div className="grid max-h-40 grid-cols-1 gap-1.5 overflow-y-auto sm:grid-cols-2">
            {projects.map((p) => (
              <Checkbox
                key={p.id}
                label={p.name}
                checked={v.projectIds.includes(p.id)}
                onCheckedChange={(c) => setV({ ...v, projectIds: c === true ? [...v.projectIds, p.id] : v.projectIds.filter((x) => x !== p.id) })}
              />
            ))}
          </div>
        </fieldset>
        <Checkbox label={t("webhooks.enabled")} checked={v.enabled} onCheckedChange={(c) => setV({ ...v, enabled: c === true })} />
      </div>
    </Dialog>
  );
}

function Deliveries({ hook }: { hook: Webhook }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const runToast = useRunWithToast();
  const list = useQuery({ queryKey: [...KEY, hook.id, "deliveries"], queryFn: () => api.admin.webhooks.deliveries(hook.id) });
  const tone = { succeeded: "success", failed: "danger", pending: "warning" } as const;
  if (list.isPending) return <Skeleton className="h-10 w-full" />;
  if (!list.data?.length) return <p className="text-xs text-fg-muted">{t("webhooks.noDeliveries")}</p>;
  return (
    <ul aria-label={t("webhooks.deliveries", { name: hook.name })} className="flex flex-col divide-y divide-border-muted rounded-md border border-border-muted text-xs">
      {list.data.map((d) => (
        <li key={d.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-1.5">
          <Badge tone={tone[d.status]}>{t(`webhooks.statuses.${d.status}`)}</Badge>
          <span className="font-mono">{d.event}</span>
          <span className="text-fg-muted">{formatDateTime(d.createdAt)}</span>
          <span className="text-fg-muted">
            {d.responseStatus ? `HTTP ${d.responseStatus}` : ""} {d.error && d.status !== "succeeded" ? d.error : ""}
            {d.nextAttemptAt ? ` · ${t("webhooks.nextAttempt", { at: formatDateTime(d.nextAttemptAt) })}` : ""}
          </span>
          {d.status !== "succeeded" && (
            <Button
              className="ml-auto"
              size="sm"
              variant="ghost"
              icon={<RotateCcw />}
              onClick={() => void runToast(api.admin.webhooks.redeliver(d.id), t("webhooks.redelivered")).then(() => qc.invalidateQueries({ queryKey: [...KEY, hook.id, "deliveries"] }))}
            >
              {t("webhooks.redeliver")}
            </Button>
          )}
        </li>
      ))}
    </ul>
  );
}

export function WebhooksSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const runToast = useRunWithToast();
  const hooks = useQuery({ queryKey: KEY, queryFn: () => api.admin.webhooks.list() });
  const projects = useProjects({ filter: "active", pageSize: 100 }).data?.items ?? [];
  const [dialog, setDialog] = useState<{ editing: Webhook | null } | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [removing, setRemoving] = useState<Webhook | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: KEY });
  const test = async (h: Webhook) => {
    try {
      const d = await api.admin.webhooks.test(h.id);
      toast(
        d.status === "succeeded"
          ? { tone: "success", title: t("webhooks.testOk", { code: d.responseStatus }) }
          : { tone: "danger", title: t("webhooks.testFailed"), description: d.error ?? (d.responseStatus ? `HTTP ${d.responseStatus}` : undefined) },
      );
      setOpen(h.id);
      void qc.invalidateQueries({ queryKey: [...KEY, h.id, "deliveries"] });
    } catch (e) {
      toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle") });
    }
  };
  return (
    <section aria-labelledby="webhooks-h" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="webhooks-h" className="text-base font-semibold text-fg">{t("webhooks.title")}</h2>
          <p className="mt-1 max-w-3xl text-sm text-fg-muted">{t("webhooks.hint")}</p>
        </div>
        <Button icon={<Plus />} onClick={() => setDialog({ editing: null })}>
          {t("webhooks.new")}
        </Button>
      </div>
      {hooks.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : !hooks.data?.length ? (
        <div className="rounded-md border border-border">
          <EmptyState compact icon={<WebhookIcon />} title={t("webhooks.none")} />
        </div>
      ) : (
        <ul className="flex flex-col gap-3">
          {hooks.data.map((h) => (
            <li key={h.id} className="rounded-md border border-border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium text-fg">{h.name}</span>
                {!h.enabled && <Badge>{t("webhooks.off")}</Badge>}
                <span className="min-w-0 truncate font-mono text-xs text-fg-muted">{h.url}</span>
                <span className="ml-auto flex items-center gap-1">
                  <Button size="sm" icon={<Send />} onClick={() => void test(h)}>
                    {t("webhooks.test")}
                  </Button>
                  <Menu
                    align="end"
                    trigger={<Button size="sm" variant="ghost" iconOnly aria-label={t("admin.actionsFor", { name: h.name })} icon={<MoreHorizontal />} />}
                    items={[
                      { label: t("webhooks.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: h }) },
                      {
                        label: t("webhooks.rotate"),
                        icon: <KeyRound />,
                        onSelect: () =>
                          void api.admin.webhooks.rotateSecret(h.id).then(
                            (r) => (r.secret && setSecret(r.secret), refresh()),
                            (e) => toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle") }),
                          ),
                      },
                      { kind: "separator" as const },
                      { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(h) },
                    ]}
                  />
                </span>
              </div>
              <div className="mt-2 flex flex-wrap gap-1.5 text-xs">
                {h.events.map((e) => (
                  <Badge key={e}>{t(`webhooks.eventNames.${e.replace(".", "_")}` as "webhooks.eventNames.work_package_created")}</Badge>
                ))}
                <span className="text-fg-muted">
                  · {h.projectIds.length ? h.projectIds.map((id) => projects.find((p) => p.id === id)?.name ?? id).join(", ") : t("webhooks.allProjects")} · {t("webhooks.secretHint", { hint: h.secretHint })}
                </span>
              </div>
              <div className="mt-2">
                <Button size="sm" variant="link" className="h-auto p-0 text-xs" aria-expanded={open === h.id} onClick={() => setOpen(open === h.id ? null : h.id)}>
                  {t("webhooks.showDeliveries")}
                </Button>
                {open === h.id && (
                  <div className="mt-2">
                    <Deliveries hook={h} />
                  </div>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {dialog && <WebhookDialog editing={dialog.editing} onClose={() => setDialog(null)} onSecret={setSecret} />}
      {secret && <SecretDialog secret={secret} onClose={() => setSecret(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("webhooks.deleteTitle", { name: removing?.name ?? "" })}
        body={t("webhooks.deleteBody")}
        confirm={t("app.delete")}
        onConfirm={async () => {
          if (removing) await runToast(api.admin.webhooks.remove(removing.id), t("admin.deleted"));
          void refresh();
          setRemoving(null);
        }}
      />
    </section>
  );
}
