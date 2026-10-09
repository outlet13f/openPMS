import { useEffect, useMemo, useRef, useState } from "react";
import { Link, Outlet, useLocation, useMatch, useNavigate, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import {
  ArrowLeft,
  Bell,
  CalendarDays,
  ChevronDown,
  FolderKanban,
  GanttChart,
  Home,
  KanbanSquare,
  LayoutDashboard,
  ListTodo,
  Palette,
  Settings,
  Shield,
  Star,
  UserRound,
  Users,
  CalendarRange,
} from "lucide-react";
import type { Me, Project } from "@/api";
import { API_MODE } from "@/api";
import { resetFakeDb } from "@/api/fake";
import { queryClient } from "./providers";
import { useProject, useProjects, useSavedQueries, useUnreadCount } from "@/api/hooks";
import { AppHeader, Sidebar, SidebarDrawer, type SidebarSection } from "@/components/shell/app-shell";
import { Menu, type MenuEntry } from "@/components/ui/overlay";
import { Input } from "@/components/ui/field";
import { Counter } from "@/components/ui/badges";
import { useLogout } from "./auth";
import { SearchBox } from "./search-box";
import { can } from "./project";
import { ADMIN_SECTIONS } from "@/pages/admin";

/* ---------- sidebars ---------- */

function GlobalSidebar({ me, onNavigate, unread }: { me: Me; onNavigate?: () => void; unread: number }) {
  const { t } = useTranslation();
  const sections: SidebarSection[] = [
    {
      items: [
        { label: t("nav.home"), to: "/", icon: <Home />, end: true },
        { label: t("nav.myPage"), to: "/my/page", icon: <UserRound /> },
        { label: t("nav.projects"), to: "/projects", icon: <FolderKanban /> },
        { label: t("nav.workPackages"), to: "/work-packages", icon: <ListTodo /> },
        { label: t("nav.gantt"), to: "/gantt", icon: <GanttChart /> },
        { label: t("nav.notifications"), to: "/notifications", icon: <Bell />, badge: unread ? <Counter tone="accent">{unread}</Counter> : undefined },
      ],
    },
  ];
  if (me.isAdmin) sections.push({ items: [{ label: t("nav.admin"), to: "/admin", icon: <Shield /> }] });
  return <Sidebar label={t("nav.globalMenu")} sections={sections} onNavigate={onNavigate} />;
}

function ProjectSwitcher({ project }: { project: Project }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const list = useProjects({ filter: "active", pageSize: 100 });
  const items: MenuEntry[] = [
    ...(list.data?.items ?? [])
      .sort((a, b) => Number(b.favorite) - Number(a.favorite))
      .map((p) => ({ label: p.name, icon: p.favorite ? <Star /> : <FolderKanban />, checked: undefined, onSelect: () => navigate(`/projects/${p.identifier}`) })),
    { kind: "separator" as const },
    { label: t("nav.allProjects"), onSelect: () => navigate("/projects") },
  ];
  return (
    <Menu
      width="w-64"
      trigger={
        <button type="button" className="inline-flex h-8 max-w-full min-w-0 items-center gap-1 rounded-md px-2 text-sm font-semibold text-fg hover:bg-surface-hover">
          <span className="truncate">{project.name}</span>
          <ChevronDown className="size-4 shrink-0 text-fg-muted" aria-hidden="true" />
        </button>
      }
      items={items}
    />
  );
}

function WorkPackageViewsNav({ project, base, onNavigate }: { project: Project; base: string; onNavigate?: () => void }) {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const views = useSavedQueries(project.id);
  const [filter, setFilter] = useState("");
  const current = params.get("view") ?? (params.get("q") ? "" : "default:open");
  const match = (name: string) => !filter || name.toLowerCase().includes(filter.toLowerCase());
  const all = (views.data ?? []).filter((v) => match(v.name));
  const toItem = (v: (typeof all)[number]) => ({ label: v.name, to: `${base}?view=${encodeURIComponent(v.id)}`, active: v.id === current });
  const sections: SidebarSection[] = [
    { title: t("wp.viewsDefault"), items: all.filter((v) => v.kind === "default").map(toItem) },
    { title: t("wp.viewsFavorite"), items: all.filter((v) => v.favorite).map(toItem) },
    { title: t("wp.viewsPublic"), items: all.filter((v) => v.kind === "public").map(toItem) },
    { title: t("wp.viewsPrivate"), items: all.filter((v) => v.kind === "private").map(toItem) },
  ].filter((s) => s.items.length);
  return (
    <Sidebar
      label={t("nav.projectMenu")}
      onNavigate={onNavigate}
      header={
        <div className="flex flex-col gap-2">
          <Link to={`/projects/${project.identifier}`} onClick={onNavigate} className="inline-flex h-8 items-center gap-2 rounded-md px-2 text-sm text-fg hover:bg-surface-hover">
            <ArrowLeft className="size-4 text-fg-muted" aria-hidden="true" />
            <span className="truncate">{t("nav.backToProject")}</span>
          </Link>
          <label className="block">
            <span className="sr-only">{t("wp.viewsSearch")}</span>
            <Input size="sm" type="search" placeholder={t("wp.viewsSearch")} value={filter} onChange={(e) => setFilter(e.target.value)} />
          </label>
        </div>
      }
      sections={sections}
    />
  );
}

function ProjectSidebar({ identifier, onNavigate }: { identifier: string; onNavigate?: () => void }) {
  const { t } = useTranslation();
  const project = useProject(identifier);
  const inViews = useMatch("/projects/:p/work-packages/*");
  const inGanttViews = useMatch("/projects/:p/gantt/*");
  if (!project.data) return <Sidebar label={t("nav.projectMenu")} sections={[]} />;
  const p = project.data;
  const base = `/projects/${p.identifier}`;
  if (inViews && !/\/work-packages\/\d+/.test(inViews.pathname)) return <WorkPackageViewsNav project={p} base={`${base}/work-packages`} onNavigate={onNavigate} />;
  if (inGanttViews) return <WorkPackageViewsNav project={p} base={`${base}/gantt`} onNavigate={onNavigate} />;
  const mod = (m: Project["enabledModules"][number]) => p.enabledModules.includes(m);
  const items = [
    { label: t("nav.overview"), to: base, icon: <LayoutDashboard />, end: true },
    { label: t("nav.workPackages"), to: `${base}/work-packages`, icon: <ListTodo /> },
    mod("gantt") && { label: t("nav.gantt"), to: `${base}/gantt`, icon: <GanttChart /> },
    mod("boards") && can(p, "board.view") && { label: t("nav.boards"), to: `${base}/boards`, icon: <KanbanSquare /> },
    mod("calendar") && { label: t("nav.calendar"), to: `${base}/calendar`, icon: <CalendarDays /> },
    mod("team_planner") && { label: t("nav.teamPlanner"), to: `${base}/team-planner`, icon: <CalendarRange /> },
    can(p, "project.view_members") && { label: t("nav.members"), to: `${base}/members`, icon: <Users /> },
    can(p, "project.edit") && { label: t("nav.settings"), to: `${base}/settings/general`, icon: <Settings /> },
  ].filter(Boolean) as SidebarSection["items"];
  return <Sidebar label={t("nav.projectMenu")} header={<ProjectSwitcher project={p} />} sections={[{ items }]} onNavigate={onNavigate} />;
}

function AdminSidebar({ me, onNavigate }: { me: Me; onNavigate?: () => void }) {
  const { t } = useTranslation();
  const groups: { title: string; keys: string[] }[] = [
    { title: t("admin.groupTitles.workPackages"), keys: ["types", "statuses", "workflows", "priorities"] },
    { title: t("admin.groupTitles.people"), keys: ["users", "groups", "roles"] },
    { title: t("admin.groupTitles.calendar"), keys: ["calendar"] },
  ];
  const visible = (k: string) => me.isAdmin || ((k === "users" || k === "groups") && me.globalPermissions.includes("global.manage_users"));
  return (
    <Sidebar
      label={t("nav.admin")}
      onNavigate={onNavigate}
      header={
        <Link to="/" onClick={onNavigate} className="inline-flex h-8 items-center gap-2 rounded-md px-2 text-sm text-fg hover:bg-surface-hover">
          <ArrowLeft className="size-4 text-fg-muted" aria-hidden="true" />
          {t("admin.back")}
        </Link>
      }
      sections={[
        { items: [{ label: t("nav.admin"), to: "/admin", icon: <Shield />, end: true }] },
        ...groups.map((g) => ({
          title: g.title,
          items: ADMIN_SECTIONS.filter((s) => g.keys.includes(s.key) && visible(s.key)).map((s) => ({ label: t(`admin.sections.${s.key}`), to: s.to, icon: s.icon })),
        })),
      ].filter((s) => s.items.length)}
    />
  );
}

/* ---------- the layout ---------- */

export function AppLayout({ me }: { me: Me }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const logout = useLogout();
  const [drawer, setDrawer] = useState(false);
  const unread = useUnreadCount(true).data ?? 0;
  const projectMatch = useMatch("/projects/:p/*");
  const identifier = projectMatch && projectMatch.params.p !== "new" ? projectMatch.params.p : undefined;
  const project = useProject(identifier).data;
  const mainRef = useRef<HTMLElement>(null);

  // Move focus to <main> when the page changes (not when only the split panel changes), so screen
  // readers land on the new content and keyboard users do not stay on a link that left the screen.
  const pageKey = location.pathname.replace(/\/(details\/\d+.*|new)$/, "");
  const lastPage = useRef(pageKey);
  useEffect(() => {
    setDrawer(false);
    if (lastPage.current === pageKey) return; // first load (and StrictMode's second run) keeps the browser's focus
    lastPage.current = pageKey;
    mainRef.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0 });
  }, [pageKey]);

  const inAdmin = !!useMatch("/admin/*");
  const sidebar = (close?: () => void) =>
    identifier ? (
      <ProjectSidebar identifier={identifier} onNavigate={close} />
    ) : inAdmin ? (
      <AdminSidebar me={me} onNavigate={close} />
    ) : (
      <GlobalSidebar me={me} onNavigate={close} unread={unread} />
    );

  const quickAdd = useMemo<MenuEntry[]>(() => {
    const items: MenuEntry[] = [];
    if (project && can(project, "wp.create")) items.push({ label: t("nav.newWorkPackage"), icon: <ListTodo />, onSelect: () => navigate(`/projects/${project.identifier}/work-packages/new`) });
    if (me.globalPermissions.includes("global.create_projects")) items.push({ label: t("nav.newProject"), icon: <FolderKanban />, onSelect: () => navigate("/projects/new") });
    return items;
  }, [project, me, navigate, t]);

  const accountItems: MenuEntry[] = [
    { label: t("nav.myAccount"), icon: <UserRound />, onSelect: () => navigate("/my/account") },
    { label: t("nav.notificationSettings"), icon: <Bell />, onSelect: () => navigate("/my/notifications") },
    { kind: "separator" },
    ...(API_MODE === "fake"
      ? [{ label: t("nav.resetDemo"), onSelect: () => (resetFakeDb(), queryClient.clear(), navigate("/login")) } as MenuEntry]
      : []),
    { label: t("nav.logout"), onSelect: () => void logout() },
  ];

  return (
    <div className="flex min-h-dvh flex-col bg-bg">
      <AppHeader
        user={me}
        unread={unread}
        search={<SearchBox />}
        quickAddItems={quickAdd}
        accountItems={accountItems}
        helpItems={[{ label: t("nav.designSystem"), icon: <Palette />, onSelect: () => navigate("/design") }]}
        onOpenMenu={() => setDrawer(true)}
      />
      <div className="flex min-h-0 flex-1">
        <div className="sticky top-header hidden h-[calc(100dvh-var(--pms-layout-header-h))] lg:flex">{sidebar()}</div>
        <SidebarDrawer open={drawer} onOpenChange={setDrawer} title={identifier ? t("nav.projectMenu") : t("nav.globalMenu")}>
          {sidebar(() => setDrawer(false))}
        </SidebarDrawer>
        <main id="main" ref={mainRef} tabIndex={-1} className="min-w-0 flex-1 outline-none">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
