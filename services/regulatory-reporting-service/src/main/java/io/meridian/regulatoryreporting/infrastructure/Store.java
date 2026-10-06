package io.meridian.regulatoryreporting.infrastructure;

import io.meridian.common.error.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** SQL beyond generic CRUD. All values are bind parameters; table names come from a fixed set. */
@Repository
public class Store {
    private static final Set<String> TABLES = Set.of("report_definition", "report_definition_version", "report_schedule",
            "report_run", "report_cell", "report_output", "report_validation_result", "report_adjustment",
            "report_submission", "crs_fatca_record", "report_job_run");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Store(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<JsonNode> find(String table, UUID id) {
        return row("select row_to_json(t)::text from " + table(table) + " t where t.id = ?", id);
    }

    public JsonNode require(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Reads a row with a row lock held until the end of the transaction. */
    public JsonNode requireLocked(String table, UUID id) {
        return row("select row_to_json(t)::text from " + table(table) + " t where t.id = ? for update", id)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<JsonNode> row(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).optional().map(json::readTree);
    }

    public List<JsonNode> rows(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).list().stream().map(json::readTree).toList();
    }

    public long count(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(Long.class).single();
    }

    public <T> Optional<T> scalar(String sql, Class<T> type, Object... args) {
        return jdbc.sql(sql).params(args).query(type).optional();
    }

    public int update(String sql, Object... args) {
        return jdbc.sql(sql).params(args).update();
    }

    /** Updates and returns the new row (the statement must end with {@code returning row_to_json(t)::text}). */
    public JsonNode updateReturning(String sql, Object... args) {
        return row(sql, args).orElseThrow(() -> new IllegalStateException("update returned no row"));
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
