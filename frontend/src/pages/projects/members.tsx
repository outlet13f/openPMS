// S23 프로젝트 멤버: add people with roles, change roles, remove (the last manager is protected).
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Trash2, UserPlus, Users } from "lucide-react";
import { api, isApiError, type Member } from "@/api";
import { useMemberMutations, useMembers, useRoles } from "@/api/hooks";
import { Avatar, Principal } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { formatDate } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { InlinePick } from "@/features/work-packages/inline-pick";

function AddMembersDialog({ open, onOpenChange, existing }: { open: boolean; onOpenChange: (o: boolean) => void; existing: Set<string> }) {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const toast = useToast();
  const roles = useRoles();
  const { add } = useMemberMutations(project.id);
  const [q, setQ] = useState("");
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [roleId, setRoleId] = useState("r-member");
  const people = useQuery({ queryKey: ["principals", q], queryFn: () => api.principals.search(q), enabled: open });
  const candidates = (people.data ?? []).filter((p) => !existing.has(p.id));
  const err = isApiError(add.error) ? add.error.message : undefined;

  const submit = async () => {
    const res = await add.mutateAsync({ userIds: [...picked], roleIds: [roleId] }).catch(() => null);
    if (res) {
      toast({ tone: "success", title: t("members.added", { count: res.length }) });
      setPicked(new Set());
      setQ("");
      onOpenChange(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="md"
      title={t("members.add")}
      footer={
        <>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={add.isPending} disabled={picked.size === 0} onClick={() => void submit()}>
            {t("members.addCount", { count: picked.size })}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("members.search")} error={err}>
          <Input data-autofocus type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("members.searchPlaceholder")} />
        </Field>
        <fieldset className="max-h-64 overflow-y-auto rounded-md border border-border p-2">
          <legend className="sr-only">{t("members.candidates")}</legend>
          {people.isPending ? (
            <Skeleton className="h-16 w-full" />
          ) : candidates.length === 0 ? (
            <p className="px-2 py-3 text-sm text-fg-muted">{t("members.noCandidates")}</p>
          ) : (
            <ul className="flex flex-col gap-1">
              {candidates.map((p) => (
                <li key={p.id} className="flex items-center gap-2 rounded-sm px-2 py-1 hover:bg-surface">
                  <Checkbox
                    label={
                      <span className="inline-flex items-center gap-2">
                        <Avatar id={p.id} name={p.name} size="xs" />
                        {p.name}
                      </span>
                    }
                    checked={picked.has(p.id)}
                    onCheckedChange={(v) =>
                      setPicked((s) => {
                        const n = new Set(s);
                        if (v === true) n.add(p.id);
                        else n.delete(p.id);
                        return n;
                      })
                    }
                  />
                </li>
              ))}
            </ul>
          )}
        </fieldset>
        <Field label={t("members.role")}>
          <Select value={roleId} onValueChange={(v) => v && setRoleId(v)} options={(roles.data ?? []).map((r) => ({ value: r.id, label: r.name }))} />
        </Field>
      </div>
    </Dialog>
  );
}

export function MembersPage() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  useDocumentTitle(`${t("nav.members")} · ${project.name}`);
  const toast = useToast();
  const members = useMembers(project.id);
  const roles = useRoles();
  const { update, remove } = useMemberMutations(project.id);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Member | null>(null);
  const canManage = can(project, "project.manage_members");
  const fail = (e: unknown) => toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle"), description: isApiError(e) && e.message !== e.title ? e.message : undefined });

  return (
    <PageBody>
      <PageHeader
        title={t("nav.members")}
        breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("nav.members") }]}
        actions={
          canManage && (
            <Button variant="primary" icon={<UserPlus />} onClick={() => setAdding(true)}>
              {t("members.add")}
            </Button>
          )
        }
      />
      {members.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : members.isError ? (
        <ErrorState error={members.error} onRetry={() => members.refetch()} />
      ) : members.data.length === 0 ? (
        <EmptyState icon={<Users />} title={t("members.empty")} />
      ) : (
        <div className="relative overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[560px] border-collapse text-sm">
            <thead>
              <tr className="h-row border-b border-border bg-surface text-left text-xs font-semibold text-fg-muted">
                <th scope="col" className="px-3">{t("members.name")}</th>
                <th scope="col" className="px-3">{t("members.email")}</th>
                <th scope="col" className="w-44 px-3">{t("members.role")}</th>
                <th scope="col" className="w-28 px-3">{t("members.since")}</th>
                {canManage && (
                  <th scope="col" className="w-12 px-3">
                    <span className="sr-only">{t("app.delete")}</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {members.data.map((m) => (
                <tr key={m.id} className="h-row border-b border-border-muted last:border-b-0">
                  <td className="px-3">
                    <Principal id={m.principal.id} name={m.principal.name} />
                  </td>
                  <td className="px-3 text-fg-muted">{m.principal.email}</td>
                  <td className="px-3">
                    <InlinePick
                      value={m.roles[0]?.id ?? null}
                      disabled={!canManage}
                      label={`${m.principal.name} ${t("members.role")}: ${m.roles.map((r) => r.name).join(", ")}`}
                      onPick={(v) => v && update.mutate({ memberId: m.id, roleIds: [v] }, { onError: fail })}
                      options={(roles.data ?? []).map((r) => ({ value: r.id, label: r.name }))}
                    >
                      {m.roles.map((r) => r.name).join(", ")}
                    </InlinePick>
                  </td>
                  <td className="px-3 text-fg-muted tabular-nums">{formatDate(m.createdAt.slice(0, 10))}</td>
                  {canManage && (
                    <td className="px-2">
                      <Button variant="ghost" size="sm" iconOnly aria-label={t("members.removeOne", { name: m.principal.name })} icon={<Trash2 />} onClick={() => setRemoving(m)} />
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {members.data && <AddMembersDialog open={adding} onOpenChange={setAdding} existing={new Set(members.data.map((m) => m.principal.id))} />}
      <Dialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        size="sm"
        title={t("members.removeTitle")}
        description={removing ? t("members.removeBody", { name: removing.principal.name }) : undefined}
        footer={
          <>
            <Button data-autofocus onClick={() => setRemoving(null)}>
              {t("app.cancel")}
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() => removing && remove.mutate(removing.id, { onSuccess: () => setRemoving(null), onError: (e) => (setRemoving(null), fail(e)) })}
            >
              {t("app.delete")}
            </Button>
          </>
        }
      >
        <span />
      </Dialog>
    </PageBody>
  );
}
