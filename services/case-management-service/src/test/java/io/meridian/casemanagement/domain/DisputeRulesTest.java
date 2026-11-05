package io.meridian.casemanagement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** WE-OPS-1: pure rules of the wallet dispute tracker + maker-checker correction workflow. */
class DisputeRulesTest {

    private static final OffsetDateTime RAISED = OffsetDateTime.of(2026, 11, 4, 9, 0, 0, 0, ZoneOffset.UTC);

    // ------------------------------------------------------------------ reason codes

    @Test
    void reasonCodeValidationAcceptsTheGsmaSetOnly() {
        assertThat(DisputeRules.REASONS)
                .containsExactlyInAnyOrder("WRONG_RECIPIENT", "DUPLICATE", "FRAUDULENT", "ERRONEOUS_AMOUNT", "OTHER");
        assertThat(DisputeRules.validReason("WRONG_RECIPIENT")).isTrue();
        assertThat(DisputeRules.validReason("ERRONEOUS_AMOUNT")).isTrue();
        assertThat(DisputeRules.validReason("CHARGEBACK")).isFalse();
        assertThat(DisputeRules.validReason(null)).isFalse();
        assertThat(DisputeRules.validEvidenceType("RECEIPT")).isTrue();
        assertThat(DisputeRules.validEvidenceType("VIDEO")).isFalse();
    }

    // ------------------------------------------------------------------ dispute lifecycle

    @Test
    void disputeLifecycleTransitionsAreForwardOnlyAndTerminalStatesAreClosed() {
        // valid
        assertThat(DisputeRules.disputeAllows("investigate", "RAISED")).isTrue();
        assertThat(DisputeRules.disputeAllows("resolve", "UNDER_INVESTIGATION")).isTrue();
        assertThat(DisputeRules.disputeAllows("resolve", "RAISED")).isTrue();
        assertThat(DisputeRules.disputeAllows("reject", "RAISED")).isTrue();
        assertThat(DisputeRules.disputeAllows("withdraw", "UNDER_INVESTIGATION")).isTrue();
        assertThat(DisputeRules.disputeAllows("add_evidence", "RAISED")).isTrue();

        // invalid: cannot re-investigate, cannot act on a terminal dispute
        assertThat(DisputeRules.disputeAllows("investigate", "UNDER_INVESTIGATION")).isFalse();
        assertThat(DisputeRules.disputeAllows("resolve", "RESOLVED")).isFalse();
        assertThat(DisputeRules.disputeAllows("reject", "WITHDRAWN")).isFalse();
        assertThat(DisputeRules.disputeAllows("withdraw", "REJECTED")).isFalse();
        assertThat(DisputeRules.disputeAllows("add_evidence", "RESOLVED")).isFalse();

        assertThat(DisputeRules.disputeOpen("RAISED")).isTrue();
        assertThat(DisputeRules.disputeOpen("UNDER_INVESTIGATION")).isTrue();
        assertThat(DisputeRules.disputeOpen("RESOLVED")).isFalse();
        assertThat(DisputeRules.disputeTerminal("RESOLVED")).isTrue();
        assertThat(DisputeRules.disputeTerminal("REJECTED")).isTrue();
        assertThat(DisputeRules.disputeTerminal("WITHDRAWN")).isTrue();
        assertThat(DisputeRules.disputeTerminal("RAISED")).isFalse();
    }

    // ------------------------------------------------------------------ SLA

    @Test
    void slaDueAtUsesOverrideHoursWhenPositiveElseTheTenantDefault() {
        assertThat(DisputeRules.dueAt(RAISED, null, 120)).isEqualTo(RAISED.plusHours(120));
        assertThat(DisputeRules.dueAt(RAISED, 0, 120)).isEqualTo(RAISED.plusHours(120));
        assertThat(DisputeRules.dueAt(RAISED, 48, 120)).isEqualTo(RAISED.plusHours(48));
    }

    @Test
    void slaBreachIsTrueOnlyWhenOpenAndPastTheDeadline() {
        OffsetDateTime due = DisputeRules.dueAt(RAISED, null, 120);
        assertThat(DisputeRules.breached(due, due.minusSeconds(1))).isFalse();
        assertThat(DisputeRules.breached(due, due)).isTrue();            // at the deadline
        assertThat(DisputeRules.breached(due, due.plusHours(1))).isTrue();
        assertThat(DisputeRules.breached(null, due)).isFalse();          // no deadline never breaches

        assertThat(DisputeRules.slaBreached("UNDER_INVESTIGATION", due, due.plusHours(1))).isTrue();
        assertThat(DisputeRules.slaBreached("RAISED", due, due.minusHours(1))).isFalse();   // not yet due
        assertThat(DisputeRules.slaBreached("RESOLVED", due, due.plusHours(1))).isFalse();  // closed: no breach
    }

    // ------------------------------------------------------------------ repeat claimant

    @Test
    void repeatClaimantTripsOnceTheWindowTotalExceedsTheThreshold() {
        // threshold 3: the flag trips when this dispute is the claimant's 4th in the window (prior >= 3)
        assertThat(DisputeRules.repeatClaimant(2, 3)).isFalse();   // total 3, at the threshold
        assertThat(DisputeRules.repeatClaimant(3, 3)).isTrue();    // total 4, over it
        assertThat(DisputeRules.repeatClaimant(0, 3)).isFalse();
        assertThat(DisputeRules.repeatClaimant(5, 0)).isFalse();   // disabled (threshold 0)
    }

    // ------------------------------------------------------------------ correction maker-checker + execution guard

    @Test
    void correctionApproveAndExecuteTransitionsAreGuarded() {
        assertThat(DisputeRules.correctionAllows("approve", "PENDING_APPROVAL")).isTrue();
        assertThat(DisputeRules.correctionAllows("approve", "REQUESTED")).isTrue();
        assertThat(DisputeRules.correctionAllows("approve", "APPROVED")).isFalse();
        assertThat(DisputeRules.correctionAllows("approve", "EXECUTED")).isFalse();
        assertThat(DisputeRules.correctionAllows("execute", "APPROVED")).isTrue();
        assertThat(DisputeRules.correctionAllows("execute", "FAILED")).isTrue();   // retry
        assertThat(DisputeRules.correctionAllows("execute", "PENDING_APPROVAL")).isFalse();

        assertThat(DisputeRules.correctionTerminal("EXECUTED")).isTrue();
        assertThat(DisputeRules.correctionTerminal("REJECTED")).isTrue();
        assertThat(DisputeRules.correctionTerminal("APPROVED")).isFalse();
    }

    @Test
    void correctionExecutesReversalAtMostOnce() {
        // executable once approved and not yet reversed
        assertThat(DisputeRules.canExecute("APPROVED", null)).isTrue();
        assertThat(DisputeRules.canExecute("APPROVED", "")).isTrue();
        // idempotent: already carries a reversal transaction id -> never executes again
        assertThat(DisputeRules.canExecute("APPROVED", "11111111-1111-1111-1111-111111111111")).isFalse();
        assertThat(DisputeRules.canExecute("EXECUTED", "11111111-1111-1111-1111-111111111111")).isFalse();
        // terminal / not-yet-approved states never execute
        assertThat(DisputeRules.canExecute("EXECUTED", null)).isFalse();
        assertThat(DisputeRules.canExecute("REJECTED", null)).isFalse();
        assertThat(DisputeRules.canExecute("PENDING_APPROVAL", null)).isFalse();
    }

    @Test
    void checkerMustDifferFromMaker() {
        assertThat(DisputeRules.distinctChecker("alice", "bob")).isTrue();
        assertThat(DisputeRules.distinctChecker("alice", "alice")).isFalse();
        assertThat(DisputeRules.distinctChecker(null, "bob")).isFalse();
    }
}
