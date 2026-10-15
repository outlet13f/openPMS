// 서비스 요청 상세: the requester's own ticket — 머리, 처음 설명, 추가 정보 (portal fields), 첨부 파일, 진행 기록 (public
// comments and status changes, oldest first) and 답변 쓰기. Status buttons come only from allowedStatuses (the 요청자
// workflow): 종료 reads "해결 확인", 처리 중 from 해결 reads "다시 열기", 취소 asks first. Someone else's ticket is the
// server's 404 ("요청을 찾을 수 없습니다."). Opening the ticket clears its "새 답변", so the lists refresh after it loads.
import { useEffect, useState } from "react";
import { useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowRight, CheckCircle2, FileText, MessageSquare, RotateCcw, Send, XCircle } from "lucide-react";
import type { IdName, PortalEntry, PortalTicket } from "@/api";
import { api, isApiError } from "@/api";
import { usePortalMutations, usePortalTicket } from "@/api/hooks";
import { StatusPill } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { RichText } from "@/components/domain/rich-text";
import { RichTextEditor } from "@/components/domain/rich-text-editor";
import { ErrorState, NotFound, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { useRunWithToast } from "@/features/admin/common";
import { useUnsavedWarning } from "@/lib/use-unsaved-warning";
import { formatDateTime, relativeTime } from "@/lib/dates";
import { ApprovalSteps } from "./approval";

// fixed status ids (real API, demo) with the name as a fallback
const CLOSED = new Set(["69baa129-0283-56e0-be38-b73ce5db2b64", "s-sd-closed"]);
const HANDLING = new Set(["d34fe673-69b6-56f9-85fd-2cb8198cfba5", "s-handling"]);
const RESOLVED = new Set(["8201e445-a46a-5135-a972-3f83ee10c3ba", "s-resolved"]);
const CANCELLED = new Set(["8ab8a2d1-0d29-5b49-a263-6284c74a7680", "s-cancel"]);
const is = (ids: Set<string>, name: string) => (s: { id: string; name: string }) => ids.has(s.id) || s.name === name;
const isClosedStatus = is(CLOSED, "종료");
const isHandling = is(HANDLING, "처리 중");
const isResolved = is(RESOLVED, "해결");
const isCancel = is(CANCELLED, "취소");

const fileSize = (n: number) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

function StatusButtons({ ticket, onRefetch }: { ticket: PortalTicket; onRefetch: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const m = usePortalMutations();
  const runToast = useRunWithToast();
  const [confirming, setConfirming] = useState<IdName | null>(null);
  const [pending, setPending] = useState<string | null>(null);
  if (!ticket.allowedStatuses.length) return null;

  const label = (s: IdName) => (isClosedStatus(s) ? t("portal.confirmResolved") : isHandling(s) && isResolved(ticket.status) ? t("portal.reopen") : s.name);
  const icon = (s: IdName) => (isClosedStatus(s) ? <CheckCircle2 /> : isCancel(s) ? <XCircle /> : isHandling(s) ? <RotateCcw /> : <ArrowRight />);
  const move = async (s: IdName) => {
    setPending(s.id);
    try {
      await m.setStatus.mutateAsync({ number: ticket.number, statusId: s.id, lockVersion: ticket.lockVersion });
      toast({ tone: "success", title: t("portal.statusDone", { name: s.name }) });
    } catch (e) {
      if (isApiError(e) && e.status === 409) {
        toast({ tone: "info", title: t("portal.conflict") });
        onRefetch();
      } else await runToast(Promise.reject(e));
    } finally {
      setPending(null);
      setConfirming(null);
    }
  };

  return (
    <div role="group" aria-label={t("portal.actions")} className="flex flex-wrap gap-2">
      {ticket.allowedStatuses.map((s) => (
        <Button
          key={s.id}
          variant={isClosedStatus(s) ? "primary" : isCancel(s) ? "danger-ghost" : "secondary"}
          icon={icon(s)}
          loading={pending === s.id}
          disabled={pending !== null && pending !== s.id}
          onClick={() => (isCancel(s) ? setConfirming(s) : void move(s))}
        >
          {label(s)}
        </Button>
      ))}
      <Dialog
        open={confirming !== null}
        onOpenChange={(o) => !o && setConfirming(null)}
        size="sm"
        title={t("portal.cancelTitle")}
        footer={
          <>
            <Button data-autofocus onClick={() => setConfirming(null)}>
              {t("portal.cancelKeep")}
            </Button>
            <Button variant="danger" loading={confirming !== null && pending === confirming.id} onClick={() => confirming && void move(confirming)}>
              {t("portal.cancelConfirm")}
            </Button>
          </>
        }
      >
        <p className="text-sm text-fg">{t("portal.cancelBody")}</p>
      </Dialog>
    </div>
  );
}

function Entry({ e }: { e: PortalEntry }) {
  const { t } = useTranslation();
  const who = e.fromMe ? t("portal.me") : e.author;
  return (
    <li className="flex gap-3 py-3">
      <span className="mt-0.5 inline-flex size-7 shrink-0 items-center justify-center rounded-full bg-surface text-fg-muted [&_svg]:size-4" aria-hidden="true">
        {e.notesHtml ? <MessageSquare /> : <ArrowRight />}
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm">
          <span className="font-semibold text-fg">{who}</span>{" "}
          <time dateTime={e.at} title={formatDateTime(e.at)} className="text-xs text-fg-muted">
            {relativeTime(e.at)}
          </time>
        </p>
        {e.statusTo !== null && (
          <p className="mt-0.5 text-sm text-fg-muted">
            <span className="sr-only">{t("portal.statusChanged")}: </span>
            {e.statusFrom ?? "-"} → <span className="font-medium text-fg">{e.statusTo}</span>
          </p>
        )}
        {e.notesHtml && <RichText html={e.notesHtml} className="mt-1.5 rounded-md bg-surface px-3 py-2" />}
      </div>
    </li>
  );
}

function ReplyBox({ ticket }: { ticket: PortalTicket }) {
  const { t } = useTranslation();
  const toast = useToast();
  const m = usePortalMutations();
  const [html, setHtml] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [round, setRound] = useState(0); // a fresh, empty editor after each reply
  useUnsavedWarning(!!html);
  const send = async () => {
    if (!html) return setError(t("portal.replyEmpty"));
    try {
      await m.comment.mutateAsync({ number: ticket.number, bodyHtml: html });
      setHtml("");
      setError(null);
      setRound((r) => r + 1);
      toast({ tone: "success", title: t("portal.sent") });
    } catch (e) {
      setError(isApiError(e) ? e.message : t("app.errorBody"));
    }
  };
  return (
    <section aria-labelledby="portal-reply" className="mt-6">
      <h2 id="portal-reply" className="mb-2 text-base font-semibold text-fg">
        {t("portal.reply")}
      </h2>
      <RichTextEditor key={round} value="" onChange={(v) => (setHtml(v), error && setError(null))} label={t("portal.reply")} placeholder={t("portal.replyPlaceholder")} onSubmit={() => void send()} invalid={!!error} />
      {error && <p className="mt-1 text-xs text-danger">{error}</p>}
      <div className="mt-2 flex justify-end">
        <Button variant="primary" size="sm" icon={<Send />} loading={m.comment.isPending} onClick={() => void send()}>
          {t("portal.send")}
        </Button>
      </div>
    </section>
  );
}

function TicketView({ ticket, onRefetch }: { ticket: PortalTicket; onRefetch: () => void }) {
  const { t } = useTranslation();
  useDocumentTitle(`#${ticket.number} ${ticket.subject}`);
  return (
    <>
      <PageHeader title={`#${ticket.number} ${ticket.subject}`} breadcrumbs={[{ label: t("portal.title"), href: "/portal" }, { label: `#${ticket.number}` }]}>
        <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-fg-muted">
          <StatusPill name={ticket.status.name} color={ticket.status.color} />
          <span>{ticket.type.name}</span>
          {ticket.item && <span>{ticket.item.name}</span>}
          <span>{ticket.desk}</span>
          <span>
            {t("portal.submittedAt")} <time dateTime={ticket.createdAt}>{formatDateTime(ticket.createdAt)}</time>
          </span>
        </div>
      </PageHeader>
      <div className="grid max-w-6xl grid-cols-1 gap-8 lg:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="min-w-0">
          <StatusButtons ticket={ticket} onRefetch={onRefetch} />
          {ticket.descriptionHtml && <RichText html={ticket.descriptionHtml} className="mt-4 rounded-md border border-border-muted px-4 py-3" />}
          <section aria-labelledby="portal-activity" className="mt-6">
            <h2 id="portal-activity" className="text-base font-semibold text-fg">
              {t("portal.activity")}
            </h2>
            {ticket.activity.length === 0 ? (
              <p className="mt-2 text-sm text-fg-muted">{t("portal.noActivity")}</p>
            ) : (
              <ol className="divide-y divide-border-muted">
                {ticket.activity.map((e) => (
                  <Entry key={e.id} e={e} />
                ))}
              </ol>
            )}
          </section>
          {ticket.approvals.length > 0 && (
            <div className="mt-6">
              <ApprovalSteps steps={ticket.approvals} />
            </div>
          )}
          {ticket.closed ? <p className="mt-6 text-sm text-fg-muted">{t("portal.closedHint")}</p> : <ReplyBox ticket={ticket} />}
        </div>
        <aside className="flex flex-col gap-6">
          {ticket.fields.length > 0 && (
            <section aria-labelledby="portal-details">
              <h2 id="portal-details" className="mb-2 text-base font-semibold text-fg">
                {t("portal.details")}
              </h2>
              <dl className="flex flex-col gap-2 text-sm">
                {ticket.fields.map((f) => (
                  <div key={f.name}>
                    <dt className="text-fg-muted">{f.name}</dt>
                    <dd className="break-words text-fg">{f.value || "-"}</dd>
                  </div>
                ))}
              </dl>
            </section>
          )}
          <section aria-labelledby="portal-files">
            <h2 id="portal-files" className="mb-2 text-base font-semibold text-fg">
              {t("portal.files")}
            </h2>
            {ticket.files.length === 0 ? (
              <p className="text-sm text-fg-muted">{t("portal.noFiles")}</p>
            ) : (
              <ul className="flex flex-col gap-1.5">
                {ticket.files.map((f) => (
                  <li key={f.id} className="flex min-w-0 items-center gap-2 text-sm">
                    <FileText className="size-4 shrink-0 text-fg-muted" aria-hidden="true" />
                    <a href={api.portal.fileUrl(ticket.number, f.id)} download={f.fileName} className="truncate text-accent hover:underline">
                      {f.fileName}
                    </a>
                    <span className="shrink-0 text-xs text-fg-muted tabular-nums">{fileSize(f.byteSize)}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </aside>
      </div>
    </>
  );
}

export function PortalTicketPage() {
  const { t } = useTranslation();
  const n = Number(useParams().number);
  const valid = Number.isInteger(n) && n > 0;
  const q = usePortalTicket(valid ? n : 0);
  const qc = useQueryClient();
  // the server cleared "새 소식" when it sent the ticket: the 내 요청 lists refresh
  useEffect(() => {
    if (q.isSuccess) void qc.invalidateQueries({ queryKey: ["portal", "tickets"] });
  }, [q.isSuccess, q.dataUpdatedAt, qc]);
  if (!valid) return <NotFound title={t("app.notFoundTitle")} />;
  if (q.isPending)
    return (
      <PageBody>
        <Skeleton className="mt-6 h-96 max-w-4xl" />
      </PageBody>
    );
  if (q.isError) {
    if (isApiError(q.error) && q.error.status === 404) return <NotFound title={q.error.title} body={t("portal.notFoundBody")} />;
    return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  }
  return (
    <PageBody>
      <TicketView ticket={q.data} onRefetch={() => void q.refetch()} />
    </PageBody>
  );
}
