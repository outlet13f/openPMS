import type { ReactNode } from "react";
import { Dialog as D, DropdownMenu as M, Tooltip as Tip } from "radix-ui";
import { Check, ChevronRight, X } from "lucide-react";
import { cn } from "@/lib/cn";
import { Button } from "./button";
import { useTranslation } from "react-i18next";

/* ---------- Dialog ---------- */

const dialogWidth = { sm: "max-w-modal-sm", md: "max-w-modal-md", lg: "max-w-modal-lg" };

interface DialogProps {
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  trigger?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  /** Optional coloured strip above the header, e.g. "날짜는 자식 작업에서 정해집니다". */
  banner?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  size?: keyof typeof dialogWidth;
}

/** Modal dialog: focus is trapped, Esc and the close button dismiss, focus returns to the trigger. */
export function Dialog({ open, defaultOpen, onOpenChange, trigger, title, description, banner, children, footer, size = "md" }: DialogProps) {
  const { t } = useTranslation();
  return (
    <D.Root open={open} defaultOpen={defaultOpen} onOpenChange={onOpenChange}>
      {trigger && <D.Trigger asChild>{trigger}</D.Trigger>}
      <D.Portal>
        <D.Overlay className="fixed inset-0 z-(--pms-z-modal) bg-backdrop" />
        <D.Content
          className={cn(
            "fixed top-1/2 left-1/2 z-(--pms-z-modal) flex max-h-[calc(100dvh-32px)] w-[calc(100vw-32px)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden",
            "rounded-lg bg-bg shadow-modal focus-visible:outline-none",
            dialogWidth[size],
          )}
          onOpenAutoFocus={(e) => {
            // Start on the element marked data-autofocus (usually the first field), not on a banner action.
            const target = (e.currentTarget as HTMLElement | null)?.querySelector<HTMLElement>("[data-autofocus]");
            if (target) {
              e.preventDefault();
              target.focus();
            }
          }}
        >
          {banner}
          <div className="flex items-start justify-between gap-4 px-5 pt-4 pb-2">
            <div className="min-w-0">
              <D.Title className="text-lg text-fg">{title}</D.Title>
              {description ? (
                <D.Description className="mt-1 text-sm text-fg-muted">{description}</D.Description>
              ) : (
                <D.Description className="sr-only">{typeof title === "string" ? title : ""}</D.Description>
              )}
            </div>
            <D.Close asChild>
              <Button variant="ghost" size="sm" iconOnly aria-label={t("app.close")} icon={<X />} />
            </D.Close>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-3">{children}</div>
          {footer && <div className="flex justify-end gap-2 border-t border-border-muted px-5 py-3">{footer}</div>}
        </D.Content>
      </D.Portal>
    </D.Root>
  );
}

export const DialogClose = D.Close;

/* ---------- Action menu ---------- */

export type MenuEntry =
  | { kind?: "item"; label: ReactNode; description?: ReactNode; icon?: ReactNode; onSelect?: () => void; disabled?: boolean; danger?: boolean; checked?: boolean; shortcut?: string }
  | { kind: "separator" }
  | { kind: "label"; label: ReactNode }
  | { kind: "sub"; label: ReactNode; icon?: ReactNode; items: MenuEntry[] };

const itemCls = cn(
  "relative flex min-h-8 cursor-default items-start gap-2 rounded-sm px-2 py-1.5 text-sm text-fg outline-none select-none",
  "data-highlighted:bg-surface-hover data-disabled:text-fg-disabled [&_svg]:mt-0.5 [&_svg]:size-4 [&_svg]:shrink-0 [&_svg]:text-fg-muted",
);

function renderEntries(entries: MenuEntry[]) {
  return entries.map((e, i) => {
    if (e.kind === "separator") return <M.Separator key={i} className="my-1 h-px bg-border-muted" />;
    if (e.kind === "label") return <M.Label key={i} className="px-2 pt-2 pb-1 text-xs font-semibold text-fg-muted">{e.label}</M.Label>;
    if (e.kind === "sub")
      return (
        <M.Sub key={i}>
          <M.SubTrigger className={cn(itemCls, "data-[state=open]:bg-surface-hover")}>
            {e.icon}
            <span className="flex-1">{e.label}</span>
            <ChevronRight aria-hidden="true" />
          </M.SubTrigger>
          <M.Portal>
            <M.SubContent sideOffset={4} className="z-(--pms-z-dropdown) min-w-48 rounded-lg border border-border bg-bg p-1 shadow-pop">
              {renderEntries(e.items)}
            </M.SubContent>
          </M.Portal>
        </M.Sub>
      );
    return (
      <M.Item key={i} disabled={e.disabled} onSelect={e.onSelect} className={cn(itemCls, e.danger && "text-danger [&_svg]:text-danger")}>
        {e.checked !== undefined ? <Check aria-hidden="true" className={cn(!e.checked && "invisible")} /> : e.icon}
        <span className="flex min-w-0 flex-1 flex-col">
          <span>{e.label}</span>
          {e.description && <span className="text-xs text-fg-muted">{e.description}</span>}
        </span>
        {e.shortcut && <kbd className="ml-4 font-sans text-xs text-fg-muted">{e.shortcut}</kbd>}
      </M.Item>
    );
  });
}

export function Menu({
  trigger,
  items,
  align = "start",
  width = "w-56",
  defaultOpen,
}: {
  trigger: ReactNode;
  items: MenuEntry[];
  align?: "start" | "end";
  width?: string;
  defaultOpen?: boolean;
}) {
  return (
    <M.Root defaultOpen={defaultOpen} modal={false}>
      <M.Trigger asChild>{trigger}</M.Trigger>
      <M.Portal>
        <M.Content align={align} sideOffset={4} className={cn("z-(--pms-z-dropdown) rounded-lg border border-border bg-bg p-1 shadow-pop", width)}>
          {renderEntries(items)}
        </M.Content>
      </M.Portal>
    </M.Root>
  );
}

/* ---------- Tooltip (labels for icon-only buttons; never the only place information lives) ---------- */

export function TooltipProvider({ children }: { children: ReactNode }) {
  return <Tip.Provider delayDuration={400}>{children}</Tip.Provider>;
}

export function Tooltip({ content, children, side = "bottom" }: { content: ReactNode; children: ReactNode; side?: "top" | "bottom" | "left" | "right" }) {
  return (
    <Tip.Root>
      <Tip.Trigger asChild>{children}</Tip.Trigger>
      <Tip.Portal>
        <Tip.Content side={side} sideOffset={6} className="z-(--pms-z-tooltip) max-w-64 rounded-md bg-fg px-2 py-1 text-xs text-bg shadow-pop">
          {content}
        </Tip.Content>
      </Tip.Portal>
    </Tip.Root>
  );
}
