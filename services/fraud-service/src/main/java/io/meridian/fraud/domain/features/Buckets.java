package io.meridian.fraud.domain.features;

import java.time.Duration;
import java.time.Instant;

/**
 * Time buckets of the feature store and the exact meaning of its sliding windows.
 *
 * <p>Counters are kept in 5-minute buckets ({@link #M5}, used for the 1 h window) and hourly buckets ({@link #H1},
 * used for the 24 h and 7 d windows). A window of length {@code W} at time {@code now} covers the buckets whose start
 * is at or after {@code floor(now) - (W - bucket)}: the current, partly elapsed bucket plus the {@code W/bucket - 1}
 * previous ones. An event at time {@code t <= now} is therefore counted iff
 * {@code floor(t) >= floor(now) - W + bucket}; the window effectively spans between {@code W - bucket} and
 * {@code W} back from now (55-60 min, 23-24 h, 167-168 h).
 */
public final class Buckets {
    public static final Duration M5 = Duration.ofMinutes(5);
    public static final Duration H1 = Duration.ofHours(1);

    /** Retention of 5-minute buckets (only the 1 h window reads them). */
    public static final Duration M5_RETENTION = Duration.ofHours(2);
    /** Retention of hourly buckets (7 d window plus a day of slack). */
    public static final Duration H1_RETENTION = Duration.ofDays(8);

    public enum Window {
        H1_WINDOW("1h", Duration.ofHours(1), M5),
        H24_WINDOW("24h", Duration.ofHours(24), H1),
        D7_WINDOW("7d", Duration.ofDays(7), H1);

        public final String suffix;
        public final Duration length;
        public final Duration bucket;

        Window(String suffix, Duration length, Duration bucket) {
            this.suffix = suffix;
            this.length = length;
            this.bucket = bucket;
        }

        /** First bucket start included in the window at {@code now}. */
        public Instant start(Instant now) {
            return floor(now, bucket).minus(length).plus(bucket);
        }

        public String granularity() {
            return bucket.equals(M5) ? "M5" : "H1";
        }

        /** Whether an event at {@code t} (not after {@code now}) is inside the window. */
        public boolean contains(Instant t, Instant now) {
            return !t.isAfter(now) && !floor(t, bucket).isBefore(start(now));
        }
    }

    private Buckets() {}

    public static Instant floor(Instant t, Duration bucket) {
        long s = bucket.getSeconds();
        long e = t.getEpochSecond();
        return Instant.ofEpochSecond(Math.floorDiv(e, s) * s);
    }
}
