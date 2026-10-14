// 서비스 tab of a ticket: the SLA panel and the ITSM values (requester, impact, urgency, assignment group). Those go
// through PATCH /work-packages/{n}/service-ticket (journaled), not the work package form; read-only without service.handle.
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { Save } from "lucide-react";
import { api, type Level, type ServiceTicketPatch, type WorkPackage } from "@/api";
import { useProject, useServiceDeskGroups, useServiceTicketUpdate } from "@/api/hooks";
import { Avatar } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Banner } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Select } from "@/components/ui/select";
import { can } from "@/app/project";
import { fieldErrorOf, useRunWithToast } from "@/features/admin/common";
import { SlaPanel } from "./sla-panel";

const LEVELS: Level[] = [1, 2, 3];
const NO_GROUP = "none";

/** People to pick as requester: anyone active (requesters are usually not members), found by name or mail. */
function RequesterPicker({ value, current, onChange, disabled, error }: { value: string; current: { id: string; name: string }; onChange: (id: string) => void; disabled: boolean; error?: string }) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setDebounced(q), 250);
    return () => clearTimeout(id);
  }, [q]);
  const people = useQuery({ queryKey: ["principals", debounced, "requester"], queryFn: () => api.principals.search(debounced), enabled: !disabled });
  const [picked, setPicked] = useState<{ id: string; name: string }[]>([]);
  const found = (people.data ?? []).filter((p) => p.kind === "user").map((p) => ({ id: p.id, name: p.name }));
  // the current requester and the one just picked stay choosable while the search moves on
  const options = [current, ...picked, ...found].filter((p, i, all) => all.findIndex((x) => x.id === p.id) === i);
  return (
    <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
      {!disabled && (
        <Field label={t("serviceDesk.requesterSearch")}>
          <Input type="search" placeholder={t("serviceDesk.requesterSearchPlaceholder")} value={q} onChange={(e) => setQ(e.target.value)} />
        </Field>
      )}
      <Field label={t("serviceDesk.fields.requester")} error={error}>
        <Select
          value={value}
          disabled={disabled}
          onValueChange={(v) => {
            const p = options.find((x) => x.id === v);
            if (p) setPicked((cur) => [...cur.filter((x) => x.id !== p.id), p]);
            onChange(v);
          }}
          placeholder={t("serviceDesk.noMatch")}
          options={options.map((p) => ({
            value: p.id,
            textValue: p.name,
            label: (
              <span className="inline-flex items-center gap-2">
                <Avatar id={p.id} name={p.name} size="xs" />
                {p.name}
              </span>
            ),
          }))}
        />
      </Field>
    </div>
  );
}

function TicketForm({ wp, editable }: { wp: WorkPackage; editable: boolean }) {
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
        if (dirty) void runToast(update.mutateAsync({ number: wp.number, patch }), t("serviceDesk.saved"));
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
  if (!wp.serviceTicket) return null;
  const editable = !!project && can(project, "service.handle");
  return (
    <div className="flex flex-col gap-5">
      <SlaPanel sla={wp.serviceTicket.sla} createdAt={wp.createdAt} />
      {/* a saved change (or someone else's) starts the form again from the ticket */}
      <TicketForm key={`${wp.lockVersion}-${wp.updatedAt}`} wp={wp} editable={editable} />
    </div>
  );
}
