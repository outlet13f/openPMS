import type { ReactNode } from "react";
import { Navigate, useLocation, useNavigate } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { api, type Me } from "@/api";
import { useMe } from "@/api/hooks";
import { FullPageSpinner, ErrorState } from "./page";

/** Renders children only for a signed-in user; otherwise sends them to /login and back afterwards. */
export function RequireAuth({ children }: { children: (me: Me) => ReactNode }) {
  const me = useMe();
  const location = useLocation();
  if (me.isPending) return <FullPageSpinner />;
  if (me.isError) return <ErrorState error={me.error} onRetry={() => me.refetch()} />;
  if (!me.data) {
    const next = location.pathname + location.search;
    return <Navigate to={`/login${next && next !== "/" ? `?next=${encodeURIComponent(next)}` : ""}`} replace />;
  }
  return <>{children(me.data)}</>;
}

export function useLogout() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  return async () => {
    await api.auth.logout();
    qc.clear();
    navigate("/login", { replace: true });
  };
}
