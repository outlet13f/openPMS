// Shared bits of the CI/CD screens (the 개발 tab, the release board and the release records): a link to the code host or
// deployment tool in a new window, and the short Asia/Seoul times the rows show.
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink } from "lucide-react";
import { DEFAULT_TZ, formatTime } from "@/lib/dates";

/** A link to the tool, in a new window, saying so to screen readers. */
export function External({ href, children, className }: { href: string; children: ReactNode; className?: string }) {
  const { t } = useTranslation();
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" className={className ?? "text-accent hover:underline"}>
      {children}
      <ExternalLink className="ml-1 inline size-3 align-[-1px]" aria-hidden="true" />
      <span className="sr-only"> ({t("cicd.opensInNew")})</span>
    </a>
  );
}

/** "10-17" (Asia/Seoul) */
export function monthDay(at: string) {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: DEFAULT_TZ, month: "2-digit", day: "2-digit" }).formatToParts(new Date(at));
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("month")}-${part("day")}`;
}

/** "10-14 15:20" (Asia/Seoul) */
export const shortWhen = (at: string) => `${monthDay(at)} ${formatTime(at)}`;
