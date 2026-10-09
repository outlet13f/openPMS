// Editable status pill: opens a menu with only the transitions the workflow allows for this user (§3.2).
import { useState } from "react";
import { DropdownMenu as M } from "radix-ui";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Check } from "lucide-react";
import { api, type WorkPackage } from "@/api";
import { qk, useAllowedStatuses } from "@/api/hooks";
import { StatusPill } from "@/components/ui/badges";
import { Spinner } from "@/components/ui/spinner";
import { useSaveWorkPackage } from "./use-save";

export function StatusMenu({ wp, size = "md" }: { wp: WorkPackage; size?: "sm" | "md" }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const editable = wp._meta.editableFields.includes("status");
  const allowed = useAllowedStatuses(wp.number, open);
  const { save, isPending } = useSaveWorkPackage();
  const qc = useQueryClient();
  // Load the allowed transitions as soon as the pill is focused or hovered, so arrow keys work the
  // moment the menu opens.
  const prefetch = () => void qc.prefetchQuery({ queryKey: qk.wpStatuses(wp.number), queryFn: () => api.workPackages.allowedStatuses(wp.number), staleTime: 10_000 });

  if (!editable) return <StatusPill size={size} name={wp.status.name} color={wp.status.color} />;

  return (
    <M.Root open={open} onOpenChange={setOpen} modal={false}>
      <M.Trigger asChild>
        <StatusPill size={size} editable name={isPending ? `${wp.status.name}…` : wp.status.name} color={wp.status.color} onFocus={prefetch} onPointerEnter={prefetch} />
      </M.Trigger>
      <M.Portal>
        <M.Content align="start" sideOffset={4} className="z-(--pms-z-dropdown) min-w-44 rounded-lg border border-border bg-bg p-1 shadow-pop">
          <M.Label className="px-2 pt-1.5 pb-1 text-xs font-semibold text-fg-muted">{t("wp.changeStatus")}</M.Label>
          <M.Item disabled className="flex h-8 items-center gap-2 rounded-sm px-2 text-sm text-fg">
            <Check className="size-4 text-accent" aria-hidden="true" />
            <span className="size-2.5 rounded-full" style={{ backgroundColor: wp.status.color }} aria-hidden="true" />
            {wp.status.name}
          </M.Item>
          {allowed.isPending && (
            <div className="flex h-8 items-center px-2 text-fg-muted">
              <Spinner size={14} label={t("app.loading")} />
            </div>
          )}
          {allowed.data?.length === 0 && <p className="max-w-56 px-2 py-1.5 text-xs text-fg-muted">{t("wp.noTransitions")}</p>}
          {allowed.data?.map((s) => (
            <M.Item
              key={s.id}
              onSelect={() => void save(wp, { statusId: s.id })}
              className="flex h-8 cursor-default items-center gap-2 rounded-sm px-2 pl-8 text-sm text-fg outline-none data-highlighted:bg-surface-hover"
            >
              <span className="size-2.5 rounded-full" style={{ backgroundColor: s.color }} aria-hidden="true" />
              {s.name}
            </M.Item>
          ))}
        </M.Content>
      </M.Portal>
    </M.Root>
  );
}
