// A table cell or attribute value that opens a small menu of choices (assignee, priority, type, version).
import type { ReactNode } from "react";
import { DropdownMenu as M } from "radix-ui";
import { Check, ChevronDown } from "lucide-react";
import { cn } from "@/lib/cn";

export interface PickOption {
  value: string | null;
  label: ReactNode;
  icon?: ReactNode;
}

export function InlinePick({
  value,
  options,
  onPick,
  disabled,
  label,
  children,
  className,
}: {
  value: string | null;
  options: PickOption[];
  onPick: (value: string | null) => void;
  disabled?: boolean;
  /** Accessible name, e.g. "담당자: 박준호. 바꾸기". */
  label: string;
  children: ReactNode;
  className?: string;
}) {
  if (disabled) return <span className={cn("inline-flex min-w-0 items-center", className)}>{children}</span>;
  return (
    <M.Root modal={false}>
      <M.Trigger
        aria-label={label}
        className={cn(
          "group -mx-1.5 inline-flex max-w-full min-w-0 items-center gap-1 rounded-sm px-1.5 py-0.5 text-left hover:bg-surface-hover data-[state=open]:bg-surface-selected",
          className,
        )}
      >
        <span className="min-w-0 truncate">{children}</span>
        <ChevronDown className="size-3.5 shrink-0 text-fg-muted opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100" aria-hidden="true" />
      </M.Trigger>
      <M.Portal>
        <M.Content align="start" sideOffset={4} className="z-(--pms-z-dropdown) max-h-72 min-w-48 overflow-y-auto rounded-lg border border-border bg-bg p-1 shadow-pop">
          {options.map((o) => (
            <M.Item
              key={o.value ?? "__none"}
              onSelect={() => o.value !== value && onPick(o.value)}
              className="flex h-8 cursor-default items-center gap-2 rounded-sm px-2 text-sm text-fg outline-none data-highlighted:bg-surface-hover"
            >
              <Check className={cn("size-4 shrink-0 text-accent", o.value !== value && "invisible")} aria-hidden="true" />
              {o.icon}
              <span className="truncate">{o.label}</span>
            </M.Item>
          ))}
        </M.Content>
      </M.Portal>
    </M.Root>
  );
}
