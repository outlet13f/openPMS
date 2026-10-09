// Files tab (S07/S08 파일): drop files or pick them, see each upload's progress or error, open or download,
// delete with confirmation. Plain image types get a thumbnail; everything else downloads.
import { useRef, useState, type DragEvent } from "react";
import { useTranslation } from "react-i18next";
import { FileText, ImageIcon, Paperclip, Trash2, Upload, X } from "lucide-react";
import { isApiError, type Attachment, type WorkPackage } from "@/api";
import { useAttachmentMutations, useAttachments } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Skeleton, Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";
import { formatDateTime, relativeTime } from "@/lib/dates";
import { ErrorState } from "@/app/page";

const PREVIEW = ["image/png", "image/jpeg", "image/gif", "image/webp"];

export function formatBytes(n: number) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

type Pending = { key: string; name: string; error?: string };

function Item({ a, onDelete }: { a: Attachment; onDelete: (a: Attachment) => void }) {
  const { t } = useTranslation();
  const image = PREVIEW.includes(a.contentType);
  return (
    <li className="flex items-center gap-3 rounded-md border border-border-muted px-3 py-2">
      <span className="inline-flex size-10 shrink-0 items-center justify-center overflow-hidden rounded-sm bg-surface text-fg-muted" aria-hidden="true">
        {image ? <img src={a.url} alt="" className="size-full object-cover" loading="lazy" /> : <FileText className="size-5" />}
      </span>
      <div className="min-w-0 flex-1">
        <a
          href={a.url}
          // data: URLs (demo) need the download attribute; real downloads name themselves
          download={image ? undefined : a.fileName}
          target={image ? "_blank" : undefined}
          rel="noopener noreferrer"
          className="block truncate text-sm font-medium text-accent hover:underline"
          title={a.fileName}
        >
          {image && <ImageIcon className="mr-1 inline size-3.5 align-[-2px]" aria-hidden="true" />}
          {a.fileName}
          <span className="sr-only"> ({image ? t("files.opensInTab") : t("files.downloads")})</span>
        </a>
        <p className="text-xs text-fg-muted">
          {formatBytes(a.byteSize)} · {a.author.name} · <time dateTime={a.createdAt} title={formatDateTime(a.createdAt)}>{relativeTime(a.createdAt)}</time>
        </p>
      </div>
      {a.canDelete && (
        <Button variant="ghost" size="sm" iconOnly aria-label={t("files.deleteOne", { name: a.fileName })} icon={<Trash2 />} onClick={() => onDelete(a)} />
      )}
    </li>
  );
}

export function FilesTab({ wp }: { wp: WorkPackage }) {
  const { t } = useTranslation();
  const toast = useToast();
  const list = useAttachments(wp.number);
  const { upload, remove } = useAttachmentMutations(wp.number);
  const [pending, setPending] = useState<Pending[]>([]);
  const [over, setOver] = useState(false);
  const [deleting, setDeleting] = useState<Attachment | null>(null);
  const [announce, setAnnounce] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const canAttach = wp._meta.allowedActions.attach;

  /** One file at a time, so errors stay next to the file they belong to. */
  const send = async (files: File[]) => {
    for (const file of files) {
      const key = `${file.name}-${file.size}-${Math.random()}`;
      setPending((p) => [...p, { key, name: file.name }]);
      try {
        await upload.mutateAsync(file);
        setPending((p) => p.filter((x) => x.key !== key));
        setAnnounce(t("files.uploaded", { name: file.name }));
      } catch (e) {
        const message = isApiError(e) ? (e.status === 413 ? e.message : (e.fieldErrors[0]?.message ?? e.message)) : t("app.errorBody");
        setPending((p) => p.map((x) => (x.key === key ? { ...x, error: message } : x)));
        setAnnounce(t("files.failed", { name: file.name }));
      }
    }
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    if (canAttach && e.dataTransfer.files.length) void send(Array.from(e.dataTransfer.files));
  };

  return (
    <div className="flex flex-col gap-4 pt-2">
      {canAttach && (
        <div
          onDragOver={(e) => (e.preventDefault(), setOver(true))}
          onDragLeave={() => setOver(false)}
          onDrop={onDrop}
          className={cn(
            "flex flex-col items-center gap-2 rounded-md border-2 border-dashed px-4 py-6 text-center text-sm text-fg-muted",
            over ? "border-accent bg-accent-subtle text-accent-subtle-text" : "border-border",
          )}
        >
          <Upload className="size-5" aria-hidden="true" />
          <p>{t("files.dropHint")}</p>
          <Button size="sm" icon={<Paperclip />} onClick={() => input.current?.click()}>
            {t("files.pick")}
          </Button>
          <input
            ref={input}
            type="file"
            multiple
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            onChange={(e) => {
              const files = Array.from(e.target.files ?? []);
              e.target.value = "";
              void send(files);
            }}
          />
          <p className="text-xs">{t("files.limit")}</p>
        </div>
      )}
      <p className="sr-only" aria-live="polite">
        {announce}
      </p>

      {pending.length > 0 && (
        <ul className="flex flex-col gap-2" aria-label={t("files.uploading")}>
          {pending.map((p) => (
            <li key={p.key} className={cn("flex items-center gap-3 rounded-md border px-3 py-2 text-sm", p.error ? "border-danger bg-danger-subtle" : "border-border-muted")}>
              {p.error ? <X className="size-4 shrink-0 text-danger" aria-hidden="true" /> : <Spinner size={16} label={t("files.uploading")} />}
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium text-fg">{p.name}</p>
                {p.error && <p className="text-xs text-danger">{p.error}</p>}
              </div>
              {p.error && (
                <Button variant="ghost" size="sm" iconOnly aria-label={t("files.dismiss", { name: p.name })} icon={<X />} onClick={() => setPending((x) => x.filter((y) => y.key !== p.key))} />
              )}
            </li>
          ))}
        </ul>
      )}

      {list.isPending ? (
        <Skeleton className="h-16 w-full" />
      ) : list.isError ? (
        <ErrorState error={list.error} onRetry={() => list.refetch()} />
      ) : list.data.length === 0 ? (
        pending.length === 0 && <EmptyState compact icon={<Paperclip />} title={t("files.empty")} />
      ) : (
        <ul className="flex flex-col gap-2" aria-label={t("wp.tabs.files")}>
          {list.data.map((a) => (
            <Item key={a.id} a={a} onDelete={setDeleting} />
          ))}
        </ul>
      )}

      <Dialog
        open={!!deleting}
        onOpenChange={(o) => !o && setDeleting(null)}
        size="sm"
        title={t("files.deleteTitle")}
        description={deleting ? t("files.deleteBody", { name: deleting.fileName }) : undefined}
        footer={
          <>
            <Button data-autofocus onClick={() => setDeleting(null)}>
              {t("app.cancel")}
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() =>
                deleting &&
                remove.mutate(deleting.id, {
                  onSuccess: () => (toast({ tone: "success", title: t("files.deleted") }), setDeleting(null)),
                  onError: (e) => toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle") }),
                })
              }
            >
              {t("app.delete")}
            </Button>
          </>
        }
      >
        <span />
      </Dialog>
    </div>
  );
}
