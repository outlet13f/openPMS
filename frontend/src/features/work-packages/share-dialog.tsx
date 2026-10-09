// Share dialog (S07/S08 공유): give one person — also outside the project — this one item with 보기, 코멘트 or
// 편집. Needs wp.share. The person gets a "shared" notification; changing the role or removing the share does not.
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Share2, Trash2 } from "lucide-react";
import { api, isApiError, type ShareRole, type WorkPackage } from "@/api";
import { useMe, useShareMutations, useShares } from "@/api/hooks";
import { Avatar } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState } from "@/app/page";

const ROLES: ShareRole[] = ["view", "comment", "edit"];

export function ShareDialog({ wp, open, onOpenChange }: { wp: WorkPackage; open: boolean; onOpenChange: (o: boolean) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const me = useMe().data;
  const shares = useShares(wp.number, open);
  const m = useShareMutations(wp.number);
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  const [pick, setPick] = useState("");
  const [role, setRole] = useState<ShareRole>("view");
  const [error, setError] = useState<string | null>(null);
  const [announce, setAnnounce] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setDebounced(q), 250);
    return () => clearTimeout(id);
  }, [q]);
  const people = useQuery({ queryKey: ["principals", debounced, "share"], queryFn: () => api.principals.search(debounced), enabled: open });
  const taken = new Set([...(shares.data ?? []).map((s) => s.user.id), me?.id]);
  const candidates = (people.data ?? []).filter((p) => p.kind === "user" && !taken.has(p.id));
  const roleOptions = ROLES.map((r) => ({ value: r, label: `${t(`share.roles.${r}`)} — ${t(`share.roleHints.${r}`)}`, textValue: t(`share.roles.${r}`) }));

  const add = () => {
    if (!pick) return setError(t("share.pickFirst"));
    const name = candidates.find((c) => c.id === pick)?.name ?? "";
    m.add.mutate(
      { userId: pick, role },
      {
        onSuccess: () => (setPick(""), setError(null), setAnnounce(t("share.added", { name }))),
        onError: (e) => setError(isApiError(e) ? (e.fieldErrors[0]?.message ?? e.message) : t("app.errorBody")),
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange} size="lg" title={t("share.title")} description={`#${wp.number} ${wp.subject}`}>
      <div className="flex flex-col gap-5">
        <p className="text-sm text-fg-muted">{t("share.intro")}</p>
        <div className="grid grid-cols-1 items-end gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_10rem_auto]">
          <Field label={t("share.search")}>
            <Input type="search" placeholder={t("share.searchPlaceholder")} value={q} onChange={(e) => setQ(e.target.value)} />
          </Field>
          <Field label={t("share.person")} error={error ?? undefined}>
            <Select
              value={pick}
              placeholder={candidates.length ? t("app.select") : t("share.noMatch")}
              disabled={!candidates.length}
              onValueChange={(v) => (setPick(v), setError(null))}
              options={candidates.map((c) => ({ value: c.id, textValue: c.name, label: <span className="inline-flex items-center gap-2"><Avatar id={c.id} name={c.name} size="xs" />{c.name}</span> }))}
            />
          </Field>
          <Field label={t("share.role")}>
            <Select value={role} onValueChange={(v) => setRole(v as ShareRole)} options={roleOptions} />
          </Field>
          <Button variant="primary" icon={<Share2 />} loading={m.add.isPending} onClick={add}>
            {t("share.add")}
          </Button>
        </div>
        <p className="sr-only" aria-live="polite">
          {announce}
        </p>

        {shares.isPending ? (
          <Skeleton className="h-20 w-full" />
        ) : shares.isError ? (
          <ErrorState error={shares.error} onRetry={() => shares.refetch()} />
        ) : shares.data.length === 0 ? (
          <EmptyState compact icon={<Share2 />} title={t("share.empty")} />
        ) : (
          <ul aria-label={t("share.list")} className="flex flex-col">
            {shares.data.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center gap-3 border-b border-border-muted py-2 last:border-b-0">
                <Avatar id={s.user.id} name={s.user.name} size="sm" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm text-fg">{s.user.name}</span>
                  {s.sharedBy && <span className="block text-xs text-fg-muted">{t("share.sharedBy", { name: s.sharedBy.name })}</span>}
                </span>
                <Select
                  size="sm"
                  className="w-28"
                  aria-label={t("share.roleOf", { name: s.user.name })}
                  value={s.role}
                  onValueChange={(v) =>
                    m.update.mutate({ id: s.id, role: v as ShareRole }, { onError: (e) => toast({ tone: "danger", title: isApiError(e) ? e.message : t("app.errorTitle") }) })
                  }
                  options={ROLES.map((r) => ({ value: r, label: t(`share.roles.${r}`) }))}
                />
                <Button
                  variant="ghost"
                  size="sm"
                  iconOnly
                  aria-label={t("share.remove", { name: s.user.name })}
                  icon={<Trash2 />}
                  onClick={() =>
                    m.remove.mutate(s.id, {
                      onSuccess: () => setAnnounce(t("share.removed", { name: s.user.name })),
                      onError: (e) => toast({ tone: "danger", title: isApiError(e) ? e.message : t("app.errorTitle") }),
                    })
                  }
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </Dialog>
  );
}
