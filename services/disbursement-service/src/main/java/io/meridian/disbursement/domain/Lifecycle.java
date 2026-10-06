package io.meridian.disbursement.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/** Allowed status transitions of programs and batches, and the derived outcome of an executed batch. */
public final class Lifecycle {
    private Lifecycle() {}

    static final Map<String, Set<String>> PROGRAM = Map.of(
            "DRAFT", Set.of("ACTIVE", "CLOSED"),
            "ACTIVE", Set.of("SUSPENDED", "CLOSED"),
            "SUSPENDED", Set.of("ACTIVE", "CLOSED"),
            "CLOSED", Set.of());

    static final Map<String, Set<String>> BATCH = Map.ofEntries(
            Map.entry("DRAFT", Set.of("DRAFT", "VALIDATING", "VALIDATED", "CANCELLED")),
            Map.entry("VALIDATING", Set.of("VALIDATED", "DRAFT")),
            Map.entry("VALIDATED", Set.of("DRAFT", "VALIDATING", "VALIDATED", "PENDING_APPROVAL", "CANCELLED")),
            Map.entry("PENDING_APPROVAL", Set.of("APPROVED", "REJECTED", "CANCELLED")),
            Map.entry("APPROVED", Set.of("PROCESSING")),
            Map.entry("PROCESSING", Set.of("PROCESSING", "COMPLETED", "PARTIALLY_COMPLETED", "FAILED")),
            Map.entry("PARTIALLY_COMPLETED", Set.of("PROCESSING")),
            Map.entry("FAILED", Set.of("PROCESSING")),
            Map.entry("COMPLETED", Set.of()),
            Map.entry("CANCELLED", Set.of()),
            Map.entry("REJECTED", Set.of()));

    /** Batch statuses in which beneficiary lines may still be changed. */
    public static final Set<String> EDITABLE_BATCH = Set.of("DRAFT");

    /** Batch statuses that block closing the program. */
    public static final Set<String> IN_FLIGHT_BATCH = Set.of("VALIDATING", "PENDING_APPROVAL", "APPROVED", "PROCESSING");

    public static void program(String from, String to) {
        check(PROGRAM, "program", from, to);
    }

    public static void batch(String from, String to) {
        check(BATCH, "batch", from, to);
    }

    public static boolean batchAllows(String from, String to) {
        return BATCH.getOrDefault(from, Set.of()).contains(to);
    }

    private static void check(Map<String, Set<String>> m, String what, String from, String to) {
        if (!m.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition",
                    "A " + what + " in status " + from + " cannot move to " + to);
        }
    }

    /** Final status of an execution run: all paid, none paid, or a mix. */
    public static String outcome(int paid, int failed) {
        if (failed == 0) {
            return "COMPLETED";
        }
        return paid == 0 ? "FAILED" : "PARTIALLY_COMPLETED";
    }

    /** Wallet transaction type of a payout per program type. */
    public static String txnType(String programType) {
        return switch (programType == null ? "" : programType) {
            case "G2P", "SOCIAL_TRANSFER", "HUMANITARIAN" -> "G2P";
            default -> "BULK_DISBURSEMENT";
        };
    }
}
