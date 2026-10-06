package io.meridian.webhook.domain;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * At-least-once retry policy: exponential-ish backoff 1m, 5m, 30m, 2h, 5h, 10h, 10h, 10h, 10h, 10h, 14h
 * (about 72 hours in total after the first attempt). A delivery is abandoned once the schedule is exhausted
 * or the next attempt would fall beyond 72 hours after the delivery was created.
 */
public final class RetrySchedule {
    public static final Duration HORIZON = Duration.ofHours(72);
    static final List<Duration> DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2),
            Duration.ofHours(5), Duration.ofHours(10), Duration.ofHours(10), Duration.ofHours(10),
            Duration.ofHours(10), Duration.ofHours(10), Duration.ofHours(14));

    private RetrySchedule() {}

    /**
     * @param attemptsMade attempts made so far, including the one that just failed (1-based)
     * @return when to try next, or empty when the delivery must be abandoned
     */
    public static Optional<OffsetDateTime> nextAttempt(int attemptsMade, OffsetDateTime createdAt, OffsetDateTime now) {
        if (attemptsMade < 1 || attemptsMade > DELAYS.size()) {
            return Optional.empty();
        }
        OffsetDateTime next = now.plus(DELAYS.get(attemptsMade - 1));
        if (next.isAfter(createdAt.plus(HORIZON).plusMinutes(30))) {
            return Optional.empty();
        }
        return Optional.of(next);
    }

    public static int maxAttempts() {
        return DELAYS.size() + 1;
    }
}
