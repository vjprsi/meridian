package io.meridian.collections.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/** State machines of collections entities; illegal moves are 409 invalid_transition. */
public final class Lifecycles {
    private Lifecycles() {}

    /** Collection case: RESOLVED and CLOSED are terminal; WRITTEN_OFF cases keep being worked (recovery). */
    public static final Map<String, Set<String>> CASE = Map.of(
            "OPEN", Set.of("IN_PROGRESS", "PROMISE_ACTIVE", "PLAN_ACTIVE", "LEGAL", "RESOLVED", "CLOSED", "WRITTEN_OFF"),
            "IN_PROGRESS", Set.of("PROMISE_ACTIVE", "PLAN_ACTIVE", "LEGAL", "RESOLVED", "CLOSED", "WRITTEN_OFF"),
            "PROMISE_ACTIVE", Set.of("IN_PROGRESS", "PLAN_ACTIVE", "LEGAL", "RESOLVED", "CLOSED", "WRITTEN_OFF"),
            "PLAN_ACTIVE", Set.of("IN_PROGRESS", "LEGAL", "RESOLVED", "CLOSED", "WRITTEN_OFF"),
            "LEGAL", Set.of("RESOLVED", "CLOSED", "WRITTEN_OFF"),
            "WRITTEN_OFF", Set.of("LEGAL", "RESOLVED", "CLOSED"),
            "RESOLVED", Set.of(),
            "CLOSED", Set.of());

    public static final Map<String, Set<String>> PROMISE = Map.of(
            "ACTIVE", Set.of("KEPT", "PARTIALLY_KEPT", "BROKEN", "CANCELLED"),
            "KEPT", Set.of(), "PARTIALLY_KEPT", Set.of(), "BROKEN", Set.of(), "CANCELLED", Set.of());

    public static final Map<String, Set<String>> PLAN = Map.of(
            "PROPOSED", Set.of("ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("COMPLETED", "BROKEN", "CANCELLED"),
            "COMPLETED", Set.of(), "BROKEN", Set.of(), "CANCELLED", Set.of());

    /** Legal proceeding: approval gate, then the court stages in order (settlement or closure possible at any stage). */
    public static final Map<String, Set<String>> LEGAL = Map.of(
            "PENDING_APPROVAL", Set.of("INITIATED", "REJECTED"),
            "INITIATED", Set.of("FILED", "SETTLED", "CLOSED"),
            "FILED", Set.of("HEARING", "SETTLED", "CLOSED"),
            "HEARING", Set.of("JUDGEMENT", "SETTLED", "CLOSED"),
            "JUDGEMENT", Set.of("ENFORCEMENT", "SETTLED", "CLOSED"),
            "ENFORCEMENT", Set.of("SETTLED", "CLOSED"),
            "SETTLED", Set.of("CLOSED"),
            "CLOSED", Set.of(),
            "REJECTED", Set.of());

    public static final Map<String, Set<String>> NBA = Map.of(
            "SUGGESTED", Set.of("ACCEPTED", "REJECTED", "EXPIRED"),
            "ACCEPTED", Set.of("EXECUTED", "EXPIRED"),
            "REJECTED", Set.of(), "EXECUTED", Set.of(), "EXPIRED", Set.of());

    public static boolean allowed(Map<String, Set<String>> machine, String from, String to) {
        return machine.getOrDefault(from, Set.of()).contains(to);
    }

    public static void require(Map<String, Set<String>> machine, String entity, String from, String to) {
        if (!allowed(machine, from, to)) {
            throw ApiException.conflict("invalid_transition", entity + " cannot move from " + from + " to " + to);
        }
    }

    public static boolean caseOpen(String status) {
        return !"RESOLVED".equals(status) && !"CLOSED".equals(status);
    }

    /** Case status after a close action: cures and settlements resolve, the rest closes. */
    public static String closingStatus(String resolution) {
        return switch (resolution) {
            case "CURED", "PAID_OFF", "RESTRUCTURED", "SETTLED" -> "RESOLVED";
            default -> "CLOSED";
        };
    }
}
