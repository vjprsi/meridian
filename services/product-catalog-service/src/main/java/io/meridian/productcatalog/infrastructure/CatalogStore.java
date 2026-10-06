package io.meridian.productcatalog.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * SQL beyond generic CRUD for the product schema. Table/column names come only from constants (whitelisted);
 * all values are bind parameters. Rows are returned as {@code row_to_json} trees.
 */
@Repository
public class CatalogStore {
    static final Set<String> TABLES = Set.of("product_template", "product", "product_version", "product_parameter",
            "interest_rate_config", "interest_rate_tier", "rate_index", "rate_index_value", "fee_definition", "fee_tier",
            "fee_waiver_rule", "tax_rule", "product_limit_template", "eligibility_rule", "product_bundle",
            "product_bundle_item", "product_script", "product_simulation", "promotional_rate", "relationship_pricing",
            "product_availability", "product_disclosure");
    static final Set<String> ORDERS = Set.of("tier_no", "param_key", "code", "id", "version_no", "rate_kind, effective_from",
            "limit_code, kyc_tier", "hook, name", "value_date desc", "priority, id");
    static final Set<String> COLUMNS = Set.of("product_version_id", "interest_rate_config_id", "fee_definition_id",
            "product_id", "rate_index_id", "product_bundle_id");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CrudEngine crud;

    public CatalogStore(JdbcClient jdbc, JsonMapper json, CrudEngine crud) {
        this.jdbc = jdbc;
        this.json = json;
        this.crud = crud;
    }

    public Optional<JsonNode> find(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where t.id = ?")
                .param(id).query(String.class).optional().map(json::readTree);
    }

    public JsonNode require(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Exclusive row lock for state transitions. */
    public JsonNode lock(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where t.id = ? for update")
                .param(id).query(String.class).optional().map(json::readTree)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Shared lock: content edits of a version serialise against its submission. */
    public JsonNode lockShared(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where t.id = ? for share")
                .param(id).query(String.class).optional().map(json::readTree)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public JsonNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table(table), Map.of("id", id), values, null).map(json::readTree)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public JsonNode insert(String table, ObjectNode values) {
        return json.readTree(crud.insert(table(table), values));
    }

    public int delete(String table, UUID id) {
        return jdbc.sql("delete from " + table(table) + " where id = ?").param(id).update();
    }

    public int deleteChildren(String table, String fk, UUID parent) {
        return jdbc.sql("delete from " + table(table) + " where " + column(fk) + " = ?").param(parent).update();
    }

    public List<JsonNode> children(String table, String fk, UUID parent, String orderBy) {
        if (!ORDERS.contains(orderBy)) {
            throw new IllegalArgumentException("order " + orderBy);
        }
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where t." + column(fk)
                        + " = ? order by " + orderBy + ", id")
                .param(parent).query(String.class).list().stream().map(json::readTree).toList();
    }

    public List<JsonNode> versionsOfProduct(UUID productId) {
        return children("product_version", "product_id", productId, "version_no");
    }

    public int nextVersionNo(UUID productId) {
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from product_version where product_id = ?")
                .param(productId).query(Integer.class).single();
    }

    public Optional<JsonNode> byCode(String table, String code) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where t.code = ?")
                .param(code).query(String.class).optional().map(json::readTree);
    }

    /** Latest index fixing on or before {@code date} (callers apply the publication lag). */
    public Optional<BigDecimal> indexRateAt(UUID indexId, LocalDate date) {
        return jdbc.sql("""
                select rate from rate_index_value where rate_index_id = ? and value_date <= ?
                order by value_date desc limit 1""")
                .params(indexId, date).query(BigDecimal.class).optional();
    }

    /** Index values newest first with optional date window and keyset cursor (by id). */
    public Map<String, Object> indexValues(UUID indexId, LocalDate from, LocalDate to, Integer limit, String cursor) {
        int n = Cursor.limit(limit);
        StringBuilder sql = new StringBuilder("select row_to_json(t)::text from rate_index_value t where t.rate_index_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(indexId);
        if (from != null) {
            sql.append(" and t.value_date >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" and t.value_date <= ?");
            args.add(to);
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" and t.id < ?");
            args.add(Cursor.decode(cursor));
        }
        sql.append(" order by t.id desc limit ?");
        args.add(n + 1);
        List<JsonNode> rows = jdbc.sql(sql.toString()).params(args).query(String.class).list().stream()
                .map(json::readTree).toList();
        boolean more = rows.size() > n;
        List<JsonNode> data = more ? rows.subList(0, n) : rows;
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("data", data);
        page.put("has_more", more);
        page.put("next_cursor", more ? Cursor.encode(UUID.fromString(data.getLast().get("id").asString())) : null);
        return page;
    }

    public boolean indexValueExists(UUID indexId, LocalDate date) {
        return jdbc.sql("select count(*) from rate_index_value where rate_index_id = ? and value_date = ?")
                .params(indexId, date).query(Long.class).single() > 0;
    }

    // ------------------------------------------------------------------ JSON helpers

    /** JSON tree to plain Java: maps, lists, BigDecimal numbers, strings, booleans, null. */
    public static Object toJava(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.properties().forEach(e -> m.put(e.getKey(), toJava(e.getValue())));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.values().forEach(v -> l.add(toJava(v)));
            return l;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return n.asString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode n) {
        Object o = toJava(n);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /** Plain Java values to a JSON tree; BigDecimal values are written as plain decimal strings (API convention). */
    public JsonNode toNode(Object v) {
        return json.valueToTree(jsonFriendly(v, true));
    }

    /**
     * As {@link #toNode} but keeping numbers numeric (for jsonb storage and checksums). Built node by node because
     * the API mapper serialises BigDecimal as text.
     */
    public JsonNode toNumericNode(Object v) {
        if (v instanceof Map<?, ?> m) {
            ObjectNode o = json.createObjectNode();
            m.forEach((k, val) -> o.set(String.valueOf(k), toNumericNode(val)));
            return o;
        }
        if (v instanceof List<?> l) {
            tools.jackson.databind.node.ArrayNode a = json.createArrayNode();
            l.forEach(x -> a.add(toNumericNode(x)));
            return a;
        }
        if (v instanceof JsonNode n) {
            return n;
        }
        if (v instanceof BigDecimal b) {
            return json.createObjectNode().put("v", b).get("v");
        }
        if (v instanceof Integer || v instanceof Long) {
            return json.createObjectNode().put("v", ((Number) v).longValue()).get("v");
        }
        if (v == null) {
            return json.createObjectNode().putNull("v").get("v");
        }
        return json.valueToTree(jsonFriendly(v, false));
    }

    static Object jsonFriendly(Object v, boolean decimalsAsText) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), jsonFriendly(val, decimalsAsText)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(jsonFriendly(x, decimalsAsText)));
            return out;
        }
        if (v instanceof BigDecimal b) {
            return decimalsAsText ? b.toPlainString() : b;
        }
        if (v instanceof LocalDate || v instanceof UUID || v instanceof Enum<?>) {
            return v.toString();
        }
        return v;
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }

    private static String column(String c) {
        if (!COLUMNS.contains(c)) {
            throw new IllegalArgumentException("unknown column " + c);
        }
        return c;
    }
}
