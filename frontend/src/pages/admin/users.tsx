// S35 관리: 사용자, 그룹
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { BadgeCheck, ClipboardCopy, Copy, Eye, KeyRound, Lock, LockOpen, Mail, MoreHorizontal, Pencil, Plus, Search, Shield, ShieldOff, Trash2, UserCog, UserPlus, Users } from "lucide-react";
import { api, type AdminGroup, type AdminUser, type TemporaryPassword } from "@/api";
import { useAdminGroups, useAdminRoles, useAdminUsers, useGroupMutations, useUserMutations } from "@/api/admin-hooks";
import { useMe } from "@/api/hooks";
import { Avatar, Principal } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox, RadioCards } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Select } from "@/components/ui/select";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog, Menu, type MenuEntry } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { formatDateTime, relativeTime } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ConfirmDialog, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { AdminGuard } from "./index";

const STATUS_TONE = { active: "success", invited: "accent", locked: "danger", deleted: "neutral" } as const;
const FILTERS = ["all", "active", "invited", "locked"] as const;

type Shown = { user: AdminUser; password: TemporaryPassword };

/** 사용자 추가: an invitation by mail (as before) or, without mail, a temporary password to pass on. */
function AddUserDialog({ onClose, onMade }: { onClose: () => void; onMade: (shown: Shown) => void }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const { create, createWithPassword } = useUserMutations();
  const runToast = useRunWithToast();
  const [v, setV] = useState({ name: "", email: "", isAdmin: false });
  const [method, setMethod] = useState<"invite" | "temporaryPassword">("invite");
  const error = method === "invite" ? create.error : createWithPassword.error;
  const submit = async () => {
    if (method === "invite") {
      if (await runToast(create.mutateAsync(v), t("admin.users.invited", { email: v.email }))) onClose();
      return;
    }
    const made = await runToast(createWithPassword.mutateAsync(v));
    if (made) onMade({ user: made.user, password: made });
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={t("admin.users.add")}
      description={t(method === "invite" ? "admin.users.inviteHint" : "admin.users.temporaryHint")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" icon={method === "invite" ? <Mail /> : <KeyRound />} loading={create.isPending || createWithPassword.isPending} onClick={() => void submit()}>
            {t(method === "invite" ? "admin.users.sendInvite" : "admin.users.createUser")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <RadioCards
          aria-label={t("admin.users.method")}
          value={method}
          onValueChange={(m) => setMethod(m as typeof method)}
          options={[
            { value: "invite", title: t("admin.users.methodInvite"), icon: <Mail /> },
            { value: "temporaryPassword", title: t("admin.users.methodTemporary"), icon: <KeyRound /> },
          ]}
        />
        <Field label={t("admin.users.name")} required error={fieldErrorOf(error, "name")}>
          <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </Field>
        <Field label={t("admin.users.email")} required error={fieldErrorOf(error, "email")}>
          <Input type="email" value={v.email} onChange={(e) => setV({ ...v, email: e.target.value })} />
        </Field>
        {me?.isAdmin && <Checkbox label={t("admin.users.makeAdmin")} checked={v.isAdmin} onCheckedChange={(c) => setV({ ...v, isAdmin: c === true })} />}
      </div>
    </Dialog>
  );
}

/** 임시 비밀번호: what the administrator passes on — the password alone, or a ready message with the address and the deadline. */
function TemporaryPasswordDialog({ shown, onClose }: { shown: Shown; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [copied, setCopied] = useState("");
  const { user, password: p } = shown;
  const message = t("admin.users.temporaryMessage", { url: `${window.location.origin}/login`, email: user.email, password: p.temporaryPassword, until: formatDateTime(p.expiresAt) });
  const copy = async (text: string, done: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(done);
    } catch {
      setCopied("");
      toast({ tone: "danger", title: t("admin.users.copyFailed") });
    }
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={t("admin.users.temporaryTitle", { name: user.name })}
      description={t("admin.users.temporaryNote")}
      footer={
        <Button variant="primary" onClick={onClose}>
          {t("admin.users.done")}
        </Button>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("admin.users.email")}>
          <Input readOnly value={user.email} />
        </Field>
        <Field label={t("admin.users.temporaryPassword")} hint={t("admin.users.temporaryUntil", { time: formatDateTime(p.expiresAt) })}>
          <Input readOnly className="font-mono" value={p.temporaryPassword} />
        </Field>
        {p.issuedAt && <p className="text-xs text-fg-muted">{t("admin.users.temporaryIssued", { name: p.issuedBy?.name ?? "-", time: formatDateTime(p.issuedAt) })}</p>}
        <div className="flex flex-wrap gap-2">
          <Button data-autofocus icon={<Copy />} onClick={() => void copy(p.temporaryPassword, t("admin.users.copiedPassword"))}>
            {t("admin.users.copyPassword")}
          </Button>
          <Button icon={<ClipboardCopy />} onClick={() => void copy(message, t("admin.users.copiedMessage"))}>
            {t("admin.users.copyMessage")}
          </Button>
        </div>
        <p role="status" aria-live="polite" className="text-sm text-fg-muted">
          {copied}
        </p>
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

const NO_ONE = "none";

/**
 * One person picked among the active users, or none: a user's 관리자(상사) or a group's 책임자 (catalog approvers).
 * Only administrators set these (the server answers 403 to user managers), so only they get here.
 */
function ApproverDialog({
  title,
  hint,
  label,
  current,
  exclude,
  error,
  pending,
  onSave,
  onClose,
}: {
  title: string;
  hint: string;
  label: string;
  current: { id: string; name: string } | null;
  exclude?: string;
  error?: string;
  pending: boolean;
  onSave: (id: string | null) => Promise<unknown>;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const users = useAdminUsers({ status: "active" });
  const [picked, setPicked] = useState(current?.id ?? NO_ONE);
  // the current person stays choosable even when no longer active
  const people = [...(current ? [current] : []), ...(users.data ?? [])].filter((u, i, all) => u.id !== exclude && all.findIndex((x) => x.id === u.id) === i);
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={title}
      description={hint}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={pending} disabled={picked === (current?.id ?? NO_ONE)} onClick={() => void onSave(picked === NO_ONE ? null : picked)}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      {users.isPending ? (
        <Skeleton className="h-10 w-full" />
      ) : (
        <Field label={label} error={error}>
          <Select
            value={picked}
            onValueChange={setPicked}
            options={[
              { value: NO_ONE, label: t("app.none") },
              ...people.map((u) => ({
                value: u.id,
                textValue: u.name,
                label: (
                  <span className="inline-flex items-center gap-2">
                    <Avatar id={u.id} name={u.name} size="xs" />
                    {u.name}
                  </span>
                ),
              })),
            ]}
          />
        </Field>
      )}
    </Dialog>
  );
}

function ManagerDialog({ user, onClose }: { user: AdminUser; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useUserMutations();
  const runToast = useRunWithToast();
  return (
    <ApproverDialog
      title={t("admin.users.managerTitle", { name: user.name })}
      hint={t("admin.users.managerHint")}
      label={t("admin.users.manager")}
      current={user.manager}
      exclude={user.id}
      error={fieldErrorOf(m.update.error, "managerId") ?? fieldErrorOf(m.update.error, "manager")}
      pending={m.update.isPending}
      onSave={async (managerId) => (await runToast(m.update.mutateAsync({ id: user.id, patch: { managerId } }), t("app.saved"))) && onClose()}
      onClose={onClose}
    />
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
  const [noManager, setNoManager] = useState(false);
  const users = useAdminUsers({ q: query || undefined, status: filter === "all" ? undefined : filter, noManager: noManager || undefined });
  const m = useUserMutations();
  const runToast = useRunWithToast();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<AdminUser | null>(null);
  const [granting, setGranting] = useState<AdminUser | null>(null);
  const [managing, setManaging] = useState<AdminUser | null>(null);
  const [issuing, setIssuing] = useState<AdminUser | null>(null);
  const [shown, setShown] = useState<Shown | null>(null);
  const live = (until: string | null) => !!until && Date.parse(until) > Date.now();
  const look = async (u: AdminUser) => {
    const p = await runToast(api.admin.users.temporaryPassword(u.id));
    if (p) setShown({ user: u, password: p });
  };

  const actions = (u: AdminUser): MenuEntry[] => {
    const self = u.id === me?.id;
    const items: MenuEntry[] = [];
    if (u.status === "invited") items.push({ label: t("admin.users.resend"), icon: <Mail />, onSelect: () => void runToast(m.resend.mutateAsync(u.id), t("admin.users.resent")) });
    // an administrator's account is handled only by administrators (the server refuses user managers with 403)
    const mine = !u.isAdmin || !!me?.isAdmin;
    const handlesPassword = (u.status === "active" || u.status === "invited") && !self && mine;
    if (handlesPassword) items.push({ label: t("admin.users.issueTemporary"), icon: <KeyRound />, onSelect: () => setIssuing(u) });
    if (handlesPassword && live(u.temporaryPasswordUntil)) items.push({ label: t("admin.users.showTemporary"), icon: <Eye />, onSelect: () => void look(u) });
    if (u.status === "active" && !self && mine) items.push({ label: t("admin.users.lock"), icon: <Lock />, onSelect: () => void runToast(m.update.mutateAsync({ id: u.id, patch: { status: "locked" } }), t("admin.users.locked", { name: u.name })) });
    if ((u.status === "locked" || u.lockedUntil) && mine) items.push({ label: t("admin.users.unlock"), icon: <LockOpen />, onSelect: () => void runToast(m.update.mutateAsync({ id: u.id, patch: { status: "active" } }), t("admin.users.unlocked", { name: u.name })) });
    if (me?.isAdmin && u.status !== "deleted") items.push({ label: t("admin.users.globalRoles"), icon: <BadgeCheck />, onSelect: () => setGranting(u) });
    // 관리자(상사): administrators only (403 for user managers)
    if (me?.isAdmin && u.status !== "deleted") items.push({ label: t("admin.users.manager"), icon: <UserCog />, onSelect: () => setManaging(u) });
    if (me?.isAdmin && !self)
      items.push(
        u.isAdmin
          ? { label: t("admin.users.revokeAdmin"), icon: <ShieldOff />, onSelect: () => void runToast(m.update.mutateAsync({ id: u.id, patch: { isAdmin: false } }), t("app.saved")) }
          : { label: t("admin.users.grantAdmin"), icon: <Shield />, onSelect: () => void runToast(m.update.mutateAsync({ id: u.id, patch: { isAdmin: true } }), t("app.saved")) },
      );
    if (!self && mine) items.push({ kind: "separator" }, { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(u) });
    return items;
  };

  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.users")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.users") }]}
        actions={
          <Button variant="primary" icon={<UserPlus />} onClick={() => setAdding(true)}>
            {t("admin.users.add")}
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
          <button
            type="button"
            aria-pressed={noManager}
            onClick={() => setNoManager((v) => !v)}
            className={cn("h-8 rounded-md border border-border bg-surface px-3 text-sm text-fg-muted hover:text-fg", noManager && "bg-bg font-medium text-fg shadow-card")}
          >
            {t("admin.users.noManager")}
          </button>
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
          <AdminTable minWidth={1000}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("admin.users.name")}</th>
                <th scope="col" className={th}>{t("admin.users.email")}</th>
                <th scope="col" className={th}>{t("admin.users.status")}</th>
                <th scope="col" className={th}>{t("admin.users.globalRoles")}</th>
                <th scope="col" className={th}>{t("admin.users.manager")}</th>
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
                        <span className="inline-flex flex-col items-start gap-0.5">
                          <Badge tone={STATUS_TONE[u.status]}>{t(`admin.users.statuses.${u.status}`)}</Badge>
                          {u.temporaryPasswordUntil &&
                            (live(u.temporaryPasswordUntil) ? (
                              <span className="text-xs text-fg-muted">{t("admin.users.temporaryBadge", { time: formatDateTime(u.temporaryPasswordUntil) })}</span>
                            ) : (
                              <Badge tone="warning">{t("admin.users.temporaryExpired")}</Badge>
                            ))}
                        </span>
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
                    <td className="px-3">{u.manager ? <Principal id={u.manager.id} name={u.manager.name} size="xs" /> : <span className="text-fg-muted">-</span>}</td>
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
      {adding && <AddUserDialog onClose={() => setAdding(false)} onMade={(s) => (setAdding(false), setShown(s))} />}
      {shown && <TemporaryPasswordDialog shown={shown} onClose={() => setShown(null)} />}
      {granting && <GlobalRolesDialog user={granting} onClose={() => setGranting(null)} />}
      {managing && <ManagerDialog user={managing} onClose={() => setManaging(null)} />}
      <ConfirmDialog
        open={!!issuing}
        onOpenChange={(o) => !o && setIssuing(null)}
        title={t("admin.users.issueTitle")}
        body={issuing && t("admin.users.issueBody", { name: issuing.name })}
        confirm={t("admin.users.issueConfirm")}
        pending={m.issueTemporary.isPending}
        onConfirm={async () => {
          const target = issuing;
          setIssuing(null);
          if (!target) return;
          const p = await runToast(m.issueTemporary.mutateAsync(target.id));
          if (p) setShown({ user: target, password: p });
        }}
      />
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

function LeadDialog({ group, onClose }: { group: AdminGroup; onClose: () => void }) {
  const { t } = useTranslation();
  const { update } = useGroupMutations();
  const runToast = useRunWithToast();
  return (
    <ApproverDialog
      title={t("admin.groups.leadTitle", { name: group.name })}
      hint={t("admin.groups.leadHint")}
      label={t("admin.groups.lead")}
      current={group.lead}
      error={fieldErrorOf(update.error, "leadUserId") ?? fieldErrorOf(update.error, "lead")}
      pending={update.isPending}
      onSave={async (leadUserId) => (await runToast(update.mutateAsync({ id: group.id, patch: { leadUserId } }), t("app.saved"))) && onClose()}
      onClose={onClose}
    />
  );
}

export function AdminGroupsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.groups"));
  const groups = useAdminGroups();
  const { remove } = useGroupMutations();
  const me = useMe().data;
  const runToast = useRunWithToast();
  const [naming, setNaming] = useState<{ group: AdminGroup | null } | null>(null);
  const [members, setMembers] = useState<AdminGroup | null>(null);
  const [leading, setLeading] = useState<AdminGroup | null>(null);
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
          <AdminTable minWidth={640}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={th}>{t("admin.groups.memberList")}</th>
                <th scope="col" className={th}>{t("admin.groups.lead")}</th>
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
                  <td className="px-3">{g.lead ? <Principal id={g.lead.id} name={g.lead.name} size="xs" /> : <span className="text-fg-muted">-</span>}</td>
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: g.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("admin.groups.manageMembers"), icon: <Users />, onSelect: () => setMembers(g) },
                        { label: t("admin.groups.rename"), icon: <Pencil />, onSelect: () => setNaming({ group: g }) },
                        // 책임자: administrators only (403 for user managers)
                        ...(me?.isAdmin ? [{ label: t("admin.groups.lead"), icon: <UserCog />, onSelect: () => setLeading(g) }] : []),
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
      {leading && <LeadDialog group={leading} onClose={() => setLeading(null)} />}
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
