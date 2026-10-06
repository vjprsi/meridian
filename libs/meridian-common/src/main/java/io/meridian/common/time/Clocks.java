package io.meridian.common.time;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * "Now" at the precision PostgreSQL stores (microseconds, UTC). JVM clocks can be finer; PostgreSQL rounds the
 * extra digits, so a JVM timestamp written to the database can read back slightly in the future and break
 * "is it effective yet" comparisons. Use this instead of {@code OffsetDateTime.now()} for anything persisted.
 */
public final class Clocks {
    private Clocks() {}

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
