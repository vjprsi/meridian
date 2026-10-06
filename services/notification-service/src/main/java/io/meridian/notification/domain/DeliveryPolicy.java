package io.meridian.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Category rules, quiet hours, destination formats and retry backoff. Pure functions. */
public final class DeliveryPolicy {
    private DeliveryPolicy() {}

    /** Categories a recipient can never opt out of. */
    public static final Set<String> MANDATORY_CATEGORIES = Set.of("SECURITY", "REGULATORY");
    /** Categories deferred during the recipient's quiet hours (time-critical ones are sent immediately). */
    public static final Set<String> QUIET_HOURS_CATEGORIES = Set.of("MARKETING", "SERVICE", "COLLECTIONS");

    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    public static boolean mandatory(String category) {
        return MANDATORY_CATEGORIES.contains(category);
    }

    public static boolean respectsQuietHours(String category) {
        return QUIET_HOURS_CATEGORIES.contains(category);
    }

    public static boolean validClock(String hhmm) {
        return hhmm != null && HH_MM.matcher(hhmm).matches();
    }

    public static Optional<ZoneId> zone(String tz) {
        if (tz == null || tz.isBlank()) {
            return Optional.of(ZoneId.of("UTC"));
        }
        try {
            return Optional.of(ZoneId.of(tz));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * If {@code now} falls inside the quiet window [start, end) in the recipient's zone, returns the instant the
     * window ends; otherwise empty. Windows may span midnight (22:00-07:00). Equal start and end means no window.
     */
    public static Optional<Instant> quietUntil(Instant now, String start, String end, String timezone) {
        if (!validClock(start) || !validClock(end)) {
            return Optional.empty();
        }
        ZoneId zoneId = zone(timezone).orElse(ZoneId.of("UTC"));
        LocalTime s = LocalTime.parse(start);
        LocalTime e = LocalTime.parse(end);
        if (s.equals(e)) {
            return Optional.empty();
        }
        ZonedDateTime local = now.atZone(zoneId);
        LocalTime t = local.toLocalTime();
        boolean inside;
        if (s.isBefore(e)) {
            inside = !t.isBefore(s) && t.isBefore(e);
        } else {
            inside = !t.isBefore(s) || t.isBefore(e);
        }
        if (!inside) {
            return Optional.empty();
        }
        LocalDateTime endAt = local.toLocalDate().atTime(e);
        if (!endAt.isAfter(local.toLocalDateTime())) {
            endAt = endAt.plusDays(1);
        }
        return Optional.of(endAt.atZone(zoneId).toInstant());
    }

    /** Null when valid, else a reason. */
    public static String destinationProblem(String channel, String destination) {
        if (destination == null || destination.isBlank()) {
            return "destination is required";
        }
        return switch (channel) {
            case "EMAIL" -> EMAIL.matcher(destination).matches() ? null : "EMAIL destination must be an email address";
            case "SMS", "WHATSAPP", "VOICE", "USSD" ->
                    E164.matcher(destination).matches() ? null : channel + " destination must be an E.164 phone number";
            default -> null;
        };
    }

    /** Exponential backoff: base * 2^(attempt-1), capped. */
    public static Duration backoff(int attemptNo, Duration base, Duration cap) {
        int exp = Math.max(0, Math.min(attemptNo - 1, 20));
        Duration d = base.multipliedBy(1L << exp);
        return d.compareTo(cap) > 0 ? cap : d;
    }

    /** Locale candidates for template lookup: exact, language only, then en. */
    public static java.util.List<String> localeFallbacks(String locale) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (locale != null && !locale.isBlank()) {
            out.add(locale);
            String lang = locale.split("[-_]")[0].toLowerCase(Locale.ROOT);
            if (!out.contains(lang)) {
                out.add(lang);
            }
        }
        if (!out.contains("en")) {
            out.add("en");
        }
        return out;
    }

    public static boolean parsableInstant(String s) {
        try {
            java.time.OffsetDateTime.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
