// 키보드 단축키 (계정 설정 · 화면 "키보드 단축키"로 끌 수 있음): ? help, / or s search, n new work package, and g + a letter
// to go somewhere. Nothing fires while typing in a field, with Ctrl/Alt/⌘ held, or when a dialog or menu is open.
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import type { Me, Project } from "@/api";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/overlay";
import { can } from "./project";

type Go = { key: string; label: string; to: string };

function typing(target: EventTarget | null) {
  const el = target as HTMLElement | null;
  if (!el) return false;
  return el.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName) || !!el.closest("[contenteditable=true],[role=dialog],[role=menu]");
}

export function useShortcuts(me: Me, project: Project | null) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [help, setHelp] = useState(false);
  const pendingG = useRef<number>(0);
  const enabled = me.preferences.keyboardShortcuts;

  const base = project ? `/projects/${project.identifier}` : "";
  const goes: Go[] = [
    { key: "h", label: t("shortcuts.goHome"), to: "/" },
    { key: "m", label: t("shortcuts.goMyPage"), to: "/my/page" },
    { key: "p", label: t("shortcuts.goProjects"), to: "/projects" },
    { key: "w", label: t("shortcuts.goWorkPackages"), to: `${base}/work-packages` },
    { key: "g", label: t("shortcuts.goGantt"), to: `${base}/gantt` },
    ...(project ? [{ key: "b", label: t("shortcuts.goBoards"), to: `${base}/boards` }, { key: "o", label: t("shortcuts.goOverview"), to: base }] : []),
    { key: "n", label: t("shortcuts.goNotifications"), to: "/notifications" },
    ...(me.isAdmin ? [{ key: "a", label: t("shortcuts.goAdmin"), to: "/admin" }] : []),
  ];
  const canCreate = !!project && can(project, "wp.create");

  useEffect(() => {
    if (!enabled) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || e.isComposing || typing(e.target)) return;
      const now = Date.now();
      if (pendingG.current && now - pendingG.current < 1500) {
        pendingG.current = 0;
        const go = goes.find((g) => g.key === e.key.toLowerCase());
        if (go) {
          e.preventDefault();
          navigate(go.to);
        }
        return;
      }
      if (e.key === "?") {
        e.preventDefault();
        setHelp(true);
      } else if (e.key === "/" || e.key === "s") {
        const box = document.querySelector<HTMLInputElement>("[data-global-search] input, input[data-global-search]");
        if (box) {
          e.preventDefault();
          box.focus();
        }
      } else if (e.key === "n" && canCreate) {
        e.preventDefault();
        navigate(`${base}/work-packages/new`);
      } else if (e.key === "g") {
        pendingG.current = now;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const rows: [string, string][] = [
    ["?", t("shortcuts.help")],
    ["/  s", t("shortcuts.search")],
    ...(canCreate ? ([["n", t("shortcuts.newWorkPackage")]] as [string, string][]) : []),
    ...goes.map((g) => [`g ${g.key}`, g.label] as [string, string]),
  ];
  const dialog = (
    <Dialog open={help} onOpenChange={setHelp} size="sm" title={t("shortcuts.title")} description={enabled ? t("shortcuts.hint") : t("shortcuts.off")} footer={<Button onClick={() => setHelp(false)}>{t("app.close")}</Button>}>
      <table className="w-full text-sm">
        <tbody>
          {rows.map(([keys, label]) => (
            <tr key={keys} className="border-b border-border-muted last:border-b-0">
              <th scope="row" className="py-1.5 pr-4 text-left font-normal">
                {keys.split("  ").map((k) => (
                  <kbd key={k} className="mr-1 rounded-sm border border-border bg-surface px-1.5 py-0.5 font-mono text-xs text-fg">
                    {k}
                  </kbd>
                ))}
              </th>
              <td className="py-1.5 text-fg-muted">{label}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Dialog>
  );
  return { dialog, openHelp: () => setHelp(true) };
}
