package io.meridian.businesschannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The channel's own tables (business users, entitlements, approval rules, payments, approvals, owned objects): rows as
 * JSON (snake_case, as the API returns them), parameterised SQL only. Tenant isolation is PostgreSQL row-level
 * security; every query also filters by the business, so one business never sees another's rows.
 */
@Component
public class Rows {
    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;

    public Rows(JdbcClient jdbc, CrudEngine crud, JsonMapper json) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }

    public JsonMapper json() {
        return json;
    }

    public ObjectNode insert(String table, ObjectNode values) {
        return (ObjectNode) json.readTree(crud.insert(table, values));
    }

    /** Applies column changes to one row (read-only columns included: the channel writes them itself). */
    public ObjectNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null).map(s -> (ObjectNode) json.readTree(s))
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** One row of the business by id. */
    public Optional<ObjectNode> find(String table, UUID business, UUID id) {
        return one("select row_to_json(t)::text from " + table + " t where t.id = ? and t.business_customer_id = ?",
                id, business);
    }

    /** One row of the business by id, locked for update until the transaction ends. */
    public Optional<ObjectNode> lock(String table, UUID business, UUID id) {
        return one("select row_to_json(t)::text from " + table
                + " t where t.id = ? and t.business_customer_id = ? for update", id, business);
    }

    public Optional<ObjectNode> one(String sql, Object... args) {
        return jdbc.sql(sql).params(Arrays.asList(args)).query(String.class).optional()
                .map(s -> (ObjectNode) json.readTree(s));
    }

    public List<ObjectNode> list(String sql, Object... args) {
        List<ObjectNode> out = new ArrayList<>();
        for (String s : jdbc.sql(sql).params(Arrays.asList(args)).query(String.class).list()) {
            out.add((ObjectNode) json.readTree(s));
        }
        return out;
    }

    public int execute(String sql, Object... args) {
        return jdbc.sql(sql).params(Arrays.asList(args)).update();
    }

    public <T> Optional<T> scalar(String sql, Class<T> type, Object... args) {
        return jdbc.sql(sql).params(Arrays.asList(args)).query(type).optional();
    }

    /** Transaction-scoped advisory lock on a text key (serialises check-then-act per business or user). */
    public void lockKey(String key) {
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtext(?))) x").param(key).query(Integer.class).single();
    }

    /** A PostgreSQL array literal of ids for {@code ?::uuid[]} (ids are UUIDs: nothing to escape). */
    public static String uuidArray(java.util.Collection<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    /** Clamps a requested page size to 1..200 (default 50). */
    public static int limit(Integer limit) {
        return limit == null ? 50 : Math.max(1, Math.min(200, limit));
    }

    /** Parses a keyset cursor (the last id of the previous page); 400 when malformed. */
    public static UUID cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_cursor", "cursor is not valid");
        }
    }

    /** A complete single page. */
    public ObjectNode page(List<ObjectNode> rows) {
        return page(rows, Integer.MAX_VALUE);
    }

    /** A page of rows fetched newest first with {@code limit + 1} rows (the extra one only signals more). */
    public ObjectNode page(List<ObjectNode> rows, int limit) {
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        boolean more = rows.size() > limit;
        List<ObjectNode> shown = more ? rows.subList(0, limit) : rows;
        shown.forEach(data::add);
        if (more) {
            out.put("next_cursor", shown.getLast().path("id").asString());
        } else {
            out.putNull("next_cursor");
        }
        out.put("has_more", more);
        return out;
    }
}
