package io.meridian.treasurydeal.domain;

import java.util.Map;
import java.util.Set;

/**
 * Status machines of the treasury entities. Deals: CAPTURED -> VERIFIED (four-eyes) -> CONFIRMED -> SETTLED ->
 * MATURED, CANCELLED before settlement. Every rejected transition is a 409 invalid_transition.
 */
public final class Lifecycle {
    private Lifecycle() {}

    public static final Map<String, Set<String>> DEAL = Map.of(
            "CAPTURED", Set.of("VERIFIED", "CANCELLED"),
            "VERIFIED", Set.of("CONFIRMED", "CANCELLED"),
            "CONFIRMED", Set.of("SETTLED", "CANCELLED"),
            "SETTLED", Set.of("MATURED"),
            "MATURED", Set.of(),
            "CANCELLED", Set.of(),
            "AMENDED", Set.of());

    public static final Map<String, Set<String>> COUNTERPARTY = Map.of(
            "PENDING_APPROVAL", Set.of("ACTIVE"),
            "ACTIVE", Set.of("SUSPENDED", "CLOSED"),
            "SUSPENDED", Set.of("ACTIVE", "CLOSED"),
            "CLOSED", Set.of());

    public static final Map<String, Set<String>> LIMIT = Map.of(
            "PENDING_APPROVAL", Set.of("ACTIVE"),
            "ACTIVE", Set.of("SUSPENDED", "EXPIRED"),
            "SUSPENDED", Set.of("ACTIVE", "EXPIRED"),
            "EXPIRED", Set.of());

    public static final Map<String, Set<String>> SETTLEMENT = Map.of(
            "PENDING", Set.of("INSTRUCTED", "SETTLED", "FAILED", "CANCELLED"),
            "INSTRUCTED", Set.of("SETTLED", "FAILED"),
            "FAILED", Set.of("INSTRUCTED", "SETTLED", "CANCELLED"),
            "SETTLED", Set.of(),
            "CANCELLED", Set.of());

    public static final Map<String, Set<String>> TRADE = Map.of(
            "PENDING", Set.of("SETTLED", "CANCELLED"),
            "SETTLED", Set.of("MATURED", "SOLD"),
            "MATURED", Set.of(), "SOLD", Set.of(), "CANCELLED", Set.of());

    public static final Map<String, Set<String>> CONFIRMATION = Map.of(
            "PENDING", Set.of("SENT", "RECEIVED", "CANCELLED"),
            "SENT", Set.of("MATCHED", "DISPUTED", "CANCELLED"),
            "RECEIVED", Set.of("MATCHED", "UNMATCHED", "DISPUTED", "CANCELLED"),
            "UNMATCHED", Set.of("MATCHED", "UNMATCHED", "DISPUTED", "CANCELLED"),
            "MATCHED", Set.of("DISPUTED"),
            "DISPUTED", Set.of("MATCHED", "UNMATCHED", "CANCELLED"),
            "CANCELLED", Set.of());

    public static boolean allowed(Map<String, Set<String>> machine, String from, String to) {
        return machine.getOrDefault(from, Set.of()).contains(to);
    }
}
