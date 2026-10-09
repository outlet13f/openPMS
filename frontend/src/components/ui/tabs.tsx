import type { ReactNode } from "react";
import { Tabs as T } from "radix-ui";
import { cn } from "@/lib/cn";
import { Counter } from "./badges";

export interface TabItem {
  value: string;
  label: ReactNode;
  count?: number;
  content: ReactNode;
  disabled?: boolean;
}

/** Underline tabs. Arrow keys move between tabs (Radix), the active tab has a 2px accent underline. */
export function Tabs({
  items,
  value,
  defaultValue,
  onValueChange,
  "aria-label": ariaLabel,
  className,
}: {
  items: TabItem[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (v: string) => void;
  "aria-label": string;
  className?: string;
}) {
  return (
    <T.Root value={value} defaultValue={defaultValue ?? items[0]?.value} onValueChange={onValueChange} className={className}>
      <T.List aria-label={ariaLabel} className="flex gap-1 overflow-x-auto border-b border-border">
        {items.map((it) => (
          <T.Trigger
            key={it.value}
            value={it.value}
            disabled={it.disabled}
            className={cn(
              "relative -mb-px inline-flex h-10 shrink-0 items-center gap-1.5 border-b-2 border-transparent px-3 text-sm text-fg-muted",
              "hover:text-fg data-[state=active]:border-accent data-[state=active]:font-semibold data-[state=active]:text-fg",
              "focus-visible:-outline-offset-2 disabled:opacity-50",
            )}
          >
            {it.label}
            {it.count !== undefined && <Counter>{it.count}</Counter>}
          </T.Trigger>
        ))}
      </T.List>
      {items.map((it) => (
        <T.Content key={it.value} value={it.value} className="pt-4 focus-visible:outline-offset-4">
          {it.content}
        </T.Content>
      ))}
    </T.Root>
  );
}
