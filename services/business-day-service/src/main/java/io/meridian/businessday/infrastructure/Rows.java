package io.meridian.businessday.infrastructure;

import io.meridian.common.error.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Row access by id as JSON (row_to_json), optionally locked, for the service's own tables. */
@Repository
public class Rows {
    private static final Set<String> TABLES = Set.of("business_day", "eod_job_definition", "eod_run", "eod_job_run",
            "date_roll", "job_schedule", "job_schedule_run", "batch_job_lock");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Rows(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<String> text(String table, UUID id, boolean lock) {
        check(table);
        return jdbc.sql("select row_to_json(t)::text from " + table + " t where id = ?" + (lock ? " for update" : ""))
                .param(id).query(String.class).optional();
    }

    public String requireText(String table, UUID id, boolean lock) {
        return text(table, id, lock).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public JsonNode require(String table, UUID id, boolean lock) {
        return json.readTree(requireText(table, id, lock));
    }

    public JsonNode tree(String rowJson) {
        return json.readTree(rowJson);
    }

    /** Rows of a query that selects {@code row_to_json(...)::text}. */
    public List<JsonNode> query(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).list().stream().map(json::readTree).toList();
    }

    public Optional<JsonNode> queryOne(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).optional().map(json::readTree);
    }

    public int update(String sql, Object... params) {
        return jdbc.sql(sql).params(params).update();
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    private static void check(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("unknown table " + table);
        }
    }
}
