// S31 관리: 역할 및 권한 — role list and the permission editor.
import { Fragment, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { MoreHorizontal, Pencil, Plus, Save, Table2, Trash2 } from "lucide-react";
import type { AdminRole } from "@/api";
import { useAdminRoles, usePermissionDefs, useRoleMutations } from "@/api/admin-hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox, RadioCards } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, NotFound, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ConfirmDialog, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

function NewRoleDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { create } = useRoleMutations();
  const runToast = useRunWithToast();
  const [name, setName] = useState("");
  const [scope, setScope] = useState<"project" | "global">("project");
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={t("admin.roles.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            loading={create.isPending}
            onClick={async () => {
              const r = await runToast(create.mutateAsync({ name, scope, permissions: scope === "project" ? ["project.view", "wp.view"] : [] }), t("admin.created"));
              if (r) navigate(`/admin/roles/${r.id}`);
            }}
          >
            {t("app.create")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("admin.name")} required error={fieldErrorOf(create.error, "name")}>
          <Input data-autofocus value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </Field>
        <RadioCards
          aria-label={t("admin.roles.scope")}
          columns={1}
          value={scope}
          onValueChange={(v) => setScope(v as "project" | "global")}
          options={[
            { value: "project", title: t("admin.roles.scopeProject"), description: t("admin.roles.scopeProjectHint") },
            { value: "global", title: t("admin.roles.scopeGlobal"), description: t("admin.roles.scopeGlobalHint") },
          ]}
        />
      </div>
    </Dialog>
  );
}

export function AdminRolesPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.roles"));
  const navigate = useNavigate();
  const roles = useAdminRoles();
  const { remove } = useRoleMutations();
  const runToast = useRunWithToast();
  const [creating, setCreating] = useState(false);
  const [removing, setRemoving] = useState<AdminRole | null>(null);
  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.roles")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.roles") }]}
        actions={
          <>
            <Button asChild icon={<Table2 />}>
              <Link to="/admin/roles/report">{t("admin.roles.report")}</Link>
            </Button>
            <Button variant="primary" icon={<Plus />} onClick={() => setCreating(true)}>
              {t("admin.roles.new")}
            </Button>
          </>
        }
      />
      <AdminGuard>
        {roles.isPending ? (
          <Skeleton className="h-40 w-full" />
        ) : roles.isError ? (
          <ErrorState error={roles.error} onRetry={() => roles.refetch()} />
        ) : (
          <AdminTable minWidth={600}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={th}>{t("admin.roles.scope")}</th>
                <th scope="col" className={`${th} text-right`}>{t("admin.roles.permissionCount")}</th>
                <th scope="col" className={`${th} text-right`}>{t("admin.roles.memberships")}</th>
                <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>
              </tr>
            </thead>
            <tbody>
              {roles.data.map((r) => (
                <tr key={r.id} className={tbodyRow}>
                  <td className="px-3">
                    <Link to={`/admin/roles/${r.id}`} className="font-medium text-fg hover:underline">
                      {r.name}
                    </Link>{" "}
                    {r.builtin && <Badge>{t("admin.roles.builtin")}</Badge>}
                  </td>
                  <td className="px-3 text-fg-muted">{r.scope === "project" ? t("admin.roles.scopeProject") : t("admin.roles.scopeGlobal")}</td>
                  <td className="px-3 text-right tabular-nums">{r.permissions.length}</td>
                  <td className="px-3 text-right tabular-nums">{r.memberCount}</td>
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: r.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("admin.roles.editPermissions"), icon: <Pencil />, onSelect: () => navigate(`/admin/roles/${r.id}`) },
                        ...(r.builtin ? [] : [{ kind: "separator" as const }, { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(r) }]),
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {creating && <NewRoleDialog onClose={() => setCreating(false)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("admin.roles.deleteTitle")}
        body={removing && t("admin.roles.deleteBody", { name: removing.name })}
        confirm={t("app.delete")}
        pending={remove.isPending}
        onConfirm={async () => {
          if (removing) await runToast(remove.mutateAsync(removing.id), t("admin.deleted"));
          setRemoving(null);
        }}
      />
    </PageBody>
  );
}

export function AdminRoleEditPage() {
  const { t } = useTranslation();
  const { id } = useParams();
  const roles = useAdminRoles();
  const defs = usePermissionDefs();
  const { update } = useRoleMutations();
  const runToast = useRunWithToast();
  const role = roles.data?.find((r) => r.id === id);
  const [name, setName] = useState("");
  const [perms, setPerms] = useState<Set<string>>(new Set());
  useEffect(() => {
    if (role) {
      setName(role.name);
      setPerms(new Set(role.permissions));
    }
  }, [role]);
  useDocumentTitle(role?.name);

  if (roles.isPending || defs.isPending) return <PageBody><Skeleton className="mt-6 h-96 w-full" /></PageBody>;
  if (roles.isError || defs.isError) return <ErrorState error={roles.error ?? defs.error} />;
  if (!role) return <NotFound />;
  const mine = defs.data.filter((d) => d.scope === role.scope);
  const groups = [...new Set(mine.map((d) => d.group))];
  const dirty = name !== role.name || perms.size !== role.permissions.length || role.permissions.some((p) => !perms.has(p));
  const toggle = (k: string, on: boolean) =>
    setPerms((s) => {
      const n = new Set(s);
      if (on) n.add(k);
      else n.delete(k);
      return n;
    });

  return (
    <PageBody>
      <PageHeader
        title={role.name}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.roles"), href: "/admin/roles" }, { label: role.name }]}
        actions={
          <Button
            variant="primary"
            icon={<Save />}
            disabled={!dirty}
            loading={update.isPending}
            onClick={() => void runToast(update.mutateAsync({ id: role.id, patch: { name, permissions: [...perms] } }), t("app.saved"))}
          >
            {t("app.save")}
          </Button>
        }
      />
      <AdminGuard>
        <div className="flex max-w-form flex-col gap-6">
          {role.builtin && <Banner tone="info">{t("admin.roles.builtinHint")}</Banner>}
          <Field label={t("admin.name")} required error={fieldErrorOf(update.error, "name")}>
            <Input value={name} maxLength={100} disabled={!!role.builtin} onChange={(e) => setName(e.target.value)} />
          </Field>
          {groups.map((g) => {
            const items = mine.filter((d) => d.group === g);
            const all = items.every((d) => perms.has(d.key));
            const some = items.some((d) => perms.has(d.key));
            return (
              <fieldset key={g} className="rounded-md border border-border p-4">
                <legend className="px-1 text-sm font-semibold text-fg">{t(`permissions.groups.${g}` as "permissions.groups.wp")}</legend>
                <div className="mb-3">
                  <Checkbox
                    label={t("admin.roles.selectAll")}
                    checked={all ? true : some ? "indeterminate" : false}
                    onCheckedChange={() => items.forEach((d) => toggle(d.key, !all))}
                  />
                </div>
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                  {items.map((d) => (
                    <Checkbox key={d.key} label={t(`permissions.keys.${d.key.replace(".", "_")}` as "permissions.keys.wp_view")} description={d.key} checked={perms.has(d.key)} onCheckedChange={(c) => toggle(d.key, c === true)} />
                  ))}
                </div>
              </fieldset>
            );
          })}
        </div>
      </AdminGuard>
    </PageBody>
  );
}

/** 권한 보고서 (/admin/roles/report): every role against every permission in one table, changed in place; only the roles changed are saved. */
export function AdminRolesReportPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.roles.report"));
  const roles = useAdminRoles();
  const defs = usePermissionDefs();
  const { update } = useRoleMutations();
  const runToast = useRunWithToast();
  const [draft, setDraft] = useState<Record<string, Set<string>>>({});
  useEffect(() => {
    if (roles.data) setDraft(Object.fromEntries(roles.data.map((r) => [r.id, new Set(r.permissions)])));
  }, [roles.data]);

  if (roles.isPending || defs.isPending) return <PageBody><Skeleton className="mt-6 h-96 w-full" /></PageBody>;
  if (roles.isError || defs.isError) return <ErrorState error={roles.error ?? defs.error} />;
  const changed = roles.data.filter((r) => {
    const d = draft[r.id];
    return d && (d.size !== r.permissions.length || r.permissions.some((p) => !d.has(p)));
  });
  const toggle = (roleId: string, key: string, on: boolean) =>
    setDraft((cur) => {
      const n = new Set(cur[roleId]);
      if (on) n.add(key);
      else n.delete(key);
      return { ...cur, [roleId]: n };
    });
  const save = async () => {
    for (const r of changed) await update.mutateAsync({ id: r.id, patch: { permissions: [...draft[r.id]] } });
  };
  const label = (key: string) => t(`permissions.keys.${key.replace(".", "_")}` as "permissions.keys.wp_view");

  return (
    <PageBody>
      <PageHeader
        title={t("admin.roles.report")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.roles"), href: "/admin/roles" }, { label: t("admin.roles.report") }]}
        actions={
          <Button variant="primary" icon={<Save />} disabled={!changed.length} loading={update.isPending} onClick={() => void runToast(save(), t("admin.roles.reportSaved", { count: changed.length }))}>
            {changed.length ? t("admin.roles.saveChanged", { count: changed.length }) : t("app.save")}
          </Button>
        }
      />
      <AdminGuard>
        <p className="mb-4 text-sm text-fg-muted">{t("admin.roles.reportHint")}</p>
        {(["project", "global"] as const).map((scope) => {
          const cols = roles.data.filter((r) => r.scope === scope);
          const keys = defs.data.filter((d) => d.scope === scope);
          if (!cols.length) return null;
          const groups = [...new Set(keys.map((d) => d.group))];
          return (
            <section key={scope} aria-labelledby={`report-${scope}`} className="mb-8">
              <h2 id={`report-${scope}`} className="mb-2 text-base font-semibold text-fg">
                {scope === "project" ? t("admin.roles.scopeProject") : t("admin.roles.scopeGlobal")}
              </h2>
              <AdminTable minWidth={320 + cols.length * 110}>
                <thead>
                  <tr className={theadRow}>
                    <th scope="col" className={th}>{t("admin.roles.permission")}</th>
                    {cols.map((r) => (
                      <th key={r.id} scope="col" className={`${th} w-28 text-center`}>
                        <Link to={`/admin/roles/${r.id}`} className="hover:underline">{r.name}</Link>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {groups.map((g) => (
                    <Fragment key={g}>
                      <tr className="border-b border-border bg-surface-sunken">
                        <th scope="colgroup" colSpan={cols.length + 1} className="px-3 py-1.5 text-left text-xs font-semibold text-fg-muted">
                          {t(`permissions.groups.${g}` as "permissions.groups.wp")}
                        </th>
                      </tr>
                      {keys.filter((d) => d.group === g).map((d) => (
                        <tr key={d.key} className={tbodyRow}>
                          <th scope="row" className="px-3 text-left text-sm font-normal text-fg">
                            {label(d.key)} <span className="text-xs text-fg-muted">{d.key}</span>
                          </th>
                          {cols.map((r) => (
                            <td key={r.id} className="px-3 text-center">
                              <input
                                type="checkbox"
                                className="size-4 accent-accent"
                                aria-label={t("admin.roles.cellLabel", { role: r.name, permission: label(d.key) })}
                                checked={!!draft[r.id]?.has(d.key)}
                                onChange={(e) => toggle(r.id, d.key, e.target.checked)}
                              />
                            </td>
                          ))}
                        </tr>
                      ))}
                    </Fragment>
                  ))}
                </tbody>
              </AdminTable>
            </section>
          );
        })}
      </AdminGuard>
    </PageBody>
  );
}
