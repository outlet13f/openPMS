// Click-to-edit text (subject, numbers). Enter or blur saves, Esc cancels; while saving the field is disabled.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Pencil } from "lucide-react";
import { cn } from "@/lib/cn";

export function InlineText({
  value,
  onSave,
  disabled,
  label,
  required,
  type = "text",
  className,
  inputClassName,
  display,
  maxLength = 255,
}: {
  value: string;
  onSave: (v: string) => Promise<boolean>;
  disabled?: boolean;
  label: string;
  required?: boolean;
  type?: "text" | "number" | "date" | "url";
  className?: string;
  inputClassName?: string;
  display?: ReactNode;
  maxLength?: number;
}) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!editing) setDraft(value);
  }, [value, editing]);
  useEffect(() => {
    if (editing) input.current?.select();
  }, [editing]);

  const commit = async () => {
    if (busy) return;
    const next = draft.trim();
    if (required && !next) {
      setError(t("wp.subjectRequired"));
      input.current?.focus();
      return;
    }
    if (next === value) {
      setEditing(false);
      return;
    }
    setBusy(true);
    const ok = await onSave(next);
    setBusy(false);
    if (ok) {
      setEditing(false);
      setError(null);
      requestAnimationFrame(() => button.current?.focus());
    }
  };

  if (disabled) return <span className={className}>{display ?? value}</span>;
  if (!editing)
    return (
      <button
        ref={button}
        type="button"
        onClick={() => setEditing(true)}
        aria-label={`${label}: ${value || t("app.none")}. ${t("app.edit")}`}
        className={cn("group -mx-1.5 inline-flex max-w-full items-start gap-1.5 rounded-sm px-1.5 text-left hover:bg-surface-hover", className)}
      >
        <span className="min-w-0 break-words">{display ?? (value || <span className="text-fg-muted">-</span>)}</span>
        <Pencil className="mt-1 size-3.5 shrink-0 text-fg-muted opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100" aria-hidden="true" />
      </button>
    );
  return (
    <span className="flex w-full flex-col gap-1">
      <input
        ref={input}
        type={type}
        aria-label={label}
        aria-invalid={!!error}
        value={draft}
        maxLength={maxLength}
        disabled={busy}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={() => void commit()}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            void commit();
          } else if (e.key === "Escape") {
            e.preventDefault();
            setDraft(value);
            setError(null);
            setEditing(false);
            requestAnimationFrame(() => button.current?.focus());
          }
        }}
        className={cn(
          "w-full rounded-md border border-focus-ring bg-bg px-2 py-1 text-fg outline-2 outline-focus-ring aria-invalid:border-danger aria-invalid:outline-danger",
          inputClassName,
        )}
      />
      {error && <span className="text-xs text-danger">{error}</span>}
    </span>
  );
}
