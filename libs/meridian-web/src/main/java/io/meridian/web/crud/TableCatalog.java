package io.meridian.web.crud;

import io.meridian.common.error.ApiException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Column names and PostgreSQL types of the service's tables, read once from information_schema. */
public class TableCatalog {
    private final JdbcClient jdbc;
    private final Map<String, Map<String, String>> tables = new ConcurrentHashMap<>();

    public TableCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Column -> PostgreSQL type usable in a cast (e.g. uuid, numeric, timestamptz, varchar, jsonb). */
    public Map<String, String> columns(String table) {
        Map<String, String> cols = tables.computeIfAbsent(table, this::load);
        if (cols.isEmpty()) {
            tables.remove(table);
            throw new IllegalStateException("table " + table + " not found in schema");
        }
        return cols;
    }

    public boolean has(String table, String column) {
        return columns(table).containsKey(column);
    }

    public String type(String table, String column) {
        String t = columns(table).get(column);
        if (t == null) {
            throw ApiException.badRequest("unknown_field", "unknown field " + column);
        }
        return t;
    }

    private Map<String, String> load(String table) {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.sql("""
                select column_name, udt_name from information_schema.columns
                where table_schema = current_schema() and table_name = ? order by ordinal_position""")
                .param(table)
                .query((rs, i) -> m.put(rs.getString(1), castType(rs.getString(2))))
                .list();
        return new HashMap<>(m);
    }

    private static String castType(String udt) {
        return switch (udt) {
            case "int4" -> "integer";
            case "int8" -> "bigint";
            case "bool" -> "boolean";
            case "bpchar" -> "text";
            default -> udt;
        };
    }
}
