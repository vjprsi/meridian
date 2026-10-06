package io.meridian.carddispute.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Row access in API shape (row_to_json) with writes through {@link CrudEngine}; RLS scopes every statement. */
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

    public ObjectNode lock(String table, UUID id) {
        return queryOne("select row_to_json(t)::text from " + table + " t where id = ? for update", id)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<ObjectNode> find(String table, UUID id) {
        return queryOne("select row_to_json(t)::text from " + table + " t where id = ?", id);
    }

    public Optional<ObjectNode> queryOne(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).query(String.class).optional().map(this::parse);
    }

    public List<ObjectNode> query(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).query(String.class).list().stream().map(this::parse).toList();
    }

    public <T> T scalar(String sql, Class<T> type, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).query(type).single();
    }

    public int execute(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).update();
    }

    /** Business date of the database clock (single-clock rule). */
    public LocalDate today() {
        return LocalDate.parse(scalar("select current_date::text", String.class));
    }

    /** Current time of the database clock as ISO-8601 text. */
    public String now() {
        return scalar("select to_char(now() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')", String.class);
    }

    public ObjectNode insert(String table, ObjectNode values) {
        if (!values.has("id")) {
            values.put("id", Ids.newId().toString());
        }
        return parse(crud.insert(table, values));
    }

    public ObjectNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null).map(this::parse)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public ObjectNode parse(String text) {
        return (ObjectNode) json.readTree(text);
    }

    public JsonMapper json() {
        return json;
    }

    public static String str(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    public static UUID uuid(JsonNode n, String f) {
        String s = str(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    public static BigDecimal dec(JsonNode n, String f) {
        String s = str(n, f);
        return s == null ? null : new BigDecimal(s);
    }

    public static LocalDate date(JsonNode n, String f) {
        String s = str(n, f);
        return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    public static boolean bool(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v != null && !v.isNull() && v.asBoolean();
    }
}
