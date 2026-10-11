// Work package detail, shared by S07 (split panel) and S08 (full page).
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { ArrowLeft, Copy, Eye, EyeOff, FolderInput, Link2, Maximize2, MoreHorizontal, Pencil, Share2, Trash2, X } from "lucide-react";
import type { WorkPackage } from "@/api";
import { useDeleteWorkPackage, useProject, useWatch, useWorkPackage } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Banner, useToast } from "@/components/ui/feedback";
import { Dialog, Menu, Tooltip } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { Badge, TypeLabel } from "@/components/ui/badges";
import { Tabs } from "@/components/ui/tabs";
import { RichText } from "@/components/domain/rich-text";
import { FilesTab } from "./files";
import { WatchersTab } from "./watchers";
import { ShareDialog } from "./share-dialog";
import { CopyDialog, MoveDialog } from "./transfer-dialogs";
import { formatDate, relativeTime } from "@/lib/dates";
import { RichTextEditor } from "@/components/domain/rich-text-editor";
import { useUnsavedWarning } from "@/lib/use-unsaved-warning";
import { cn } from "@/lib/cn";
import { ErrorState, useDocumentTitle } from "@/app/page";
import { Attributes } from "./attributes";
import { ActivityTab } from "./activity";
import { RelationsTab } from "./relations";
import { TimeTab } from "./time-tab";
import { StatusMenu } from "./status-menu";
import { InlineText } from "./inline-text";
import { useSaveWorkPackage } from "./use-save";

export const DETAIL_TABS = ["overview", "activity", "files", "relations", "watchers", "time"] as const;
export type DetailTab = (typeof DETAIL_TABS)[number];
/** A route's ":tab" segment, or the fallback when it is missing or unknown. */
export const asDetailTab = (s: string | undefined, fallback: DetailTab): DetailTab => (DETAIL_TABS.includes(s as DetailTab) ? (s as DetailTab) : fallback);

function Description({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const { save, isPending } = useSaveWorkPackage();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const can = wp._meta.editableFields.includes("description");
  useUnsavedWarning(editing && draft !== wp.descriptionHtml);

  if (editing)
    return (
      <div className="flex flex-col gap-2">
        <RichTextEditor
          value={wp.descriptionHtml}
          onChange={setDraft}
          label={t("wp.fields.description")}
          autoFocus
          minRows={5}
          onSubmit={async () => (await save(wp, { descriptionHtml: draft })) && setEditing(false)}
          onCancel={() => setEditing(false)}
        />
        <div className="flex justify-end gap-2">
          <Button size="sm" onClick={() => setEditing(false)}>
            {t("app.cancel")}
          </Button>
          <Button size="sm" variant="primary" loading={isPending} onClick={async () => (await save(wp, { descriptionHtml: draft })) && setEditing(false)}>
            {t("app.save")}
          </Button>
        </div>
      </div>
    );
  if (!wp.descriptionHtml)
    return can ? (
      <button type="button" onClick={() => (setDraft(""), setEditing(true))} className="w-full rounded-md border border-dashed border-border px-3 py-3 text-left text-sm text-fg-muted italic hover:bg-surface">
        {t("wp.descriptionPlaceholder")}
      </button>
    ) : null;
  return (
    <div className="group relative">
      <RichText html={wp.descriptionHtml} />
      {can && (
        <Button
          variant="ghost"
          size="sm"
          iconOnly
          className="absolute -top-1 right-0 opacity-0 group-hover:opacity-100 focus-visible:opacity-100"
          aria-label={`${t("wp.fields.description")} ${t("app.edit")}`}
          icon={<Pencil />}
          onClick={() => (setDraft(wp.descriptionHtml), setEditing(true))}
        />
      )}
    </div>
  );
}

function DeleteDialog({ wp, open, onOpenChange, onDeleted }: { wp: WorkPackage; open: boolean; onOpenChange: (o: boolean) => void; onDeleted: () => void }) {
  const { t } = useTranslation();
  const del = useDeleteWorkPackage();
  const [withChildren, setWithChildren] = useState(false);
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="sm"
      title={t("wp.deleteTitle")}
      description={t("wp.deleteBody", { number: wp.number, subject: wp.subject })}
      footer={
        <>
          <Button data-autofocus onClick={() => onOpenChange(false)}>
            {t("app.cancel")}
          </Button>
          <Button variant="danger" loading={del.isPending} onClick={async () => (await del.mutateAsync({ number: wp.number, children: withChildren ? "delete" : "reparent" }), onDeleted())}>
            {t("app.delete")}
          </Button>
        </>
      }
    >
      {wp.childCount > 0 ? (
        <Checkbox
          label={t("wp.deleteChildren", { count: wp.childCount })}
          description={withChildren ? undefined : t("wp.keepChildren")}
          checked={withChildren}
          onCheckedChange={(v) => setWithChildren(v === true)}
        />
      ) : (
        <span />
      )}
    </Dialog>
  );
}

export function WorkPackageDetail({
  number,
  mode,
  tab,
  tabHref,
  closeHref,
  fullHref,
  backHref,
  afterDeleteHref,
}: {
  number: number;
  mode: "panel" | "full";
  tab: DetailTab;
  tabHref: (tab: DetailTab) => string;
  closeHref?: string;
  fullHref?: string;
  backHref?: string;
  afterDeleteHref: string;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const toast = useToast();
  const q = useWorkPackage(number);
  // the item's project says whether time is tracked there and what I may do with it (시간 tab)
  const project = useProject(q.data?.project.identifier);
  const watch = useWatch(number);
  const { save } = useSaveWorkPackage();
  const [deleting, setDeleting] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [transfer, setTransfer] = useState<"copy" | "move" | null>(null);
  useDocumentTitle(q.data ? `#${q.data.number} ${q.data.subject}` : undefined);
  // Opening an item in the split panel moves focus into the panel, so keyboard and screen reader
  // users land on what they opened instead of staying in the table behind it.
  const articleRef = useRef<HTMLElement>(null);
  const loaded = !!q.data;
  useEffect(() => {
    if (mode === "panel" && loaded) articleRef.current?.focus({ preventScroll: true });
  }, [mode, number, loaded]);

  if (q.isPending)
    return (
      <div className="flex flex-col gap-3 p-4">
        <Skeleton className="h-5 w-1/3" />
        <Skeleton className="h-7 w-3/4" />
        <Skeleton className="h-32 w-full" />
      </div>
    );
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const wp = q.data;
  const readonlyStatus = wp.status.isReadonly && wp._meta.allowedActions.edit;

  const header = (
    <div className={cn("flex flex-col gap-2", mode === "panel" ? "border-b border-border-muted px-4 pt-3 pb-3" : "pt-4 pb-3")}>
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0 text-sm text-fg-muted">
          {mode === "full" && backHref && (
            <Link to={backHref} className="mr-2 inline-flex items-center gap-1 text-accent hover:underline">
              <ArrowLeft className="size-4" aria-hidden="true" />
              {t("wp.listTitle")}
            </Link>
          )}
          {wp.parent && (
            <Link to={`/projects/${wp.parent.project.identifier}/work-packages/${wp.parent.number}/activity`} className="truncate hover:underline">
              {t("wp.fields.parent")}: #{wp.parent.number} {wp.parent.subject}
            </Link>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {wp._meta.sharedWithMe && (
            <Badge tone="accent" icon={<Share2 />}>
              {t("share.sharedWithMe", { role: t(`share.roles.${wp._meta.sharedWithMe}`) })}
            </Badge>
          )}
          {wp._meta.allowedActions.share && (
            <Tooltip content={t("share.title")}>
              <Button variant="ghost" size="sm" iconOnly aria-label={t("share.title")} icon={<Share2 />} onClick={() => setSharing(true)} />
            </Tooltip>
          )}
          <Tooltip content={wp.watching ? t("wp.unwatch") : t("wp.watch")}>
            <Button variant="ghost" size="sm" iconOnly aria-pressed={wp.watching} aria-label={wp.watching ? t("wp.unwatch") : t("wp.watch")} icon={wp.watching ? <EyeOff /> : <Eye />} onClick={() => watch.mutate(!wp.watching)} />
          </Tooltip>
          <Menu
            align="end"
            trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("app.more")} icon={<MoreHorizontal />} />}
            items={[
              {
                label: t("wp.copyLink"),
                icon: <Link2 />,
                onSelect: () => {
                  void navigator.clipboard?.writeText(`${location.origin}/projects/${wp.project.identifier}/work-packages/${wp.number}/activity`);
                  toast({ tone: "info", title: t("wp.linkCopied") });
                },
              },
              { label: t("transfer.copyMenu"), icon: <Copy />, onSelect: () => setTransfer("copy") },
              ...(wp._meta.allowedActions.move ? [{ label: t("transfer.moveMenu"), icon: <FolderInput />, onSelect: () => setTransfer("move") }] : []),
              ...(wp._meta.allowedActions.delete ? [{ kind: "separator" as const }, { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setDeleting(true) }] : []),
            ]}
          />
          {mode === "panel" && fullHref && (
            <Tooltip content={t("wp.openFull")}>
              <Button asChild variant="ghost" size="sm" iconOnly aria-label={t("wp.openFull")} icon={<Maximize2 />}>
                <Link to={fullHref} />
              </Button>
            </Tooltip>
          )}
          {mode === "panel" && closeHref && (
            <Tooltip content={t("wp.closePanel")}>
              <Button asChild variant="ghost" size="sm" iconOnly aria-label={t("wp.closePanel")} icon={<X />}>
                <Link to={closeHref} />
              </Button>
            </Tooltip>
          )}
        </div>
      </div>
      <h1 className={cn("flex items-start gap-2 text-fg", mode === "panel" ? "text-lg" : "text-xl")}>
        <TypeLabel name={wp.type.name} color={wp.type.color} className="mt-1.5 shrink-0" />
        <InlineText
          label={t("wp.fields.subject")}
          required
          value={wp.subject}
          disabled={!wp._meta.editableFields.includes("subject")}
          className="min-w-0 flex-1"
          onSave={async (v) => !!(await save(wp, { subject: v }))}
        />
      </h1>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <StatusMenu wp={wp} />
        <p className="text-xs text-fg-muted">{t("wp.createdBy", { number: wp.number, author: wp.author.name, date: formatDate(wp.createdAt.slice(0, 10)), updated: relativeTime(wp.updatedAt) })}</p>
      </div>
      {readonlyStatus && <Banner tone="warning">{t("wp.readOnlyStatus", { status: wp.status.name })}</Banner>}
    </div>
  );

  const timeProject = project.data;
  const timeOn = !!timeProject?.enabledModules.includes("time_tracking") && (timeProject.myPermissions.includes("time.log") || timeProject.myPermissions.includes("time.view"));
  const canLogTime = !!timeProject?.myPermissions.includes("time.log");

  const summary = (
    <div className="flex flex-col gap-5">
      <section aria-label={t("wp.fields.description")}>
        <Description wp={wp} />
      </section>
      <Attributes wp={wp} />
    </div>
  );

  const tabs = (all: DetailTab[]) => {
    // the watcher list needs wp.view_watchers, the time tab the time tracking module and a time permission; a link to a hidden tab falls back to the first
    const items = all.filter((k) => (k !== "watchers" || wp._meta.allowedActions.viewWatchers) && (k !== "time" || timeOn));
    return (
      <Tabs
        aria-label={t("wp.listTitle")}
        value={items.includes(tab) ? tab : items[0]}
        onValueChange={(v) => navigate(tabHref(v as DetailTab), { replace: true })}
        items={items.map((k) => ({
          value: k,
          label: t(`wp.tabs.${k}`),
          count: k === "relations" ? wp.relationCount : k === "files" ? wp.attachmentCount : k === "watchers" ? wp.watcherCount : undefined,
          content:
            k === "overview" ? summary
            : k === "activity" ? <ActivityTab wp={wp} />
            : k === "files" ? <FilesTab wp={wp} />
            : k === "watchers" ? <WatchersTab wp={wp} />
            : k === "time" ? <TimeTab wp={wp} canLog={canLogTime} />
            : <RelationsTab wp={wp} />,
        }))}
      />
    );
  };

  return (
    <article ref={articleRef} tabIndex={-1} aria-label={`#${wp.number} ${wp.subject}`} className="flex min-h-0 flex-col outline-none">
      {header}
      {mode === "panel" ? (
        <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-8">{tabs(["overview", "activity", "files", "relations", "watchers", "time"])}</div>
      ) : (
        <div className="grid grid-cols-1 gap-8 pb-12 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <div className="pt-2">{summary}</div>
          <div>{tabs(["activity", "files", "relations", "watchers", "time"])}</div>
        </div>
      )}
      {sharing && <ShareDialog wp={wp} open onOpenChange={setSharing} />}
      {transfer === "copy" && <CopyDialog wp={wp} onClose={() => setTransfer(null)} />}
      {transfer === "move" && <MoveDialog wp={wp} onClose={() => setTransfer(null)} />}
      <DeleteDialog
        wp={wp}
        open={deleting}
        onOpenChange={setDeleting}
        onDeleted={() => {
          toast({ tone: "success", title: t("wp.deleted") });
          navigate(afterDeleteHref);
        }}
      />
    </article>
  );
}
