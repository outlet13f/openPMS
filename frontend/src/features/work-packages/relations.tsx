// Relations tab (S08): parent, children and typed relations, with add/remove.
import { useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Network, Plus, Trash2 } from "lucide-react";
import { api, isApiError, type RelationType, type WorkPackage } from "@/api";
import { useRelationMutations, useRelations } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Counter, StatusPill, TypeLabel } from "@/components/ui/badges";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { formatDateRange } from "@/lib/dates";
import { ErrorState } from "@/app/page";

type Item = { id: string; number: number; subject: string; type: WorkPackage["type"]; status: WorkPackage["status"]; project: WorkPackage["project"]; startDate: string | null; dueDate: string | null };

function RelatedCard({ item, onRemove, removeLabel }: { item: Item; onRemove?: () => void; removeLabel?: string }) {
  return (
    <li className="flex items-start gap-2 border-b border-border-muted px-3 py-2.5 last:border-b-0">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2 text-xs text-fg-muted">
          <TypeLabel name={item.type.name} color={item.type.color} />
          <span className="tabular-nums">#{item.number}</span>
          <StatusPill size="sm" name={item.status.name} color={item.status.color} />
        </div>
        <Link to={`/projects/${item.project.identifier}/work-packages/${item.number}/relations`} className="mt-0.5 block text-sm font-medium text-fg hover:underline">
          {item.subject}
        </Link>
        {(item.startDate || item.dueDate) && <p className="text-xs text-fg-muted tabular-nums">{formatDateRange(item.startDate, item.dueDate)}</p>}
      </div>
      {onRemove && <Button variant="ghost" size="sm" iconOnly aria-label={removeLabel} icon={<Trash2 />} onClick={onRemove} />}
    </li>
  );
}

function Group({ title, count, children }: { title: string; count: number; children: ReactNode }) {
  return (
    <section aria-label={title} className="overflow-hidden rounded-md border border-border">
      <h3 className="flex items-center gap-2 bg-surface px-3 py-2 text-sm font-semibold text-fg">
        {title} <Counter>{count}</Counter>
      </h3>
      <ul>{children}</ul>
    </section>
  );
}

const ORDER: RelationType[] = ["follows", "precedes", "relates", "blocks", "blocked", "duplicates", "duplicated", "includes", "partof", "requires", "required"];

function AddRelation({ wp, onDone }: { wp: WorkPackage; onDone: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const { add } = useRelationMutations(wp.number);
  const [type, setType] = useState<RelationType>("follows");
  const [q, setQ] = useState("");
  const [picked, setPicked] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const hits = useQuery({ queryKey: ["wp-autocomplete", q], queryFn: () => api.workPackages.autocomplete(q), enabled: q.trim().length > 0 && picked === null });

  const submit = async () => {
    if (!picked) {
      setError(t("relations.pick"));
      return;
    }
    try {
      await add.mutateAsync({ type, toNumber: picked });
      toast({ tone: "success", title: t("relations.added") });
      onDone();
    } catch (e) {
      setError(isApiError(e) ? e.message : t("app.errorBody"));
    }
  };

  return (
    <div className="mb-4 flex flex-col gap-3 rounded-md border border-border bg-surface p-3">
      <Field label={t("relations.add")} hint={t(`relations.typeHints.${type}` as "relations.typeHints.relates", { defaultValue: "" }) || undefined}>
        <Select value={type} onValueChange={(v) => setType(v as RelationType)} options={ORDER.map((r) => ({ value: r, label: t(`relations.types.${r}`) }))} />
      </Field>
      <Field label={t("relations.pick")} error={error ?? undefined}>
        <Input
          data-autofocus
          autoFocus
          value={q}
          onChange={(e) => (setQ(e.target.value), setPicked(null), setError(null))}
          placeholder="#12"
          role="combobox"
          aria-expanded={!!hits.data?.length && picked === null}
          aria-controls="relation-candidates"
        />
      </Field>
      {picked === null && hits.data && hits.data.length > 0 && (
        <ul id="relation-candidates" role="listbox" aria-label={t("relations.pick")} className="max-h-48 overflow-y-auto rounded-md border border-border bg-bg p-1">
          {hits.data
            .filter((h) => h.number !== wp.number)
            .map((h) => (
              <li key={h.id} role="option" aria-selected={false}>
                <button type="button" onClick={() => (setPicked(h.number), setQ(`#${h.number} ${h.subject}`))} className="w-full rounded-sm px-2 py-1.5 text-left text-sm text-fg hover:bg-surface-hover">
                  <span className="text-fg-muted tabular-nums">#{h.number}</span> {h.subject} <span className="text-xs text-fg-muted">· {h.project.name}</span>
                </button>
              </li>
            ))}
        </ul>
      )}
      <div className="flex justify-end gap-2">
        <Button size="sm" onClick={onDone}>
          {t("app.cancel")}
        </Button>
        <Button size="sm" variant="primary" loading={add.isPending} onClick={() => void submit()}>
          {t("relations.add")}
        </Button>
      </div>
    </div>
  );
}

export function RelationsTab({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const rel = useRelations(wp.number);
  const { remove } = useRelationMutations(wp.number);
  const [adding, setAdding] = useState(false);
  const canManage = wp._meta.allowedActions.manageRelations;

  if (rel.isPending) return <Skeleton className="h-24 w-full" />;
  if (rel.isError) return <ErrorState error={rel.error} onRetry={() => rel.refetch()} />;
  const { relations, parent, children } = rel.data;
  const empty = !relations.length && !parent && !children.length;

  return (
    <div className="flex flex-col gap-3">
      {canManage && !adding && (
        <div>
          <Button size="sm" icon={<Plus />} onClick={() => setAdding(true)}>
            {t("relations.add")}
          </Button>
        </div>
      )}
      {adding && <AddRelation wp={wp} onDone={() => setAdding(false)} />}
      {empty && !adding && (
        <EmptyState compact icon={<Network />} title={t("relations.empty")}>
          {t("relations.emptyBody")}
        </EmptyState>
      )}
      {parent && (
        <Group title={t("relations.parent")} count={1}>
          <RelatedCard item={parent} />
        </Group>
      )}
      {children.length > 0 && (
        <Group title={t("relations.children")} count={children.length}>
          {children.map((c) => (
            <RelatedCard key={c.id} item={c} />
          ))}
        </Group>
      )}
      {ORDER.map((type) => {
        const list = relations.filter((r) => r.type === type);
        if (!list.length) return null;
        return (
          <Group key={type} title={t(`relations.types.${type}`)} count={list.length}>
            {list.map((r) => (
              <RelatedCard key={r.id} item={r.other} removeLabel={`${t("relations.remove")}: #${r.other.number}`} onRemove={canManage ? () => remove.mutate(r.id) : undefined} />
            ))}
          </Group>
        );
      })}
    </div>
  );
}
