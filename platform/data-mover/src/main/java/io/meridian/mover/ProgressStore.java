package io.meridian.mover;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The mover's own progress database (resumability and the verification manifest). */
public class ProgressStore {
    static final String OPEN = "('PENDING', 'COPYING', 'VERIFYING', 'CONFIRMING')";

    public record Run(UUID moveId, UUID tenantId, String fromCell, String toCell, String strategy, JsonNode request,
                      String status, boolean planned, boolean docsDone, String docsLastKey, long docsCopied,
                      long docsBytes, boolean docsVerified, String docsDigest, String checkpoint, int attempts,
                      String lastError) {}

    public record Item(String service, String table, int ord, String phase, UUID lastId, long rowsCopied,
                       JsonNode deferred, Long sourceRows, Long targetRows, String sourceDigest, String targetDigest) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ProgressStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Records a copy request; false when the move is already known (redelivered event: idempotent). */
    public boolean register(UUID moveId, UUID tenantId, String fromCell, String toCell, String strategy, JsonNode request,
                            OffsetDateTime notBefore) {
        return jdbc.sql("""
                insert into move_run (move_id, tenant_id, from_cell, to_cell, strategy, request, not_before)
                values (?, ?, ?, ?, ?, ?::jsonb, ?) on conflict (move_id) do nothing""")
                .params(moveId, tenantId, fromCell, toCell, strategy, json.writeValueAsString(request), notBefore)
                .update() == 1;
    }

    /** Runs that are open, due and not leased by a live instance. */
    public List<UUID> due() {
        return jdbc.sql("select move_id from move_run where status in " + OPEN
                        + " and not_before <= now() and (lease_until is null or lease_until < now()) order by created_at")
                .query(UUID.class).list();
    }

    /** Takes (or renews) the lease of a due run; empty when another instance holds it or it is not due / open. */
    public Optional<Run> claim(UUID moveId, String owner, Duration lease) {
        int n = jdbc.sql("update move_run set owner = ?, lease_until = now() + ?::interval, attempts = attempts + 1, "
                        + "updated_at = now() where move_id = ? and status in " + OPEN
                        + " and not_before <= now() and (lease_until is null or lease_until < now() or owner = ?)")
                .params(owner, lease.toSeconds() + " seconds", moveId, owner).update();
        return n == 1 ? run(moveId) : Optional.empty();
    }

    public void heartbeat(UUID moveId, String owner, Duration lease) {
        jdbc.sql("update move_run set lease_until = now() + ?::interval, updated_at = now() where move_id = ? and owner = ?")
                .params(lease.toSeconds() + " seconds", moveId, owner).update();
    }

    /** Gives the run back (transient failure or wait): another tick (any instance) resumes it. */
    public void release(UUID moveId, String error, OffsetDateTime notBefore) {
        jdbc.sql("update move_run set owner = null, lease_until = null, last_error = ?, "
                        + "not_before = greatest(not_before, coalesce(?, not_before)), updated_at = now() where move_id = ?")
                .params(error, notBefore, moveId).update();
    }

    public void status(UUID moveId, String status, String error) {
        jdbc.sql("update move_run set status = ?, last_error = coalesce(?, last_error), updated_at = now(), "
                        + "completed_at = case when ? in ('COMPLETED', 'FAILED', 'CANCELLED') then now() else completed_at end, "
                        + "owner = case when ? in ('COMPLETED', 'FAILED', 'CANCELLED') then null else owner end, "
                        + "lease_until = case when ? in ('COMPLETED', 'FAILED', 'CANCELLED') then null else lease_until end "
                        + "where move_id = ?")
                .params(status, error, status, status, status, moveId).update();
    }

    /** Operator retry of a FAILED run (after fixing the cause): back to COPYING; copied pages are skipped. */
    public boolean retry(UUID moveId) {
        return jdbc.sql("""
                update move_run set status = 'COPYING', owner = null, lease_until = null, completed_at = null,
                       updated_at = now() where move_id = ? and status = 'FAILED'""").param(moveId).update() == 1;
    }

    /** Latest runs for the operator view. */
    public List<Run> recent(int limit) {
        return jdbc.sql("select move_id from move_run order by created_at desc limit ?").param(limit)
                .query(UUID.class).list().stream().map(id -> run(id).orElseThrow()).toList();
    }

    public Optional<Run> run(UUID moveId) {
        return jdbc.sql("""
                select move_id, tenant_id, from_cell, to_cell, strategy, request::text, status, planned, docs_done,
                       docs_last_key, docs_copied, docs_bytes, docs_verified, docs_digest, checkpoint, attempts, last_error
                from move_run where move_id = ?""").param(moveId)
                .query((rs, i) -> new Run(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), json.readTree(rs.getString(6)), rs.getString(7),
                        rs.getBoolean(8), rs.getBoolean(9), rs.getString(10), rs.getLong(11), rs.getLong(12),
                        rs.getBoolean(13), rs.getString(14), rs.getString(15), rs.getInt(16), rs.getString(17)))
                .optional();
    }

    // ------------------------------------------------------------------ items

    public void plan(UUID moveId, List<Item> items) {
        for (Item it : items) {
            jdbc.sql("""
                    insert into move_item (move_id, service, table_name, ord) values (?, ?, ?, ?)
                    on conflict do nothing""").params(moveId, it.service(), it.table(), it.ord()).update();
        }
        jdbc.sql("update move_run set planned = true, updated_at = now() where move_id = ?").param(moveId).update();
    }

    public List<Item> items(UUID moveId) {
        return jdbc.sql("""
                select service, table_name, ord, phase, last_id, rows_copied, deferred::text, source_rows, target_rows,
                       source_digest, target_digest
                from move_item where move_id = ? order by service, ord""").param(moveId)
                .query((rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getLong(6), json.readTree(rs.getString(7)),
                        (Long) rs.getObject(8), (Long) rs.getObject(9), rs.getString(10), rs.getString(11)))
                .list();
    }

    /** After a page: the copy position, rows imported and rows waiting for a parent (kept for the retry pass). */
    public void copied(UUID moveId, String service, String table, UUID lastId, long inserted, JsonNode deferred) {
        jdbc.sql("""
                update move_item set last_id = coalesce(?, last_id), rows_copied = rows_copied + ?, deferred = ?::jsonb,
                       updated_at = now() where move_id = ? and service = ? and table_name = ?""")
                .params(lastId, inserted, json.writeValueAsString(deferred), moveId, service, table).update();
    }

    public void phase(UUID moveId, String service, String table, String phase) {
        jdbc.sql("update move_item set phase = ?, updated_at = now() where move_id = ? and service = ? and table_name = ?")
                .params(phase, moveId, service, table).update();
    }

    public void verified(UUID moveId, String service, String table, long sourceRows, long targetRows, String sourceDigest,
                         String targetDigest, boolean match) {
        jdbc.sql("""
                update move_item set source_rows = ?, target_rows = ?, source_digest = ?, target_digest = ?,
                       phase = case when ? then 'VERIFIED' else phase end, updated_at = now()
                where move_id = ? and service = ? and table_name = ?""")
                .params(sourceRows, targetRows, sourceDigest, targetDigest, match, moveId, service, table).update();
    }

    // ------------------------------------------------------------------ documents and completion

    public void docsProgress(UUID moveId, String lastKey, long objects, long bytes, boolean done) {
        jdbc.sql("""
                update move_run set docs_last_key = ?, docs_copied = docs_copied + ?, docs_bytes = docs_bytes + ?,
                       docs_done = ?, updated_at = now() where move_id = ?""")
                .params(lastKey, objects, bytes, done, moveId).update();
    }

    public void docsVerified(UUID moveId, String digest) {
        jdbc.sql("update move_run set docs_verified = true, docs_digest = ?, updated_at = now() where move_id = ?")
                .params(digest, moveId).update();
    }

    public void checkpoint(UUID moveId, String checkpoint, JsonNode manifest) {
        jdbc.sql("update move_run set checkpoint = ?, manifest = ?::jsonb, updated_at = now() where move_id = ?")
                .params(checkpoint, json.writeValueAsString(manifest), moveId).update();
    }
}
