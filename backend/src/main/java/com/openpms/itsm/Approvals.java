package com.openpms.itsm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Approvals of a catalog request (docs/superpowers/specs/2026-10-14-itsm-phase2-catalog-design.md §3): steps in order,
 * anyone of a step decides it, one rejection ends the request.
 */
public class Approvals {
    public static final String MANAGER = "requester_manager";
    public static final String GROUP_LEAD = "group_lead";

    public record StepRule(int stepNo, String kind) {}

    public record Planned(int stepNo, String kind, UUID approverId, String decision, String skipReason) {}

    private static final List<String> KIND_ORDER = List.of(MANAGER, GROUP_LEAD);

    /** The rows to create for a new request. Pure: the caller looks up the manager and the group lead. */
    public static List<Planned> plan(List<StepRule> rules, UUID requesterId, UUID managerId, UUID groupLeadId) {
        var sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(StepRule::stepNo).thenComparingInt(r -> KIND_ORDER.indexOf(r.kind())));
        var out = new ArrayList<Planned>();
        Integer current = null;
        for (int i = 0; i < sorted.size(); i++) {
            var r = sorted.get(i);
            var who = MANAGER.equals(r.kind()) ? managerId : groupLeadId;
            String skip = null;
            if (who == null) skip = "no_approver";
            else if (who.equals(requesterId)) skip = "requester";
            else {
                for (var earlier : out) {
                    if (earlier.stepNo() == r.stepNo() && earlier.skipReason() == null && Objects.equals(earlier.approverId(), who)) skip = "duplicate";
                }
            }
            if (skip != null) {
                out.add(new Planned(r.stepNo(), r.kind(), skip.equals("no_approver") ? null : who, "skipped", skip));
                continue;
            }
            if (current == null) current = r.stepNo();
            out.add(new Planned(r.stepNo(), r.kind(), who, r.stepNo() == current ? "pending" : "waiting", null));
        }
        return out;
    }

    public static boolean needsApproval(List<Planned> planned) {
        return planned.stream().anyMatch(p -> "pending".equals(p.decision()));
    }
}
