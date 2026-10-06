package io.meridian.filegateway.infrastructure;

import io.meridian.common.error.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Row access by id as JSON (row_to_json), optionally locked, for this service's tables. */
@Repository
public class Rows {
    private static final Set<String> TABLES = Set.of("file_channel", "file_format_definition", "file_route",
            "inbound_file", "outbound_file", "file_transfer_log");

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

    public List<JsonNode> query(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).list().stream().map(json::readTree).toList();
    }

    public Optional<JsonNode> queryOne(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).optional().map(json::readTree);
    }

    /** Runs an UPDATE ... RETURNING row_to_json(...)::text and returns the row. */
    public String updateReturning(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    public int update(String sql, Object... params) {
        return jdbc.sql(sql).params(params).update();
    }

    private static void check(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("unknown table " + table);
        }
    }
}
