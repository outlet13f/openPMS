import { forwardRef, type ReactNode } from "react";
import { Select as S } from "radix-ui";
import { Check, ChevronDown } from "lucide-react";
import { cn } from "@/lib/cn";
import { useTranslation } from "react-i18next";

export interface SelectOption {
  value: string;
  label: ReactNode;
  /** Text for type-ahead when the label is not plain text (e.g. avatar + name). */
  textValue?: string;
  disabled?: boolean;
}

interface SelectProps {
  options: SelectOption[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  size?: "sm" | "md";
  className?: string;
  id?: string;
  "aria-label"?: string;
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
}

export const Select = forwardRef<HTMLButtonElement, SelectProps>(function Select(
  { options, value, defaultValue, onValueChange, placeholder, disabled, size = "md", className, ...aria },
  ref,
) {
  const { t } = useTranslation();
  return (
    <S.Root value={value} defaultValue={defaultValue} onValueChange={onValueChange} disabled={disabled}>
      <S.Trigger
        ref={ref}
        {...aria}
        className={cn(
          "inline-flex w-full items-center justify-between gap-2 rounded-md border border-border-input bg-bg text-left text-sm text-fg",
          "hover:border-fg-muted data-placeholder:text-fg-muted",
          "focus-visible:outline-2 focus-visible:outline-offset-0 focus-visible:outline-focus-ring",
          "disabled:cursor-not-allowed disabled:bg-surface-sunken disabled:text-fg-disabled",
          "aria-invalid:border-danger",
          size === "sm" ? "h-control-sm px-2 text-xs" : "h-control-md px-2.5",
          className,
        )}
      >
        <S.Value placeholder={placeholder ?? t("app.select")} />
        <S.Icon>
          <ChevronDown className="size-4 text-fg-muted" aria-hidden="true" />
        </S.Icon>
      </S.Trigger>
      <S.Portal>
        <S.Content
          position="popper"
          sideOffset={4}
          className="z-(--pms-z-dropdown) max-h-72 min-w-(--radix-select-trigger-width) overflow-hidden rounded-lg border border-border bg-bg p-1 shadow-pop"
        >
          <S.Viewport>
            {options.map((o) => (
              <S.Item
                key={o.value}
                value={o.value}
                disabled={o.disabled}
                textValue={o.textValue}
                className={cn(
                  "relative flex h-8 cursor-default items-center rounded-sm pr-2 pl-7 text-sm text-fg outline-none select-none",
                  "data-highlighted:bg-surface-hover data-disabled:text-fg-disabled",
                )}
              >
                <S.ItemIndicator className="absolute left-2 inline-flex">
                  <Check className="size-4 text-accent" aria-hidden="true" />
                </S.ItemIndicator>
                <S.ItemText>{o.label}</S.ItemText>
              </S.Item>
            ))}
          </S.Viewport>
        </S.Content>
      </S.Portal>
    </S.Root>
  );
});
