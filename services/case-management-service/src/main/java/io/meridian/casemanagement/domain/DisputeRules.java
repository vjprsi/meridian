package io.meridian.casemanagement.domain;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * WE-OPS-1 (D-ED-13, G-09): the pure rules of the non-card wallet transaction-correction workflow and the dispute
 * tracker, separated from the service orchestration so they unit-test without a database, ledger or wallet-service.
 *
 * <p>Covers the GSMA-recognised reason set shared by both features; the forward-only dispute lifecycle
 * (RAISED -&gt; UNDER_INVESTIGATION -&gt; RESOLVED | REJECTED | WITHDRAWN) and which action each status allows; the
 * per-tenant SLA due/breach maths; the repeat-claimant threshold; and the correction's maker-checker + idempotent
 * execution guard (a correction executes its reversal at most once, never from a terminal state).
 *
 * <pre>
 * dispute:    RAISED -> UNDER_INVESTIGATION (investigate) -> RESOLVED | REJECTED (resolve / reject)
 *             RAISED | UNDER_INVESTIGATION -> WITHDRAWN (withdraw) ; RESOLVED / REJECTED / WITHDRAWN are terminal
 * correction: REQUESTED -> PENDING_APPROVAL (submit) -> APPROVED (checker, a different user) -> EXECUTED (reversal)
 *             REQUESTED | PENDING_APPROVAL -> REJECTED ; APPROVED -> FAILED (reversal error, retryable)
 *             EXECUTED / REJECTED are terminal
 * </pre>
 */
public final class DisputeRules {
    private DisputeRules() {}

    /** GSMA-recognised reasons for a correction or dispute (OTHER is the catch-all). */
    public static final Set<String> REASONS =
            Set.of("WRONG_RECIPIENT", "DUPLICATE", "FRAUDULENT", "ERRONEOUS_AMOUNT", "OTHER");

    public static final Set<String> EVIDENCE_TYPES =
            Set.of("TRANSACTION_REF", "RECEIPT", "COUNTERPARTY", "SCREENSHOT", "NOTE", "OTHER");

    public static boolean validReason(String reason) {
        return reason != null && REASONS.contains(reason);
    }

    public static boolean validEvidenceType(String type) {
        return type != null && EVIDENCE_TYPES.contains(type);
    }

    // ------------------------------------------------------------------ dispute lifecycle

    public static final Set<String> DISPUTE_OPEN = Set.of("RAISED", "UNDER_INVESTIGATION");
    public static final Set<String> DISPUTE_TERMINAL = Set.of("RESOLVED", "REJECTED", "WITHDRAWN");

    /** The dispute status each action may run from. */
    public static final Map<String, Set<String>> DISPUTE_ACTIONS = Map.of(
            "investigate", Set.of("RAISED"),
            "resolve", Set.of("RAISED", "UNDER_INVESTIGATION"),
            "reject", Set.of("RAISED", "UNDER_INVESTIGATION"),
            "withdraw", Set.of("RAISED", "UNDER_INVESTIGATION"),
            "add_evidence", DISPUTE_OPEN,
            "edit", DISPUTE_OPEN);

    public static boolean disputeAllows(String action, String status) {
        return DISPUTE_ACTIONS.getOrDefault(action, Set.of()).contains(status);
    }

    public static boolean disputeOpen(String status) {
        return DISPUTE_OPEN.contains(status);
    }

    public static boolean disputeTerminal(String status) {
        return DISPUTE_TERMINAL.contains(status);
    }

    // ------------------------------------------------------------------ SLA

    /** The SLA deadline: {@code raisedAt} plus the override hours when positive, otherwise the per-tenant default. */
    public static OffsetDateTime dueAt(OffsetDateTime raisedAt, Integer overrideHours, int defaultHours) {
        int hours = overrideHours != null && overrideHours > 0 ? overrideHours : defaultHours;
        return raisedAt.plus(Duration.ofHours(hours));
    }

    /** True once {@code now} is at or past the deadline; a dispute with no deadline never breaches. */
    public static boolean breached(OffsetDateTime dueAt, OffsetDateTime now) {
        return dueAt != null && !now.isBefore(dueAt);
    }

    /** A dispute breaches its SLA only while still open and past the deadline. */
    public static boolean slaBreached(String status, OffsetDateTime dueAt, OffsetDateTime now) {
        return disputeOpen(status) && breached(dueAt, now);
    }

    // ------------------------------------------------------------------ repeat claimant

    /**
     * Repeat-claimant screening: true when the claimant's disputes in the window exceed the configured threshold.
     * {@code priorCount} counts the claimant's earlier disputes in the window; the one being raised makes the total
     * {@code priorCount + 1}, so the flag trips once that total is greater than {@code threshold}.
     */
    public static boolean repeatClaimant(int priorCount, int threshold) {
        return threshold > 0 && priorCount + 1 > threshold;
    }

    // ------------------------------------------------------------------ correction lifecycle (maker-checker + execution)

    public static final Set<String> CORRECTION_TERMINAL = Set.of("EXECUTED", "REJECTED");

    public static final Map<String, Set<String>> CORRECTION_ACTIONS = Map.of(
            "submit", Set.of("REQUESTED"),
            "approve", Set.of("REQUESTED", "PENDING_APPROVAL"),
            "reject", Set.of("REQUESTED", "PENDING_APPROVAL"),
            "execute", Set.of("APPROVED", "FAILED"));

    public static boolean correctionAllows(String action, String status) {
        return CORRECTION_ACTIONS.getOrDefault(action, Set.of()).contains(status);
    }

    public static boolean correctionTerminal(String status) {
        return CORRECTION_TERMINAL.contains(status);
    }

    /**
     * Idempotency / terminal-state guard for the reversal execution: a correction may execute its reversal only from
     * APPROVED (or FAILED, a retry) and only while it has not already produced a reversal transaction. An EXECUTED,
     * REJECTED, REQUESTED or PENDING_APPROVAL correction, or one that already carries a reversal id, never executes.
     */
    public static boolean canExecute(String status, String existingReversalTxnId) {
        return (existingReversalTxnId == null || existingReversalTxnId.isBlank())
                && correctionAllows("execute", status);
    }

    /** The checker must differ from the maker (four-eyes). */
    public static boolean distinctChecker(String maker, String checker) {
        return maker != null && checker != null && !maker.equals(checker);
    }
}
