import type { ReactNode } from "react";
import { Link, NavLink } from "react-router";
import { Dialog as D } from "radix-ui";
import { Bell, ChevronDown, CircleHelp, LayoutGrid, Menu as MenuIcon, PanelLeftClose, Plus, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";
import { Avatar } from "@/components/ui/avatar";
import { Menu, Tooltip, type MenuEntry } from "@/components/ui/overlay";

/* ---------- Header: dark band, 56px. Placeholder colours until /replica-brand ---------- */

const headerBtn =
  "inline-flex h-8 items-center justify-center gap-1 rounded-md px-2 text-header-fg hover:bg-header-hover focus-visible:outline-header-fg [&_svg]:size-4";

export function AppHeader({
  user,
  unread = 0,
  search,
  quickAddItems,
  accountItems,
  helpItems,
  moduleItems,
  onOpenMenu,
  notificationsHref = "/notifications",
}: {
  user: { id: string; name: string };
  unread?: number;
  search?: ReactNode;
  quickAddItems?: MenuEntry[];
  accountItems?: MenuEntry[];
  helpItems?: MenuEntry[];
  /** Global modules (grid button); desktop only — phones use the drawer. */
  moduleItems?: MenuEntry[];
  onOpenMenu?: () => void;
  notificationsHref?: string;
}) {
  const { t } = useTranslation();
  return (
    <header className="sticky top-0 z-(--pms-z-header) flex h-header items-center gap-2 bg-header-bg px-3 text-header-fg">
      <a href="#main" className="sr-only rounded-md bg-bg px-2 py-1 text-fg focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50">
        {t("app.skipToContent")}
      </a>
      {onOpenMenu && (
        <button type="button" className={cn(headerBtn, "lg:hidden")} aria-label={t("nav.openMenu")} onClick={onOpenMenu}>
          <MenuIcon />
        </button>
      )}
      {moduleItems && moduleItems.length > 0 && (
        <span className="hidden lg:inline-flex">
          <Menu
            width="w-60"
            trigger={
              <button type="button" className={headerBtn} aria-label={t("nav.modules")}>
                <LayoutGrid />
              </button>
            }
            items={moduleItems}
          />
        </span>
      )}
      <Link to="/" className="mr-2 inline-flex h-8 items-center gap-2 rounded-md px-1 font-bold tracking-tight hover:bg-header-hover focus-visible:outline-header-fg">
        {/* Wordmark placeholder: the real logo comes from /replica-brand */}
        <span aria-hidden="true" className="inline-flex size-6 items-center justify-center rounded-sm bg-header-fg text-xs font-black text-header-bg">
          P
        </span>
        <span className="hidden sm:inline">{t("app.name")}</span>
      </Link>
      <div className="mx-auto hidden w-full max-w-md md:block">{search}</div>
      <div className="ml-auto flex items-center gap-1 md:ml-0">
        {quickAddItems && quickAddItems.length > 0 && (
          <Menu
            align="end"
            trigger={
              <button type="button" className={cn(headerBtn, "bg-accent hover:bg-accent-hover")} aria-label={t("nav.newMenu")}>
                <Plus />
                <ChevronDown className="size-3!" aria-hidden="true" />
              </button>
            }
            items={quickAddItems}
          />
        )}
        <Tooltip content={t("nav.notifications")}>
          <Link to={notificationsHref} className={cn(headerBtn, "relative")} aria-label={unread ? t("nav.unread", { count: unread }) : t("nav.notifications")}>
            <Bell />
            {unread > 0 && (
              <span aria-hidden="true" className="absolute -top-0.5 -right-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold text-on-danger">
                {unread > 99 ? "99+" : unread}
              </span>
            )}
          </Link>
        </Tooltip>
        {helpItems && (
          <Menu
            align="end"
            trigger={
              <button type="button" className={headerBtn} aria-label={t("nav.help")}>
                <CircleHelp />
              </button>
            }
            items={helpItems}
          />
        )}
        <Menu
          align="end"
          width="w-60"
          trigger={
            <button type="button" className={cn(headerBtn, "px-1")} aria-label={t("nav.account", { name: user.name })}>
              <Avatar id={user.id} name={user.name} size="md" className="ring-2 ring-header-fg/30" />
            </button>
          }
          items={[{ kind: "label", label: user.name }, ...(accountItems ?? [])]}
        />
      </div>
    </header>
  );
}

/* ---------- Sidebar: 280px, header slot + sections of links ---------- */

export interface SidebarItem {
  label: string;
  to: string;
  icon?: ReactNode;
  badge?: ReactNode;
  /** Match the path exactly (home, overview) instead of as a prefix. */
  end?: boolean;
  /** Force the active state (e.g. the selected saved view, which lives in the query string). */
  active?: boolean;
}
export interface SidebarSection {
  title?: string;
  items: SidebarItem[];
}

const itemCls = (active: boolean) =>
  cn(
    "relative flex h-sidebar-item items-center gap-2.5 rounded-md px-2.5 text-sm text-fg hover:bg-surface-hover [&_svg]:size-4 [&_svg]:shrink-0 [&_svg]:text-fg-muted",
    active && "bg-surface-selected font-semibold before:absolute before:inset-y-1.5 before:left-0 before:w-1 before:rounded-full before:bg-accent",
  );

export function Sidebar({
  label,
  header,
  sections,
  footer,
  onNavigate,
  onCollapse,
  className,
}: {
  label: string;
  header?: ReactNode;
  sections: SidebarSection[];
  footer?: ReactNode;
  onNavigate?: () => void;
  /** Desktop only: shows a "메뉴 접기" button at the bottom. */
  onCollapse?: () => void;
  className?: string;
}) {
  const { t } = useTranslation();
  return (
    <nav aria-label={label} className={cn("flex w-sidebar shrink-0 flex-col border-r border-border-muted bg-bg", className)}>
      {header && <div className="border-b border-border-muted p-3">{header}</div>}
      <div className="flex-1 overflow-y-auto p-3">
        {sections.map((s, i) => (
          <div key={i} className={cn(i > 0 && "mt-4")}>
            {s.title && <h2 className="px-2.5 pb-1 text-xs font-semibold text-fg-muted">{s.title}</h2>}
            <ul className="flex flex-col gap-0.5">
              {s.items.map((it) => (
                <li key={it.to + it.label}>
                  <NavLink
                    to={it.to}
                    end={it.end}
                    onClick={onNavigate}
                    className={({ isActive }) => itemCls(it.active ?? isActive)}
                    aria-current={it.active ? "page" : undefined}
                  >
                    {it.icon}
                    <span className="min-w-0 flex-1 truncate">{it.label}</span>
                    {it.badge}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      {footer && <div className="border-t border-border-muted p-3">{footer}</div>}
      {onCollapse && (
        <div className="hidden border-t border-border-muted p-2 lg:block">
          <button
            type="button"
            onClick={onCollapse}
            className="inline-flex h-8 w-full items-center gap-2 rounded-md px-2.5 text-sm text-fg-muted hover:bg-surface-hover hover:text-fg [&_svg]:size-4"
          >
            <PanelLeftClose aria-hidden="true" />
            {t("nav.collapse")}
          </button>
        </div>
      )}
    </nav>
  );
}

/** Below lg the sidebar becomes a drawer: focus is trapped, Esc closes, focus returns to the menu button. */
export function SidebarDrawer({ open, onOpenChange, title, children }: { open: boolean; onOpenChange: (o: boolean) => void; title: string; children: ReactNode }) {
  const { t } = useTranslation();
  return (
    <D.Root open={open} onOpenChange={onOpenChange}>
      <D.Portal>
        <D.Overlay className="fixed inset-0 z-(--pms-z-sidebar-overlay) bg-backdrop lg:hidden" />
        <D.Content className="fixed inset-y-0 left-0 z-(--pms-z-sidebar-overlay) flex max-w-[85vw] flex-col bg-bg shadow-modal focus-visible:outline-none lg:hidden">
          <D.Title className="sr-only">{title}</D.Title>
          <D.Description className="sr-only">{title}</D.Description>
          <D.Close className="absolute top-3 right-3 z-10 inline-flex size-8 items-center justify-center rounded-md text-fg-muted hover:bg-surface-hover" aria-label={t("app.close")}>
            <X className="size-4" />
          </D.Close>
          <div className="flex min-h-0 flex-1">{children}</div>
        </D.Content>
      </D.Portal>
    </D.Root>
  );
}
