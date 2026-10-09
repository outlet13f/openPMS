import type { ReactNode } from "react";
import { Bell, ChevronDown, CircleHelp, LayoutGrid, PanelLeftClose, Plus, Search } from "lucide-react";
import { cn } from "@/lib/cn";
import { Button } from "@/components/ui/button";
import { Avatar } from "@/components/ui/avatar";
import { Menu } from "@/components/ui/overlay";

/* ---------- Header: dark band, 56px. Placeholder colours until /replica-brand ---------- */

const headerBtn =
  "inline-flex h-8 items-center justify-center gap-1 rounded-md px-2 text-header-fg hover:bg-header-hover focus-visible:outline-header-fg [&_svg]:size-4";

export function AppHeader({ unread = 0, user }: { unread?: number; user: { id: string; name: string } }) {
  return (
    <header className="sticky top-0 z-(--pms-z-header) flex h-header items-center gap-2 bg-header-bg px-3 text-header-fg">
      <a href="#main" className="sr-only rounded-md bg-bg px-2 py-1 text-fg focus:not-sr-only focus:absolute focus:top-2 focus:left-2">
        본문으로 건너뛰기
      </a>
      <button type="button" className={headerBtn} aria-label="모듈 메뉴">
        <LayoutGrid />
      </button>
      <a href="/" className="mr-2 inline-flex h-8 items-center gap-2 rounded-md px-1 font-bold tracking-tight hover:bg-header-hover">
        {/* Wordmark placeholder: the real logo comes from /replica-brand */}
        <span aria-hidden="true" className="inline-flex size-6 items-center justify-center rounded-sm bg-header-fg text-xs font-black text-header-bg">P</span>
        <span>openPMS</span>
      </a>
      <div className="mx-auto hidden w-full max-w-md md:block">
        <label className="relative block">
          <span className="sr-only">전체 검색</span>
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-header-fg-muted" />
          <input
            type="search"
            placeholder="작업, 프로젝트 검색 (#번호)"
            className="h-8 w-full rounded-md border border-header-fg-muted/40 bg-header-hover pr-2 pl-8 text-sm text-header-fg placeholder:text-header-fg-muted focus-visible:outline-header-fg"
          />
        </label>
      </div>
      <div className="ml-auto flex items-center gap-1 md:ml-0">
        <button type="button" className={cn(headerBtn, "md:hidden")} aria-label="검색">
          <Search />
        </button>
        <Menu
          align="end"
          trigger={
            <button type="button" className={cn(headerBtn, "bg-accent hover:bg-accent-hover")} aria-label="새로 만들기">
              <Plus />
              <ChevronDown className="size-3!" />
            </button>
          }
          items={[{ label: "새 프로젝트" }, { label: "새 작업" }, { label: "새 보드" }]}
        />
        <button type="button" className={cn(headerBtn, "relative")} aria-label={unread ? `알림 ${unread}건 읽지 않음` : "알림"}>
          <Bell />
          {unread > 0 && (
            <span aria-hidden="true" className="absolute -top-0.5 -right-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold text-on-danger">
              {unread > 99 ? "99+" : unread}
            </span>
          )}
        </button>
        <button type="button" className={headerBtn} aria-label="도움말">
          <CircleHelp />
        </button>
        <button type="button" className={cn(headerBtn, "px-1")} aria-label={`${user.name} 계정 메뉴`}>
          <Avatar id={user.id} name={user.name} size="md" className="ring-2 ring-header-fg/30" />
        </button>
      </div>
    </header>
  );
}

/* ---------- Sidebar: 280px, project switcher + module menu + saved views ---------- */

export interface SidebarItem { label: string; icon?: ReactNode; href: string; active?: boolean; badge?: ReactNode }
export interface SidebarSection { title?: string; items: SidebarItem[] }

export function Sidebar({ project, sections, className }: { project: string; sections: SidebarSection[]; className?: string }) {
  return (
    <nav aria-label="프로젝트 메뉴" className={cn("flex w-sidebar shrink-0 flex-col border-r border-border-muted bg-bg", className)}>
      <div className="flex items-center justify-between gap-2 border-b border-border-muted p-3">
        <button type="button" className="inline-flex h-8 min-w-0 items-center gap-1 rounded-md px-2 text-sm font-semibold text-fg hover:bg-surface-hover">
          <span className="truncate">{project}</span>
          <ChevronDown className="size-4 shrink-0 text-fg-muted" aria-hidden="true" />
        </button>
        <Button variant="ghost" size="sm" iconOnly aria-label="메뉴 접기" icon={<PanelLeftClose />} />
      </div>
      <div className="flex-1 overflow-y-auto p-3">
        {sections.map((s, i) => (
          <div key={i} className={cn(i > 0 && "mt-4")}>
            {s.title && <h2 className="px-2.5 pb-1 text-xs font-semibold text-fg-muted">{s.title}</h2>}
            <ul className="flex flex-col gap-0.5">
              {s.items.map((it) => (
                <li key={it.label}>
                  <a
                    href={it.href}
                    aria-current={it.active ? "page" : undefined}
                    className={cn(
                      "relative flex h-sidebar-item items-center gap-2.5 rounded-md px-2.5 text-sm text-fg hover:bg-surface-hover [&_svg]:size-4 [&_svg]:text-fg-muted",
                      it.active && "bg-surface-selected font-semibold before:absolute before:inset-y-1.5 before:left-0 before:w-1 before:rounded-full before:bg-accent",
                    )}
                  >
                    {it.icon}
                    <span className="flex-1 truncate">{it.label}</span>
                    {it.badge}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </nav>
  );
}
