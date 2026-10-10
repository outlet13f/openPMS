// S35 관리: 사용자, 그룹
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { BadgeCheck, Lock, LockOpen, Mail, MoreHorizontal, Pencil, Plus, Search, Shield, ShieldOff, Trash2, UserPlus, Users } from "lucide-react";
import type { AdminGroup, AdminUser } from "@/api";
import { useAdminGroups, useAdminRoles, useAdminUsers, useGroupMutations, useUserMutations } from "@/api/admin-hooks";
import { useMe } from "@/api/hooks";
import { Avatar, Principal } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { EmptyState } from "@/components/ui/feedback";
import { Dialog, Menu, type MenuEntry } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { formatDateTime, relativeTime } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ConfirmDialog, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

const STATUS_TONE = { active: "success", invited: "accent", locked: "danger", deleted: "neutral" } as const;
const FILTERS = ["all", "active", "invited", "locked"] as const;

function InviteDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const { create } = useUserMutations();
  const runToast = useRunWithToast();
  const [v, setV] = useState({ name: "", email: "", isAdmin: false });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={t("admin.users.invite")}
      description={t("admin.users.inviteHint")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" icon={<Mail />} loading={create.isPending} onClick={async () => (await runToast(create.mutateAsync(v), t("admin.users.invited", { email: v.email }))) && onClose()}>
            {t("admin.users.sendInvite")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("admin.users.name")} required error={fieldErrorOf(create.error, "name")}>
          <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </Field>
        <Field label={t("admin.users.email")} required error={fieldErrorOf(create.error, "email")}>
          <Input type="email" value={v.email} onChange={(e) => setV({ ...v, email: e.target.value })} />
        </Field>
        {me?.isAdmin && <Checkbox label={t("admin.users.makeAdmin")} checked={v.isAdmin} onCheckedChange={(c) => setV({ ...v, isAdmin: c === true })} />}
      </div>
    </Dialog>
  );
}

/** S31 전역 역할: what this person may do across the instance (only administrators hand these out). */
function GlobalRolesDialog({ user, onClose }: { user: AdminUser; onClose: () => void }) {
  const { t } = useTranslation();
  const roles = useAdminRoles();
  const m = useUserMutations();
  const runToast = useRunWithToast();
  const [picked, setPicked] = useState(user.globalRoles.map((r) => r.id));
  const global = (roles.data ?? []).filter((r) => r.scope === "global");
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={t("admin.users.globalRolesTitle", { name: user.name })}
      description={t("admin.users.globalRolesHint")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            loading={m.globalRoles.isPending}
            onClick={async () => (await runToast(m.globalRoles.mutateAsync({ id: user.id, roleIds: picked }), t("app.saved"))) && onClose()}
          >
            {t("app.save")}
          </Button>
        </>
      }
    >
      {roles.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : global.length === 0 ? (
        <p className="text-sm text-fg-muted">{t("admin.users.noGlobalRoles")}</p>
      ) : (
        <div role="group" aria-label={t("admin.users.globalRoles")} className="flex flex-col gap-2">
          {global.map((r) => (
            <Checkbox
              key={r.id}
              label={r.name}
              description={r.permissions.map((p) => t(`permissions.keys.${p.replace(".", "_")}` as "permissions.keys.wp_view")).join(", ")}
              checked={picked.includes(r.id)}
              onCheckedChange={(c) => setPicked((ps) => (c === true ? [...ps, r.id] : ps.filter((x) => x !== r.id)))}
            />
          ))}
        </div>
      )}
    </Dialog>
  );
}

export function AdminUsersPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.users"));
  const me = useMe().data;
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>("all");
  const [q, setQ] = useState("");
  const [query, setQuery] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setQuery(q), 250);
    return () => clearTimeout(id);
  }, [q]);
  const users = useAdminUsers({ q: query || undefined, status: filter === "all" ? undefined : filter });
  const m = useUserMutations();
  const runToast = useRunWithToast();
  const [inviting, setInviting] = useState(false);
  const [removing, setRemoving] = useState<AdminUser | null>(null);
  const [granting, setGranting] = useState<AdminUser | null>(null);

  const actions = (u: AdminUser): MenuEntry[] => {
    const self = u.id === me?.id;
    const items: MenuEntry[] = [];
    if (u.status === "invited") items.push({ label: t("admin.users.resend"), icon: <Mail />, onSelect: () => void runToast(m.resend.mutateAsync(u.id), t("admin.users.resent")) });
    if (u.status === "active" && !self) items.push({ label: t("admin.users.lock"), icon: <Lock />, onSelect: () => void runToast(m.update.mutateAsync({ id: u.id, patch: { status: "locked" } }), t("admin.users.locked", { name: u.name })) });
    if (u.status === "locked" || u.lockedUntil) items.push({ label: t("admin.users.unlock"), icon: <LockOpen />, onSelect: () => void runToast(m.update.mutateAsync({ id: u.id, patch: { status: "active" } }), t("admin.users.unlocked", { name: u.name })) });
    if (me?.isAdmin && u.status !== "deleted") items.push({ label: t("admin.users.globalRoles"), icon: <BadgeCheck />, onSelect: () => setGranting(u) });
    if (me?.isAdmin && !self)
      items.push(
        u.isAdmin
          ? { label: t("admin.users.revokeAdmin"), icon: <ShieldOff />, onSelect: () => void runToast(m.update.mutateAsync({ id: u.id, patch: { isAdmin: false } }), t("app.saved")) }
          : { label: t("admin.users.grantAdmin"), icon: <Shield />, onSelect: () => void runToast(m.update.mutateAsync({ id: u.id, patch: { isAdmin: true } }), t("app.saved")) },
      );
    if (!self) items.push({ kind: "separator" }, { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(u) });
    return items;
  };

  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.users")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.users") }]}
        actions={
          <Button variant="primary" icon={<UserPlus />} onClick={() => setInviting(true)}>
            {t("admin.users.invite")}
          </Button>
        }
      />
      <AdminGuard userManagers>
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <div role="group" aria-label={t("admin.users.statusFilter")} className="inline-flex flex-wrap rounded-md border border-border bg-surface p-0.5">
            {FILTERS.map((f) => (
              <button
                key={f}
                type="button"
                aria-pressed={filter === f}
                onClick={() => setFilter(f)}
                className={cn("h-7 rounded-sm px-3 text-sm text-fg-muted hover:text-fg", filter === f && "bg-bg font-medium text-fg shadow-card")}
              >
                {t(`admin.users.filters.${f}`)}
              </button>
            ))}
          </div>
          <label className="relative ml-auto w-full sm:w-64">
            <span className="sr-only">{t("admin.users.search")}</span>
            <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-fg-muted" />
            <Input size="sm" type="search" className="pl-8" placeholder={t("admin.users.search")} value={q} onChange={(e) => setQ(e.target.value)} />
          </label>
        </div>
        {users.isPending ? (
          <Skeleton className="h-48 w-full" />
        ) : users.isError ? (
          <ErrorState error={users.error} onRetry={() => users.refetch()} />
        ) : users.data.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState icon={<Users />} title={t("admin.users.empty")} />
          </div>
        ) : (
          <AdminTable minWidth={880}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("admin.users.name")}</th>
                <th scope="col" className={th}>{t("admin.users.email")}</th>
                <th scope="col" className={th}>{t("admin.users.status")}</th>
                <th scope="col" className={th}>{t("admin.users.globalRoles")}</th>
                <th scope="col" className={`${th} text-right`}>{t("admin.projects")}</th>
                <th scope="col" className={th}>{t("admin.users.lastLogin")}</th>
                <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>
              </tr>
            </thead>
            <tbody>
              {users.data.map((u) => {
                const acts = actions(u);
                return (
                  <tr key={u.id} className={tbodyRow}>
                    <td className="px-3">
                      <span className="inline-flex items-center gap-2">
                        <Principal id={u.id} name={u.name} />
                        {u.isAdmin && <Badge tone="accent">{t("auth.roleAdmin")}</Badge>}
                        {u.id === me?.id && <span className="text-xs text-fg-muted">({t("admin.users.you")})</span>}
                      </span>
                    </td>
                    <td className="px-3 text-fg-muted">{u.email}</td>
                    <td className="px-3">
                      {u.lockedUntil ? (
                        <span className="inline-flex flex-col items-start gap-0.5">
                          <Badge tone="warning">{t("admin.users.lockedOut")}</Badge>
                          <span className="text-xs text-fg-muted">{t("admin.users.lockedUntil", { time: formatDateTime(u.lockedUntil) })}</span>
                        </span>
                      ) : (
                        <Badge tone={STATUS_TONE[u.status]}>{t(`admin.users.statuses.${u.status}`)}</Badge>
                      )}
                    </td>
                    <td className="px-3">
                      {u.globalRoles.length ? (
                        <span className="flex flex-wrap gap-1">
                          {u.globalRoles.map((r) => (
                            <Badge key={r.id}>{r.name}</Badge>
                          ))}
                        </span>
                      ) : (
                        <span className="text-fg-muted">-</span>
                      )}
                    </td>
                    <td className="px-3 text-right tabular-nums">{u.projectCount}</td>
                    <td className="px-3 text-fg-muted">{u.lastLoginAt ? relativeTime(u.lastLoginAt) : "-"}</td>
                    <td className="px-2">
                      {acts.length > 0 && <Menu align="end" trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: u.name })} icon={<MoreHorizontal />} />} items={acts} />}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {inviting && <InviteDialog onClose={() => setInviting(false)} />}
      {granting && <GlobalRolesDialog user={granting} onClose={() => setGranting(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("admin.users.deleteTitle")}
        body={removing && t("admin.users.deleteBody", { name: removing.name })}
        confirm={t("app.delete")}
        pending={m.remove.isPending}
        onConfirm={async () => {
          if (removing) await runToast(m.remove.mutateAsync(removing.id), t("admin.users.deleted"));
          setRemoving(null);
        }}
      />
    </PageBody>
  );
}

/* ---------- groups ---------- */

function GroupMembersDialog({ group, onClose }: { group: AdminGroup; onClose: () => void }) {
  const { t } = useTranslation();
  const users = useAdminUsers({ status: "active" });
  const { setMembers } = useGroupMutations();
  const runToast = useRunWithToast();
  const [ids, setIds] = useState<Set<string>>(new Set(group.members.map((m) => m.id)));
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={t("admin.groups.members", { name: group.name })}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={setMembers.isPending} onClick={async () => (await runToast(setMembers.mutateAsync({ id: group.id, userIds: [...ids] }), t("app.saved"))) && onClose()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      {users.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : (
        <fieldset className="flex max-h-72 flex-col gap-2 overflow-y-auto">
          <legend className="sr-only">{t("admin.groups.members", { name: group.name })}</legend>
          {users.data?.map((u) => (
            <Checkbox
              key={u.id}
              label={
                <span className="inline-flex items-center gap-2">
                  <Avatar id={u.id} name={u.name} size="xs" />
                  {u.name}
                </span>
              }
              checked={ids.has(u.id)}
              onCheckedChange={(c) =>
                setIds((s) => {
                  const n = new Set(s);
                  if (c === true) n.add(u.id);
                  else n.delete(u.id);
                  return n;
                })
              }
            />
          ))}
        </fieldset>
      )}
    </Dialog>
  );
}

function GroupNameDialog({ group, onClose }: { group: AdminGroup | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { create, rename } = useGroupMutations();
  const runToast = useRunWithToast();
  const [name, setName] = useState(group?.name ?? "");
  const mutation = group ? rename : create;
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={group ? t("admin.groups.rename") : t("admin.groups.new")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            loading={mutation.isPending}
            onClick={async () => (await runToast(group ? rename.mutateAsync({ id: group.id, name }) : create.mutateAsync(name), t("app.saved"))) && onClose()}
          >
            {t("app.save")}
          </Button>
        </>
      }
    >
      <Field label={t("admin.name")} required error={fieldErrorOf(mutation.error, "name")}>
        <Input data-autofocus value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
      </Field>
    </Dialog>
  );
}

export function AdminGroupsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.groups"));
  const groups = useAdminGroups();
  const { remove } = useGroupMutations();
  const runToast = useRunWithToast();
  const [naming, setNaming] = useState<{ group: AdminGroup | null } | null>(null);
  const [members, setMembers] = useState<AdminGroup | null>(null);
  const [removing, setRemoving] = useState<AdminGroup | null>(null);
  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.groups")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.groups") }]}
        actions={
          <Button variant="primary" icon={<Plus />} onClick={() => setNaming({ group: null })}>
            {t("admin.groups.new")}
          </Button>
        }
      />
      <AdminGuard userManagers>
        {groups.isPending ? (
          <Skeleton className="h-32 w-full" />
        ) : groups.isError ? (
          <ErrorState error={groups.error} onRetry={() => groups.refetch()} />
        ) : groups.data.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState icon={<Users />} title={t("admin.groups.empty")}>
              {t("admin.groups.emptyBody")}
            </EmptyState>
          </div>
        ) : (
          <AdminTable minWidth={520}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={th}>{t("admin.groups.memberList")}</th>
                <th scope="col" className={`${th} w-12`}><span className="sr-only">{t("app.more")}</span></th>
              </tr>
            </thead>
            <tbody>
              {groups.data.map((g) => (
                <tr key={g.id} className={tbodyRow}>
                  <td className="px-3 font-medium text-fg">{g.name}</td>
                  <td className="px-3">
                    {g.members.length === 0 ? (
                      <span className="text-fg-muted">{t("admin.groups.noMembers")}</span>
                    ) : (
                      <span className="inline-flex items-center gap-1" aria-label={g.members.map((m) => m.name).join(", ")}>
                        {g.members.slice(0, 6).map((m) => (
                          <Avatar key={m.id} id={m.id} name={m.name} size="sm" />
                        ))}
                        <span className="ml-1 text-xs text-fg-muted">{t("admin.groups.count", { count: g.members.length })}</span>
                      </span>
                    )}
                  </td>
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: g.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("admin.groups.manageMembers"), icon: <Users />, onSelect: () => setMembers(g) },
                        { label: t("admin.groups.rename"), icon: <Pencil />, onSelect: () => setNaming({ group: g }) },
                        { kind: "separator" },
                        { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(g) },
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {naming && <GroupNameDialog group={naming.group} onClose={() => setNaming(null)} />}
      {members && <GroupMembersDialog group={members} onClose={() => setMembers(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("admin.groups.deleteTitle")}
        body={removing && t("admin.groups.deleteBody", { name: removing.name })}
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
