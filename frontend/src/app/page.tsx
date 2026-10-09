// Page-level building blocks shared by every screen: header, loading, error and not-found states.
import { useEffect, type ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { FileQuestion, Hammer, Lock, PlugZap, RefreshCw } from "lucide-react";
import { isApiError } from "@/api";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/feedback";
import { Breadcrumbs } from "@/components/ui/nav";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/cn";

/** Sets document.title as "<page> · openPMS" so tabs and history stay readable. */
export function useDocumentTitle(title: string | undefined) {
  useEffect(() => {
    if (title) document.title = `${title} · openPMS`;
  }, [title]);
}

export function PageHeader({
  title,
  breadcrumbs,
  actions,
  children,
  className,
}: {
  title: ReactNode;
  breadcrumbs?: { label: ReactNode; href?: string }[];
  actions?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("pt-4 pb-3", className)}>
      {breadcrumbs && <Breadcrumbs items={breadcrumbs} />}
      <div className="mt-1 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <h1 className="min-w-0 truncate text-xl text-fg">{title}</h1>
        {actions && <div className="flex flex-wrap items-center gap-toolbar">{actions}</div>}
      </div>
      {children}
    </div>
  );
}

export function FullPageSpinner() {
  const { t } = useTranslation();
  return (
    <div className="flex min-h-[50dvh] items-center justify-center text-fg-muted">
      <Spinner size={20} label={t("app.loading")} />
    </div>
  );
}

export function NotFound({ title, body }: { title?: string; body?: string }) {
  const { t } = useTranslation();
  useDocumentTitle(title ?? t("app.notFoundTitle"));
  return (
    <EmptyState
      icon={<FileQuestion />}
      title={title ?? t("app.notFoundTitle")}
      action={
        <Button asChild variant="secondary">
          <Link to="/">{t("app.goHome")}</Link>
        </Button>
      }
    >
      {body ?? t("app.notFoundBody")}
    </EmptyState>
  );
}

/** Turns any query error into the right state: 404 → not found, 403 → no permission, else retry. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { t } = useTranslation();
  if (isApiError(error) && error.status === 404) return <NotFound body={error.message} />;
  if (isApiError(error) && error.status === 403)
    return (
      <EmptyState icon={<Lock />} title={t("app.forbiddenTitle")}>
        {error.message}
      </EmptyState>
    );
  return (
    <EmptyState
      icon={<PlugZap />}
      title={t("app.errorTitle")}
      action={
        onRetry && (
          <Button icon={<RefreshCw />} onClick={onRetry}>
            {t("app.retry")}
          </Button>
        )
      }
    >
      {t("app.errorBody")}
    </EmptyState>
  );
}

/** Placeholder for screens that come later in the build order (replica/build-log.md lists them). */
export function StubPage({ id, name }: { id: string; name: string }) {
  const { t } = useTranslation();
  useDocumentTitle(name);
  return (
    <div className="px-content md:px-8">
      <PageHeader title={name} />
      <div className="rounded-lg border border-dashed border-border">
        <EmptyState icon={<Hammer />} title={t("app.stubTitle")}>
          {t("app.stubBody", { id, name })}
        </EmptyState>
      </div>
    </div>
  );
}

/** Standard page padding inside the app layout. */
export function PageBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("px-content pb-16 md:px-8", className)}>{children}</div>;
}
