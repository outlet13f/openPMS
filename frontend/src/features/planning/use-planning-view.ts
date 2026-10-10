// View state for a calendar or team planner (S17, S18). Like the list (view-state.ts), it lives in the URL:
//   /calendar/:id or /team-planner/:id   the saved query (queries.view = calendar | team_planner)
//   ?q=<base64url spec>                  unsaved filter changes on top of it
//   ?date=YYYY-MM-DD                     a day inside the visible period (default: today)
//   ?mode=month|week (calendar), ?span=1|2 (team planner)
// Keeping the period in the URL means opening and closing the details panel does not jump back to today.
import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router";
import type { IsoDate, QueryFilter, QuerySpec, SavedQuery } from "@/api";
import { useSavedQueries } from "@/api/hooks";
import { addDays, isoWeekday, todayIso, weekStart } from "@/lib/dates";
import { decodeSpec, encodeSpec } from "@/features/work-packages/view-state";

/** The first day of the week that contains d (관리 · 날짜 형식 · 주 시작 요일). */
export function startOfWeek(d: IsoDate): IsoDate {
  const sinceStart = (isoWeekday(d) % 7) - weekStart(); // isoWeekday: Monday 1 … Sunday 7
  return addDays(d, -((sinceStart + 7) % 7));
}

/** The filter the calendar and team planner add at query time: items whose dates touch the window. */
export const overlapFilter = (from: IsoDate, to: IsoDate): QueryFilter => ({ field: "dates", op: "overlaps", values: [from, to] });

const isIsoDate = (s: string | null): s is IsoDate => !!s && /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s));

export function usePlanningView(projectId: string, kind: "calendar" | "team_planner", id: string | undefined) {
  const [params, setParams] = useSearchParams();
  const views = useSavedQueries(projectId, kind);
  const view: SavedQuery | undefined = views.data?.find((v) => v.id === id);
  const override = params.get("q") ? decodeSpec(params.get("q")!) : null;
  const spec: QuerySpec | undefined = override ?? view?.spec;
  const date = isIsoDate(params.get("date")) ? params.get("date")! : todayIso();

  const setParam = useCallback(
    (key: string, value: string | null) =>
      setParams(
        (p) => {
          if (value === null) p.delete(key);
          else p.set(key, value);
          return p;
        },
        { replace: true },
      ),
    [setParams],
  );
  const setSpec = useCallback((next: QuerySpec) => setParam("q", encodeSpec(next)), [setParam]);
  const reset = useCallback(() => setParam("q", null), [setParam]);
  const setDate = useCallback((d: IsoDate) => setParam("date", d === todayIso() ? null : d), [setParam]);
  /** Query string to carry along when opening and closing the details or new panel. */
  const search = useMemo(() => {
    const p = new URLSearchParams(params);
    for (const k of ["startDate", "dueDate", "assignee", "type", "parent"]) p.delete(k);
    return p.toString() ? `?${p.toString()}` : "";
  }, [params]);

  return { views, view, spec, dirty: !!override, setSpec, reset, date, setDate, params, setParam, search };
}
