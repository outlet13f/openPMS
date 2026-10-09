// Every screen in replica/recon.md gets a route (architecture.md §4.3). Screens not built yet render
// a StubPage naming their S-ID; replica/build-log.md tracks which is which.
import type { ReactElement } from "react";
import { createBrowserRouter, Navigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import type { ParseKeys } from "i18next";
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
import { DesignPage } from "@/pages/design-page";
import { GanttDetailRoute, GanttPage } from "@/pages/gantt";
import { BoardsPage, NewBoardPage } from "@/pages/boards/list";
import { BoardDetailRoute, BoardPage } from "@/pages/boards/board";

function Stub({ id, nameKey }: { id: string; nameKey: ParseKeys }) {
  const { t } = useTranslation();
  return <StubPage id={id} name={t(nameKey) as string} />;
}

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
  { path: "/password-reset", element: <Stub id="S01" nameKey="stubs.passwordReset" /> },
  { path: "/invitations/:token", element: <Stub id="S01" nameKey="stubs.invitation" /> },
  { path: "/design", element: <DesignPage /> },
  {
    path: "/",
    element: <Authed />,
    children: [
      { index: true, element: <MeRoute render={(me) => <HomePage me={me} />} /> },
      { path: "my/page", element: <Stub id="S21" nameKey="nav.myPage" /> },
      { path: "my/account", element: <Stub id="S37" nameKey="nav.myAccount" /> },
      { path: "my/notifications", element: <Stub id="S20" nameKey="nav.notificationSettings" /> },
      {
        path: "notifications",
        element: <NotificationsPage />,
        children: [{ path: "details/:number/:tab?", element: <NotificationDetailRoute /> }],
      },
      { path: "search", element: <Stub id="S22" nameKey="stubs.search" /> },
      { path: "work-packages", element: <WorkPackagesPage /> },
      { path: "gantt", element: <Stub id="S13" nameKey="nav.gantt" /> },
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
          { path: "calendar", element: <RequireModule module="calendar"><Stub id="S17" nameKey="nav.calendar" /></RequireModule> },
          { path: "team-planner", element: <RequireModule module="team_planner"><Stub id="S18" nameKey="nav.teamPlanner" /></RequireModule> },
          { path: "members", element: <MembersPage /> },
          { path: "settings/:section", element: <Stub id="S24–S26" nameKey="nav.settings" /> },
          { path: "*", element: <NotFound /> },
        ],
      },
      { path: "admin", element: <AdminStub /> },
      { path: "admin/:section/*", element: <AdminStub /> },
      { path: "*", element: <NotFound /> },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
]);
