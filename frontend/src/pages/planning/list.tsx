// S17 캘린더 목록, S18 팀 플래너 목록: the project's saved calendars or team planners (queries.view).
import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { CalendarDays, CalendarRange, Plus } from "lucide-react";
import { isApiError, type QuerySpec } from "@/api";
import { useMe, useSavedQueries, useSavedQueryMutations } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badges";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";

type Kind = "calendar" | "team_planner";
const path = (kind: Kind) => (kind === "calendar" ? "calendar" : "team-planner");

/** A new calendar shows open work; a new team planner starts with the person who made it. */
function initialSpec(kind: Kind, meId: string): QuerySpec {
  return {
    v: 1,
    filters: kind === "team_planner" ? [{ field: "assignee", op: "eq", values: [meId] }, { field: "status", op: "open" }] : [{ field: "status", op: "open" }],
    columns: ["number", "subject", "type", "status", "assignee", "priority", "dueDate"],
    sort: [{ field: "startDate", dir: "asc" }],
    groupBy: null,
    hierarchy: false,
    sums: false,
    includeSubprojects: false,
  };
}

function CreateDialog({ kind, open, onOpenChange }: { kind: Kind; open: boolean; onOpenChange: (o: boolean) => void }) {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const me = useMe().data;
  const navigate = useNavigate();
  const toast = useToast();
  const { create } = useSavedQueryMutations(project.id);
  const [name, setName] = useState("");
  const [isPublic, setPublic] = useState(true);
  const canPublic = can(project, "query.manage_public");
  const err = isApiError(create.error) ? create.error.fieldErrors[0]?.message ?? create.error.message : undefined;

  const submit = async (e?: FormEvent) => {
    e?.preventDefault();
    if (!me) return;
    const q = await create.mutateAsync({ projectId: project.id, name, isPublic: isPublic && canPublic, view: kind, spec: initialSpec(kind, me.id) }).catch(() => null);
    if (q) {
      toast({ tone: "success", title: t("planning.created"), description: q.name });
      onOpenChange(false);
      navigate(`/projects/${project.identifier}/${path(kind)}/${q.id}`);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="sm"
      title={t(kind === "calendar" ? "planning.newCalendar" : "planning.newPlanner")}
      footer={
        <>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={create.isPending} onClick={() => void submit()}>
            {t("app.create")}
          </Button>
        </>
      }
    >
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        <Field label={t("planning.name")} required error={err}>
          <Input data-autofocus value={name} maxLength={255} onChange={(e) => setName(e.target.value)} />
        </Field>
        {canPublic && <Checkbox label={t("planning.public")} description={t("planning.publicHint")} checked={isPublic} onCheckedChange={(v) => setPublic(v === true)} />}
      </form>
    </Dialog>
  );
}

export function PlanningListPage({ kind }: { kind: Kind }) {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const title = t(kind === "calendar" ? "nav.calendar" : "nav.teamPlanner");
  useDocumentTitle(`${title} · ${project.name}`);
  const views = useSavedQueries(project.id, kind);
  const [creating, setCreating] = useState(false);
  const canCreate = can(project, "query.save");
  const base = `/projects/${project.identifier}/${path(kind)}`;
  const newLabel = t(kind === "calendar" ? "planning.newCalendar" : "planning.newPlanner");
  const createButton = canCreate && (
    <Button variant="primary" icon={<Plus />} onClick={() => setCreating(true)}>
      {newLabel}
    </Button>
  );

  return (
    <PageBody>
      <PageHeader title={title} breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: title }]} actions={createButton} />
      {views.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : views.isError ? (
        <ErrorState error={views.error} onRetry={() => views.refetch()} />
      ) : views.data.length === 0 ? (
        <div className="rounded-md border border-border">
          <EmptyState
            icon={kind === "calendar" ? <CalendarDays /> : <CalendarRange />}
            title={t(kind === "calendar" ? "planning.calendarsEmpty" : "planning.plannersEmpty")}
            action={createButton}
          >
            {t(kind === "calendar" ? "planning.calendarsEmptyBody" : "planning.plannersEmptyBody")}
          </EmptyState>
        </div>
      ) : (
        <div className="relative overflow-x-auto rounded-md border border-border">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="h-row border-b border-border bg-surface text-left text-xs font-semibold text-fg-muted">
                <th scope="col" className="px-3">{t("planning.name")}</th>
                <th scope="col" className="w-32 px-3">{t("planning.visibility")}</th>
              </tr>
            </thead>
            <tbody>
              {views.data.map((v) => (
                <tr key={v.id} className="h-row border-b border-border-muted last:border-b-0 hover:bg-surface">
                  <td className="px-3">
                    <Link to={`${base}/${v.id}`} className="font-medium text-fg hover:underline">
                      {v.name}
                    </Link>
                  </td>
                  <td className="px-3">
                    <Badge>{v.kind === "public" ? t("planning.public") : t("planning.private")}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {creating && <CreateDialog kind={kind} open={creating} onOpenChange={setCreating} />}
    </PageBody>
  );
}
