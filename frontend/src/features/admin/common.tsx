// Building blocks shared by the administration and project settings screens.
import { useId, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDownToLine, ArrowDown, ArrowUp, ArrowUpToLine, Check, ListOrdered, Minus } from "lucide-react";
import { isApiError } from "@/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/field";
import { useToast } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { contrast } from "@/lib/color";

/** Wraps a mutation call with the standard success / failure toasts. Resolves to true on success. */
export function useRunWithToast() {
  const { t } = useTranslation();
  const toast = useToast();
  return async <T,>(p: Promise<T>, success?: string): Promise<T | null> => {
    try {
      const r = await p;
      if (success) toast({ tone: "success", title: success });
      return r;
    } catch (e) {
      if (isApiError(e) && e.fieldErrors.length) return null; // shown next to the field by the form
      toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle"), description: isApiError(e) && e.message !== e.title ? e.message : undefined });
      return null;
    }
  };
}

/** Error message for one field from a failed mutation (422 field errors). */
export function fieldErrorOf(error: unknown, field: string) {
  return isApiError(error) ? error.fieldErrors.find((f) => f.field === field)?.message : undefined;
}

/** Yes / no cell with an icon and text (never colour alone). */
export function YesNo({ value }: { value: boolean }) {
  const { t } = useTranslation();
  return value ? (
    <span className="inline-flex items-center gap-1 text-fg">
      <Check className="size-4 text-success" aria-hidden="true" />
      {t("admin.yes")}
    </span>
  ) : (
    <span className="inline-flex items-center gap-1 text-fg-muted">
      <Minus className="size-4" aria-hidden="true" />
      <span className="sr-only">{t("admin.no")}</span>
    </span>
  );
}

/**
 * Colour input with a native picker and a hex field. `asText` warns when the colour would be used
 * as text on white and falls below 4.5:1 (type labels); status pills pick their own text colour.
 */
export function ColorField({ label, value, onChange, asText, error }: { label: string; value: string; onChange: (v: string) => void; asText?: boolean; error?: string }) {
  const { t } = useTranslation();
  const id = useId();
  const valid = /^#[0-9a-fA-F]{6}$/.test(value);
  const ratio = valid ? contrast(value, "#ffffff") : null;
  const low = asText && ratio !== null && ratio < 4.5;
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-fg">
        {label}
      </label>
      <div className="flex items-center gap-2">
        <input
          type="color"
          aria-label={`${label} ${t("admin.colorPicker")}`}
          value={valid ? value : "#000000"}
          onChange={(e) => onChange(e.target.value)}
          className="h-control-md w-12 shrink-0 cursor-pointer rounded-md border border-border-input bg-bg p-1"
        />
        <Input id={id} value={value} maxLength={7} spellCheck={false} className="w-32 font-mono" aria-invalid={!!error || !valid} onChange={(e) => onChange(e.target.value)} aria-describedby={`${id}-hint`} />
        {ratio !== null && <span className="text-xs text-fg-muted tabular-nums">{t("admin.contrastOnWhite", { ratio: ratio.toFixed(2) })}</span>}
      </div>
      <p id={`${id}-hint`} className={low || error ? "text-xs text-danger" : "text-xs text-fg-muted"}>
        {error ?? (low ? t("admin.contrastLow") : t("admin.colorHint"))}
      </p>
    </div>
  );
}

/** Reorder through one menu (components.md "ReorderList") instead of four buttons per row. */
export function OrderMenu<T extends { id: string }>({ items, item, label, onReorder }: { items: T[]; item: T; label: string; onReorder: (ids: string[]) => void }) {
  const { t } = useTranslation();
  const ids = items.map((x) => x.id);
  const i = ids.indexOf(item.id);
  const move = (to: number) => {
    const next = ids.filter((x) => x !== item.id);
    next.splice(Math.max(0, Math.min(next.length, to)), 0, item.id);
    onReorder(next);
  };
  return (
    <Menu
      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.reorder", { name: label })} icon={<ListOrdered />} />}
      items={[
        { label: t("admin.moveTop"), icon: <ArrowUpToLine />, disabled: i === 0, onSelect: () => move(0) },
        { label: t("admin.moveUp"), icon: <ArrowUp />, disabled: i === 0, onSelect: () => move(i - 1) },
        { label: t("admin.moveDown"), icon: <ArrowDown />, disabled: i === ids.length - 1, onSelect: () => move(i + 1) },
        { label: t("admin.moveBottom"), icon: <ArrowDownToLine />, disabled: i === ids.length - 1, onSelect: () => move(ids.length) },
      ]}
    />
  );
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  body,
  confirm,
  pending,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  title: string;
  body?: ReactNode;
  confirm: string;
  pending?: boolean;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="sm"
      title={title}
      footer={
        <>
          <Button data-autofocus onClick={() => onOpenChange(false)}>
            {t("app.cancel")}
          </Button>
          <Button variant="danger" loading={pending} onClick={onConfirm}>
            {confirm}
          </Button>
        </>
      }
    >
      <div className="text-sm text-fg">{body}</div>
    </Dialog>
  );
}

/** Standard admin data table wrapper (positioned for sr-only content, scrolls sideways when needed). */
export function AdminTable({ children, minWidth = 640 }: { children: ReactNode; minWidth?: number }) {
  return (
    <div className="relative overflow-x-auto rounded-md border border-border">
      <table className="w-full border-collapse text-sm" style={{ minWidth }}>
        {children}
      </table>
    </div>
  );
}

export const th = "px-3 text-left text-xs font-semibold whitespace-nowrap text-fg-muted";
export const theadRow = "h-row border-b border-border bg-surface";
export const tbodyRow = "h-row border-b border-border-muted last:border-b-0 hover:bg-surface";
