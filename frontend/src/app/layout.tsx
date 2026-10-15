import { useEffect, useMemo, useRef, useState } from "react";
import { Link, Outlet, useLocation, useMatch, useNavigate, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { applyTheme } from "@/lib/theme";
import { ArrowLeft, Bell, BookOpen, CalendarDays, CalendarRange, ChevronDown, ClipboardCheck, Clock, Code2, FolderKanban, GanttChart, Home, KanbanSquare, Keyboard, LayoutDashboard, LifeBuoy, ListTodo, Palette, PanelLeftOpen, Settings, Shield, Star, UserRound, Users } from "lucide-react";
import type { Me, Project } from "@/api";
import { API_MODE } from "@/api";
import { resetFakeDb } from "@/api/fake";
import { queryClient } from "./providers";
import { usePortalApprovals, useProject, useProjects, useSavedQueries, useUnreadCount } from "@/api/hooks";
import { AppHeader, Sidebar, SidebarDrawer, type SidebarSection } from "@/components/shell/app-shell";
import { Menu, Tooltip, type MenuEntry } from "@/components/ui/overlay";
import { Input } from "@/components/ui/field";
import { Counter } from "@/components/ui/badges";
import { useLogout } from "./auth";
import { SearchBox } from "./search-box";
import { useShortcuts } from "./shortcuts";
import { can } from "./project";
import { ADMIN_SECTIONS } from "@/pages/admin";
import { HelpPanel } from "@/features/help/help-panel";
import { topicFor, type Topic } from "@/features/help/topics";

/* ---------- sidebars ---------- */

function GlobalSidebar({ me, onNavigate, unread, onCollapse }: { me: Me; onNavigate?: () => void; unread: number; onCollapse?: () => void }) {
  const { t } = useTranslation();
  // 내 승인함: the badge counts the approvals waiting for me and is hidden at 0 (the text carries the count for screen readers)
  const approvals = usePortalApprovals().data?.length ?? 0;
  const sections: SidebarSection[] = [
    {
      items: [
        { label: t("nav.home"), to: "/", icon: <Home />, end: true },
        { label: t("nav.myPage"), to: "/my/page", icon: <UserRound /> },
        { label: t("nav.timesheet"), to: "/my/timesheet", icon: <Clock /> },
        { label: t("nav.projects"), to: "/projects", icon: <FolderKanban /> },
        { label: t("nav.workPackages"), to: "/work-packages", icon: <ListTodo /> },
        { label: t("nav.gantt"), to: "/gantt", icon: <GanttChart /> },
        { label: t("nav.notifications"), to: "/notifications", icon: <Bell />, badge: unread ? <Counter tone="accent">{unread}</Counter> : undefined },
        { label: t("portal.title"), to: "/portal", icon: <LifeBuoy />, end: true },
        {
          label: t("portal.inbox.title"),
          to: "/portal/approvals",
          icon: <ClipboardCheck />,
          badge: approvals ? (
            <Counter tone="accent">
              <span aria-hidden="true">{approvals}</span>
              <span className="sr-only">{t("portal.inbox.navCount", { count: approvals })}</span>
            </Counter>
          ) : undefined,
        },
      ],
    },
  ];
  if (me.isAdmin) sections.push({ items: [{ label: t("nav.admin"), to: "/admin", icon: <Shield /> }] });
  return <Sidebar label={t("nav.globalMenu")} sections={sections} onNavigate={onNavigate} onCollapse={onCollapse} />;
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

function WorkPackageViewsNav({ project, base, onNavigate, onCollapse }: { project: Project; base: string; onNavigate?: () => void; onCollapse?: () => void }) {
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
      onCollapse={onCollapse}
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

function ProjectSidebar({ identifier, onNavigate, onCollapse }: { identifier: string; onNavigate?: () => void; onCollapse?: () => void }) {
  const { t } = useTranslation();
  const project = useProject(identifier);
  const inViews = useMatch("/projects/:p/work-packages/*");
  const inGanttViews = useMatch("/projects/:p/gantt/*");
  if (!project.data) return <Sidebar label={t("nav.projectMenu")} sections={[]} />;
  const p = project.data;
  const base = `/projects/${p.identifier}`;
  if (inViews && !/\/work-packages\/\d+/.test(inViews.pathname)) return <WorkPackageViewsNav project={p} base={`${base}/work-packages`} onNavigate={onNavigate} onCollapse={onCollapse} />;
  if (inGanttViews) return <WorkPackageViewsNav project={p} base={`${base}/gantt`} onNavigate={onNavigate} onCollapse={onCollapse} />;
  const mod = (m: Project["enabledModules"][number]) => p.enabledModules.includes(m);
  const items = [
    { label: t("nav.overview"), to: base, icon: <LayoutDashboard />, end: true },
    { label: t("nav.workPackages"), to: `${base}/work-packages`, icon: <ListTodo /> },
    mod("gantt") && { label: t("nav.gantt"), to: `${base}/gantt`, icon: <GanttChart /> },
    mod("boards") && can(p, "board.view") && { label: t("nav.boards"), to: `${base}/boards`, icon: <KanbanSquare /> },
    mod("calendar") && { label: t("nav.calendar"), to: `${base}/calendar`, icon: <CalendarDays /> },
    mod("team_planner") && { label: t("nav.teamPlanner"), to: `${base}/team-planner`, icon: <CalendarRange /> },
    mod("time_tracking") && can(p, "time.view") && { label: t("nav.timeReport"), to: `${base}/time`, icon: <Clock /> },
    can(p, "project.view_members") && { label: t("nav.members"), to: `${base}/members`, icon: <Users /> },
    can(p, "project.edit") ? { label: t("nav.settings"), to: `${base}/settings/general`, icon: <Settings /> }
    // agents without project.edit still reach the service desk settings (read-only below service.manage)
    : mod("service_desk") && can(p, "service.view") && { label: t("nav.settings"), to: `${base}/settings/service-desk`, icon: <Settings /> },
  ].filter(Boolean) as SidebarSection["items"];
  return <Sidebar label={t("nav.projectMenu")} header={<ProjectSwitcher project={p} />} sections={[{ items }]} onNavigate={onNavigate} onCollapse={onCollapse} />;
}

function AdminSidebar({ me, onNavigate, onCollapse }: { me: Me; onNavigate?: () => void; onCollapse?: () => void }) {
  const { t } = useTranslation();
  const groups: { title: string; keys: string[] }[] = [
    { title: t("admin.groupTitles.workPackages"), keys: ["types", "statuses", "workflows", "priorities", "customFields", "progress"] },
    { title: t("admin.groupTitles.people"), keys: ["users", "groups", "roles", "authentication"] },
    { title: t("admin.groupTitles.calendar"), keys: ["calendar", "serviceCalendars"] },
    { title: t("admin.groupTitles.integrations"), keys: ["integrations"] },
    { title: t("admin.groupTitles.system"), keys: ["system"] },
  ];
  const visible = (k: string) => me.isAdmin || ((k === "users" || k === "groups") && me.globalPermissions.includes("global.manage_users"));
  return (
    <Sidebar
      label={t("nav.admin")}
      onNavigate={onNavigate}
      onCollapse={onCollapse}
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
  useEffect(() => applyTheme(me.theme), [me.theme]);
  const navigate = useNavigate();
  const location = useLocation();
  const logout = useLogout();
  const [drawer, setDrawer] = useState(false);
  // 이 화면 도움말: the manual section for this screen, in a panel over it
  const [help, setHelp] = useState<Topic | null>(null);
  const screenTopic = topicFor(location.pathname);
  // Desktop sidebar can be collapsed to a thin rail; remembered per browser (a convenience, not data).
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem("openpms.sidebarCollapsed") === "1";
    } catch {
      return false;
    }
  });
  const setCollapsedSaved = (v: boolean) => {
    setCollapsed(v);
    try {
      localStorage.setItem("openpms.sidebarCollapsed", v ? "1" : "0");
    } catch {
      /* storage unavailable */
    }
  };
  const unread = useUnreadCount(true).data ?? 0;
  const projectMatch = useMatch("/projects/:p/*");
  const identifier = projectMatch && projectMatch.params.p !== "new" ? projectMatch.params.p : undefined;
  const project = useProject(identifier).data;
  const mainRef = useRef<HTMLElement>(null);
  const shortcuts = useShortcuts(me, project ?? null);

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
  const sidebar = (close?: () => void, onCollapse?: () => void) =>
    identifier ? (
      <ProjectSidebar identifier={identifier} onNavigate={close} onCollapse={onCollapse} />
    ) : inAdmin ? (
      <AdminSidebar me={me} onNavigate={close} onCollapse={onCollapse} />
    ) : (
      <GlobalSidebar me={me} onNavigate={close} unread={unread} onCollapse={onCollapse} />
    );

  const moduleItems: MenuEntry[] = [
    { label: t("nav.home"), icon: <Home />, onSelect: () => navigate("/") },
    { label: t("nav.myPage"), icon: <UserRound />, onSelect: () => navigate("/my/page") },
    { label: t("nav.timesheet"), icon: <Clock />, onSelect: () => navigate("/my/timesheet") },
    { label: t("nav.projects"), icon: <FolderKanban />, onSelect: () => navigate("/projects") },
    { label: t("nav.workPackages"), icon: <ListTodo />, onSelect: () => navigate("/work-packages") },
    { label: t("nav.gantt"), icon: <GanttChart />, onSelect: () => navigate("/gantt") },
    { label: t("nav.notifications"), icon: <Bell />, onSelect: () => navigate("/notifications") },
    { label: t("portal.title"), icon: <LifeBuoy />, onSelect: () => navigate("/portal") },
    { label: t("portal.inbox.title"), icon: <ClipboardCheck />, onSelect: () => navigate("/portal/approvals") },
    ...(me.isAdmin ? [{ kind: "separator" as const }, { label: t("nav.admin"), icon: <Shield />, onSelect: () => navigate("/admin") }] : []),
  ];

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
        helpItems={[
          ...(screenTopic ? [{ label: t("help.thisScreen"), icon: <BookOpen />, onSelect: () => setHelp(screenTopic) }] : []),
          { label: t("help.userManual"), icon: <BookOpen />, onSelect: () => navigate("/help") },
          ...(me.isAdmin ? [{ label: t("help.adminManual"), icon: <BookOpen />, onSelect: () => navigate("/help/admin") }] : []),
          { kind: "separator" as const },
          { label: t("shortcuts.title"), icon: <Keyboard />, shortcut: "?", onSelect: shortcuts.openHelp },
          { label: t("apiDocs.title"), icon: <Code2 />, onSelect: () => navigate("/help/api") },
          { label: t("nav.designSystem"), icon: <Palette />, onSelect: () => navigate("/design") },
        ]}
        moduleItems={moduleItems}
        onOpenMenu={() => setDrawer(true)}
      />
      {shortcuts.dialog}
      <HelpPanel topic={help} onClose={() => setHelp(null)} />
      <div className="flex min-h-0 flex-1">
        {collapsed ? (
          <div className="sticky top-header hidden h-[calc(100dvh-var(--pms-layout-header-h))] w-12 shrink-0 flex-col items-center border-r border-border-muted py-3 lg:flex">
            <Tooltip content={t("nav.expand")} side="right">
              <button
                type="button"
                onClick={() => setCollapsedSaved(false)}
                aria-label={t("nav.expand")}
                className="inline-flex size-8 items-center justify-center rounded-md text-fg-muted hover:bg-surface-hover hover:text-fg"
              >
                <PanelLeftOpen className="size-4" />
              </button>
            </Tooltip>
          </div>
        ) : (
          <div className="sticky top-header hidden h-[calc(100dvh-var(--pms-layout-header-h))] lg:flex">{sidebar(undefined, () => setCollapsedSaved(true))}</div>
        )}
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
