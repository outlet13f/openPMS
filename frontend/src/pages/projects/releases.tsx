// 프로젝트 › 릴리스 (CI/CD 3단계): the 검증 보드 (what is in the source environment and not yet in the target, by
// verification) and the 릴리스 기록 (what each target deployment brought in). The menu shows it with the cicd module on and
// cicd.view; the API answers 404 otherwise. The tabs are links: /releases and /releases/records (/releases/records/:releaseId
// opens a record under the list, and the breadcrumbs lead back to the list).
import { useNavigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Tabs } from "@/components/ui/tabs";
import { PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { useCurrentProject } from "@/app/project";
import { useReleaseRecord } from "@/api/hooks";
import { ReleaseBoardView } from "@/features/releases/board";
import { ReleaseRecordsView } from "@/features/releases/records";
import { shortWhen } from "@/features/cicd/links";

export type ReleasesTab = "board" | "records";

export function ProjectReleasesPage({ tab }: { tab: ReleasesTab }) {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const navigate = useNavigate();
  const params = useParams();
  const base = `/projects/${project.identifier}/releases`;
  const releaseId = tab === "records" ? params.releaseId : undefined;
  // the same query as the record under the list (one request), for the last breadcrumb
  const record = useReleaseRecord(project.identifier, releaseId);
  useDocumentTitle(`${t(`releases.tabs.${tab}`)} · ${project.name}`);
  const crumbs: { label: string; href?: string }[] = [{ label: project.name, href: `/projects/${project.identifier}` }];
  if (releaseId)
    crumbs.push(
      { label: t("releases.title"), href: base },
      { label: t("releases.tabs.records"), href: `${base}/records` },
      { label: record.data ? t("releases.records.crumb", { environment: record.data.release.environment.name, when: shortWhen(record.data.release.deployedAt) }) : t("releases.records.crumbLoading") },
    );
  else crumbs.push({ label: t("releases.title") });
  return (
    <PageBody>
      <PageHeader title={t("releases.title")} breadcrumbs={crumbs} />
      <Tabs
        aria-label={t("releases.tabs.label")}
        value={tab}
        onValueChange={(v) => navigate(v === "records" ? `${base}/records` : base)}
        items={[
          { value: "board", label: t("releases.tabs.board"), content: tab === "board" ? <ReleaseBoardView /> : null },
          { value: "records", label: t("releases.tabs.records"), content: tab === "records" ? <ReleaseRecordsView releaseId={releaseId} /> : null },
        ]}
      />
    </PageBody>
  );
}
