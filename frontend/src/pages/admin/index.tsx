// /admin: access guard and the administration landing page.
import type { ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { CalendarDays, CircleDot, Flag, Gauge, GitBranch, GitMerge, KeyRound, ListPlus, Lock, Plug, Server, Shapes, ShieldCheck, Users, UsersRound } from "lucide-react";
import { useMe } from "@/api/hooks";
import { EmptyState } from "@/components/ui/feedback";
import { PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { Capabilities } from "@/features/help/capabilities";

/**
 * Administrators only; user and group screens also accept global.manage_users (architecture.md §4.2), the CI/CD
 * connections screen global.manage_cicd.
 */
export function AdminGuard({ children, userManagers, cicdManagers }: { children: ReactNode; userManagers?: boolean; cicdManagers?: boolean }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const ok =
    !!me &&
    (me.isAdmin || (userManagers && me.globalPermissions.includes("global.manage_users")) || (cicdManagers && me.globalPermissions.includes("global.manage_cicd")));
  if (!ok)
    return (
      <EmptyState icon={<Lock />} title={t("app.forbiddenTitle")}>
        {t("admin.onlyAdmins")}
      </EmptyState>
    );
  return <>{children}</>;
}

export const ADMIN_SECTIONS = [
  { to: "/admin/types", key: "types", icon: <Shapes /> },
  { to: "/admin/statuses", key: "statuses", icon: <CircleDot /> },
  { to: "/admin/workflows", key: "workflows", icon: <GitBranch /> },
  { to: "/admin/priorities", key: "priorities", icon: <Flag /> },
  { to: "/admin/custom-fields", key: "customFields", icon: <ListPlus /> },
  { to: "/admin/progress", key: "progress", icon: <Gauge /> },
  { to: "/admin/users", key: "users", icon: <Users /> },
  { to: "/admin/groups", key: "groups", icon: <UsersRound /> },
  { to: "/admin/roles", key: "roles", icon: <ShieldCheck /> },
  { to: "/admin/authentication", key: "authentication", icon: <KeyRound /> },
  { to: "/admin/calendar", key: "calendar", icon: <CalendarDays /> },
  { to: "/admin/integrations", key: "integrations", icon: <Plug /> },
  { to: "/admin/cicd", key: "cicd", icon: <GitMerge /> },
  { to: "/admin/system", key: "system", icon: <Server /> },
] as const;

export function AdminHomePage() {
  const { t } = useTranslation();
  useDocumentTitle(t("nav.admin"));
  return (
    <PageBody>
      <PageHeader title={t("nav.admin")} />
      <AdminGuard>
        <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {ADMIN_SECTIONS.map((s) => (
            <li key={s.key}>
              <Link to={s.to} className="flex h-full items-start gap-3 rounded-lg border border-border p-4 hover:border-border-input hover:bg-surface [&_svg]:size-5 [&_svg]:shrink-0 [&_svg]:text-fg-muted">
                {s.icon}
                <span>
                  <span className="block text-sm font-semibold text-fg">{t(`admin.sections.${s.key}`)}</span>
                  <span className="mt-0.5 block text-sm text-fg-muted">{t(`admin.sectionHints.${s.key}`)}</span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
        <Capabilities />
      </AdminGuard>
    </PageBody>
  );
}
