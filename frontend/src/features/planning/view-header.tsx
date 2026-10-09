// Header parts shared by the calendar (S17) and team planner (S18): period navigation and the ⋯ view menu.
import { useState } from "react";
import { useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { ChevronLeft, ChevronRight, MoreHorizontal, Pencil, Trash2 } from "lucide-react";
import { isApiError, type SavedQuery } from "@/api";
import { useSavedQueryMutations } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { useToast } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";

export function PeriodNav({ label, onPrev, onToday, onNext }: { label: string; onPrev: () => void; onToday: () => void; onNext: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1">
      <Button iconOnly aria-label={t("planning.prev")} icon={<ChevronLeft />} onClick={onPrev} />
      <Button onClick={onToday}>{t("planning.today")}</Button>
      <Button iconOnly aria-label={t("planning.next")} icon={<ChevronRight />} onClick={onNext} />
      {/* announced when the period changes, so screen reader users hear where they are */}
      <h2 className="ml-2 text-base font-semibold whitespace-nowrap text-fg tabular-nums" aria-live="polite">
        {label}
      </h2>
    </div>
  );
}

export function PlanningViewMenu({ view, projectId, listHref }: { view: SavedQuery; projectId: string; listHref: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const toast = useToast();
  const { update, remove } = useSavedQueryMutations(projectId);
  const [renaming, setRenaming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [name, setName] = useState(view.name);
  const err = isApiError(update.error) ? update.error.fieldErrors[0]?.message ?? update.error.message : undefined;
  if (!view.canEdit) return null;

  return (
    <>
      <Menu
        align="end"
        trigger={<Button iconOnly aria-label={t("planning.menu")} icon={<MoreHorizontal />} />}
        items={[
          { label: t("planning.rename"), icon: <Pencil />, onSelect: () => (setName(view.name), setRenaming(true)) },
          { label: t("planning.delete"), icon: <Trash2 />, danger: true, onSelect: () => setDeleting(true) },
        ]}
      />
      <Dialog
        open={renaming}
        onOpenChange={setRenaming}
        size="sm"
        title={t("planning.rename")}
        footer={
          <>
            <Button onClick={() => setRenaming(false)}>{t("app.cancel")}</Button>
            <Button
              variant="primary"
              loading={update.isPending}
              onClick={async () => {
                const q = await update.mutateAsync({ id: view.id, patch: { name } }).catch(() => null);
                if (q) {
                  toast({ tone: "success", title: t("planning.renamed") });
                  setRenaming(false);
                }
              }}
            >
              {t("app.save")}
            </Button>
          </>
        }
      >
        <Field label={t("planning.name")} required error={err}>
          <Input data-autofocus value={name} maxLength={255} onChange={(e) => setName(e.target.value)} />
        </Field>
      </Dialog>
      <Dialog
        open={deleting}
        onOpenChange={setDeleting}
        size="sm"
        title={t("planning.deleteTitle", { name: view.name })}
        description={t("planning.deleteBody")}
        footer={
          <>
            <Button data-autofocus onClick={() => setDeleting(false)}>
              {t("app.cancel")}
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() =>
                remove.mutate(view.id, {
                  onSuccess: () => {
                    toast({ tone: "success", title: t("planning.deleted") });
                    navigate(listHref);
                  },
                })
              }
            >
              {t("app.delete")}
            </Button>
          </>
        }
      >
        <span />
      </Dialog>
    </>
  );
}
