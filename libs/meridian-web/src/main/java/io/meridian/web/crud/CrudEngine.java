package io.meridian.web.crud;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generic, tenant-scoped CRUD over one service's tables, used by the generated API default methods.
 * PostgreSQL does the type mapping: writes go through {@code json_populate_record}, reads come back as
 * {@code row_to_json}. Only real column names are ever placed in SQL; all values are bind parameters.
 * Business logic lives in controllers that override the generated methods.
 */
public class CrudEngine {
    /** Query parameters with paging meaning rather than column filters. */
    private static final Set<String> RESERVED = Set.of("limit", "cursor", "created_after", "created_before");
    /** Server-managed columns a client can never write. */
    private static final Set<String> SERVER_COLUMNS =
            Set.of("id", "tenant_id", "created_at", "created_by", "updated_at", "updated_by", "row_version");
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TableCatalog catalog;
    private final Idempotency idempotency;

    public CrudEngine(JdbcClient jdbc, JsonMapper json, TableCatalog catalog, Idempotency idempotency) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
        this.idempotency = idempotency;
        Crud.register(this);
    }

    // ------------------------------------------------------------------ read

    public <T> ResponseEntity<T> list(String table, Class<T> pageType, Crud.Params params) {
        TenantContext.require();
        Map<String, Object> p = params.asMap();
        int limit = Cursor.limit(p.get("limit") instanceof Integer i ? i : null);
        StringBuilder sql = new StringBuilder("select row_to_json(t)::text from " + ident(table) + " t where true");
        List<Object> args = new ArrayList<>();
        appendFilters(table, p, sql, args);
        if (p.get("cursor") instanceof String c && !c.isBlank()) {
            sql.append(" and t.id < ?::uuid");
            args.add(Cursor.decode(c).toString());
        }
        if (p.get("created_after") != null) {
            sql.append(" and t.created_at >= ?::timestamptz");
            args.add(p.get("created_after").toString());
        }
        if (p.get("created_before") != null) {
            sql.append(" and t.created_at < ?::timestamptz");
            args.add(p.get("created_before").toString());
        }
        sql.append(" order by t.id desc limit ?");
        args.add(limit + 1);
        List<String> rows = jdbc.sql(sql.toString()).params(args).query(String.class).list();
        return ResponseEntity.ok(page(rows, limit, pageType));
    }

    public <T> ResponseEntity<T> get(String table, Class<T> type, Crud.Params params) {
        TenantContext.require();
        String row = findOne(table, params.asMap())
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), params.asMap().get("id")));
        return withEtag(ResponseEntity.ok(), row, type);
    }

    /** Fetches one row as JSON text by id plus any other path parameters (e.g. parent id). */
    public Optional<String> findOne(String table, Map<String, Object> keys) {
        StringBuilder sql = new StringBuilder("select row_to_json(t)::text from " + ident(table) + " t where true");
        List<Object> args = new ArrayList<>();
        appendFilters(table, keys, sql, args);
        return jdbc.sql(sql.toString()).params(args).query(String.class).optional();
    }

    // ------------------------------------------------------------------ write

    public <T> ResponseEntity<T> create(String table, Class<T> type, Object body, String idempotencyKey,
                                        Crud.Params params) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, type, () -> {
            ObjectNode values = toColumns(table, body);
            params.asMap().forEach((k, v) -> {
                if (!RESERVED.contains(k) && !"id".equals(k) && catalog.has(table, k)) {
                    values.put(k, v.toString());
                }
            });
            if (catalog.has(table, "idempotency_key") && !values.has("idempotency_key")) {
                values.put("idempotency_key", idempotencyKey);
            }
            String row = insert(table, values);
            return withEtag(ResponseEntity.status(HttpStatus.CREATED), row, type);
        });
    }

    /** Inserts the given column values (client and server supplied) and returns the new row as JSON text. */
    public String insert(String table, ObjectNode values) {
        values.put("tenant_id", TenantContext.require().toString());
        if (catalog.has(table, "created_by")) {
            values.put("created_by", TenantContext.principal());
        }
        if (catalog.has(table, "updated_by")) {
            values.put("updated_by", TenantContext.principal());
        }
        List<String> cols = new ArrayList<>();
        values.propertyNames().forEach(name -> {
            if (catalog.has(table, name)) {
                cols.add(ident(name));
            }
        });
        String colList = String.join(", ", cols);
        String selList = String.join(", ", cols.stream().map(c -> "r." + c).toList());
        String sql = "insert into " + ident(table) + " as t (" + colList + ") select " + selList
                + " from json_populate_record(null::" + ident(table) + ", ?::json) r returning row_to_json(t)::text";
        return jdbc.sql(sql).param(json.writeValueAsString(values)).query(String.class).single();
    }

    /**
     * Inserts many rows of one table in a single statement (json_populate_recordset); all rows must carry the same
     * column set. Returns the inserted rows as JSON text (order not guaranteed).
     */
    public List<String> insertMany(String table, List<ObjectNode> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String tenant = TenantContext.require().toString();
        tools.jackson.databind.node.ArrayNode arr = json.createArrayNode();
        for (ObjectNode r : rows) {
            r.put("tenant_id", tenant);
            if (catalog.has(table, "created_by")) {
                r.put("created_by", TenantContext.principal());
            }
            if (catalog.has(table, "updated_by")) {
                r.put("updated_by", TenantContext.principal());
            }
            arr.add(r);
        }
        List<String> cols = new ArrayList<>();
        rows.getFirst().propertyNames().forEach(name -> {
            if (catalog.has(table, name)) {
                cols.add(ident(name));
            }
        });
        String colList = String.join(", ", cols);
        String selList = String.join(", ", cols.stream().map(c -> "r." + c).toList());
        String sql = "insert into " + ident(table) + " as t (" + colList + ") select " + selList
                + " from json_populate_recordset(null::" + ident(table) + ", ?::json) r returning row_to_json(t)::text";
        return jdbc.sql(sql).param(json.writeValueAsString(arr)).query(String.class).list();
    }

    public <T> ResponseEntity<T> update(String table, Class<T> type, Object body, String ifMatch, Crud.Params params) {
        TenantContext.require();
        Map<String, Object> keys = params.asMap();
        int expected = parseVersion(ifMatch);
        ObjectNode values = toColumns(table, body);
        keys.keySet().forEach(values::remove);
        if (values.isEmpty()) {
            return get(table, type, params);
        }
        String row = updateRow(table, keys, values, expected)
                .orElseThrow(() -> findOne(table, keys).isPresent()
                        ? ApiException.conflict("version_conflict",
                                "If-Match " + expected + " does not match the current version; reload and retry")
                        : ApiException.notFound(table.replace('_', ' '), keys.get("id")));
        return withEtag(ResponseEntity.ok(), row, type);
    }

    /** Applies column changes when row_version matches; empty if not found or stale. */
    public Optional<String> updateRow(String table, Map<String, Object> keys, ObjectNode values, Integer expectedVersion) {
        List<String> cols = new ArrayList<>();
        values.propertyNames().forEach(name -> {
            if (catalog.has(table, name) && !SERVER_COLUMNS.contains(name)) {
                cols.add(ident(name));
            }
        });
        if (cols.isEmpty()) {
            return findOne(table, keys);
        }
        String set = cols.size() == 1
                ? cols.getFirst() + " = r." + cols.getFirst()
                : "(" + String.join(", ", cols) + ") = (" + String.join(", ", cols.stream().map(c -> "r." + c).toList()) + ")";
        StringBuilder sql = new StringBuilder("update " + ident(table) + " as t set " + set
                + (catalog.has(table, "updated_by") ? ", updated_by = ?" : "")
                + " from json_populate_record(null::" + ident(table) + ", ?::json) r where true");
        List<Object> args = new ArrayList<>();
        if (catalog.has(table, "updated_by")) {
            args.add(TenantContext.principal());
        }
        args.add(json.writeValueAsString(values));
        appendFilters(table, keys, sql, args);
        if (expectedVersion != null && catalog.has(table, "row_version")) {
            sql.append(" and t.row_version = ?");
            args.add(expectedVersion);
        }
        sql.append(" returning row_to_json(t)::text");
        return jdbc.sql(sql.toString()).params(args).query(String.class).optional();
    }

    public ResponseEntity<Void> delete(String table, Crud.Params params) {
        TenantContext.require();
        StringBuilder sql = new StringBuilder("delete from " + ident(table) + " t where true");
        List<Object> args = new ArrayList<>();
        appendFilters(table, params.asMap(), sql, args);
        int n = jdbc.sql(sql.toString()).params(args).update();
        if (n == 0) {
            throw ApiException.notFound(table.replace('_', ' '), params.asMap().get("id"));
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ helpers

    /** Converts an API model into column values: snake_case names, nulls dropped, unknown fields ignored. */
    public ObjectNode toColumns(String table, Object body) {
        ObjectNode values = json.createObjectNode();
        if (body == null) {
            return values;
        }
        JsonNode tree = json.valueToTree(body);
        tree.propertyNames().forEach(name -> {
            JsonNode v = tree.get(name);
            if (v != null && !v.isNull() && catalog.has(table, name) && !SERVER_COLUMNS.contains(name)) {
                values.set(name, v);
            }
        });
        return values;
    }

    public <T> T read(String rowJson, Class<T> type) {
        return json.readValue(rowJson, type);
    }

    private <T> T page(List<String> rows, int limit, Class<T> pageType) {
        boolean more = rows.size() > limit;
        List<String> data = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            UUID last = UUID.fromString(json.readTree(data.getLast()).get("id").asString());
            next = Cursor.encode(last);
        }
        String body = "{\"data\":[" + String.join(",", data) + "],\"has_more\":" + more
                + ",\"next_cursor\":" + (next == null ? "null" : "\"" + next + "\"") + "}";
        return json.readValue(body, pageType);
    }

    private <T> ResponseEntity<T> withEtag(ResponseEntity.BodyBuilder b, String row, Class<T> type) {
        JsonNode v = json.readTree(row).get("row_version");
        if (v != null && !v.isNull()) {
            b.eTag("\"" + v.asString() + "\"");
        }
        return b.body(json.readValue(row, type));
    }

    private void appendFilters(String table, Map<String, Object> p, StringBuilder sql, List<Object> args) {
        p.forEach((k, v) -> {
            if (RESERVED.contains(k) || v == null || !catalog.has(table, k)) {
                return;
            }
            sql.append(" and t.").append(ident(k)).append(" = ?::").append(catalog.type(table, k));
            args.add(v.toString());
        });
    }

    private static int parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        String v = ifMatch.replace("W/", "").replace("\"", "").trim();
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }

    private static String ident(String name) {
        if (!IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("illegal identifier " + name);
        }
        return name;
    }
}
