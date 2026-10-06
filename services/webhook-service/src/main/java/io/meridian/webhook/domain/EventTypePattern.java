package io.meridian.webhook.domain;

import java.util.regex.Pattern;

/**
 * Event-type subscription patterns: an exact type ({@code payments.payment.settled}), a prefix wildcard
 * ({@code payments.*} matches every type under {@code payments.}) or {@code *} (everything).
 */
public final class EventTypePattern {
    private static final Pattern VALID =
            Pattern.compile("\\*|[a-z0-9_]+(\\.[a-z0-9_]+)*(\\.\\*)?");

    private EventTypePattern() {}

    public static boolean isValid(String pattern) {
        return pattern != null && pattern.length() <= 128 && VALID.matcher(pattern).matches();
    }

    public static boolean matches(String pattern, String eventType) {
        if (pattern == null || eventType == null) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith(".*")) {
            return eventType.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(eventType);
    }

    /** A concrete event type (no wildcard) as used by test sends and ingested events. */
    public static boolean isConcreteType(String eventType) {
        return isValid(eventType) && !eventType.contains("*");
    }
}
