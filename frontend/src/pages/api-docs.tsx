// API 문서 (/help/api): how integrations reach openPMS — tokens, scopes, versions, limits, errors — and the main routes,
// read from the OpenAPI description the server publishes (/api/v1/openapi.json).
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badges";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";

type OpenApi = { info: { title: string; version: string; description: string }; paths: Record<string, Record<string, { summary?: string }>> };

export function ApiDocsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("apiDocs.title"));
  const doc = useQuery({
    queryKey: ["openapi"],
    queryFn: async () => {
      const r = await fetch("/api/v1/openapi.json");
      if (!r.ok) throw new Error(`${r.status}`);
      return (await r.json()) as OpenApi;
    },
    staleTime: Infinity,
  });
  const h2 = "mb-2 text-base font-semibold text-fg";
  return (
    <PageBody>
      <PageHeader title={t("apiDocs.title")} />
      <div className="flex max-w-4xl flex-col gap-8 text-sm text-fg">
        <section aria-labelledby="api-auth">
          <h2 id="api-auth" className={h2}>{t("apiDocs.authTitle")}</h2>
          <p>{t("apiDocs.auth")}</p>
          <pre className="mt-2 overflow-x-auto rounded-md border border-border bg-surface p-3 font-mono text-xs">{`curl -H "Authorization: Bearer opms_…" \\\n     ${window.location.origin}/api/v1/work-packages/3`}</pre>
          <p className="mt-2">
            <Link to="/my/account" className="text-accent hover:underline">{t("apiDocs.makeToken")}</Link>
          </p>
        </section>
        <section aria-labelledby="api-rules">
          <h2 id="api-rules" className={h2}>{t("apiDocs.rulesTitle")}</h2>
          <ul className="list-disc pl-5 leading-relaxed">
            <li>{t("apiDocs.scopes")}</li>
            <li>{t("apiDocs.closed")}</li>
            <li>{t("apiDocs.limit")}</li>
            <li>{t("apiDocs.versions")}</li>
            <li>{t("apiDocs.errors")}</li>
            <li>{t("apiDocs.locking")}</li>
          </ul>
        </section>
        <section aria-labelledby="api-paths">
          <h2 id="api-paths" className={h2}>{t("apiDocs.pathsTitle")}</h2>
          <p className="mb-3 text-fg-muted">
            {t("apiDocs.pathsHint")}{" "}
            <a href="/api/v1/openapi.json" className="text-accent underline">openapi.json</a>
          </p>
          {doc.isPending ? (
            <Skeleton className="h-40 w-full" />
          ) : doc.isError ? (
            <ErrorState error={doc.error} onRetry={() => doc.refetch()} />
          ) : (
            <table className="w-full border-collapse">
              <thead>
                <tr className="border-b border-border text-left text-xs text-fg-muted">
                  <th scope="col" className="py-2 pr-3">{t("apiDocs.method")}</th>
                  <th scope="col" className="py-2 pr-3">{t("apiDocs.path")}</th>
                  <th scope="col" className="py-2">{t("apiDocs.summary")}</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(doc.data.paths).flatMap(([path, ops]) =>
                  Object.entries(ops).map(([method, op]) => (
                    <tr key={`${method} ${path}`} className="border-b border-border-muted">
                      <td className="py-1.5 pr-3"><Badge tone={method === "get" ? "neutral" : "accent"}>{method.toUpperCase()}</Badge></td>
                      <td className="py-1.5 pr-3 font-mono text-xs">/api/v1{path}</td>
                      <td className="py-1.5">{op.summary}</td>
                    </tr>
                  )),
                )}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </PageBody>
  );
}
