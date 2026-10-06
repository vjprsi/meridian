package io.meridian.rewards.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Row-level access used by the use cases: rows travel as JSON objects (PostgreSQL {@code row_to_json}) so that the
 * column name is the mapping, exactly like the generic CRUD engine. Table names passed in are internal constants;
 * every value is a bind parameter. Row-level security scopes all statements to the current tenant.
 */
@Repository
public class Rows {
    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;

    public Rows(JdbcClient jdbc, CrudEngine crud, JsonMapper json) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** Reads and locks one row for the rest of the transaction; 404 when absent (or owned by another tenant). */
    public ObjectNode lock(String table, UUID id) {
        return query("select row_to_json(t)::text from " + table + " t where t.id = ? for update", id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<ObjectNode> find(String table, UUID id) {
        return query("select row_to_json(t)::text from " + table + " t where t.id = ?", id).stream().findFirst();
    }

    public ObjectNode get(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Applies the given column values (NullNode clears a column) and returns the updated row. */
    public ObjectNode update(String table, UUID id, ObjectNode values) {
        String row = crud.updateRow(table, Map.of("id", id), values, null)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
        return tree(row);
    }

    /** Optimistic update: empty when the row version no longer matches. */
    public Optional<ObjectNode> update(String table, UUID id, ObjectNode values, int expectedVersion) {
        return crud.updateRow(table, Map.of("id", id), values, expectedVersion).map(this::tree);
    }

    public ObjectNode insert(String table, ObjectNode values) {
        return tree(crud.insert(table, values));
    }

    public List<ObjectNode> query(String sql, Object... args) {
        return jdbc.sql(sql).params(java.util.Arrays.asList(args)).query(String.class).list().stream().map(this::tree).toList();
    }

    public Optional<ObjectNode> queryOne(String sql, Object... args) {
        return query(sql, args).stream().findFirst();
    }

    public int execute(String sql, Object... args) {
        return jdbc.sql(sql).params(java.util.Arrays.asList(args)).update();
    }

    /** The database clock (transaction start time): the single clock for every SLA and deadline comparison. */
    public java.time.OffsetDateTime now() {
        return jdbc.sql("select now()").query(java.time.OffsetDateTime.class).single()
                .withOffsetSameInstant(java.time.ZoneOffset.UTC);
    }

    public int count(String sql, Object... args) {
        Integer n = jdbc.sql(sql).params(java.util.Arrays.asList(args)).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }

    public ObjectNode tree(String rowJson) {
        return (ObjectNode) json.readTree(rowJson);
    }

    /**
     * Keyset page over an arbitrary row query. {@code baseSql} must select {@code row_to_json(t)::text} from an
     * alias {@code t} and end with its where clause; ordering and limits are appended here.
     */
    public JsonNode page(String baseSql, List<Object> args, Integer limit, String cursor) {
        int n = Cursor.limit(limit);
        StringBuilder sql = new StringBuilder(baseSql);
        List<Object> params = new ArrayList<>(args);
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" and t.id < ?");
            params.add(Cursor.decode(cursor));
        }
        sql.append(" order by t.id desc limit ?");
        params.add(n + 1);
        List<ObjectNode> rows = query(sql.toString(), params.toArray());
        boolean more = rows.size() > n;
        List<ObjectNode> data = more ? rows.subList(0, n) : rows;
        ObjectNode page = json.createObjectNode();
        page.set("data", json.valueToTree(data));
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", Cursor.encode(UUID.fromString(data.getLast().get("id").asString())));
        } else {
            page.putNull("next_cursor");
        }
        return page;
    }

    public <T> T read(JsonNode node, Class<T> type) {
        return json.treeToValue(node, type);
    }
}
