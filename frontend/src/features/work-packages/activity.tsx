// Activity tab (components.md "ActivityStream"): journal entries and comments with @mentions.
// partial: plain-text comment box with a mention picker instead of the Tiptap rich text editor.
import { useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { MessageSquare, Pencil, Send } from "lucide-react";
import type { Journal, UserRef, WorkPackage } from "@/api";
import { isApiError } from "@/api";
import { useActivities, useComment, useEditComment, useFilterOptions } from "@/api/hooks";
import { Avatar } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { RichText } from "@/components/domain/rich-text";
import { formatDateTime, relativeTime } from "@/lib/dates";
import { htmlToText, textToHtml } from "@/lib/text";
import { Textarea } from "@/components/ui/field";
import { cn } from "@/lib/cn";
import { ErrorState } from "@/app/page";

function Entry({ j, wpNumber, people }: { j: Journal; wpNumber: number; people: UserRef[] }) {
  const { t } = useTranslation();
  const changes = j.details.filter((d) => d.field !== "_created");
  const edit = useEditComment(wpNumber);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const saveEdit = async () => {
    if (!draft.trim()) return setError(t("activity.commentEmpty"));
    try {
      await edit.mutateAsync({ journalId: j.id, bodyHtml: textToHtml(draft, people) });
      setEditing(false);
      setError(null);
    } catch (e) {
      setError(isApiError(e) ? e.message : t("app.errorBody"));
    }
  };
  return (
    <li className="flex gap-3 py-3">
      <Avatar id={j.user.id} name={j.user.name} size="sm" className="mt-0.5" />
      <div className="min-w-0 flex-1">
        <p className="text-sm">
          <span className="font-semibold text-fg">{j.user.name}</span>{" "}
          <time dateTime={j.createdAt} title={formatDateTime(j.createdAt)} className="text-xs text-fg-muted">
            {relativeTime(j.createdAt)}
          </time>
          {j.updatedAt !== j.createdAt && j.notesHtml && <span className="ml-1 text-xs text-fg-muted">· {t("activity.edited")}</span>}
          {j.canEdit && !editing && (
            <Button
              variant="ghost"
              size="sm"
              iconOnly
              className="ml-1 align-middle"
              aria-label={t("activity.editComment")}
              icon={<Pencil />}
              onClick={() => (setDraft(htmlToText(j.notesHtml)), setEditing(true))}
            />
          )}
        </p>
        {j.cause && <p className="mt-0.5 text-xs text-fg-muted">{t(`activity.becauseOf.${j.cause.kind}`, { number: j.cause.workPackageNumber })}</p>}
        {j.details.some((d) => d.field === "_created") && <p className="mt-0.5 text-sm text-fg-muted">{t("activity.created")}</p>}
        {changes.length > 0 && (
          <ul className="mt-1 flex flex-col gap-0.5 text-sm text-fg-muted">
            {changes.map((d) => (
              <li key={d.field}>
                {d.field === "descriptionHtml"
                  ? t("activity.descriptionChanged")
                  : d.to === null
                    ? t("activity.cleared", { label: d.label })
                    : d.from === null
                      ? t("activity.set", { label: d.label, to: d.to })
                      : t("activity.changed", { label: d.label, from: d.from, to: d.to })}
              </li>
            ))}
          </ul>
        )}
        {j.notesHtml && !editing && <RichText html={j.notesHtml} className="mt-1.5 rounded-md bg-surface px-3 py-2" />}
        {editing && (
          <div className="mt-1.5 flex flex-col gap-2">
            <label htmlFor={`edit-${j.id}`} className="sr-only">
              {t("activity.editComment")}
            </label>
            <Textarea
              id={`edit-${j.id}`}
              rows={3}
              autoFocus
              value={draft}
              aria-invalid={!!error}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") setEditing(false);
                if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void saveEdit();
              }}
            />
            {error && <p className="text-xs text-danger">{error}</p>}
            <div className="flex justify-end gap-2">
              <Button size="sm" onClick={() => setEditing(false)}>
                {t("app.cancel")}
              </Button>
              <Button size="sm" variant="primary" loading={edit.isPending} onClick={() => void saveEdit()}>
                {t("app.save")}
              </Button>
            </div>
          </div>
        )}
      </div>
    </li>
  );
}

function CommentBox({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const people = useFilterOptions(wp.project.id).data?.users ?? [];
  const comment = useComment(wp.number);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [caret, setCaret] = useState(0);
  const [active, setActive] = useState(0);
  const [dismissed, setDismissed] = useState(false);
  const ref = useRef<HTMLTextAreaElement>(null);

  // "@이" right before the caret → suggest members whose name contains "이"
  const query = useMemo(() => {
    if (dismissed) return null;
    const m = text.slice(0, caret).match(/(?:^|\s)@([^\s@]{0,20})$/);
    return m ? m[1] : null;
  }, [text, caret, dismissed]);
  const matches = query === null ? [] : people.filter((p) => p.name.toLowerCase().includes(query.toLowerCase())).slice(0, 6);

  const insert = (name: string) => {
    const before = text.slice(0, caret).replace(/@([^\s@]{0,20})$/, `@${name} `);
    const next = before + text.slice(caret);
    setText(next);
    setActive(0);
    requestAnimationFrame(() => {
      ref.current?.focus();
      ref.current?.setSelectionRange(before.length, before.length);
      setCaret(before.length);
    });
  };

  const submit = async () => {
    if (!text.trim()) {
      setError(t("activity.commentEmpty"));
      ref.current?.focus();
      return;
    }
    try {
      await comment.mutateAsync(textToHtml(text, people));
      setText("");
      setError(null);
    } catch (e) {
      setError(isApiError(e) ? e.message : t("app.errorBody"));
    }
  };

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (matches.length) {
      if (e.key === "ArrowDown") return (e.preventDefault(), setActive((a) => (a + 1) % matches.length));
      if (e.key === "ArrowUp") return (e.preventDefault(), setActive((a) => (a - 1 + matches.length) % matches.length));
      if (e.key === "Enter" || e.key === "Tab") return (e.preventDefault(), insert(matches[active].name));
      if (e.key === "Escape") return (e.preventDefault(), setDismissed(true));
    }
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      void submit();
    }
  };

  return (
    <div className="relative mt-2 border-t border-border-muted pt-3">
      <label htmlFor={`comment-${wp.number}`} className="sr-only">
        {t("activity.commentLabel")}
      </label>
      <textarea
        id={`comment-${wp.number}`}
        ref={ref}
        rows={3}
        value={text}
        placeholder={t("activity.commentPlaceholder")}
        aria-invalid={!!error}
        aria-describedby={error ? `comment-${wp.number}-err` : undefined}
        aria-autocomplete="list"
        aria-controls={matches.length ? `mention-${wp.number}` : undefined}
        aria-activedescendant={matches.length ? `mention-${wp.number}-${active}` : undefined}
        onChange={(e) => (setText(e.target.value), setCaret(e.target.selectionStart ?? 0), setActive(0), setDismissed(false))}
        onSelect={(e) => setCaret(e.currentTarget.selectionStart ?? 0)}
        onKeyDown={onKey}
        className="w-full rounded-md border border-border-input bg-bg px-2.5 py-2 text-sm text-fg placeholder:text-fg-muted focus-visible:border-focus-ring focus-visible:outline-2 focus-visible:outline-offset-0 focus-visible:outline-focus-ring aria-invalid:border-danger"
      />
      {matches.length > 0 && (
        <ul id={`mention-${wp.number}`} role="listbox" aria-label={t("activity.mentionHint")} className="absolute bottom-full left-0 z-(--pms-z-dropdown) mb-1 w-60 rounded-lg border border-border bg-bg p-1 shadow-pop">
          {matches.map((p, i) => (
            <li
              key={p.id}
              id={`mention-${wp.number}-${i}`}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => (e.preventDefault(), insert(p.name))}
              className={cn("flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm", i === active && "bg-surface-hover")}
            >
              <Avatar id={p.id} name={p.name} size="xs" />
              {p.name}
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p id={`comment-${wp.number}-err`} className="mt-1 text-xs text-danger">
          {error}
        </p>
      )}
      <div className="mt-2 flex justify-end">
        <Button variant="primary" size="sm" icon={<Send />} loading={comment.isPending} onClick={() => void submit()}>
          {t("activity.commentSubmit")}
        </Button>
      </div>
    </div>
  );
}

export function ActivityTab({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const activities = useActivities(wp.number);
  const people = useFilterOptions(wp.project.id).data?.users ?? [];
  if (activities.isPending)
    return (
      <div className="flex flex-col gap-3 py-3">
        {Array.from({ length: 3 }, (_, i) => (
          <Skeleton key={i} className="h-10 w-full" />
        ))}
      </div>
    );
  if (activities.isError) return <ErrorState error={activities.error} onRetry={() => activities.refetch()} />;
  return (
    <div>
      {activities.data.length === 0 ? (
        <EmptyState compact icon={<MessageSquare />} title={t("activity.empty")} />
      ) : (
        <ol className="divide-y divide-border-muted" aria-label={t("activity.title")}>
          {activities.data.map((j) => (
            <Entry key={j.id} j={j} wpNumber={wp.number} people={people} />
          ))}
        </ol>
      )}
      {wp._meta.allowedActions.comment ? <CommentBox wp={wp} /> : <p className="mt-3 text-sm text-fg-muted">{t("activity.noPermission")}</p>}
    </div>
  );
}
