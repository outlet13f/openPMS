// 프로젝트 › 릴리스 (CI/CD 3단계): the 검증 보드 (what is in the source environment and not yet in the target, by
// verification) and the 릴리스 기록 (what each target deployment brought in). The menu shows it with the cicd module on and
// cicd.view; the API answers 404 otherwise. The tabs are links: /releases and /releases/records.
import { useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { Tabs } from "@/components/ui/tabs";
import { PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { useCurrentProject } from "@/app/project";
import { ReleaseBoardView } from "@/features/releases/board";

export type ReleasesTab = "board" | "records";

export function ProjectReleasesPage({ tab }: { tab: ReleasesTab }) {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const navigate = useNavigate();
  const base = `/projects/${project.identifier}/releases`;
  useDocumentTitle(`${t(`releases.tabs.${tab}`)} · ${project.name}`);
  return (
    <PageBody>
      <PageHeader title={t("releases.title")} breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("releases.title") }]} />
      <Tabs
        aria-label={t("releases.tabs.label")}
        value={tab}
        onValueChange={(v) => navigate(v === "records" ? `${base}/records` : base)}
        items={[
          { value: "board", label: t("releases.tabs.board"), content: tab === "board" ? <ReleaseBoardView /> : null },
          // the records list and a record's items come with Task 12
          { value: "records", label: t("releases.tabs.records"), content: null },
        ]}
      />
    </PageBody>
  );
}
