// 서비스 tab of a ticket: the SLA panel and the ITSM values (requester, impact, urgency, assignment group). Those go
// through PATCH /work-packages/{n}/service-ticket (journaled), not the work package form; read-only without service.handle.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Save } from "lucide-react";
import { type Level, type ServiceTicketPatch, type WorkPackage } from "@/api";
import { useProject, useServiceDeskGroups, useServiceTicketUpdate } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Banner } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Select } from "@/components/ui/select";
import { can } from "@/app/project";
import { fieldErrorOf, useRunWithToast } from "@/features/admin/common";
import { SlaPanel } from "./sla-panel";
import { ApprovalPanel } from "./approval-panel";
import { RequesterPicker } from "./requester-picker";

const LEVELS: Level[] = [1, 2, 3];
const NO_GROUP = "none";

function TicketForm({ wp, editable, onSaved }: { wp: WorkPackage; editable: boolean; onSaved: () => void }) {
  const { t } = useTranslation();
  const ticket = wp.serviceTicket!;
  const groups = useServiceDeskGroups(wp.project.identifier);
  const update = useServiceTicketUpdate();
  const runToast = useRunWithToast();
  const [requesterId, setRequesterId] = useState(ticket.requester.id);
  const [impact, setImpact] = useState<Level>(ticket.impact);
  const [urgency, setUrgency] = useState<Level>(ticket.urgency);
  const [groupId, setGroupId] = useState(ticket.assignmentGroup?.id ?? NO_GROUP);
  const [applyMatrix, setApplyMatrix] = useState(false);

  const patch: ServiceTicketPatch = {};
  if (requesterId !== ticket.requester.id) patch.requesterId = requesterId;
  if (impact !== ticket.impact) patch.impact = impact;
  if (urgency !== ticket.urgency) patch.urgency = urgency;
  if (groupId !== (ticket.assignmentGroup?.id ?? NO_GROUP)) patch.assignmentGroupId = groupId === NO_GROUP ? null : groupId;
  if (applyMatrix) patch.applyMatrix = true;
  const dirty = Object.keys(patch).length > 0;

  const levelOptions = LEVELS.map((l) => ({ value: String(l), label: t(`serviceDesk.levels.${l}`) }));
  const groupOptions = [
    { value: NO_GROUP, label: t("serviceDesk.noGroup") },
    ...(groups.data ?? (ticket.assignmentGroup ? [ticket.assignmentGroup] : [])).map((g) => ({ value: g.id, label: g.name })),
  ];
  const err = (f: string) => fieldErrorOf(update.error, f);

  return (
    <form
      className="flex flex-col gap-4"
      aria-label={t("wp.tabs.service")}
      onSubmit={(e) => {
        e.preventDefault();
        // a save that changes nothing visible (the matrix gives the same priority) still starts the form again
        if (dirty) void runToast(update.mutateAsync({ number: wp.number, patch }), t("serviceDesk.saved")).then((r) => r && onSaved());
      }}
    >
      {!editable && <Banner tone="info">{t("serviceDesk.readOnly")}</Banner>}
      <RequesterPicker value={requesterId} current={ticket.requester} onChange={setRequesterId} disabled={!editable} error={err("requester") ?? err("requesterId")} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label={t("serviceDesk.fields.impact")} error={err("impact")}>
          <Select value={String(impact)} disabled={!editable} onValueChange={(v) => setImpact(Number(v) as Level)} options={levelOptions} />
        </Field>
        <Field label={t("serviceDesk.fields.urgency")} error={err("urgency")}>
          <Select value={String(urgency)} disabled={!editable} onValueChange={(v) => setUrgency(Number(v) as Level)} options={levelOptions} />
        </Field>
      </div>
      <Checkbox label={t("serviceDesk.fields.applyMatrix")} checked={applyMatrix} disabled={!editable} onCheckedChange={(v) => setApplyMatrix(v === true)} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label={t("serviceDesk.fields.assignmentGroup")} error={err("assignmentGroup") ?? err("assignmentGroupId")}>
          <Select value={groupId} disabled={!editable} onValueChange={setGroupId} options={groupOptions} />
        </Field>
        <Field label={t("serviceDesk.fields.channel")}>
          <Input readOnly value={t(`serviceDesk.channels.${ticket.channel}`)} />
        </Field>
      </div>
      {editable && (
        <div className="flex justify-end">
          <Button type="submit" variant="primary" icon={<Save />} disabled={!dirty} loading={update.isPending}>
            {t("app.save")}
          </Button>
        </div>
      )}
    </form>
  );
}

export function ServiceTab({ wp }: { wp: WorkPackage }) {
  const project = useProject(wp.project.identifier).data;
  const [saves, setSaves] = useState(0);
  if (!wp.serviceTicket) return null;
  const editable = !!project && can(project, "service.handle");
  return (
    <div className="flex flex-col gap-5">
      <SlaPanel sla={wp.serviceTicket.sla} createdAt={wp.createdAt} />
      {editable && <ApprovalPanel number={wp.number} />}
      {/* my save, or someone else's change, starts the form again from the ticket */}
      <TicketForm key={`${wp.lockVersion}-${wp.updatedAt}-${saves}`} wp={wp} editable={editable} onSaved={() => setSaves((n) => n + 1)} />
    </div>
  );
}
