package io.meridian.deposit.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/**
 * Status state machines of every deposit-service aggregate. An action may only move an entity along an edge
 * listed here; anything else is rejected with {@code 409 invalid_transition}.
 */
public enum Lifecycle {
    DEPOSIT_ACCOUNT("deposit account", Map.ofEntries(
            Map.entry("PENDING_APPROVAL", Set.of("PENDING_FUNDING", "REJECTED")),
            Map.entry("PENDING_FUNDING", Set.of("ACTIVE", "CLOSING")),
            Map.entry("ACTIVE", Set.of("FROZEN", "BLOCKED_DEBIT", "BLOCKED_CREDIT", "BLOCKED", "INACTIVE", "DORMANT",
                    "CLOSING")),
            Map.entry("BLOCKED_DEBIT", Set.of("ACTIVE", "BLOCKED_CREDIT", "BLOCKED", "FROZEN", "CLOSING")),
            Map.entry("BLOCKED_CREDIT", Set.of("ACTIVE", "BLOCKED_DEBIT", "BLOCKED", "FROZEN", "CLOSING")),
            Map.entry("BLOCKED", Set.of("ACTIVE", "BLOCKED_DEBIT", "BLOCKED_CREDIT", "FROZEN")),
            Map.entry("FROZEN", Set.of("ACTIVE", "BLOCKED_DEBIT", "BLOCKED_CREDIT", "BLOCKED", "CLOSING")),
            Map.entry("INACTIVE", Set.of("ACTIVE", "DORMANT", "FROZEN", "CLOSING")),
            Map.entry("DORMANT", Set.of("ACTIVE", "FROZEN", "CLOSING")),
            Map.entry("CLOSING", Set.of("CLOSED", "ACTIVE", "PENDING_FUNDING", "BLOCKED_DEBIT", "BLOCKED_CREDIT",
                    "FROZEN", "INACTIVE", "DORMANT")),
            Map.entry("CLOSED", Set.of()),
            Map.entry("REJECTED", Set.of()))),
    OVERDRAFT_FACILITY("overdraft facility", Map.of(
            "PENDING_APPROVAL", Set.of("ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("SUSPENDED", "CANCELLED", "EXPIRED"),
            "SUSPENDED", Set.of("ACTIVE", "CANCELLED", "EXPIRED"),
            "EXPIRED", Set.of(),
            "CANCELLED", Set.of())),
    ACCOUNT_CLOSURE("account closure", Map.of(
            "REQUESTED", Set.of("PENDING_APPROVAL", "CANCELLED", "REJECTED"),
            "PENDING_APPROVAL", Set.of("SETTLING", "REJECTED", "CANCELLED"),
            "SETTLING", Set.of("COMPLETED"),
            "COMPLETED", Set.of(),
            "REJECTED", Set.of(),
            "CANCELLED", Set.of())),
    TERM_DEPOSIT("term deposit", Map.of(
            "PENDING_FUNDING", Set.of("ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("MATURED", "ROLLED_OVER", "BROKEN"),
            "MATURED", Set.of("CLOSED"),
            "ROLLED_OVER", Set.of(),
            "BROKEN", Set.of(),
            "CLOSED", Set.of(),
            "CANCELLED", Set.of())),
    SAVINGS_POT("savings pot", Map.of(
            "ACTIVE", Set.of("GOAL_REACHED", "LOCKED", "CLOSED"),
            "GOAL_REACHED", Set.of("ACTIVE", "CLOSED"),
            "LOCKED", Set.of("ACTIVE", "CLOSED"),
            "CLOSED", Set.of())),
    STANDING_ORDER("standing order", Map.of(
            "ACTIVE", Set.of("PAUSED", "CANCELLED", "COMPLETED", "FAILED_SUSPENDED"),
            "PAUSED", Set.of("ACTIVE", "CANCELLED"),
            "FAILED_SUSPENDED", Set.of("ACTIVE", "CANCELLED"),
            "COMPLETED", Set.of(),
            "CANCELLED", Set.of())),
    CHEQUE_BOOK("cheque book", Map.of(
            "REQUESTED", Set.of("PRINTED", "DISPATCHED", "ACTIVE", "CANCELLED"),
            "PRINTED", Set.of("DISPATCHED", "ACTIVE", "CANCELLED"),
            "DISPATCHED", Set.of("ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("EXHAUSTED", "CANCELLED"),
            "EXHAUSTED", Set.of(),
            "CANCELLED", Set.of())),
    CHEQUE("cheque", Map.of(
            "ISSUED", Set.of("PRESENTED", "STOPPED", "CANCELLED"),
            "PRESENTED", Set.of("CLEARED", "RETURNED"),
            "CLEARED", Set.of("RETURNED"),
            "RETURNED", Set.of(),
            "STOPPED", Set.of(),
            "CANCELLED", Set.of())),
    VIRTUAL_ACCOUNT("virtual account", Map.of(
            "ACTIVE", Set.of("SUSPENDED", "CLOSED"),
            "SUSPENDED", Set.of("ACTIVE", "CLOSED"),
            "CLOSED", Set.of())),
    ACCOUNT_BLOCK("account block", Map.of(
            "ACTIVE", Set.of("LIFTED", "EXPIRED"),
            "LIFTED", Set.of(),
            "EXPIRED", Set.of()));

    private final String label;
    private final Map<String, Set<String>> edges;

    Lifecycle(String label, Map<String, Set<String>> edges) {
        this.label = label;
        this.edges = edges;
    }

    public boolean allows(String from, String to) {
        return edges.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws {@code 409 invalid_transition} unless {@code from -> to} is an allowed edge. */
    public void require(String from, String to) {
        if (!allows(from, to)) {
            throw ApiException.conflict("invalid_transition",
                    "A " + label + " in status " + from + " cannot move to " + to);
        }
    }

    /** Throws {@code 409 invalid_transition} unless the current status is one of {@code allowed}. */
    public void requireIn(String current, String action, String... allowed) {
        for (String a : allowed) {
            if (a.equals(current)) {
                return;
            }
        }
        throw ApiException.conflict("invalid_transition",
                "Cannot " + action + " a " + label + " in status " + current);
    }

    public boolean isTerminal(String status) {
        return edges.getOrDefault(status, Set.of()).isEmpty();
    }
}
