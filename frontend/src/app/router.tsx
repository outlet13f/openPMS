// Every screen in replica/recon.md gets a route (architecture.md §4.3). Admin sections not built yet render
// a StubPage; replica/build-log.md tracks what is missing.
import { lazy, Suspense, type ReactElement } from "react";
import { createBrowserRouter, Navigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import type { Me } from "@/api";
import { useMe } from "@/api/hooks";
import { RequireAuth } from "./auth";
import { AppLayout } from "./layout";
import { NotFound, StubPage } from "./page";
import { ProjectLayout, RequireModule } from "./project";
import { LoginPage } from "@/pages/login";
import { HomePage } from "@/pages/home";
import { ProjectsPage } from "@/pages/projects/list";
import { NewProjectPage } from "@/pages/projects/new";
import { ProjectOverviewPage } from "@/pages/projects/overview";
import { MembersPage } from "@/pages/projects/members";
import { WorkPackagesPage } from "@/pages/work-packages/list";
import { DetailsPanelRoute, FullViewRoute, NewPanelRoute } from "@/pages/work-packages/panels";
import { NotificationDetailRoute, NotificationsPage } from "@/pages/notifications";
import { GanttDetailRoute, GanttPage } from "@/pages/gantt";
import { AdminHomePage } from "@/pages/admin";
import { AdminTypesPage } from "@/pages/admin/types";
import { NotificationSettingsPage } from "@/pages/notification-settings";
import { AccountPage } from "@/pages/account";
import { SearchPage } from "@/pages/search";
import { MyPage } from "@/pages/my-page";
import { TimesheetPage } from "@/pages/timesheet";
import { ProjectTimeReportPage } from "@/pages/projects/time-report";
import { ProjectReleasesPage } from "@/pages/projects/releases";
import { AdminCustomFieldsPage } from "@/pages/admin/custom-fields";
import { AdminProgressPage } from "@/pages/admin/progress";
import { AdminTypeFormPage } from "@/pages/admin/type-form";
import { AdminAuthenticationPage } from "@/pages/admin/authentication";
import { InvitationPage, PasswordResetPage } from "@/pages/auth-links";
import { AdminPrioritiesPage, AdminStatusesPage } from "@/pages/admin/statuses";
import { AdminWorkflowsPage } from "@/pages/admin/workflows";
import { AdminRoleEditPage, AdminRolesPage, AdminRolesReportPage } from "@/pages/admin/roles";
import { AdminGroupsPage, AdminUsersPage } from "@/pages/admin/users";
import { AdminCalendarPage } from "@/pages/admin/calendar";
import { AdminIntegrationsPage } from "@/pages/admin/integrations";
import { AdminCicdPage } from "@/pages/admin/cicd";
import { AdminSystemPage } from "@/pages/admin/system";
import { ApiDocsPage } from "@/pages/api-docs";
import { ProjectSettingsPage } from "@/pages/projects/settings";
import { BoardsPage, NewBoardPage } from "@/pages/boards/list";
import { BoardDetailRoute, BoardPage } from "@/pages/boards/board";
import { PlanningListPage } from "@/pages/planning/list";
import { PlanningDetailRoute, PlanningNewRoute } from "@/pages/planning/routes";
import { TeamPlannerPage } from "@/pages/planning/team-planner";
import { Skeleton } from "@/components/ui/spinner";

// FullCalendar is only needed on S17, so the calendar page is its own chunk.
const CalendarPage = lazy(() => import("@/pages/planning/calendar").then((m) => ({ default: m.CalendarPage })));
// The component catalogue is for the people building the app (help menu): signed in only, its own chunk.
const DesignPage = lazy(() => import("@/pages/design-page").then((m) => ({ default: m.DesignPage })));
// 도움말 (the manuals) is its own chunk; the manuals' text is loaded separately on first use
const HelpPage = lazy(() => import("@/pages/help").then((m) => ({ default: m.HelpPage })));
const helpPage = (manual: "user" | "admin") => (
  <Suspense fallback={<Skeleton className="m-8 h-96" />}>
    <HelpPage manual={manual} />
  </Suspense>
);

function AdminStub() {
  const { t } = useTranslation();
  const { section } = useParams();
  return <StubPage id="S27–S36" name={`${t("nav.admin")}${section ? ` · ${section}` : ""}`} />;
}

function Authed() {
  return <RequireAuth>{(me) => <AppLayout me={me} />}</RequireAuth>;
}

/** Below <Authed/> the user is always loaded, so screens can take it as a prop. */
function MeRoute({ render }: { render: (me: Me) => ReactElement }) {
  const me = useMe().data;
  return me ? render(me) : null;
}

export const router = createBrowserRouter([
  { path: "/login", element: <LoginPage /> },
  { path: "/password-reset", element: <PasswordResetPage /> },
  { path: "/invitations/:token", element: <InvitationPage /> },
  {
    path: "/design",
    element: (
      <RequireAuth>
        {() => (
          <Suspense fallback={<Skeleton className="h-96 w-full" />}>
            <DesignPage />
          </Suspense>
        )}
      </RequireAuth>
    ),
  },
  {
    path: "/",
    element: <Authed />,
    children: [
      { index: true, element: <MeRoute render={(me) => <HomePage me={me} />} /> },
      { path: "my/page", element: <MyPage /> },
      { path: "my/timesheet", element: <TimesheetPage /> },
      { path: "my/account", element: <AccountPage /> },
      { path: "my/notifications", element: <NotificationSettingsPage /> },
      {
        path: "notifications",
        element: <NotificationsPage />,
        children: [{ path: "details/:number/:tab?", element: <NotificationDetailRoute /> }],
      },
      { path: "search", element: <SearchPage /> },
      { path: "work-packages", element: <WorkPackagesPage /> },
      { path: "gantt", element: <GanttPage /> },
      { path: "projects", element: <MeRoute render={(me) => <ProjectsPage me={me} />} /> },
      { path: "projects/new", element: <NewProjectPage /> },
      {
        path: "projects/:p",
        element: <ProjectLayout />,
        children: [
          { index: true, element: <ProjectOverviewPage /> },
          {
            path: "work-packages",
            element: (
              <RequireModule module="work_packages">
                <WorkPackagesPage />
              </RequireModule>
            ),
            children: [
              { path: "details/:number/:tab?", element: <DetailsPanelRoute /> },
              { path: "new", element: <NewPanelRoute /> },
            ],
          },
          { path: "work-packages/:number/:tab?", element: <FullViewRoute /> },
          {
            path: "gantt",
            element: <RequireModule module="gantt"><GanttPage /></RequireModule>,
            children: [{ path: "details/:number/:tab?", element: <GanttDetailRoute /> }],
          },
          { path: "boards", element: <RequireModule module="boards"><BoardsPage /></RequireModule> },
          { path: "boards/new", element: <RequireModule module="boards"><NewBoardPage /></RequireModule> },
          {
            path: "boards/:id",
            element: <RequireModule module="boards"><BoardPage /></RequireModule>,
            children: [{ path: "details/:number/:tab?", element: <BoardDetailRoute /> }],
          },
          { path: "calendar", element: <RequireModule module="calendar"><PlanningListPage kind="calendar" /></RequireModule> },
          {
            path: "calendar/:id",
            element: (
              <RequireModule module="calendar">
                <Suspense fallback={<Skeleton className="m-8 h-96" />}>
                  <CalendarPage />
                </Suspense>
              </RequireModule>
            ),
            children: [
              { path: "details/:number/:tab?", element: <PlanningDetailRoute kind="calendar" /> },
              { path: "new", element: <PlanningNewRoute kind="calendar" /> },
            ],
          },
          { path: "time", element: <RequireModule module="time_tracking"><ProjectTimeReportPage /></RequireModule> },
          { path: "releases", element: <RequireModule module="cicd"><ProjectReleasesPage tab="board" /></RequireModule> },
          { path: "releases/records", element: <RequireModule module="cicd"><ProjectReleasesPage tab="records" /></RequireModule> },
          { path: "releases/records/:releaseId", element: <RequireModule module="cicd"><ProjectReleasesPage tab="records" /></RequireModule> },
          { path: "team-planner", element: <RequireModule module="team_planner"><PlanningListPage kind="team_planner" /></RequireModule> },
          {
            path: "team-planner/:id",
            element: <RequireModule module="team_planner"><TeamPlannerPage /></RequireModule>,
            children: [
              { path: "details/:number/:tab?", element: <PlanningDetailRoute kind="team-planner" /> },
              { path: "new", element: <PlanningNewRoute kind="team-planner" /> },
            ],
          },
          { path: "members", element: <MembersPage /> },
          { path: "settings", element: <ProjectSettingsPage /> },
          { path: "settings/:section", element: <ProjectSettingsPage /> },
          { path: "*", element: <NotFound /> },
        ],
      },
      { path: "admin", element: <AdminHomePage /> },
      { path: "admin/types", element: <AdminTypesPage /> },
      { path: "admin/types/:id/form", element: <AdminTypeFormPage /> },
      { path: "admin/custom-fields", element: <AdminCustomFieldsPage /> },
      { path: "admin/progress", element: <AdminProgressPage /> },
      { path: "admin/authentication", element: <AdminAuthenticationPage /> },
      { path: "admin/statuses", element: <AdminStatusesPage /> },
      { path: "admin/workflows", element: <AdminWorkflowsPage /> },
      { path: "admin/priorities", element: <AdminPrioritiesPage /> },
      { path: "admin/roles", element: <AdminRolesPage /> },
      { path: "admin/roles/report", element: <AdminRolesReportPage /> },
      { path: "admin/roles/:id", element: <AdminRoleEditPage /> },
      { path: "admin/users", element: <AdminUsersPage /> },
      { path: "admin/groups", element: <AdminGroupsPage /> },
      { path: "admin/calendar", element: <AdminCalendarPage /> },
      { path: "admin/integrations", element: <AdminIntegrationsPage /> },
      { path: "admin/cicd", element: <AdminCicdPage /> },
      { path: "admin/system", element: <AdminSystemPage /> },
      { path: "help", element: helpPage("user") },
      { path: "help/admin", element: helpPage("admin") },
      { path: "help/api", element: <ApiDocsPage /> },
      { path: "admin/:section/*", element: <AdminStub /> },
      { path: "*", element: <NotFound /> },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
]);
