package io.meridian.aiplatform.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** SQL beyond generic CRUD. All values are bind parameters; table and column names come from fixed sets. */
@Repository
public class Store {
    private static final Set<String> TABLES = Set.of("ml_model", "ml_model_version", "model_deployment", "model_monitoring_metric",
            "ml_feature_definition", "copilot_definition", "copilot_interaction", "ai_copilot_feedback", "llm_gateway_policy", "ai_job_run");
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");

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

    public Optional<JsonNode> updateReturning(String sql, Object... args) {
        return row(sql, args);
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }

    /** Current database time as epoch seconds (one clock for issue and expiry checks). */
    public long nowEpoch() {
        return scalar("select extract(epoch from now())::bigint", Long.class).orElseThrow();
    }

    /**
     * Keyset page over a table with equality filters plus a fixed visibility condition (e.g. owner or shared).
     * Returns the standard page shape {data, has_more, next_cursor}.
     */
    public <T> T page(String table, String condition, List<Object> conditionArgs, Map<String, Object> filters,
                      Integer limit, String cursor, Class<T> pageType) {
        int max = Cursor.limit(limit);
        StringBuilder sql = new StringBuilder("select row_to_json(t)::text from " + table(table) + " t where " + condition);
        List<Object> args = new ArrayList<>(conditionArgs);
        filters.forEach((k, v) -> {
            if (v != null) {
                if (!IDENT.matcher(k).matches()) {
                    throw new IllegalArgumentException("bad column " + k);
                }
                sql.append(" and t.").append(k).append(" = ?");
                args.add(v);
            }
        });
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" and t.id < ?");
            args.add(Cursor.decode(cursor));
        }
        sql.append(" order by t.id desc limit ?");
        args.add(max + 1);
        List<String> rows = jdbc.sql(sql.toString()).params(args).query(String.class).list();
        boolean more = rows.size() > max;
        List<String> data = more ? rows.subList(0, max) : rows;
        String next = more ? Cursor.encode(UUID.fromString(json.readTree(data.getLast()).get("id").asString())) : null;
        String body = "{\"data\":[" + String.join(",", data) + "],\"has_more\":" + more + ",\"next_cursor\":"
                + (next == null ? "null" : "\"" + next + "\"") + "}";
        return json.readValue(body, pageType);
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
