package io.meridian.cardauthorization.domain;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/** Start of the current spend-limit period, in UTC, for a given (database) time. */
public final class Periods {
    public static final OffsetDateTime EPOCH = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private Periods() {}

    public static OffsetDateTime start(String period, OffsetDateTime now) {
        OffsetDateTime day = now.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        return switch (period) {
            case "DAILY" -> day;
            case "WEEKLY" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "MONTHLY" -> day.withDayOfMonth(1);
            case "YEARLY" -> day.withDayOfYear(1);
            case "LIFETIME", "PER_TRANSACTION" -> EPOCH;
            default -> throw new IllegalArgumentException("unknown period " + period);
        };
    }

    /** Whether a counter is cumulative (PER_TRANSACTION limits only cap the single amount). */
    public static boolean cumulative(String period) {
        return !"PER_TRANSACTION".equals(period);
    }
}
