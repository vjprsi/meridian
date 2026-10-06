package io.meridian.businessday.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Rules of the automatic (cross-tenant) EOD trigger: when a business date is due and what to do with it.
 * A business date is due once its local cut-off time has passed in the policy's time zone; the trigger then
 * starts EOD (OPEN / CUT_OFF date without any EOD run), or — after a fully successful EOD and only when the policy
 * allows it — rolls the date. Anything else is left to operators (a failed or partial EOD is never retried or
 * forced automatically), which makes the trigger idempotent per legal entity and business date.
 */
public final class EodTrigger {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    private EodTrigger() {}

    /** Effective trigger settings of one legal entity. */
    public record Policy(boolean enabled, LocalTime cutOff, ZoneId zone, boolean autoRoll, String source) {}

    /** Outcome of evaluating one legal entity. */
    public enum Decision {
        /** EOD was (or would be) started. */
        START,
        /** The business date was (or would be) rolled after a successful EOD. */
        ROLL,
        /** Cut-off time not reached yet. */
        NOT_DUE,
        /** Automatic EOD is disabled by policy. */
        DISABLED,
        /** EOD already ran (or runs) for this business date: nothing to do. */
        ALREADY_RAN,
        /** The tenant has no active EOD job definitions (install the standard pack). */
        NO_JOBS,
        /** Starting or rolling failed. */
        FAILED
    }

    /** The instant the business date becomes due: its cut-off time in the zone. */
    public static OffsetDateTime dueAt(LocalDate businessDate, LocalTime cutOff, ZoneId zone) {
        return businessDate.atTime(cutOff).atZone(zone).toOffsetDateTime();
    }

    /**
     * What to do with the current business date of a legal entity.
     *
     * @param state            business-day state
     * @param latestRunStatus  status of the latest EOD run of the date, null when none exists
     * @param hasActiveJobs    the tenant has active job definitions
     * @param due              the cut-off time has passed
     */
    public static Decision decide(Policy policy, BusinessDayState state, String latestRunStatus, boolean hasActiveJobs,
                                  boolean due) {
        if (!policy.enabled()) {
            return Decision.DISABLED;
        }
        if (!due) {
            return Decision.NOT_DUE;
        }
        if (latestRunStatus == null) {
            if (state != BusinessDayState.OPEN && state != BusinessDayState.CUT_OFF) {
                return Decision.ALREADY_RAN;
            }
            return hasActiveJobs ? Decision.START : Decision.NO_JOBS;
        }
        if (policy.autoRoll() && "SUCCEEDED".equals(latestRunStatus) && state == BusinessDayState.EOD_RUNNING) {
            return Decision.ROLL;
        }
        return Decision.ALREADY_RAN;
    }

    /** Parses a local cut-off time {@code HH:mm}. */
    public static LocalTime parseCutOff(String text) {
        try {
            return LocalTime.parse(text == null ? "" : text.trim(), HH_MM);
        } catch (DateTimeParseException e) {
            throw new DomainException(422, "invalid_cut_off_time", "cut_off_time must be a local time HH:mm, got " + text);
        }
    }

    /** Parses an IANA time zone id. */
    public static ZoneId parseZone(String text) {
        try {
            return ZoneId.of(text.trim());
        } catch (DateTimeException | NullPointerException e) {
            throw new DomainException(422, "invalid_time_zone", "time_zone must be an IANA time zone id, got " + text);
        }
    }
}
