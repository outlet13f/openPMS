import { cn } from "@/lib/cn";
import { avatarColor } from "@/lib/color";

const sizes = { xs: "size-5 text-[10px]", sm: "size-6 text-[11px]", md: "size-8 text-xs" } as const;

/**
 * Initials avatar. Korean names use the given name: two syllables (김가나 → 가나), or one at xs size (→ 가).
 * Latin names use first + last initials.
 */
export function initials(name: string, max: 1 | 2 = 2) {
  const trimmed = name.trim();
  if (!trimmed) return "?";
  if (/[ㄱ-힝]/.test(trimmed)) return trimmed.replace(/\s+/g, "").slice(-2).slice(0, max);
  const parts = trimmed.split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

export function Avatar({ id, name, size = "sm", className }: { id: string; name: string; size?: keyof typeof sizes; className?: string }) {
  const role = avatarColor(id);
  return (
    <span
      aria-hidden="true"
      className={cn("inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white select-none", sizes[size], className)}
      style={{ backgroundColor: `var(--pms-color-data-${role})` }}
    >
      {initials(name, size === "xs" ? 1 : 2)}
    </span>
  );
}

/** Avatar plus visible name; the name is the accessible text. */
export function Principal({ id, name, size = "sm", muted, className }: { id: string; name: string; size?: keyof typeof sizes; muted?: boolean; className?: string }) {
  return (
    <span className={cn("inline-flex min-w-0 items-center gap-2 text-sm", muted ? "text-fg-muted" : "text-fg", className)}>
      <Avatar id={id} name={name} size={size} />
      <span className="truncate">{name}</span>
    </span>
  );
}
