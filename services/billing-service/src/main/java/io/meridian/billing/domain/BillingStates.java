package io.meridian.billing.domain;

import java.util.Map;
import java.util.Set;

/** Allowed status transitions for price plans, subscriptions and invoices. */
public final class BillingStates {
    private BillingStates() {}

    /** Price plans: drafted, then published (ACTIVE), then retired. A draft may be discarded (RETIRED). */
    public static final Map<String, Set<String>> PRICE_PLAN = Map.of(
            "DRAFT", Set.of("ACTIVE", "RETIRED"),
            "ACTIVE", Set.of("RETIRED"),
            "RETIRED", Set.of());

    /** Subscriptions: CANCELLED is terminal and only reachable through the cancel action. */
    public static final Map<String, Set<String>> SUBSCRIPTION = Map.of(
            "TRIAL", Set.of("ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("PAST_DUE", "SUSPENDED", "CANCELLED"),
            "PAST_DUE", Set.of("ACTIVE", "SUSPENDED", "CANCELLED"),
            "SUSPENDED", Set.of("ACTIVE", "CANCELLED"),
            "CANCELLED", Set.of());

    public static final Map<String, Set<String>> INVOICE = Map.of(
            "DRAFT", Set.of("ISSUED", "VOID"),
            "ISSUED", Set.of("PARTIALLY_PAID", "PAID", "OVERDUE", "VOID"),
            "PARTIALLY_PAID", Set.of("PARTIALLY_PAID", "PAID", "OVERDUE"),
            "OVERDUE", Set.of("PARTIALLY_PAID", "PAID", "VOID"),
            "PAID", Set.of(),
            "VOID", Set.of());

    public static boolean allowed(Map<String, Set<String>> machine, String from, String to) {
        return machine.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws {@link TransitionException} when the transition is not allowed. */
    public static void require(Map<String, Set<String>> machine, String entity, String from, String to) {
        if (!allowed(machine, from, to)) {
            throw new TransitionException(entity + " cannot move from " + from + " to " + to);
        }
    }

    /** Raised for an illegal state change (mapped to 409 invalid_transition). */
    public static class TransitionException extends RuntimeException {
        public TransitionException(String message) {
            super(message);
        }
    }
}
