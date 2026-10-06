package io.meridian.card.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/** Physical card order state machine (issuer actions and bureau status updates). */
public final class OrderLifecycle {
    private OrderLifecycle() {}

    public static final Set<String> TERMINAL = Set.of("DELIVERED", "RETURNED", "FAILED", "CANCELLED");

    /** Bureau-reported moves (FAILED is allowed from any non-terminal status). */
    private static final Map<String, Set<String>> BUREAU = Map.of(
            "SENT_TO_BUREAU", Set.of("IN_PRODUCTION", "SHIPPED"),
            "IN_PRODUCTION", Set.of("SHIPPED"),
            "SHIPPED", Set.of("DELIVERED", "RETURNED"));

    public static String submit(String from) {
        if (!"PENDING".equals(from)) {
            throw invalid("submit", from);
        }
        return "SENT_TO_BUREAU";
    }

    public static String cancel(String from) {
        if (!"PENDING".equals(from)) {
            throw invalid("cancel", from);
        }
        return "CANCELLED";
    }

    public static void reship(String from) {
        if (!"RETURNED".equals(from)) {
            throw invalid("reship", from);
        }
    }

    /**
     * Applies a bureau status update. Returns {@code null} when the order already has that status (duplicate
     * callback, idempotent no-op).
     */
    public static String bureauUpdate(String from, String to) {
        if (from.equals(to)) {
            return null;
        }
        if ("FAILED".equals(to) && !TERMINAL.contains(from) && !"PENDING".equals(from)) {
            return to;
        }
        if (BUREAU.getOrDefault(from, Set.of()).contains(to)) {
            return to;
        }
        throw ApiException.conflict("invalid_transition", "Bureau update " + from + " -> " + to + " is not allowed");
    }

    private static ApiException invalid(String action, String from) {
        return ApiException.conflict("invalid_transition", "Cannot " + action + " an order in status " + from);
    }
}
