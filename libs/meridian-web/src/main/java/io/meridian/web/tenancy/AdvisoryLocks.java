package io.meridian.web.tenancy;

import io.meridian.common.error.ApiException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Cross-replica mutual exclusion for work that spans several transactions and remote calls (SEC-28: inbound rail
 * messages and credits, where "check for a duplicate, then process" must not interleave). Uses a PostgreSQL
 * session-level advisory lock held on a dedicated connection; waiting callers poll with {@code pg_try_advisory_lock}
 * and do not hold a connection between attempts (so waiters cannot starve the pool the lock holder needs). After
 * {@code wait} the caller gets 409 {@code in_progress}.
 */
public final class AdvisoryLocks {
    private final DataSource dataSource;

    public AdvisoryLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T withLock(String key, Duration wait, Supplier<T> work) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            try (Connection c = dataSource.getConnection()) {
                if (tryLock(c, key)) {
                    try {
                        return work.get();
                    } finally {
                        unlock(c, key);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("advisory lock failed", e);
            }
            if (System.nanoTime() > deadline) {
                throw ApiException.conflict("in_progress", "The same message is being processed; retry later");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("in_progress", "Interrupted while waiting for the same message");
            }
        }
    }

    private static boolean tryLock(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select pg_try_advisory_lock(hashtextextended(?, 0))")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
            ps.setString(1, key);
            ps.execute();
        }
    }
}
