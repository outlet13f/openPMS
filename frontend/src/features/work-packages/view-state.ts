// The list's view lives in the URL (features.csv "보기 상태를 URL로 직렬화"):
//   ?view=<saved query id>   which saved/default view is the starting point
//   ?q=<base64url spec>      unsaved changes on top of it (filters, sort, columns…)
//   ?page=&size=             pagination
import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router";
import type { QuerySpec, SavedQuery } from "@/api";
import { useSavedQueries } from "@/api/hooks";

export function encodeSpec(spec: QuerySpec): string {
  const bytes = new TextEncoder().encode(JSON.stringify(spec));
  let bin = "";
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function decodeSpec(s: string): QuerySpec | null {
  try {
    const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/"));
    const json = new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
    const spec = JSON.parse(json) as QuerySpec;
    return spec && spec.v === 1 && Array.isArray(spec.filters) && Array.isArray(spec.columns) ? spec : null;
  } catch {
    return null;
  }
}

export function useListState(projectId: string | undefined) {
  const [params, setParams] = useSearchParams();
  const views = useSavedQueries(projectId);
  const viewId = params.get("view") ?? "default:open";
  const view: SavedQuery | undefined = views.data?.find((v) => v.id === viewId) ?? views.data?.find((v) => v.id === "default:open");
  const override = params.get("q") ? decodeSpec(params.get("q")!) : null;
  const spec: QuerySpec | undefined = override ?? view?.spec;
  const page = Math.max(1, Number(params.get("page") ?? 1) || 1);
  const pageSize = [20, 100].includes(Number(params.get("size"))) ? Number(params.get("size")) : 20;

  const setSpec = useCallback(
    (next: QuerySpec) =>
      setParams(
        (p) => {
          p.set("q", encodeSpec(next));
          p.delete("page");
          return p;
        },
        { replace: true },
      ),
    [setParams],
  );
  const resetToView = useCallback((id?: string) => setParams((p) => (p.delete("q"), p.delete("page"), id && p.set("view", id), p)), [setParams]);
  const setPage = useCallback((n: number) => setParams((p) => (p.set("page", String(n)), p)), [setParams]);
  const setPageSize = useCallback((n: number) => setParams((p) => (p.set("size", String(n)), p.delete("page"), p)), [setParams]);
  /** Query string to carry along when opening/closing the details panel, so the list keeps its state. */
  const search = useMemo(() => (params.toString() ? `?${params.toString()}` : ""), [params]);

  return { views, view, spec, dirty: !!override, page, pageSize, setSpec, resetToView, setPage, setPageSize, search };
}
