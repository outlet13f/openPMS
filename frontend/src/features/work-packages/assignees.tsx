// 공동 담당자 (replica/fixes.md 6번): the people who share a work package, each with their own estimate, and the dialog
// that edits the list. The first person is the main assignee (the one the list sorts and groups by); the 담당자 row of
// the detail keeps its quick pick for that person, and this dialog handles everyone.
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowUp, Plus, Trash2, Users } from "lucide-react";
import type { Assignee, UserRef, WorkPackage } from "@/api";
import { Avatar, Principal } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/field";
import { Select } from "@/components/ui/select";
import { Dialog } from "@/components/ui/overlay";
import { Counter } from "@/components/ui/badges";


/** Co-assignees under the main one on the detail: name and hours each. */
export function CoAssignees({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const others = wp.assignees.slice(1);
  const main = wp.assignees[0];
  if (!others.length && main?.workHours == null) return null;
  return (
    <ul aria-label={t("wp.assignees.listLabel")} className="mt-1 flex flex-col gap-1">
      {main?.workHours != null && (
        <li className="text-xs text-fg-muted">{t("wp.assignees.mainHours", { hours: main.workHours })}</li>
      )}
      {others.map((a) => (
        <li key={a.user.id} className="flex items-center gap-2">
          <Principal id={a.user.id} name={a.user.name} size="xs" />
          {a.workHours !== null && <span className="text-xs text-fg-muted tabular-nums">{t("wp.hoursUnit", { value: a.workHours })}</span>}
        </li>
      ))}
    </ul>
  );
}

/** "+2" beside the main assignee in a table cell or on a card; its accessible name lists the others. */
export function MoreAssignees({ assignees }: { assignees: Assignee[] }) {
  const { t } = useTranslation();
  const others = assignees.slice(1);
  if (!others.length) return null;
  const names = others.map((a) => a.user.name).join(", ");
  return (
    <span title={names} aria-label={t("wp.assignees.more", { count: others.length, names })} className="ml-1 inline-flex">
      <Counter>+{others.length}</Counter>
    </span>
  );
}

/** Small stacked avatars for cards: up to three, then +N. */
export function AssigneeAvatars({ assignees }: { assignees: Assignee[] }) {
  const { t } = useTranslation();
  const shown = assignees.slice(0, 3);
  const names = assignees.map((a) => a.user.name).join(", ");
  return (
    <span role="img" aria-label={t("wp.assignees.cardLabel", { names })} title={names} className="inline-flex items-center -space-x-1.5">
      {shown.map((a) => (
        <Avatar key={a.user.id} id={a.user.id} name={a.user.name} size="xs" className="ring-2 ring-bg" />
      ))}
      {assignees.length > 3 && <span className="ml-2 text-xs text-fg-muted">+{assignees.length - 3}</span>}
    </span>
  );
}

interface Row {
  userId: string;
  hours: string;
}

/** The whole list: add people, give each an estimate, make someone the main assignee, take people off. */
export function AssigneesDialog({
  wp,
  people,
  open,
  onOpenChange,
  onSave,
}: {
  wp: WorkPackage;
  /** who can be assigned (project members) */
  people: UserRef[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSave: (list: { userId: string; workHours: number | null }[]) => Promise<boolean>;
}) {
  const { t } = useTranslation();
  const [rows, setRows] = useState<Row[]>([]);
  const [adding, setAdding] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (open) {
      setRows(wp.assignees.map((a) => ({ userId: a.user.id, hours: a.workHours === null ? "" : String(a.workHours) })));
      setError(null);
      setAdding("");
    }
  }, [open, wp.assignees]);
  const nameOf = (id: string) => people.find((p) => p.id === id)?.name ?? wp.assignees.find((a) => a.user.id === id)?.user.name ?? "";
  const free = people.filter((p) => !rows.some((r) => r.userId === p.id));
  const total = rows.reduce((n, r) => n + (r.hours.trim() === "" ? 0 : Number(r.hours) || 0), 0);
  const set = (i: number, hours: string) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, hours } : r)));

  const submit = async () => {
    const bad = rows.find((r) => r.hours.trim() !== "" && (!Number.isFinite(Number(r.hours)) || Number(r.hours) < 0 || Number(r.hours) > 9999));
    if (bad) return setError(t("wp.assignees.badHours", { name: nameOf(bad.userId) }));
    setSaving(true);
    const ok = await onSave(rows.map((r) => ({ userId: r.userId, workHours: r.hours.trim() === "" ? null : Number(r.hours) })));
    setSaving(false);
    if (ok) onOpenChange(false);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={t("wp.assignees.title")}
      description={t("wp.assignees.hint")}
      footer={
        <>
          <Button variant="secondary" onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button onClick={() => void submit()} loading={saving}>{t("app.save")}</Button>
        </>
      }
    >
      {rows.length === 0 ? (
        <p className="py-3 text-sm text-fg-muted">{t("wp.unassigned")}</p>
      ) : (
        <ol aria-label={t("wp.assignees.title")} className="flex flex-col divide-y divide-border-muted">
          {rows.map((r, i) => {
            const name = nameOf(r.userId);
            return (
              <li key={r.userId} className="flex flex-wrap items-center gap-2 py-2">
                <span className="flex min-w-0 flex-1 items-center gap-2">
                  <Principal id={r.userId} name={name} size="sm" />
                  {i === 0 && <span className="rounded-sm bg-accent-subtle px-1.5 py-0.5 text-xs text-accent-subtle-text">{t("wp.assignees.main")}</span>}
                </span>
                <label className="flex items-center gap-1.5 text-sm text-fg-muted">
                  <span className="sr-only">{t("wp.assignees.hoursOf", { name })}</span>
                  <Input
                    size="sm"
                    type="number"
                    min={0}
                    max={9999}
                    step={0.5}
                    inputMode="decimal"
                    value={r.hours}
                    onChange={(e) => set(i, e.target.value)}
                    placeholder={t("wp.assignees.hoursPlaceholder")}
                    className="w-24 text-right tabular-nums"
                    aria-invalid={!!error && error.includes(name)}
                  />
                  <span aria-hidden="true">{t("wp.assignees.hoursSuffix")}</span>
                </label>
                {i > 0 && (
                  <Button variant="ghost" size="sm" iconOnly icon={<ArrowUp />} aria-label={t("wp.assignees.makeMain", { name })}
                    onClick={() => setRows((rs) => [rs[i], ...rs.filter((_, j) => j !== i)])} />
                )}
                <Button variant="ghost" size="sm" iconOnly icon={<Trash2 />} aria-label={t("wp.assignees.remove", { name })}
                  onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))} />
              </li>
            );
          })}
        </ol>
      )}
      <div className="mt-3 flex items-end gap-2">
        <div className="min-w-0 flex-1">
          <Select
            aria-label={t("wp.assignees.addLabel")}
            placeholder={free.length ? t("wp.assignees.addPlaceholder") : t("wp.assignees.nobodyLeft")}
            disabled={!free.length}
            value={adding}
            onValueChange={setAdding}
            options={free.map((p) => ({ value: p.id, label: p.name, textValue: p.name }))}
          />
        </div>
        <Button variant="secondary" icon={<Plus />} disabled={!adding} onClick={() => (setRows((rs) => [...rs, { userId: adding, hours: "" }]), setAdding(""))}>
          {t("wp.assignees.add")}
        </Button>
      </div>
      <p className="mt-3 flex items-center gap-1.5 text-sm text-fg-muted" aria-live="polite">
        <Users aria-hidden="true" className="size-4" />
        {t("wp.assignees.total", { count: rows.length, hours: Math.round(total * 100) / 100 })}
        {wp.workHours !== null && <span>{t("wp.assignees.ofWork", { work: wp.workHours })}</span>}
      </p>
      {error && <p role="alert" className="mt-2 text-sm text-danger">{error}</p>}
    </Dialog>
  );
}
