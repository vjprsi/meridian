package io.meridian.reconciliation.infrastructure;

import io.meridian.common.error.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SQL beyond generic CRUD for reconciliation. All values are bind parameters; table names come from a fixed set. */
@Repository
public class ReconStore {
    private static final Set<String> TABLES = Set.of("recon_source", "recon_file", "recon_record", "recon_rule",
            "recon_run", "recon_match", "recon_exception");

    /** Matches that are in force: not compensating records and not undone by a later compensating record. */
    public static final String ACTIVE_MATCHES = """
            select m.* from recon_match m
            where m.unmatched_at is null and not exists (
                select 1 from recon_match c where c.unmatched_at is not null
                  and c.external_record_ids = m.external_record_ids and c.internal_entry_ids = m.internal_entry_ids
                  and (c.created_at > m.created_at or (c.created_at = m.created_at and c.id > m.id)))""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ReconStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<JsonNode> find(String table, UUID id) {
        return row("select row_to_json(t)::text from " + table(table) + " t where t.id = ?", id);
    }

    public Optional<JsonNode> lock(String table, UUID id) {
        return row("select row_to_json(t)::text from " + table(table) + " t where t.id = ? for update", id);
    }

    public JsonNode require(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public JsonNode requireLocked(String table, UUID id) {
        return lock(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<JsonNode> row(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).optional().map(json::readTree);
    }

    public List<JsonNode> rows(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).list().stream().map(json::readTree).toList();
    }

    public List<UUID> ids(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(UUID.class).list();
    }

    public long count(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(Long.class).single();
    }

    public int update(String sql, Object... args) {
        return jdbc.sql(sql).params(args).update();
    }

    /** Transaction-scoped advisory lock serialising writers on one logical key (e.g. one source's matching). */
    public void advisoryLock(String key) {
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) x").param(key)
                .query(Integer.class).list();
    }

    /** External record ids currently covered by an active match. */
    public Set<UUID> matchedExternalIds() {
        return Set.copyOf(ids("select (jsonb_array_elements_text(m.external_record_ids))::uuid from ("
                + ACTIVE_MATCHES + ") m"));
    }

    /** Internal (ledger) entry ids currently covered by an active match. */
    public Set<UUID> matchedInternalIds() {
        return Set.copyOf(ids("select (jsonb_array_elements_text(m.internal_entry_ids))::uuid from ("
                + ACTIVE_MATCHES + ") m"));
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
