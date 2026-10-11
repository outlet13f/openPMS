// Activity tab (components.md "ActivityStream"): journal entries and comments with @mentions.
// Comments are written in the rich text editor (Tiptap) with @mentions; Ctrl+Enter sends. A filter shows everything,
// comments only or changes only (remembered in this browser); the sort flips the account setting 최신 활동 위로.
// Where the project has internal comments on, permitted roles may mark a comment internal (only they read it).
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDownUp, Lock, MessageSquare, Pencil, Send } from "lucide-react";
import type { Journal, UserRef, WorkPackage } from "@/api";
import { isApiError } from "@/api";
import { useAccountMutations, useActivities, useComment, useEditComment, useFilterOptions, useMe } from "@/api/hooks";
import { Checkbox, Segmented } from "@/components/ui/choice";
import { useUnsavedWarning } from "@/lib/use-unsaved-warning";
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
  useUnsavedWarning(editing && draft !== j.notesHtml);
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
          {j.isInternal && (
            <span className="ml-1.5 inline-flex items-center gap-1 rounded-sm bg-warning-subtle px-1.5 text-xs font-medium text-warning">
              <Lock className="size-3" aria-hidden="true" />
              {t("activity.internal")}
            </span>
          )}
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
        {j.notesHtml && !editing && (
          <RichText html={j.notesHtml} className={j.isInternal ? "mt-1.5 rounded-md border border-warning bg-warning-subtle px-3 py-2" : "mt-1.5 rounded-md bg-surface px-3 py-2"} />
        )}
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
  const [internal, setInternal] = useState(false);
  useUnsavedWarning(!!html);

  const submit = async () => {
    if (!html) return setError(t("activity.commentEmpty"));
    try {
      await comment.mutateAsync({ bodyHtml: html, internal: internal && wp._meta.allowedActions.commentInternal });
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
      <div className="mt-2 flex flex-wrap items-center justify-between gap-3">
        {wp._meta.allowedActions.commentInternal && (
          <Checkbox label={t("activity.markInternal")} description={t("activity.markInternalHint")} checked={internal} onCheckedChange={(c) => setInternal(c === true)} />
        )}
        <Button variant="primary" size="sm" icon={<Send />} loading={comment.isPending} onClick={() => void submit()}>
          {t("activity.commentSubmit")}
        </Button>
      </div>
    </div>
  );
}

type ActivityFilter = "all" | "comments" | "changes";
const FILTER_KEY = "openpms.activityFilter";
function storedFilter(): ActivityFilter {
  try {
    const v = localStorage.getItem(FILTER_KEY);
    return v === "comments" || v === "changes" ? v : "all";
  } catch {
    return "all";
  }
}
function rememberFilter(v: ActivityFilter) {
  try {
    localStorage.setItem(FILTER_KEY, v);
  } catch {
    // private window: the choice lasts until the page closes
  }
}

export function ActivityTab({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const activities = useActivities(wp.number);
  const people = useFilterOptions(wp.project.id).data?.users ?? [];
  // S37 "최신 활동을 맨 위에": newest first, with the comment box on top
  const newestFirst = useMe().data?.preferences.commentsNewestFirst ?? false;
  const account = useAccountMutations();
  const [show, setShow] = useState<ActivityFilter>(storedFilter);
  if (activities.isPending)
    return (
      <div className="flex flex-col gap-3 py-3">
        {Array.from({ length: 3 }, (_, i) => (
          <Skeleton key={i} className="h-10 w-full" />
        ))}
      </div>
    );
  if (activities.isError) return <ErrorState error={activities.error} onRetry={() => activities.refetch()} />;
  const shown = activities.data.filter((j) => show === "all" || (show === "comments" ? !!j.notesHtml : j.details.length > 0 && !j.notesHtml));
  const entries = newestFirst ? [...shown].reverse() : shown;
  const box = wp._meta.allowedActions.comment ? <CommentBox wp={wp} /> : <p className="mt-3 text-sm text-fg-muted">{t("activity.noPermission")}</p>;
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2 py-2">
        <Segmented
          aria-label={t("activity.filter")}
          value={show}
          onValueChange={(v) => (setShow(v as ActivityFilter), rememberFilter(v as ActivityFilter))}
          options={(["all", "comments", "changes"] as const).map((f) => ({ value: f, label: t(`activity.filters.${f}`) }))}
        />
        <Button
          size="sm"
          variant="ghost"
          icon={<ArrowDownUp />}
          loading={account.preferences.isPending}
          onClick={() => account.preferences.mutate({ commentsNewestFirst: !newestFirst })}
          aria-label={t("activity.sortToggle", { order: newestFirst ? t("activity.newestFirst") : t("activity.oldestFirst") })}
        >
          {newestFirst ? t("activity.newestFirst") : t("activity.oldestFirst")}
        </Button>
      </div>
      {newestFirst && box}
      {entries.length === 0 ? (
        <EmptyState compact icon={<MessageSquare />} title={show === "all" ? t("activity.empty") : t("activity.emptyFiltered")} />
      ) : (
        <ol className="divide-y divide-border-muted" aria-label={t("activity.title")}>
          {entries.map((j) => (
            <Entry key={j.id} j={j} wpNumber={wp.number} people={people} />
          ))}
        </ol>
      )}
      {!newestFirst && box}
    </div>
  );
}
