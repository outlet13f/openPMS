import { useId, type ReactNode } from "react";
import { Checkbox as C, RadioGroup as R, ToggleGroup as T } from "radix-ui";
import { Check, Minus } from "lucide-react";
import { cn } from "@/lib/cn";

/* ---------- Checkbox ---------- */

interface CheckboxProps {
  label?: ReactNode;
  description?: ReactNode;
  checked?: boolean | "indeterminate";
  defaultChecked?: boolean;
  onCheckedChange?: (checked: boolean | "indeterminate") => void;
  disabled?: boolean;
  "aria-label"?: string;
}

export function Checkbox({ label, description, ...rest }: CheckboxProps) {
  const id = useId();
  return (
    <div className="flex items-start gap-2">
      <C.Root
        id={id}
        {...rest}
        className={cn(
          "peer mt-0.5 inline-flex size-4 shrink-0 items-center justify-center rounded-sm border border-border-input bg-bg",
          "data-[state=checked]:border-accent data-[state=checked]:bg-accent data-[state=checked]:text-on-accent",
          "data-[state=indeterminate]:border-accent data-[state=indeterminate]:bg-accent data-[state=indeterminate]:text-on-accent",
          "disabled:cursor-not-allowed disabled:opacity-50",
        )}
      >
        <C.Indicator>
          {rest.checked === "indeterminate" ? <Minus className="size-3" strokeWidth={3} /> : <Check className="size-3" strokeWidth={3} />}
        </C.Indicator>
      </C.Root>
      {label && (
        <label htmlFor={id} className="text-sm text-fg peer-disabled:text-fg-disabled">
          {label}
          {description && <span className="block text-xs text-fg-muted">{description}</span>}
        </label>
      )}
    </div>
  );
}

/* ---------- Radio cards (board type, project template) ---------- */

export interface RadioCardOption {
  value: string;
  title: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
  disabled?: boolean;
}

export function RadioCards({
  options,
  value,
  defaultValue,
  onValueChange,
  columns = 2,
  "aria-label": ariaLabel,
}: {
  options: RadioCardOption[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (v: string) => void;
  columns?: 1 | 2 | 3;
  "aria-label": string;
}) {
  return (
    <R.Root
      value={value}
      defaultValue={defaultValue}
      onValueChange={onValueChange}
      aria-label={ariaLabel}
      className={cn("grid gap-3", columns === 1 ? "grid-cols-1" : columns === 2 ? "grid-cols-1 sm:grid-cols-2" : "grid-cols-1 sm:grid-cols-3")}
    >
      {options.map((o) => (
        <R.Item
          key={o.value}
          value={o.value}
          disabled={o.disabled}
          className={cn(
            "group flex items-start gap-3 rounded-lg border border-border bg-bg p-4 text-left",
            "hover:border-border-input hover:bg-surface",
            "data-[state=checked]:border-accent data-[state=checked]:bg-accent-subtle data-[state=checked]:outline-1 data-[state=checked]:outline-accent",
            "disabled:cursor-not-allowed disabled:opacity-50",
          )}
        >
          <span
            aria-hidden="true"
            className="mt-0.5 inline-flex size-4 shrink-0 items-center justify-center rounded-full border border-border-input bg-bg group-data-[state=checked]:border-accent"
          >
            <R.Indicator className="size-2 rounded-full bg-accent" />
          </span>
          <span className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="text-sm font-semibold text-fg">{o.title}</span>
            {o.description && <span className="text-sm text-fg-muted">{o.description}</span>}
          </span>
          {o.icon && <span className="shrink-0 text-fg-muted [&_svg]:size-8">{o.icon}</span>}
        </R.Item>
      ))}
    </R.Root>
  );
}

/* ---------- Segmented control (scheduling mode: 수동 / 자동) ---------- */

export function Segmented({
  options,
  value,
  defaultValue,
  onValueChange,
  "aria-label": ariaLabel,
}: {
  options: { value: string; label: ReactNode; icon?: ReactNode; disabled?: boolean }[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (v: string) => void;
  "aria-label": string;
}) {
  return (
    <T.Root
      type="single"
      value={value}
      defaultValue={defaultValue}
      onValueChange={(v) => v && onValueChange?.(v)}
      aria-label={ariaLabel}
      className="inline-flex w-fit rounded-md border border-border bg-surface p-0.5"
    >
      {options.map((o) => (
        <T.Item
          key={o.value}
          value={o.value}
          disabled={o.disabled}
          className={cn(
            "inline-flex h-7 items-center gap-1.5 rounded-sm px-3 text-sm text-fg-muted [&_svg]:size-4",
            "hover:text-fg data-[state=on]:bg-bg data-[state=on]:font-medium data-[state=on]:text-fg data-[state=on]:shadow-card",
            "disabled:opacity-50",
          )}
        >
          {o.icon}
          {o.label}
        </T.Item>
      ))}
    </T.Root>
  );
}
