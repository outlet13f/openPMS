import type { ReactNode } from "react";
import { Navigate, useLocation, useNavigate } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { api, type Me } from "@/api";
import { useDisplaySettings, useMe } from "@/api/hooks";
import { configureDisplay } from "@/lib/dates";
import { FullPageSpinner, ErrorState } from "./page";

/** Renders children only for a signed-in user; otherwise sends them to /login and back afterwards. */
export function RequireAuth({ children }: { children: (me: Me) => ReactNode }) {
  const me = useMe();
  const display = useDisplaySettings(!!me.data);
  const location = useLocation();
  if (me.isPending || (me.data && display.isPending)) return <FullPageSpinner />;
  // set before the screens render their dates; without an answer the Korean defaults stay
  if (display.data) configureDisplay(display.data);
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
