// The two manuals the help screens show, read from docs/deliverables at build time (one source: the documents
// themselves). Each is its own chunk, loaded the first time someone opens help.
import { useQuery } from "@tanstack/react-query";
import { manual, type Manual } from "./markdown";

export type ManualId = "user" | "admin";

const loaders: Record<ManualId, () => Promise<{ default: string }>> = {
  user: () => import("../../../../docs/deliverables/07-사용자매뉴얼.md?raw"),
  admin: () => import("../../../../docs/deliverables/10-운영자매뉴얼.md?raw"),
};

/** Where each manual lives in the app; the documents link to each other by file name. */
export const MANUAL_PATH: Record<ManualId, string> = { user: "/help", admin: "/help/admin" };
const FILES: Record<string, ManualId> = { "07-사용자매뉴얼.md": "user", "10-운영자매뉴얼.md": "admin" };

export const manualOfFile = (file: string): ManualId | undefined => FILES[file];

export const loadManual = async (id: ManualId): Promise<Manual> => manual((await loaders[id]()).default);

export const useManual = (id: ManualId) => useQuery<Manual>({ queryKey: ["manual", id], queryFn: () => loadManual(id), staleTime: Infinity, gcTime: Infinity });
