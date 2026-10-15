// 승인 panel of the 서비스 tab (ITSM 2단계): every approval row of a catalog request — step, kind, approver, decision,
// when and the approver's note — and "승인자 바꾸기" on the rows not decided yet. Shown only with service.handle, and
// only when the request has approval rows. The note is plain text (never HTML), clamped to a few lines.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { UserCog } from "lucide-react";
import type { AgentApproval, ApprovalDecision } from "@/api";
import { useReplaceApprover, useTicketApprovals } from "@/api/hooks";
import { Principal } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/overlay";
import { formatDateTime } from "@/lib/dates";
import { AdminTable, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { RequesterPicker } from "./service-tab";

const DECISION_TONE: Record<ApprovalDecision, "neutral" | "accent" | "success" | "danger"> = {
  waiting: "neutral",
  pending: "accent",
  approved: "success",
  rejected: "danger",
  skipped: "neutral",
};

function ReplaceDialog({ number, row, onClose }: { number: number; row: AgentApproval; onClose: () => void }) {
  const { t } = useTranslation();
  const replace = useReplaceApprover(number);
  const runToast = useRunWithToast();
  const [approverId, setApproverId] = useState(row.approver?.id ?? "");
  const changed = !!approverId && approverId !== row.approver?.id;
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={t("serviceDesk.approvals.replaceTitle", { n: row.stepNo })}
      description={t(`serviceDesk.approvalKinds.${row.kind}`)}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button
            variant="primary"
            disabled={!changed}
            loading={replace.isPending}
            onClick={async () => (await runToast(replace.mutateAsync({ approvalId: row.id, approverId }), t("serviceDesk.approvals.replaced"))) && onClose()}
          >
            {t("app.save")}
          </Button>
        </>
      }
    >
      <RequesterPicker
        value={approverId}
        current={row.approver}
        onChange={setApproverId}
        disabled={false}
        error={fieldErrorOf(replace.error, "approver") ?? fieldErrorOf(replace.error, "approverId")}
        labels={{ search: t("serviceDesk.approvals.search"), pick: t("serviceDesk.approvals.approver") }}
      />
    </Dialog>
  );
}

export function ApprovalPanel({ number }: { number: number }) {
  const { t } = useTranslation();
  const q = useTicketApprovals(number);
  const [replacing, setReplacing] = useState<AgentApproval | null>(null);
  const rows = q.data ?? [];
  if (rows.length === 0) return null;
  return (
    <section aria-labelledby="approval-panel-h" className="flex flex-col gap-3 rounded-md border border-border p-3">
      <h3 id="approval-panel-h" className="text-sm font-semibold text-fg">
        {t("serviceDesk.approvals.title")}
      </h3>
      <AdminTable minWidth={640}>
        <thead>
          <tr className={theadRow}>
            <th scope="col" className={th}>{t("serviceDesk.approvals.stepCol")}</th>
            <th scope="col" className={th}>{t("serviceDesk.approvals.kind")}</th>
            <th scope="col" className={th}>{t("serviceDesk.approvals.approver")}</th>
            <th scope="col" className={th}>{t("serviceDesk.approvals.decision")}</th>
            <th scope="col" className={th}>{t("serviceDesk.approvals.decidedAt")}</th>
            <th scope="col" className={th}>{t("serviceDesk.approvals.comment")}</th>
            <th scope="col" className={`${th} w-12`}>
              <span className="sr-only">{t("app.more")}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} className={`${tbodyRow} align-top`}>
              <td className="px-3 py-1.5 whitespace-nowrap">{t("serviceDesk.approvals.step", { n: r.stepNo })}</td>
              <td className="px-3 py-1.5 whitespace-nowrap">{t(`serviceDesk.approvalKinds.${r.kind}`)}</td>
              <td className="px-3 py-1.5 whitespace-nowrap">{r.approver ? <Principal id={r.approver.id} name={r.approver.name} size="xs" /> : <span className="text-fg-muted">-</span>}</td>
              <td className="px-3 py-1.5">
                <span className="inline-flex flex-col items-start gap-0.5">
                  <Badge tone={DECISION_TONE[r.decision]} className="whitespace-nowrap">{t(`serviceDesk.approvals.decisions.${r.decision}`)}</Badge>
                  {r.decision === "skipped" && r.skipReason && <span className="text-xs text-fg-muted">{t(`serviceDesk.approvals.skipReasons.${r.skipReason}`)}</span>}
                </span>
              </td>
              <td className="px-3 py-1.5 text-fg-muted tabular-nums whitespace-nowrap">{r.decidedAt ? formatDateTime(r.decidedAt) : "-"}</td>
              <td className="max-w-64 min-w-48 px-3 py-1.5">
                {/* plain text: React escapes it; long notes are clamped (the whole note in the tooltip) */}
                {r.comment ? (
                  <p className="line-clamp-3 break-words whitespace-pre-wrap text-fg" title={r.comment}>
                    {r.comment}
                  </p>
                ) : (
                  <span className="text-fg-muted">-</span>
                )}
              </td>
              <td className="px-2 py-1">
                {(r.decision === "pending" || r.decision === "waiting") && (
                  <Button
                    variant="ghost"
                    size="sm"
                    icon={<UserCog />}
                    className="whitespace-nowrap"
                    aria-label={`${t("serviceDesk.approvals.step", { n: r.stepNo })} ${t(`serviceDesk.approvalKinds.${r.kind}`)} ${t("serviceDesk.approvals.replace")}`}
                    onClick={() => setReplacing(r)}
                  >
                    {t("serviceDesk.approvals.replace")}
                  </Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </AdminTable>
      {replacing && <ReplaceDialog number={number} row={replacing} onClose={() => setReplacing(null)} />}
    </section>
  );
}
