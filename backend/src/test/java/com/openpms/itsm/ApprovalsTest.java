package com.openpms.itsm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalsTest {
    static final UUID REQ = UUID.randomUUID(), MGR = UUID.randomUUID(), LEAD = UUID.randomUUID();
    static final Approvals.StepRule M1 = new Approvals.StepRule(1, "requester_manager");
    static final Approvals.StepRule L2 = new Approvals.StepRule(2, "group_lead");
    static final Approvals.StepRule L1 = new Approvals.StepRule(1, "group_lead");

    @Test void managerThenLead() {
        var p = Approvals.plan(List.of(M1, L2), REQ, MGR, LEAD);
        assertThat(p).containsExactly(
                new Approvals.Planned(1, "requester_manager", MGR, "pending", null),
                new Approvals.Planned(2, "group_lead", LEAD, "waiting", null));
        assertThat(Approvals.needsApproval(p)).isTrue();
    }

    @Test void aMissingManagerSkipsTheStepAndTheNextBecomesCurrent() {
        var p = Approvals.plan(List.of(M1, L2), REQ, null, LEAD);
        assertThat(p).containsExactly(
                new Approvals.Planned(1, "requester_manager", null, "skipped", "no_approver"),
                new Approvals.Planned(2, "group_lead", LEAD, "pending", null));
    }

    @Test void theRequesterNeverApprovesTheirOwnRequest() {
        var p = Approvals.plan(List.of(M1, L2), REQ, REQ, REQ);
        assertThat(p).extracting(Approvals.Planned::decision).containsOnly("skipped");
        assertThat(p).extracting(Approvals.Planned::skipReason).containsOnly("requester");
        assertThat(Approvals.needsApproval(p)).isFalse();
    }

    @Test void twoKindsInOneStepAreEitherOne() {
        var p = Approvals.plan(List.of(M1, L1), REQ, MGR, LEAD);
        assertThat(p).extracting(Approvals.Planned::decision).containsExactly("pending", "pending");
    }

    @Test void theSamePersonTwiceInAStepIsAskedOnce() {
        var p = Approvals.plan(List.of(L1, M1), REQ, MGR, MGR);
        assertThat(p).containsExactly(
                new Approvals.Planned(1, "requester_manager", MGR, "pending", null),
                new Approvals.Planned(1, "group_lead", MGR, "skipped", "duplicate"));
    }

    @Test void noRulesNoApproval() {
        assertThat(Approvals.plan(List.of(), REQ, MGR, LEAD)).isEmpty();
        assertThat(Approvals.needsApproval(List.of())).isFalse();
    }
}
