package io.meridian.businessday.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lease-based distributed locks in {@code batch_job_lock}. A lock is free when absent or its lease expired;
 * acquiring an expired lock increments the fencing token so a stale holder can be detected and rejected.
 * The holder can renew its lease; only the holder with the current token can release it.
 */
@Repository
public class LockStore {
    private final JdbcClient jdbc;

    public LockStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Acquires (or re-acquires, when already held by {@code holder}) the lock; returns the fencing token. */
    public Optional<Long> acquire(String lockKey, String holder, Duration lease) {
        return jdbc.sql("""
                insert into batch_job_lock as l (tenant_id, lock_key, holder, acquired_at, expires_at, fencing_token,
                                                 created_by, updated_by)
                values (?, ?, ?, now(), now() + make_interval(secs => ?), 1, ?, ?)
                on conflict (tenant_id, lock_key) do update
                   set holder = excluded.holder,
                       acquired_at = case when l.holder = excluded.holder and l.expires_at > now()
                                          then l.acquired_at else now() end,
                       expires_at = excluded.expires_at,
                       fencing_token = case when l.holder = excluded.holder and l.expires_at > now()
                                            then l.fencing_token else l.fencing_token + 1 end,
                       updated_by = excluded.updated_by,
                       force_released_at = case when l.holder = excluded.holder and l.expires_at > now()
                                                then l.force_released_at end,
                       force_released_by = case when l.holder = excluded.holder and l.expires_at > now()
                                                then l.force_released_by end,
                       force_release_reason = case when l.holder = excluded.holder and l.expires_at > now()
                                                   then l.force_release_reason end
                 where l.expires_at <= now() or l.holder = excluded.holder
                returning fencing_token""")
                .params(TenantContext.require(), lockKey, holder, (double) lease.toMillis() / 1000.0,
                        TenantContext.principal(), TenantContext.principal())
                .query(Long.class)
                .optional();
    }

    /** Extends the lease if still held with the given token. */
    public boolean renew(String lockKey, String holder, long token, Duration lease) {
        return jdbc.sql("""
                update batch_job_lock set expires_at = now() + make_interval(secs => ?)
                where lock_key = ? and holder = ? and fencing_token = ?""")
                .params((double) lease.toMillis() / 1000.0, lockKey, holder, token)
                .update() == 1;
    }

    public void release(String lockKey, String holder) {
        jdbc.sql("delete from batch_job_lock where lock_key = ? and holder = ?").params(lockKey, holder).update();
    }

    public Optional<String> lockRow(UUID id) {
        return jdbc.sql("select row_to_json(l)::text from batch_job_lock l where id = ? for update")
                .param(id).query(String.class).optional();
    }

    /** Expires the lease now and bumps the fencing token (the stale holder can no longer renew); returns the row. */
    public String forceRelease(UUID id, String by, String reason) {
        return jdbc.sql("""
                update batch_job_lock set expires_at = now(), fencing_token = fencing_token + 1,
                       force_released_at = now(), force_released_by = ?, force_release_reason = ?, updated_by = ?
                where id = ? returning row_to_json(batch_job_lock)::text""")
                .params(by, reason, by, id).query(String.class).single();
    }
}
