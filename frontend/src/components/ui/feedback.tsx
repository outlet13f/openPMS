import { createContext, useCallback, useContext, useState, type ReactNode } from "react";
import { Toast as T } from "radix-ui";
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from "lucide-react";
import { cn } from "@/lib/cn";
import { Button } from "./button";

/* ---------- Banner: inline message inside a page or dialog ---------- */

const bannerTone = {
  info: { box: "bg-info-subtle", icon: <Info className="text-accent" /> },
  success: { box: "bg-success-subtle", icon: <CheckCircle2 className="text-success" /> },
  warning: { box: "bg-warning-subtle", icon: <AlertTriangle className="text-warning" /> },
  danger: { box: "bg-danger-subtle", icon: <XCircle className="text-danger" /> },
};

export function Banner({
  tone = "info",
  title,
  children,
  action,
  onDismiss,
  flush,
}: {
  tone?: keyof typeof bannerTone;
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  onDismiss?: () => void;
  /** Flush banners sit edge to edge at the top of a dialog or panel (no radius). */
  flush?: boolean;
}) {
  const t = bannerTone[tone];
  return (
    <div
      role={tone === "danger" || tone === "warning" ? "alert" : "status"}
      className={cn("flex items-start gap-3 px-4 py-3 text-sm text-fg [&>svg]:mt-0.5 [&>svg]:size-4 [&>svg]:shrink-0", t.box, !flush && "rounded-md")}
    >
      {t.icon}
      <div className="min-w-0 flex-1">
        {title && <p className="font-semibold">{title}</p>}
        {children && <div className={cn(title && "mt-0.5")}>{children}</div>}
      </div>
      {action}
      {onDismiss && <Button variant="ghost" size="sm" iconOnly aria-label="알림 닫기" icon={<X />} onClick={onDismiss} />}
    </div>
  );
}

/* ---------- Empty state ---------- */

export function EmptyState({ icon, title, children, action, compact }: { icon?: ReactNode; title: ReactNode; children?: ReactNode; action?: ReactNode; compact?: boolean }) {
  return (
    <div className={cn("flex flex-col items-center justify-center text-center", compact ? "gap-1.5 py-6" : "gap-2 py-12")}>
      {icon && <div className="mb-1 text-fg-muted [&_svg]:size-8" aria-hidden="true">{icon}</div>}
      <p className={cn("font-semibold text-fg", compact ? "text-sm" : "text-lg")}>{title}</p>
      {children && <div className="max-w-sm text-sm text-fg-muted">{children}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

/* ---------- Toast: transient confirmation, announced politely ---------- */

type ToastTone = "success" | "danger" | "info";
interface ToastMsg { id: number; tone: ToastTone; title: ReactNode; description?: ReactNode; action?: { label: string; onClick: () => void } }

const ToastCtx = createContext<(t: Omit<ToastMsg, "id">) => void>(() => {});
export const useToast = () => useContext(ToastCtx);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastMsg[]>([]);
  const push = useCallback((t: Omit<ToastMsg, "id">) => setItems((xs) => [...xs, { ...t, id: Date.now() + Math.random() }]), []);
  return (
    <ToastCtx.Provider value={push}>
      <T.Provider swipeDirection="right" duration={5000} label="알림">
        {children}
        {items.map((t) => (
          <T.Root
            key={t.id}
            type={t.tone === "danger" ? "foreground" : "background"}
            onOpenChange={(open) => !open && setItems((xs) => xs.filter((x) => x.id !== t.id))}
            className="flex w-80 items-start gap-3 rounded-lg border border-border bg-bg p-3 shadow-pop [&>svg]:mt-0.5 [&>svg]:size-4 [&>svg]:shrink-0"
          >
            {t.tone === "success" ? <CheckCircle2 className="text-success" /> : t.tone === "danger" ? <XCircle className="text-danger" /> : <Info className="text-accent" />}
            <div className="min-w-0 flex-1">
              <T.Title className="text-sm font-semibold text-fg">{t.title}</T.Title>
              {t.description && <T.Description className="mt-0.5 text-sm text-fg-muted">{t.description}</T.Description>}
            </div>
            {t.action && (
              <T.Action altText={t.action.label} asChild>
                <Button variant="link" size="sm" onClick={t.action.onClick}>{t.action.label}</Button>
              </T.Action>
            )}
            <T.Close asChild>
              <Button variant="ghost" size="sm" iconOnly aria-label="닫기" icon={<X />} />
            </T.Close>
          </T.Root>
        ))}
        <T.Viewport className="fixed right-4 bottom-4 z-(--pms-z-toast) flex flex-col gap-2 outline-none" />
      </T.Provider>
    </ToastCtx.Provider>
  );
}
