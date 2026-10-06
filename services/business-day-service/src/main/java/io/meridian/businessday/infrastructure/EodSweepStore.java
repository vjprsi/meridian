package io.meridian.businessday.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Platform-global {@code eod_sweep} rows (no tenant, no row-level security). Timestamps come from the database
 * clock, which is also the clock the trigger evaluates cut-off times against.
 */
@Repository
public class EodSweepStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public EodSweepStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The database's current time (the single clock for due checks). */
    public OffsetDateTime dbNow() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single();
    }

    /**
     * Claims a sweep slot: inserts a RUNNING sweep unless one with the same slot key exists (another instance or an
     * earlier call already ran it). Returns the new sweep id, or empty when the slot was taken.
     */
    public Optional<UUID> claim(String trigger, String slotKey, OffsetDateTime asOf, String requestedBy, List<UUID> tenantIds) {
        String ids = tenantIds == null || tenantIds.isEmpty() ? null : json.writeValueAsString(tenantIds);
        return jdbc.sql("""
                insert into eod_sweep (trigger_source, slot_key, as_of, status, requested_by, tenant_ids, started_at,
                                       created_by, updated_by)
                values (?, ?, ?, 'RUNNING', ?, ?::jsonb, now(), ?, ?)
                on conflict (slot_key) do nothing
                returning id""")
                .params(trigger, slotKey, asOf, requestedBy, ids, requestedBy, requestedBy)
                .query(UUID.class).optional();
    }

    public Optional<String> bySlot(String slotKey) {
        return jdbc.sql("select row_to_json(s)::text from eod_sweep s where slot_key = ?").param(slotKey)
                .query(String.class).optional();
    }

    public String finish(UUID id, String status, int tenantsTotal, int tenantsFailed, int runsStarted, int datesRolled,
                         int entitiesSkipped, ArrayNode results, String error) {
        return jdbc.sql("""
                update eod_sweep set status = ?, tenants_total = ?, tenants_failed = ?, runs_started = ?, dates_rolled = ?,
                       entities_skipped = ?, results = ?::jsonb, error = ?, finished_at = now(), updated_at = now(),
                       row_version = row_version + 1
                where id = ? returning row_to_json(eod_sweep)::text""")
                .params(status, tenantsTotal, tenantsFailed, runsStarted, datesRolled, entitiesSkipped,
                        json.writeValueAsString(results), error, id)
                .query(String.class).single();
    }
}
