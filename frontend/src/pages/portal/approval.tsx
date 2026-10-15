// 승인 상세 (/portal/approvals/:id): what the approver may read — title, requester, item, form values, files and the step
// list (names and decisions only) — and 승인 / 반려. 반려 asks for a reason (the requester reads it); 승인 may carry an
// 의견 (the agents read it). Anyone but the approver of a pending row gets the server's 404. All user-entered text is
// plain text; only the description goes through the sanitized RichText.
import { useState } from "react";
import { useNavigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Check, FileText, X } from "lucide-react";
import type { ApprovalDetail, PortalApproval } from "@/api";
import { api, isApiError } from "@/api";
import { useApprovalDecision, usePortalApproval } from "@/api/hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/feedback";
import { Field, Textarea } from "@/components/ui/field";
import { Dialog } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { RichText } from "@/components/domain/rich-text";
import { ErrorState, NotFound, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { fieldErrorOf, th, theadRow } from "@/features/admin/common";
import { formatDateTime } from "@/lib/dates";

const fileSize = (n: number) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

const tone = { waiting: "neutral", pending: "warning", approved: "success", rejected: "danger", skipped: "neutral" } as const;

/** 승인 진행: one row per step and approver (decision as a text badge, never colour alone). */
export function ApprovalSteps({ steps }: { steps: PortalApproval[] }) {
  const { t } = useTranslation();
  if (!steps.length) return null;
  return (
    <section aria-labelledby="portal-approval-steps">
      <h2 id="portal-approval-steps" className="mb-2 text-base font-semibold text-fg">
        {t("portal.approval.title")}
      </h2>
      <div className="relative overflow-x-auto rounded-md border border-border">
        <table className="w-full border-collapse text-sm">
          <caption className="sr-only">{t("portal.approval.caption")}</caption>
          <thead>
            <tr className={theadRow}>
              <th scope="col" className={th}>{t("portal.approval.step")}</th>
              <th scope="col" className={th}>{t("portal.approval.approver")}</th>
              <th scope="col" className={th}>{t("portal.approval.decision")}</th>
              <th scope="col" className={th}>{t("portal.approval.decidedAt")}</th>
            </tr>
          </thead>
          <tbody>
            {steps.map((s, i) => (
              <tr key={`${s.stepNo}-${i}`} className="border-b border-border-muted last:border-b-0">
                <td className="px-3 py-2 whitespace-nowrap text-fg-muted">{t("portal.approval.stepLabel", { n: s.stepNo })}</td>
                <td className="px-3 py-2 text-fg">{s.approverName ?? <span className="text-fg-muted">{t("portal.approval.noApprover")}</span>}</td>
                <td className="px-3 py-2">
                  <Badge tone={tone[s.decision]}>{t(`portal.approval.decisions.${s.decision}`)}</Badge>
                </td>
                <td className="px-3 py-2 whitespace-nowrap text-fg-muted">{s.decidedAt ? <time dateTime={s.decidedAt}>{formatDateTime(s.decidedAt)}</time> : "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function Decide({ detail }: { detail: ApprovalDetail }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const toast = useToast();
  const m = useApprovalDecision();
  const [comment, setComment] = useState("");
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [commentError, setCommentError] = useState<string | null>(null);
  const [pending, setPending] = useState<"approve" | "reject" | null>(null);

  const decide = async (decision: "approve" | "reject") => {
    if (decision === "reject" && !reason.trim()) return setReasonError(t("portal.review.rejectReasonRequired"));
    setPending(decision);
    try {
      await m.mutateAsync({ id: detail.id, decision, comment: (decision === "reject" ? reason : comment).trim() || undefined });
      toast({ tone: "success", title: t(decision === "approve" ? "portal.review.approved" : "portal.review.rejected") });
      navigate("/portal/approvals");
    } catch (e) {
      if (isApiError(e) && e.status === 409) {
        toast({ tone: "info", title: t("portal.review.alreadyDecided") });
        navigate("/portal/approvals");
      } else if (isApiError(e) && e.status === 404) {
        toast({ tone: "danger", title: e.title });
        navigate("/portal/approvals");
      } else if (isApiError(e) && fieldErrorOf(e, "comment")) {
        // a rejection's reason sits in the dialog, an approval's 의견 under its own field
        if (decision === "reject") setReasonError(fieldErrorOf(e, "comment") ?? null);
        else setCommentError(fieldErrorOf(e, "comment") ?? null);
        setPending(null);
      } else {
        toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle"), description: isApiError(e) && e.message !== e.title ? e.message : undefined });
        setPending(null);
      }
    }
  };

  return (
    <section aria-labelledby="portal-review-actions" className="flex flex-col gap-3">
      <h2 id="portal-review-actions" className="text-base font-semibold text-fg">
        {t("portal.review.actions")}
      </h2>
      <Field label={t("portal.review.comment")} hint={t("portal.review.commentHint")} error={commentError ?? undefined}>
        <Textarea rows={3} maxLength={2000} value={comment} onChange={(e) => (setComment(e.target.value), commentError && setCommentError(null))} />
      </Field>
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" icon={<Check />} loading={pending === "approve"} disabled={pending !== null && pending !== "approve"} onClick={() => void decide("approve")}>
          {t("portal.review.approve")}
        </Button>
        <Button variant="danger-ghost" icon={<X />} disabled={pending !== null} onClick={() => (setReason(""), setReasonError(null), setRejecting(true))}>
          {t("portal.review.reject")}
        </Button>
      </div>
      <Dialog
        open={rejecting}
        onOpenChange={(o) => !o && pending === null && setRejecting(false)}
        size="sm"
        title={t("portal.review.rejectTitle")}
        description={t("portal.review.rejectBody")}
        footer={
          <>
            <Button disabled={pending !== null} onClick={() => setRejecting(false)}>
              {t("app.cancel")}
            </Button>
            <Button variant="danger" loading={pending === "reject"} onClick={() => void decide("reject")}>
              {t("portal.review.reject")}
            </Button>
          </>
        }
      >
        <Field label={t("portal.review.rejectReason")} required error={reasonError ?? undefined}>
          <Textarea data-autofocus rows={4} maxLength={2000} value={reason} onChange={(e) => (setReason(e.target.value), reasonError && setReasonError(null))} />
        </Field>
      </Dialog>
    </section>
  );
}

function Review({ detail }: { detail: ApprovalDetail }) {
  const { t } = useTranslation();
  const title = t("portal.review.title", { number: detail.number });
  useDocumentTitle(`${title} ${detail.subject}`);
  return (
    <>
      <PageHeader
        title={`${title} ${detail.subject}`}
        breadcrumbs={[{ label: t("portal.title"), href: "/portal" }, { label: t("portal.inbox.title"), href: "/portal/approvals" }, { label: `#${detail.number}` }]}
      >
        <dl className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-sm">
          <div className="flex gap-1.5">
            <dt className="text-fg-muted">{t("portal.review.requester")}</dt>
            <dd className="text-fg">{detail.requester}</dd>
          </div>
          {detail.item && (
            <div className="flex gap-1.5">
              <dt className="text-fg-muted">{t("portal.review.item")}</dt>
              <dd className="text-fg">{detail.item}</dd>
            </div>
          )}
          <div className="flex gap-1.5">
            <dt className="text-fg-muted">{t("portal.review.desk")}</dt>
            <dd className="text-fg">{detail.desk}</dd>
          </div>
        </dl>
      </PageHeader>
      <div className="grid max-w-6xl grid-cols-1 gap-8 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="flex min-w-0 flex-col gap-6">
          <section aria-labelledby="portal-review-content">
            <h2 id="portal-review-content" className="mb-2 text-base font-semibold text-fg">
              {t("portal.review.content")}
            </h2>
            {detail.descriptionHtml && <RichText html={detail.descriptionHtml} className="rounded-md border border-border-muted px-4 py-3" />}
          </section>
          {detail.fields.length > 0 && (
            <section aria-labelledby="portal-review-details">
              <h2 id="portal-review-details" className="mb-2 text-base font-semibold text-fg">
                {t("portal.review.details")}
              </h2>
              <dl className="flex flex-col gap-2 text-sm">
                {detail.fields.map((f) => (
                  <div key={f.name}>
                    <dt className="text-fg-muted">{f.name}</dt>
                    <dd className="break-words whitespace-pre-wrap text-fg">{f.value || "-"}</dd>
                  </div>
                ))}
              </dl>
            </section>
          )}
          <section aria-labelledby="portal-review-files">
            <h2 id="portal-review-files" className="mb-2 text-base font-semibold text-fg">
              {t("portal.review.files")}
            </h2>
            {detail.files.length === 0 ? (
              <p className="text-sm text-fg-muted">{t("portal.review.noFiles")}</p>
            ) : (
              <ul className="flex flex-col gap-1.5">
                {detail.files.map((f) => (
                  <li key={f.id} className="flex min-w-0 items-center gap-2 text-sm">
                    <FileText className="size-4 shrink-0 text-fg-muted" aria-hidden="true" />
                    <a href={api.portal.approvalFileUrl(detail.id, f.id)} download={f.fileName} className="truncate text-accent hover:underline">
                      {f.fileName}
                    </a>
                    <span className="shrink-0 text-xs text-fg-muted tabular-nums">{fileSize(f.byteSize)}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <ApprovalSteps steps={detail.steps} />
        </div>
        <aside>
          <Decide detail={detail} />
        </aside>
      </div>
    </>
  );
}

export function PortalApprovalPage() {
  const { id = "" } = useParams();
  const q = usePortalApproval(id);
  if (q.isPending)
    return (
      <PageBody>
        <Skeleton className="mt-6 h-96 max-w-4xl" />
      </PageBody>
    );
  if (q.isError) {
    if (isApiError(q.error) && q.error.status === 404) return <NotFound title={q.error.title} body={q.error.message !== q.error.title ? q.error.message : undefined} />;
    return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  }
  return (
    <PageBody>
      <Review detail={q.data} />
    </PageBody>
  );
}
