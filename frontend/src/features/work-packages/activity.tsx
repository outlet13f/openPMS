// Activity tab (components.md "ActivityStream"): journal entries and comments with @mentions.
// Comments are written in the rich text editor (Tiptap) with @mentions; Ctrl+Enter sends.
import { useState } from "react";
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
import { RichTextEditor } from "@/components/domain/rich-text-editor";
import { ErrorState } from "@/app/page";

function Entry({ j, wpNumber, people }: { j: Journal; wpNumber: number; people: UserRef[] }) {
  const { t } = useTranslation();
  const changes = j.details.filter((d) => d.field !== "_created");
  const edit = useEditComment(wpNumber);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const saveEdit = async () => {
    if (!draft) return setError(t("activity.commentEmpty"));
    try {
      await edit.mutateAsync({ journalId: j.id, bodyHtml: draft });
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
              onClick={() => (setDraft(j.notesHtml), setEditing(true))}
            />
          )}
        </p>
        {j.cause && <p className="mt-0.5 text-xs text-fg-muted">{t(`activity.becauseOf.${j.cause.kind}`, { number: j.cause.workPackageNumber })}</p>}
        {j.details.some((d) => d.field === "_created") && <p className="mt-0.5 text-sm text-fg-muted">{t("activity.created")}</p>}
        {changes.length > 0 && (
          <ul className="mt-1 flex flex-col gap-0.5 text-sm text-fg-muted">
            {changes.map((d) => (
              <li key={d.field}>
                {d.field.startsWith("attachment")
                  ? d.to
                    ? t("activity.attached", { name: d.to })
                    : t("activity.detached", { name: d.from })
                  : d.field === "descriptionHtml"
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
            <RichTextEditor
              value={j.notesHtml}
              onChange={setDraft}
              label={t("activity.editComment")}
              people={people}
              autoFocus
              invalid={!!error}
              onSubmit={() => void saveEdit()}
              onCancel={() => setEditing(false)}
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
  const [html, setHtml] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [round, setRound] = useState(0); // a fresh, empty editor after each comment

  const submit = async () => {
    if (!html) return setError(t("activity.commentEmpty"));
    try {
      await comment.mutateAsync(html);
      setHtml("");
      setError(null);
      setRound((r) => r + 1);
    } catch (e) {
      setError(isApiError(e) ? e.message : t("app.errorBody"));
    }
  };

  return (
    <div className="relative mt-2 border-t border-border-muted pt-3">
      <RichTextEditor
        key={round}
        value=""
        onChange={(v) => (setHtml(v), error && setError(null))}
        label={t("activity.commentLabel")}
        placeholder={t("activity.commentPlaceholder")}
        people={people}
        onSubmit={() => void submit()}
        invalid={!!error}
      />
      {error && <p className="mt-1 text-xs text-danger">{error}</p>}
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
