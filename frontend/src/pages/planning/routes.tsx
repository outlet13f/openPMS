// Split-panel routes shared by the calendar (S17) and team planner (S18): details and new work package.
// Kept apart from calendar.tsx so the team planner does not pull in FullCalendar.
import { useParams } from "react-router";
import { useCurrentProject } from "@/app/project";
import { WorkPackageDetail, asDetailTab } from "@/features/work-packages/detail";
import { usePlanningView } from "@/features/planning/use-planning-view";
import { NewPanelRoute } from "@/pages/work-packages/panels";

/** Details panel inside a calendar or team planner (same split panel as the list). */
export function PlanningDetailRoute({ kind }: { kind: "calendar" | "team-planner" }) {
  const { id, number, tab } = useParams();
  const project = useCurrentProject();
  const st = usePlanningView(project.id, kind === "calendar" ? "calendar" : "team_planner", id);
  const n = Number(number);
  const base = `/projects/${project.identifier}/${kind}/${id}`;
  const current = asDetailTab(tab, "overview");
  return (
    <WorkPackageDetail
      key={n}
      number={n}
      mode="panel"
      tab={current}
      tabHref={(x) => `${base}/details/${n}/${x}${st.search}`}
      closeHref={`${base}${st.search}`}
      fullHref={`/projects/${project.identifier}/work-packages/${n}/${current === "overview" ? "activity" : current}`}
      afterDeleteHref={`${base}${st.search}`}
    />
  );
}

/** New work package panel inside a calendar or team planner; dates and assignee come from the URL. */
export function PlanningNewRoute({ kind }: { kind: "calendar" | "team-planner" }) {
  const { id } = useParams();
  const project = useCurrentProject();
  return <NewPanelRoute base={`/projects/${project.identifier}/${kind}/${id}`} />;
}
