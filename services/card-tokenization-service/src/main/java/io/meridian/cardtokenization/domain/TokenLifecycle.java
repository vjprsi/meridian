package io.meridian.cardtokenization.domain;

import java.util.Map;
import java.util.Set;

/** Allowed network token status transitions; DEACTIVATED and DECLINED are terminal. */
public final class TokenLifecycle {
    private static final Map<String, Set<String>> NEXT = Map.of(
            "REQUESTED", Set.of("ACTIVE", "PENDING_VERIFICATION", "DECLINED"),
            "PENDING_VERIFICATION", Set.of("ACTIVE", "DECLINED", "DEACTIVATED"),
            "ACTIVE", Set.of("SUSPENDED", "DEACTIVATED"),
            "SUSPENDED", Set.of("ACTIVE", "DEACTIVATED"),
            "DEACTIVATED", Set.of(),
            "DECLINED", Set.of());

    /** Statuses that count against the per-card token limit and follow card status changes. */
    public static final Set<String> LIVE = Set.of("ACTIVE", "SUSPENDED", "PENDING_VERIFICATION", "REQUESTED");

    private TokenLifecycle() {}

    public static boolean allowed(String from, String to) {
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean terminal(String status) {
        return NEXT.getOrDefault(status, Set.of()).isEmpty();
    }

    /**
     * Token status a card status implies: FROZEN suspends, ACTIVE resumes, end-of-life card statuses deactivate;
     * null when the card status does not affect tokens (e.g. CREATED).
     */
    public static String targetForCardStatus(String cardStatus) {
        return switch (cardStatus) {
            case "FROZEN" -> "SUSPENDED";
            case "ACTIVE" -> "ACTIVE";
            case "BLOCKED", "LOST", "STOLEN", "CLOSED", "EXPIRED", "REPLACED" -> "DEACTIVATED";
            default -> null;
        };
    }

    /**
     * Status a scheme lifecycle notification moves the token to; null when it does not change the status
     * (PAN_UPDATED). Provisioning / step-up event types are not lifecycle notifications.
     */
    public static String targetForNetworkEvent(String eventType) {
        return switch (eventType) {
            case "ACTIVATED", "RESUMED" -> "ACTIVE";
            case "SUSPENDED" -> "SUSPENDED";
            case "DEACTIVATED" -> "DEACTIVATED";
            case "PAN_UPDATED" -> null;
            default -> throw new IllegalArgumentException("not a lifecycle notification: " + eventType);
        };
    }

    /** card_token_event type recorded for a transition into {@code to}. */
    public static String eventFor(String from, String to) {
        return switch (to) {
            case "ACTIVE" -> "SUSPENDED".equals(from) ? "RESUMED" : "ACTIVATED";
            case "SUSPENDED" -> "SUSPENDED";
            case "DEACTIVATED" -> "DEACTIVATED";
            case "DECLINED" -> "PROVISIONING_DECLINED";
            default -> throw new IllegalArgumentException(to);
        };
    }
}
