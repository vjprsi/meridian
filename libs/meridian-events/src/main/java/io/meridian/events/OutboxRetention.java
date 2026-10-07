package io.meridian.events;

import io.meridian.common.cell.CellRole;
import io.meridian.common.time.Clocks;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * MR-GAME-DAY: purges relayed outbox rows once they are older than the replay window
 * ({@code meridian.events.retention.window}, default 72h), in bounded batches so a large backlog never holds one long
 * transaction. Unpublished rows are never purged. Skipped in a warm standby cell (read-only replica).
 */
public class OutboxRetention {
    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

    private final JdbcClient jdbc;
    private final Duration window;
    private final int batchSize;
    private final CellRole role;
    private io.micrometer.core.instrument.Counter purged;

    public OutboxRetention(JdbcClient jdbc, Duration window, int batchSize, CellRole role) {
        this.jdbc = jdbc;
        this.window = window;
        this.batchSize = Math.max(1, batchSize);
        this.role = role;
    }

    public void bindMetrics(io.micrometer.core.instrument.MeterRegistry meters) {
        purged = meters.counter("meridian.outbox.purged");
    }

    @Scheduled(fixedDelayString = "${meridian.events.retention.purge-interval:PT15M}",
            initialDelayString = "${meridian.events.retention.purge-initial-delay:PT1M}")
    public void scheduledPurge() {
        if (role != null && !role.active()) {
            return;
        }
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Outbox purge failed; will retry: {}", e.toString());
        }
    }

    /** Deletes relayed rows published before now - window; returns how many. */
    public long purge() {
        return purgeBefore(Clocks.now().minus(window));
    }

    long purgeBefore(OffsetDateTime cutoff) {
        long total = 0;
        int n;
        do {
            n = jdbc.sql("""
                    delete from outbox where id in (
                        select id from outbox where published_at is not null and published_at < ?
                        order by published_at limit ?)""")
                    .params(cutoff, batchSize).update();
            total += n;
        } while (n == batchSize);
        if (total > 0) {
            if (purged != null) {
                purged.increment(total);
            }
            log.info("Purged {} relayed outbox events published before {}", total, cutoff);
        }
        return total;
    }

    public Duration window() {
        return window;
    }
}
