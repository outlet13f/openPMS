import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/cn";
import { readableOn } from "@/lib/color";
import { useTranslation } from "react-i18next";

/* ---------- Counter: "필터 2", "관계 6", unread badge ---------- */

export function Counter({ children, tone = "neutral", className }: { children: ReactNode; tone?: "neutral" | "accent" | "danger"; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex h-5 min-w-5 items-center justify-center rounded-full px-1.5 text-xs font-medium tabular-nums",
        tone === "neutral" && "bg-surface-selected text-fg",
        tone === "accent" && "bg-accent text-on-accent",
        tone === "danger" && "bg-danger text-on-danger",
        className,
      )}
    >
      {children}
    </span>
  );
}

/* ---------- Badge: labels such as "공개", "보관됨", "기본값" ---------- */

const badgeTone = {
  neutral: "border-border bg-surface text-fg-muted",
  accent: "border-transparent bg-accent-subtle text-accent-subtle-text",
  success: "border-transparent bg-success-subtle text-success",
  warning: "border-transparent bg-warning-subtle text-warning",
  danger: "border-transparent bg-danger-subtle text-danger",
};

export function Badge({ children, tone = "neutral", icon, className }: { children: ReactNode; tone?: keyof typeof badgeTone; icon?: ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex h-5 items-center gap-1 rounded-sm border px-1.5 text-xs font-medium [&_svg]:size-3", badgeTone[tone], className)}>
      {icon}
      {children}
    </span>
  );
}

/* ---------- Status pill: colour comes from the status row, text colour is computed ---------- */

interface StatusPillProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  name: string;
  color: string;
  /** Editable pills are buttons that open the allowed-status menu; read-only pills are plain text. */
  editable?: boolean;
  size?: "sm" | "md";
}

export const StatusPill = forwardRef<HTMLButtonElement, StatusPillProps>(function StatusPill(
  { name, color, editable, size = "md", className, ...rest },
  ref,
) {
  const { t } = useTranslation();
  const style = { backgroundColor: color, color: readableOn(color) };
  const cls = cn(
    "inline-flex max-w-full items-center gap-1 rounded-full font-medium whitespace-nowrap",
    size === "sm" ? "h-5 px-2 text-xs" : "h-6 px-2.5 text-xs",
    className,
  );
  if (!editable) {
    return (
      <span className={cls} style={style}>
        <span className="truncate">{name}</span>
      </span>
    );
  }
  return (
    <button
      ref={ref}
      type="button"
      aria-haspopup="menu"
      aria-label={t("wp.statusPill", { name })}
      className={cn(cls, "hover:brightness-95 active:brightness-90")}
      style={style}
      {...rest}
    >
      <span className="truncate">{name}</span>
      <ChevronDown className="size-3.5 shrink-0" aria-hidden="true" />
    </button>
  );
});

/* ---------- Type label: coloured, bold text. Admin colour picker guarantees 4.5:1 on white ---------- */

export function TypeLabel({ name, color, className }: { name: string; color: string; className?: string }) {
  return (
    <span className={cn("text-xs font-bold tracking-tight whitespace-nowrap", className)} style={{ color }}>
      {name}
    </span>
  );
}

/* ---------- Priority: dot + name (dot alone is never the only signal) ---------- */

export function Priority({ name, color, hideLabel, className }: { name: string; color: string; hideLabel?: boolean; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-sm text-fg", className)} title={hideLabel ? name : undefined}>
      <span aria-hidden="true" className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: color }} />
      {hideLabel ? <span className="sr-only">{name}</span> : name}
    </span>
  );
}
