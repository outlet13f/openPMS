import { createContext, useContext, type ReactNode } from "react";
import { Outlet, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { PowerOff } from "lucide-react";
import type { ModuleKey, Project } from "@/api";
import { useProject } from "@/api/hooks";
import { EmptyState } from "@/components/ui/feedback";
import { ErrorState, FullPageSpinner } from "./page";

const ProjectCtx = createContext<Project | null>(null);

/** The project of the current /projects/:p/* route. Only valid below ProjectLayout. */
export function useCurrentProject(): Project {
  const p = useContext(ProjectCtx);
  if (!p) throw new Error("useCurrentProject outside ProjectLayout");
  return p;
}

export function useOptionalProject(): Project | null {
  return useContext(ProjectCtx);
}

export function ProjectLayout() {
  const { p } = useParams();
  const project = useProject(p);
  if (project.isPending) return <FullPageSpinner />;
  if (project.isError) return <ErrorState error={project.error} onRetry={() => project.refetch()} />;
  return (
    <ProjectCtx.Provider value={project.data}>
      <Outlet />
    </ProjectCtx.Provider>
  );
}

export const can = (project: Project, permission: string) => project.myPermissions.includes(permission);

/** A module the project has switched off shows a clear message instead of the original's bare 403. */
export function RequireModule({ module, children }: { module: ModuleKey; children: ReactNode }) {
  const project = useCurrentProject();
  const { t } = useTranslation();
  if (!project.enabledModules.includes(module))
    return (
      <EmptyState icon={<PowerOff />} title={t("app.moduleOff")}>
        {t("app.moduleOffBody")}
      </EmptyState>
    );
  return <>{children}</>;
}
