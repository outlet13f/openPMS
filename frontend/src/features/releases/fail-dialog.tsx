// "실패…" on a card of the release board: the failure reason (required, at most 2,000 characters) and a note that the
// work package's assignees are told and its status stays. A 422 on the reason shows under the field; any other error (the
// card left the board meanwhile, …) is a toast and the dialog stays open.
import { useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { isApiError, type ReleaseCard } from "@/api";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/feedback";
import { Field, Textarea } from "@/components/ui/field";
import { Dialog } from "@/components/ui/overlay";
import { fieldErrorOf } from "@/features/admin/common";

const MAX_REASON = 2000;

export function FailDialog({
  card,
  pending,
  onSubmit,
  onClose,
}: {
  card: ReleaseCard;
  pending: boolean;
  /** resolves when saved (the caller closes the dialog and says so); rejects with the API error */
  onSubmit: (reason: string) => Promise<unknown>;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const toast = useToast();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | undefined>();
  const field = useRef<HTMLTextAreaElement>(null);
  const formId = useId();
  const remainingId = useId();

  const submit = async () => {
    const trimmed = reason.trim();
    if (!trimmed) {
      setError(t("releases.fail.reasonRequired"));
      field.current?.focus();
      return;
    }
    setError(undefined);
    try {
      await onSubmit(trimmed);
    } catch (e) {
      const message = fieldErrorOf(e, "reason");
      if (message) {
        setError(message);
        field.current?.focus();
      } else toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle"), description: isApiError(e) && e.message !== e.title ? e.message : undefined });
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="md"
      title={t("releases.fail.title", { number: card.number })}
      description={t("releases.fail.note")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button type="submit" form={formId} variant="danger" loading={pending}>
            {t("releases.fail.submit")}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        noValidate
        className="flex flex-col gap-1.5"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Field label={t("releases.fail.reason")} required error={error}>
          <Textarea
            ref={field}
            data-autofocus
            rows={5}
            maxLength={MAX_REASON}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              if (error) setError(undefined);
            }}
          />
        </Field>
        <p id={remainingId} aria-live="polite" className="text-right text-xs text-fg-muted tabular-nums">
          {t("releases.fail.remaining", { count: MAX_REASON - reason.length })}
        </p>
      </form>
    </Dialog>
  );
}
