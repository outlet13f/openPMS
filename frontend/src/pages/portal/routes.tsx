// 서비스 요청 (the requester's portal): every signed-in employee picks a service desk, submits an incident or a
// service request and follows their own requests. Loaded as its own chunk from app/router.tsx ("portal/*").
import { Route, Routes } from "react-router";
import { NotFound } from "@/app/page";
import { PortalApprovalPage } from "./approval";
import { PortalApprovalsPage } from "./approvals";
import { PortalHomePage } from "./home";
import { PortalItemPage } from "./item";
import { PortalNewTicketPage } from "./new-ticket";
import { PortalTicketPage } from "./ticket";

export function PortalRoutes() {
  return (
    <Routes>
      <Route index element={<PortalHomePage />} />
      <Route path="new/:desk" element={<PortalNewTicketPage />} />
      <Route path="items/:itemId" element={<PortalItemPage />} />
      <Route path="approvals" element={<PortalApprovalsPage />} />
      <Route path="approvals/:id" element={<PortalApprovalPage />} />
      <Route path="tickets/:number" element={<PortalTicketPage />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
