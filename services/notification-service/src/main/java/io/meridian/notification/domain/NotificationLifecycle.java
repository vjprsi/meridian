package io.meridian.notification.domain;

import java.util.Map;
import java.util.Set;

/**
 * Notification status state machine.
 *
 * <pre>
 * QUEUED ──dispatch──> SENT ──receipt──> DELIVERED ──read──> READ
 *   │  └─all providers failed, budget left──> QUEUED (retry with backoff)
 *   ├──> FAILED (permanent / retries exhausted) ──resend──> QUEUED
 *   ├──> SUPPRESSED (opted out, or cancelled)
 *   └──> EXPIRED (past expires_at) ──resend──> QUEUED
 * SCHEDULED ──due──> QUEUED / ──cancel──> SUPPRESSED / ──> EXPIRED
 * </pre>
 * The contract has no CANCELLED status, so a cancelled notification becomes SUPPRESSED with failure_reason
 * {@code cancelled}.
 */
public final class NotificationLifecycle {
    private NotificationLifecycle() {}

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "QUEUED", Set.of("QUEUED", "SENT", "DELIVERED", "FAILED", "SUPPRESSED", "EXPIRED", "SCHEDULED"),
            "SCHEDULED", Set.of("QUEUED", "SENT", "DELIVERED", "FAILED", "SUPPRESSED", "EXPIRED"),
            "SENT", Set.of("DELIVERED", "FAILED", "READ"),
            "DELIVERED", Set.of("READ"),
            "READ", Set.of(),
            "FAILED", Set.of("QUEUED"),
            "EXPIRED", Set.of("QUEUED"),
            "SUPPRESSED", Set.of());

    public static boolean canMove(String from, String to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean cancellable(String status) {
        return "QUEUED".equals(status) || "SCHEDULED".equals(status);
    }

    public static boolean resendable(String status) {
        return "FAILED".equals(status) || "EXPIRED".equals(status);
    }

    public static boolean readable(String status) {
        return "SENT".equals(status) || "DELIVERED".equals(status);
    }

    /** Maps provider receipt vocabularies onto our statuses; null if unknown. */
    public static String receiptStatus(String providerStatus) {
        if (providerStatus == null) {
            return null;
        }
        return switch (providerStatus.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DELIVERED", "DELIVRD", "DELIVERY", "SUCCESS" -> "DELIVERED";
            case "FAILED", "UNDELIVERED", "UNDELIV", "REJECTED", "BOUNCED", "BOUNCE", "EXPIRED", "ERROR" -> "FAILED";
            case "READ", "OPENED", "SEEN" -> "READ";
            case "SENT", "ACCEPTED", "QUEUED" -> "SENT";
            default -> null;
        };
    }
}
