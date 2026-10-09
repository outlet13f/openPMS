// Watchers tab (S07/S08 주시자): who gets "watched" notifications for this item. Members with
// wp.add_watchers add people who can see the item; anyone can stop watching, others need wp.delete_watchers.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Eye, UserPlus, X } from "lucide-react";
import { isApiError, type WorkPackage } from "@/api";
import { useFilterOptions, useWatcherMutations, useWatchers } from "@/api/hooks";
import { Avatar } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState } from "@/app/page";

export function WatchersTab({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const toast = useToast();
  const list = useWatchers(wp.number);
  const { add, remove } = useWatcherMutations(wp.number);
  const canAdd = wp._meta.allowedActions.addWatchers;
  const people = useFilterOptions(wp.project.id).data?.users ?? [];
  const [pick, setPick] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [announce, setAnnounce] = useState("");

  if (list.isPending) return <Skeleton className="mt-2 h-24 w-full" />;
  if (list.isError) return <ErrorState error={list.error} onRetry={() => list.refetch()} />;
  const watching = new Set(list.data.map((w) => w.user.id));
  const candidates = people.filter((u) => !watching.has(u.id));

  const submit = () => {
    if (!pick) return setError(t("watchers.pickFirst"));
    const name = people.find((u) => u.id === pick)?.name ?? "";
    add.mutate(pick, {
      onSuccess: () => (setPick(""), setError(null), setAnnounce(t("watchers.added", { name }))),
      onError: (e) => setError(isApiError(e) ? (e.fieldErrors[0]?.message ?? e.message) : t("app.errorBody")),
    });
  };

  return (
    <div className="flex flex-col gap-4 pt-2">
      {canAdd && (
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <Select
              aria-label={t("watchers.person")}
              aria-invalid={!!error}
              size="sm"
              className="min-w-0 flex-1"
              value={pick}
              placeholder={candidates.length ? t("watchers.choose") : t("watchers.everyone")}
              disabled={!candidates.length}
              onValueChange={(v) => (setPick(v), setError(null))}
              options={candidates.map((u) => ({ value: u.id, textValue: u.name, label: <span className="inline-flex items-center gap-2"><Avatar id={u.id} name={u.name} size="xs" />{u.name}</span> }))}
            />
            <Button size="sm" icon={<UserPlus />} loading={add.isPending} disabled={!candidates.length} onClick={submit}>
              {t("watchers.add")}
            </Button>
          </div>
          {error && <p className="text-xs text-danger">{error}</p>}
        </div>
      )}
      <p className="sr-only" aria-live="polite">
        {announce}
      </p>

      {list.data.length === 0 ? (
        <EmptyState compact icon={<Eye />} title={t("watchers.empty")}>
          {t("watchers.emptyBody")}
        </EmptyState>
      ) : (
        <ul className="flex flex-col" aria-label={t("wp.tabs.watchers")}>
          {list.data.map((w) => (
            <li key={w.user.id} className="flex items-center gap-3 border-b border-border-muted py-2 last:border-b-0">
              <Avatar id={w.user.id} name={w.user.name} size="sm" />
              <span className="min-w-0 flex-1 truncate text-sm text-fg">{w.user.name}</span>
              {w.canRemove && (
                <Button
                  variant="ghost"
                  size="sm"
                  iconOnly
                  aria-label={t("watchers.remove", { name: w.user.name })}
                  icon={<X />}
                  loading={remove.isPending && remove.variables === w.user.id}
                  onClick={() =>
                    remove.mutate(w.user.id, {
                      onSuccess: () => setAnnounce(t("watchers.removed", { name: w.user.name })),
                      onError: (e) => toast({ tone: "danger", title: isApiError(e) ? e.message : t("app.errorTitle") }),
                    })
                  }
                />
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
