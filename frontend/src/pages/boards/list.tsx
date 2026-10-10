// S14 보드 목록, S16 새 보드
import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { Columns3, GitBranch, KanbanSquare, Milestone, Plus, Users } from "lucide-react";
import { isApiError, type BoardKind } from "@/api";
import { useBoardMutations, useBoards } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badges";
import { RadioCards } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Skeleton } from "@/components/ui/spinner";
import { formatDate } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";

export function BoardsPage() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  useDocumentTitle(`${t("nav.boards")} · ${project.name}`);
  const boards = useBoards(project.id);
  const base = `/projects/${project.identifier}/boards`;
  const canManage = can(project, "board.manage");
  return (
    <PageBody>
      <PageHeader
        title={t("nav.boards")}
        breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("nav.boards") }]}
        actions={
          canManage && (
            <Button asChild variant="primary" icon={<Plus />}>
              <Link to={`${base}/new`}>{t("boards.new")}</Link>
            </Button>
          )
        }
      />
      {boards.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : boards.isError ? (
        <ErrorState error={boards.error} onRetry={() => boards.refetch()} />
      ) : boards.data.length === 0 ? (
        <div className="rounded-md border border-border">
          <EmptyState
            icon={<KanbanSquare />}
            title={t("boards.empty")}
            action={
              canManage && (
                <Button asChild variant="primary" icon={<Plus />}>
                  <Link to={`${base}/new`}>{t("boards.new")}</Link>
                </Button>
              )
            }
          >
            {t("boards.emptyBody")}
          </EmptyState>
        </div>
      ) : (
        <div className="relative overflow-x-auto rounded-md border border-border">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="h-row border-b border-border bg-surface text-left text-xs font-semibold text-fg-muted">
                <th scope="col" className="px-3">{t("boards.name")}</th>
                <th scope="col" className="w-40 px-3">{t("boards.kind")}</th>
                <th scope="col" className="w-32 px-3">{t("boards.createdAt")}</th>
              </tr>
            </thead>
            <tbody>
              {boards.data.map((b) => (
                <tr key={b.id} className="h-row border-b border-border-muted last:border-b-0 hover:bg-surface">
                  <td className="px-3">
                    <Link to={`${base}/${b.id}`} className="font-medium text-fg hover:underline">
                      {b.name}
                    </Link>
                  </td>
                  <td className="px-3">
                    <Badge>{t(`boards.kinds.${b.kind}`)}</Badge>
                  </td>
                  <td className="px-3 text-fg-muted tabular-nums">{formatDate(b.createdAt.slice(0, 10))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </PageBody>
  );
}

export function NewBoardPage() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  useDocumentTitle(t("boards.new"));
  const navigate = useNavigate();
  const toast = useToast();
  const { create } = useBoardMutations(project.id);
  const [name, setName] = useState("");
  const [kind, setKind] = useState<BoardKind>("status");
  const base = `/projects/${project.identifier}/boards`;
  const err = isApiError(create.error) ? create.error.fieldErrors[0]?.message ?? create.error.message : undefined;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const b = await create.mutateAsync({ name, kind }).catch(() => null);
    if (b) {
      toast({ tone: "success", title: t("boards.created"), description: b.name });
      navigate(`${base}/${b.id}`);
    }
  };

  return (
    <PageBody>
      <PageHeader title={t("boards.new")} breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("nav.boards"), href: base }, { label: t("boards.new") }]} />
      <form onSubmit={submit} noValidate className="flex max-w-modal-lg flex-col gap-5">
        <Field label={t("boards.name")} required error={err}>
          <Input autoFocus value={name} maxLength={255} onChange={(e) => setName(e.target.value)} />
        </Field>
        <div>
          <p className="mb-2 text-sm font-medium text-fg">{t("boards.kind")}</p>
          <RadioCards
            aria-label={t("boards.kind")}
            value={kind}
            onValueChange={(v) => setKind(v as BoardKind)}
            options={[
              { value: "status", title: t("boards.kinds.status"), description: t("boards.kindHints.status"), icon: <KanbanSquare /> },
              { value: "assignee", title: t("boards.kinds.assignee"), description: t("boards.kindHints.assignee"), icon: <Users /> },
              { value: "version", title: t("boards.kinds.version"), description: t("boards.kindHints.version"), icon: <Milestone /> },
              { value: "basic", title: t("boards.kinds.basic"), description: t("boards.kindHints.basic"), icon: <Columns3 /> },
              { value: "parent_child", title: t("boards.kinds.parent_child"), description: t("boards.kindHints.parent_child"), icon: <GitBranch />, disabled: true },
            ]}
          />
        </div>
        <div className="flex gap-2">
          <Button type="submit" variant="primary" loading={create.isPending}>
            {t("app.create")}
          </Button>
          <Button asChild>
            <Link to={base}>{t("app.cancel")}</Link>
          </Button>
        </div>
      </form>
    </PageBody>
  );
}
