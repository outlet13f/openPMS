// "openPMS로 할 수 있는 일": the user manual's chapters and sections as a list of what the system does, each a link
// to the explanation. Shown on the administration home, where people ask "can it do …?".
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { BookOpen } from "lucide-react";
import { Skeleton } from "@/components/ui/spinner";
import { useManual } from "./manuals";

export function Capabilities() {
  const { t } = useTranslation();
  const m = useManual("user");
  if (m.isPending) return <Skeleton className="h-40 w-full" />;
  if (!m.data) return null;
  // the numbered chapters that describe features (the guide's own preface, tips, FAQ and appendix are left out)
  const skip = new Set(["1", "12", "13", "14"]);
  const chapters = m.data.sections.filter((s) => s.level === 2 && s.number && !skip.has(s.number));
  return (
    <section aria-labelledby="capabilities" className="mt-8">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="capabilities" className="text-base font-semibold text-fg">{t("help.capabilities")}</h2>
        <span className="flex gap-4 text-sm">
          <Link to="/help" className="inline-flex items-center gap-1 text-accent underline underline-offset-2"><BookOpen aria-hidden="true" className="size-4" />{t("help.userManual")}</Link>
          <Link to="/help/admin" className="inline-flex items-center gap-1 text-accent underline underline-offset-2"><BookOpen aria-hidden="true" className="size-4" />{t("help.adminManual")}</Link>
        </span>
      </div>
      <p className="mb-4 max-w-prose text-sm text-fg-muted">{t("help.capabilitiesHint")}</p>
      <ul className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2 xl:grid-cols-3">
        {chapters.map((c) => {
          const children = m.data.sections.filter((s) => s.level === 3 && s.start > c.start && s.start < c.end);
          return (
            <li key={c.id}>
              <Link to={`/help#${encodeURIComponent(c.id)}`} className="text-sm font-semibold text-fg hover:underline">{c.title.replace(/^\d+\.\s*/, "")}</Link>
              <ul className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-sm">
                {children.map((s) => (
                  <li key={s.id}>
                    <Link to={`/help#${encodeURIComponent(s.id)}`} className="text-fg-muted hover:text-fg hover:underline">{s.title.replace(/^[\d.]+\s*/, "")}</Link>
                  </li>
                ))}
              </ul>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
