import {
  cloneElement,
  forwardRef,
  isValidElement,
  useId,
  type InputHTMLAttributes,
  type ReactElement,
  type ReactNode,
  type TextareaHTMLAttributes,
} from "react";
import { cn } from "@/lib/cn";

const control = [
  "w-full rounded-md border border-border-input bg-bg text-sm text-fg placeholder:text-fg-muted",
  "transition-colors duration-(--pms-motion-fast)",
  "hover:border-fg-muted",
  "focus-visible:outline-2 focus-visible:outline-offset-0 focus-visible:outline-focus-ring focus-visible:border-focus-ring",
  "disabled:cursor-not-allowed disabled:bg-surface-sunken disabled:text-fg-disabled",
  "aria-invalid:border-danger aria-invalid:focus-visible:outline-danger",
  "read-only:bg-surface-sunken",
].join(" ");

export const Input = forwardRef<HTMLInputElement, Omit<InputHTMLAttributes<HTMLInputElement>, "size"> & { size?: "sm" | "md" }>(
  function Input({ className, size = "md", ...rest }, ref) {
    return (
      <input
        ref={ref}
        className={cn(control, size === "sm" ? "h-control-sm px-2 text-xs" : "h-control-md px-2.5", className)}
        {...rest}
      />
    );
  },
);

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  function Textarea({ className, rows = 4, ...rest }, ref) {
    return <textarea ref={ref} rows={rows} className={cn(control, "px-2.5 py-2 leading-5", className)} {...rest} />;
  },
);

interface FieldProps {
  label: ReactNode;
  /** One control element; Field wires id, aria-describedby and aria-invalid onto it. */
  children: ReactElement<Record<string, unknown>>;
  hint?: ReactNode;
  error?: ReactNode;
  required?: boolean;
  /** "stack": label above (forms). "inline": label left, value right (work package attribute rows). */
  layout?: "stack" | "inline";
  className?: string;
}

export function Field({ label, children, hint, error, required, layout = "stack", className }: FieldProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [errorId, hintId].filter(Boolean).join(" ") || undefined;
  const child = isValidElement(children)
    ? cloneElement(children, {
        id,
        "aria-describedby": describedBy,
        "aria-invalid": error ? true : undefined,
        "aria-required": required || undefined,
      })
    : children;

  return (
    <div className={cn(layout === "inline" ? "grid grid-cols-[140px_1fr] items-start gap-x-3" : "flex flex-col gap-1.5", className)}>
      <label htmlFor={id} className={cn("text-sm font-medium text-fg", layout === "inline" && "pt-1.5 text-fg-muted")}>
        {label}
        {required && (
          <span className="ml-0.5 text-danger" aria-hidden="true">
            *
          </span>
        )}
      </label>
      <div className="flex min-w-0 flex-col gap-1">
        {child}
        {error && (
          <p id={errorId} className="text-xs text-danger">
            {error}
          </p>
        )}
        {hint && (
          <p id={hintId} className="text-xs text-fg-muted">
            {hint}
          </p>
        )}
      </div>
    </div>
  );
}
