package io.meridian.loan.domain;

import java.util.Map;
import java.util.Set;

/** Allowed status transitions of the loan-service aggregates. */
public final class Lifecycles {
    private Lifecycles() {}

    public static final class InvalidTransition extends RuntimeException {
        private final String code;

        public InvalidTransition(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final Map<String, Set<String>> LOAN = Map.of(
            "PENDING_DISBURSEMENT", Set.of("ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("IN_ARREARS", "RESTRUCTURED", "PAID_OFF", "WRITTEN_OFF", "CANCELLED"),
            "IN_ARREARS", Set.of("ACTIVE", "RESTRUCTURED", "PAID_OFF", "WRITTEN_OFF", "CHARGED_OFF"),
            "RESTRUCTURED", Set.of("ACTIVE", "IN_ARREARS", "PAID_OFF", "WRITTEN_OFF", "RESTRUCTURED"),
            "PAID_OFF", Set.of("CLOSED", "ACTIVE", "IN_ARREARS", "RESTRUCTURED"),
            "WRITTEN_OFF", Set.of("CLOSED"),
            "CHARGED_OFF", Set.of("CLOSED"),
            "CLOSED", Set.of(),
            "CANCELLED", Set.of());

    public static final Map<String, Set<String>> RESTRUCTURE = Map.of(
            "PROPOSED", Set.of("PENDING_APPROVAL", "CANCELLED"),
            "PENDING_APPROVAL", Set.of("APPROVED", "REJECTED", "CANCELLED"),
            "APPROVED", Set.of("APPLIED", "CANCELLED"),
            "REJECTED", Set.of(),
            "APPLIED", Set.of(),
            "CANCELLED", Set.of());

    public static final Map<String, Set<String>> WRITE_OFF = Map.of(
            "PENDING_APPROVAL", Set.of("POSTED", "REJECTED"),
            "APPROVED", Set.of("POSTED"),
            "POSTED", Set.of(),
            "REJECTED", Set.of());

    public static final Map<String, Set<String>> CREDIT_LINE = Map.of(
            "PENDING_ACTIVATION", Set.of("ACTIVE", "CLOSED"),
            "ACTIVE", Set.of("FROZEN", "SUSPENDED", "IN_ARREARS", "CLOSED"),
            "FROZEN", Set.of("ACTIVE", "CLOSED", "IN_ARREARS"),
            "SUSPENDED", Set.of("ACTIVE", "CLOSED"),
            "IN_ARREARS", Set.of("ACTIVE", "FROZEN", "CHARGED_OFF"),
            "CLOSED", Set.of(),
            "CHARGED_OFF", Set.of());

    public static final Map<String, Set<String>> DISBURSEMENT = Map.of(
            "SCHEDULED", Set.of("PENDING", "COMPLETED", "FAILED", "CANCELLED"),
            "PENDING", Set.of("COMPLETED", "FAILED", "CANCELLED"),
            "FAILED", Set.of("COMPLETED", "FAILED", "CANCELLED"),
            "COMPLETED", Set.of("REVERSED"),
            "REVERSED", Set.of(),
            "CANCELLED", Set.of());

    public static final Map<String, Set<String>> BNPL = Map.of(
            "PENDING", Set.of("ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("COMPLETED", "DEFAULTED", "REFUNDED", "CANCELLED"),
            "DEFAULTED", Set.of("COMPLETED", "REFUNDED"),
            "COMPLETED", Set.of("REFUNDED"),
            "REFUNDED", Set.of(),
            "CANCELLED", Set.of());

    public static boolean allowed(Map<String, Set<String>> machine, String from, String to) {
        return machine.getOrDefault(from, Set.of()).contains(to);
    }

    public static void require(Map<String, Set<String>> machine, String what, String from, String to) {
        if (!allowed(machine, from, to)) {
            throw new InvalidTransition("invalid_transition", what + " cannot move from " + from + " to " + to);
        }
    }
}
