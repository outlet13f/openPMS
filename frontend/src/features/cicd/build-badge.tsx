// The build badge next to a merge request or commit on the 개발 tab: success, failure, running or canceled, always
// in words as well as an icon and a tone (never colour alone). Several workflows of one commit are summed up by the
// server (any failure shows) and counted here. Clicking opens the build page on the tool, in a new window.
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { CircleCheck, CircleSlash, CircleX, ExternalLink, LoaderCircle } from "lucide-react";
import type { CicdBuild, CicdBuildStatus } from "@/api";
import { Badge } from "@/components/ui/badges";
import { formatDateTime } from "@/lib/dates";

const LOOK: Record<CicdBuildStatus, { tone: "success" | "danger" | "accent" | "neutral"; icon: ReactNode }> = {
  success: { tone: "success", icon: <CircleCheck aria-hidden="true" /> },
  failed: { tone: "danger", icon: <CircleX aria-hidden="true" /> },
  running: { tone: "accent", icon: <LoaderCircle aria-hidden="true" /> },
  canceled: { tone: "neutral", icon: <CircleSlash aria-hidden="true" /> },
};

export function BuildBadge({ build, className }: { build: CicdBuild; className?: string }) {
  const { t } = useTranslation();
  const look = LOOK[build.status];
  return (
    <a href={build.webUrl} target="_blank" rel="noopener noreferrer" title={formatDateTime(build.at)} className={`inline-flex rounded-sm hover:underline ${className ?? ""}`}>
      <Badge tone={look.tone} icon={look.icon}>
        {t(`cicd.build.statuses.${build.status}`)}
        {build.count > 1 && <span className="font-normal">{t("cicd.build.workflows", { count: build.count })}</span>}
        <ExternalLink aria-hidden="true" />
        <span className="sr-only"> ({t("cicd.opensInNew")})</span>
      </Badge>
    </a>
  );
}
