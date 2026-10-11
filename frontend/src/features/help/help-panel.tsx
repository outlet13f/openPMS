// 이 화면 도움말: the manual's section for the current screen in a panel on the right, so whatever is open (a form,
// a filter) stays as it is. Links to other sections switch the panel; 전체 매뉴얼에서 보기 opens the help page there.
import { useEffect, useRef, useState } from "react";
import * as D from "@radix-ui/react-dialog";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { BookOpen, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/spinner";
import { MarkdownView, type LinkHandler } from "./markdown-view";
import { MANUAL_PATH, manualOfFile, useManual } from "./manuals";
import { sectionByNumber } from "./markdown";
import type { Topic } from "./topics";

export function HelpPanel({ topic, onClose }: { topic: Topic | null; onClose: () => void }) {
  const { t } = useTranslation();
  const [current, setCurrent] = useState<{ manual: Topic["manual"]; id: string | null; number: string | null } | null>(null);
  useEffect(() => {
    setCurrent(topic && { manual: topic.manual, id: null, number: topic.section });
  }, [topic]);
  const m = useManual(current?.manual ?? "user");
  const body = useRef<HTMLDivElement>(null);
  const section = m.data && current ? (current.id ? m.data.sections.find((s) => s.id === current.id) : sectionByNumber(m.data, current.number ?? "")) : undefined;
  useEffect(() => {
    body.current?.scrollTo({ top: 0 }); // (scrollTo returns a promise in newer browsers: not the effect's clean-up)
  }, [section?.id]);
  const path = MANUAL_PATH[current?.manual ?? "user"];
  const links: LinkHandler = {
    anchorHref: (id) => `${path}#${encodeURIComponent(id)}`,
    onAnchor: (id) => current && setCurrent({ ...current, id, number: null }),
    docHref: (file, id) => {
      const other = manualOfFile(file);
      return other ? `${MANUAL_PATH[other]}${id ? `#${encodeURIComponent(id)}` : ""}` : null;
    },
  };
  return (
    <D.Root open={!!topic} onOpenChange={(o) => !o && onClose()}>
      <D.Portal>
        <D.Overlay className="fixed inset-0 z-(--pms-z-modal) bg-backdrop" />
        <D.Content className="fixed inset-y-0 right-0 z-(--pms-z-modal) flex w-full max-w-xl flex-col bg-bg shadow-modal focus-visible:outline-none">
          <div className="flex items-start justify-between gap-3 border-b border-border-muted px-5 pt-4 pb-3">
            <div className="min-w-0">
              <p className="flex items-center gap-1.5 text-xs text-fg-muted">
                <BookOpen aria-hidden="true" className="size-3.5" />
                {current?.manual === "admin" ? t("help.adminManual") : t("help.userManual")}
              </p>
              <D.Title className="mt-1 text-lg font-semibold text-fg">{section?.title ?? t("help.thisScreen")}</D.Title>
              <D.Description className="sr-only">{t("help.panelDescription")}</D.Description>
            </div>
            <D.Close asChild>
              <Button variant="ghost" size="sm" iconOnly aria-label={t("app.close")} icon={<X />} />
            </D.Close>
          </div>
          <div ref={body} className="min-h-0 flex-1 overflow-y-auto px-5 pb-6">
            {m.isPending ? (
              <Skeleton className="mt-4 h-64 w-full" />
            ) : !section || !m.data ? (
              <p className="mt-4 text-sm text-fg-muted">{t("help.notFound")}</p>
            ) : (
              <MarkdownView blocks={m.data.blocks.slice(section.start + 1, section.end)} links={links} headingOffset={0} />
            )}
          </div>
          <div className="flex justify-end gap-2 border-t border-border-muted px-5 py-3">
            <Button asChild variant="secondary" size="sm">
              <Link to={section ? `${path}#${encodeURIComponent(section.id)}` : path} onClick={onClose}>
                {t("help.openFull")}
              </Link>
            </Button>
          </div>
        </D.Content>
      </D.Portal>
    </D.Root>
  );
}
