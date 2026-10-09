import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@/i18n";
import { isApiError } from "@/api";
import { TooltipProvider } from "@/components/ui/overlay";
import { ToastProvider } from "@/components/ui/feedback";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 404/403/401 will not fix themselves; other failures get one retry.
      retry: (count, err) => !(isApiError(err) && [401, 403, 404].includes(err.status)) && count < 1,
      refetchOnWindowFocus: false,
      staleTime: 10_000,
    },
  },
});

export function Providers({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <ToastProvider>{children}</ToastProvider>
      </TooltipProvider>
    </QueryClientProvider>
  );
}
