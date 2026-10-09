import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { isApiError, type WorkPackage, type WorkPackagePatch } from "@/api";
import { invalidateWorkPackages, qk, useUpdateWorkPackage } from "@/api/hooks";
import { useToast } from "@/components/ui/feedback";

/**
 * Saves one change to a work package with its lockVersion. Conflicts (409) replace the cached item with
 * the server's current version and tell the user; rule violations (422) show the server's reason.
 * Resolves to the saved item, or null when the save was rejected.
 */
export function useSaveWorkPackage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const update = useUpdateWorkPackage();

  const save = useCallback(
    async (wp: Pick<WorkPackage, "number" | "lockVersion">, patch: Omit<WorkPackagePatch, "lockVersion">): Promise<WorkPackage | null> => {
      try {
        return await update.mutateAsync({ number: wp.number, patch: { ...patch, lockVersion: wp.lockVersion } });
      } catch (err) {
        if (isApiError(err) && err.isConflict) {
          if (err.current) qc.setQueryData(qk.wp(wp.number), err.current);
          invalidateWorkPackages(qc, wp.number);
          toast({ tone: "danger", title: t("app.conflict"), description: t("app.conflictBody") });
        } else if (isApiError(err)) {
          toast({ tone: "danger", title: err.title, description: err.message !== err.title ? err.message : undefined });
        } else {
          toast({ tone: "danger", title: t("app.errorTitle"), description: t("app.errorBody") });
        }
        return null;
      }
    },
    [update, qc, toast, t],
  );

  return { save, isPending: update.isPending };
}
