package io.meridian.pricingrules.infrastructure;

import io.meridian.common.error.ApiException;
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

/** SQL beyond generic CRUD for the pricing schema. Table names are constants; values are bind parameters. */
@Repository
public class PricingStore {
    private static final Set<String> TABLES = Set.of("decision_table", "decision_table_version",
            "rule_evaluation_log", "product_price_quote");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CrudEngine crud;

    public PricingStore(JdbcClient jdbc, JsonMapper json, CrudEngine crud) {
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

    /** Row lock for state transitions (serialises concurrent actions on the same aggregate). */
    public JsonNode lock(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where t.id = ? for update")
                .param(id).query(String.class).optional().map(json::readTree)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Applies server-side column changes (no optimistic check; caller holds the row lock). */
    public JsonNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table(table), Map.of("id", id), values, null).map(json::readTree)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<JsonNode> tableByCode(String code) {
        return jdbc.sql("select row_to_json(t)::text from decision_table t where t.code = ?")
                .param(code).query(String.class).optional().map(json::readTree);
    }

    public int nextVersionNo(UUID tableId) {
        // Lock the parent so concurrent version creation cannot pick the same number.
        jdbc.sql("select id from decision_table where id = ? for update").param(tableId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("decision table", tableId));
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from decision_table_version where decision_table_id = ?")
                .param(tableId).query(Integer.class).single();
    }

    public List<JsonNode> versionsInStatus(UUID tableId, String status) {
        return jdbc.sql("""
                select row_to_json(t)::text from decision_table_version t
                where t.decision_table_id = ? and t.status = ? order by t.version_no""")
                .params(tableId, status).query(String.class).list().stream().map(json::readTree).toList();
    }

    /** Converts a JSON tree to plain Java values: maps, lists, BigDecimal numbers, strings, booleans, null. */
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
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** Plain Java values to a JSON tree: numbers stay numbers (scale normalised), dates become ISO text. */
    public JsonNode toNode(Object v) {
        return json.valueToTree(jsonFriendly(v));
    }

    public static Object jsonFriendly(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), jsonFriendly(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(PricingStore::jsonFriendly).toList();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof BigDecimal b) {
            return b.stripTrailingZeros().scale() < 0 ? b.setScale(0) : b.stripTrailingZeros();
        }
        return v;
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
