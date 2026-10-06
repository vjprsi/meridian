package io.meridian.loanorigination.domain;

import java.util.Map;
import java.util.Set;

/** State machines of the origination aggregates (pure; the application layer maps violations to 409). */
public final class Lifecycles {
    private Lifecycles() {}

    public static final class InvalidTransition extends RuntimeException {
        public InvalidTransition(String message) {
            super(message);
        }
    }

    private static final Set<String> DECISIONS = Set.of("APPROVED", "DECLINED", "REFERRED", "COUNTER_OFFERED");

    public static final Map<String, Set<String>> APPLICATION = Map.ofEntries(
            Map.entry("DRAFT", Set.of("SUBMITTED", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("SUBMITTED", union(DECISIONS, "IN_REVIEW", "BUREAU_PENDING", "SCORED", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("IN_REVIEW", union(DECISIONS, "BUREAU_PENDING", "SCORED", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("BUREAU_PENDING", union(DECISIONS, "SUBMITTED", "IN_REVIEW", "SCORED", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("SCORED", union(DECISIONS, "IN_REVIEW", "BUREAU_PENDING", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("REFERRED", union(DECISIONS, "IN_REVIEW", "BUREAU_PENDING", "SCORED", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("APPROVED", Set.of("OFFER_ACCEPTED", "DECLINED", "COUNTER_OFFERED", "REFERRED", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("COUNTER_OFFERED", Set.of("OFFER_ACCEPTED", "APPROVED", "DECLINED", "REFERRED", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("DECLINED", Set.of("APPROVED", "COUNTER_OFFERED", "REFERRED")),
            Map.entry("OFFER_ACCEPTED", Set.of("SIGNED", "APPROVED", "WITHDRAWN", "EXPIRED", "CANCELLED")),
            Map.entry("SIGNED", Set.of("BOOKED", "WITHDRAWN", "CANCELLED")),
            Map.entry("BOOKED", Set.of("DISBURSED", "CANCELLED")),
            Map.entry("DISBURSED", Set.of()),
            Map.entry("WITHDRAWN", Set.of()),
            Map.entry("EXPIRED", Set.of()),
            Map.entry("CANCELLED", Set.of()));

    /** Applicants, financial items and declared data may change until a decision is taken. */
    public static final Set<String> APPLICATION_EDITABLE =
            Set.of("DRAFT", "SUBMITTED", "IN_REVIEW", "BUREAU_PENDING", "SCORED", "REFERRED");
    /** States in which the automatic or manual decision process may run. */
    public static final Set<String> DECIDABLE = Set.of("SUBMITTED", "IN_REVIEW", "SCORED", "REFERRED");
    /** States from which a manual decision (incl. override of an automatic one) may be proposed. */
    public static final Set<String> MANUALLY_DECIDABLE =
            Set.of("SUBMITTED", "IN_REVIEW", "SCORED", "REFERRED", "APPROVED", "COUNTER_OFFERED", "DECLINED");
    public static final Set<String> APPLICATION_TERMINAL = Set.of("DISBURSED", "WITHDRAWN", "EXPIRED", "CANCELLED");

    public static final Map<String, Set<String>> OFFER = Map.of(
            "ISSUED", Set.of("ACCEPTED", "DECLINED", "EXPIRED", "WITHDRAWN"),
            "ACCEPTED", Set.of("WITHDRAWN", "DECLINED", "EXPIRED"),
            "DECLINED", Set.of(), "EXPIRED", Set.of(), "WITHDRAWN", Set.of());

    public static final Map<String, Set<String>> ESIGN = Map.of(
            "CREATED", Set.of("SENT", "VOIDED", "EXPIRED"),
            "SENT", Set.of("VIEWED", "SIGNED", "DECLINED", "EXPIRED", "VOIDED"),
            "VIEWED", Set.of("SIGNED", "DECLINED", "EXPIRED", "VOIDED"),
            "SIGNED", Set.of(), "DECLINED", Set.of(), "EXPIRED", Set.of(), "VOIDED", Set.of());

    public static final Map<String, Set<String>> SCORECARD_VERSION = Map.of(
            "DRAFT", Set.of("VALIDATING", "CHAMPION", "CHALLENGER", "RETIRED"),
            "VALIDATING", Set.of("CHAMPION", "CHALLENGER", "RETIRED"),
            "ACTIVE", Set.of("CHAMPION", "CHALLENGER", "RETIRED"),
            "CHALLENGER", Set.of("CHAMPION", "CHALLENGER", "RETIRED"),
            "CHAMPION", Set.of("RETIRED"),
            "RETIRED", Set.of());

    public static final Map<String, Set<String>> DOCUMENT = Map.of(
            "REQUESTED", Set.of("UPLOADED", "REJECTED", "EXPIRED"),
            "UPLOADED", Set.of("VERIFIED", "REJECTED", "EXPIRED"),
            "REJECTED", Set.of("UPLOADED"),
            "VERIFIED", Set.of("EXPIRED"),
            "EXPIRED", Set.of("UPLOADED"));

    public static void require(Map<String, Set<String>> machine, String what, String from, String to) {
        Set<String> allowed = machine.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new InvalidTransition(what + " cannot move from " + from + " to " + to);
        }
    }

    public static boolean allowed(Map<String, Set<String>> machine, String from, String to) {
        Set<String> allowed = machine.get(from);
        return allowed != null && allowed.contains(to);
    }

    private static Set<String> union(Set<String> base, String... more) {
        java.util.HashSet<String> s = new java.util.HashSet<>(base);
        s.addAll(Set.of(more));
        return Set.copyOf(s);
    }
}
