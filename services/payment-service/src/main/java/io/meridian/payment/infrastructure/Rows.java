package io.meridian.payment.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.page.Cursor;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Row access for business actions: rows travel as JSON objects in API shape (snake_case column names), reads use
 * {@code row_to_json}, writes go through {@link CrudEngine} (tenant, audit columns, json_populate_record).
 * Row-level security scopes every statement to the current tenant.
 */
@Component
public class Rows {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");

    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;

    public Rows(JdbcClient jdbc, CrudEngine crud, JsonMapper json) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
    }

    public JsonMapper json() {
        return json;
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }

    public ArrayNode array() {
        return json.createArrayNode();
    }

    /** Loads a row and locks it for the rest of the transaction. */
    public ObjectNode lock(String table, UUID id) {
        return queryOne("select row_to_json(t)::text from " + ident(table) + " t where id = ? for update", id)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public ObjectNode get(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<ObjectNode> find(String table, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return queryOne("select row_to_json(t)::text from " + ident(table) + " t where id = ?", id);
    }

    public Optional<ObjectNode> queryOne(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).query(String.class).optional().map(this::parse);
    }

    public List<ObjectNode> query(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).query(String.class).list().stream().map(this::parse).toList();
    }

    public <T> Optional<T> scalar(String sql, Class<T> type, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).query(type).optional();
    }

    public int execute(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).update();
    }

    /** Database clock (single clock for validity windows). */
    public OffsetDateTime now() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single();
    }

    public LocalDate today() {
        return jdbc.sql("select current_date").query(LocalDate.class).single();
    }

    /** Inserts a row with a server-generated id (unless given) and returns it. */
    public ObjectNode insert(String table, ObjectNode values) {
        if (!values.has("id")) {
            values.put("id", Ids.newId().toString());
        }
        return parse(crud.insert(table, values));
    }

    /** Updates the given columns of one row (callers hold the row lock) and returns it. */
    public ObjectNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null).map(this::parse)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public ObjectNode columns(String table, Object body) {
        return crud.toColumns(table, body);
    }

    /**
     * One cursor page ({@code data, has_more, next_cursor}) of {@code select row_to_json(t)::text from <table> t
     * where <where>} ordered newest first.
     */
    public ObjectNode page(String table, String where, Integer limit, String cursor, Object... params) {
        int lim = Cursor.limit(limit);
        List<Object> args = new ArrayList<>(Arrays.asList(params));
        StringBuilder sql = new StringBuilder("select row_to_json(t)::text from " + ident(table) + " t where " + where);
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" and t.id < ?::uuid");
            args.add(Cursor.decode(cursor).toString());
        }
        sql.append(" order by t.id desc limit ?");
        args.add(lim + 1);
        List<String> rows = jdbc.sql(sql.toString()).params(args).query(String.class).list();
        boolean more = rows.size() > lim;
        List<String> data = more ? rows.subList(0, lim) : rows;
        ObjectNode page = object();
        ArrayNode arr = page.putArray("data");
        data.forEach(r -> arr.add(parse(r)));
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", Cursor.encode(UUID.fromString(parse(data.getLast()).get("id").asString())));
        } else {
            page.putNull("next_cursor");
        }
        return page;
    }

    public ObjectNode parse(String text) {
        return (ObjectNode) json.readTree(text);
    }

    public <T> T as(JsonNode row, Class<T> type) {
        return json.treeToValue(row, type);
    }

    // ------------------------------------------------------------------ typed field access

    public static String str(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    public static UUID uuid(JsonNode n, String f) {
        String s = str(n, f);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    public static BigDecimal dec(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString());
    }

    public static BigDecimal decOr0(JsonNode n, String f) {
        BigDecimal d = dec(n, f);
        return d == null ? BigDecimal.ZERO : d;
    }

    public static LocalDate date(JsonNode n, String f) {
        String s = str(n, f);
        return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    public static OffsetDateTime ts(JsonNode n, String f) {
        String s = str(n, f);
        return s == null ? null : OffsetDateTime.parse(s);
    }

    public static int integer(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? 0 : v.asInt();
    }

    public static boolean bool(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v != null && !v.isNull() && v.asBoolean();
    }

    private static String ident(String name) {
        if (!IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("illegal identifier " + name);
        }
        return name;
    }
}
