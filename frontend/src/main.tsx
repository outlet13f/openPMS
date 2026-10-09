import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router";
import "./styles/index.css";
import { TooltipProvider } from "@/components/ui/overlay";
import { ToastProvider } from "@/components/ui/feedback";
import { DesignPage } from "@/pages/design-page";

const router = createBrowserRouter([
  { path: "/design", element: <DesignPage /> },
  // Screens arrive with /replica-build; until then everything points at the design system page.
  { path: "*", element: <Navigate to="/design" replace /> },
]);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <TooltipProvider>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </TooltipProvider>
  </StrictMode>,
);
