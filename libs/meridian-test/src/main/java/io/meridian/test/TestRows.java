package io.meridian.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts minimal valid rows straight into a service's schema as the owner role (bypassing the API and row-level
 * security), for tests of cross-cutting machinery such as the data-lifecycle endpoints. Every NOT NULL column without a
 * default gets a value: the caller's override, a parent row created recursively for a foreign key, the first value of
 * a single-column {@code IN (...)} check (enums), or a type-appropriate value.
 */
public final class TestRows implements AutoCloseable {
    private static final Pattern FIRST_LITERAL = Pattern.compile("'([^']*)'");
    private final Connection c;
    private final UUID tenant;

    public TestRows(String schema, UUID tenant) {
        try {
            this.c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl() + "&currentSchema=" + schema,
                    "meridian", "meridian");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        this.tenant = tenant;
    }

    /** Inserts a row into {@code table} and returns its id. */
    public UUID insert(String table, Map<String, Object> overrides) {
        return insert(table, overrides, 0);
    }

    /** Single value query (verification). */
    public Object value(String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID insert(String table, Map<String, Object> overrides, int depth) {
        if (depth > 6) {
            throw new IllegalStateException("foreign-key chain too deep at " + table);
        }
        try {
            Map<String, String> fks = foreignKeys(table);
            Map<String, String> checks = enumChecks(table);
            Map<String, Object> values = new LinkedHashMap<>();
            List<String> casts = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    select column_name, data_type, udt_name, is_nullable = 'YES', column_default is not null,
                           character_maximum_length
                    from information_schema.columns where table_schema = current_schema() and table_name = ?
                    order by ordinal_position""")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String col = rs.getString(1);
                        String type = rs.getString(2);
                        String udt = rs.getString(3);
                        boolean nullable = rs.getBoolean(4);
                        boolean hasDefault = rs.getBoolean(5);
                        Integer len = (Integer) rs.getObject(6);
                        Object v;
                        if (overrides.containsKey(col)) {
                            v = overrides.get(col);
                        } else if ("tenant_id".equals(col)) {
                            v = tenant;
                        } else if (nullable || hasDefault) {
                            continue;
                        } else if (fks.containsKey(col)) {
                            v = insert(fks.get(col), Map.of(), depth + 1);
                        } else if (checks.containsKey(col)) {
                            v = checks.get(col);
                        } else {
                            v = sample(type, udt, len);
                        }
                        values.put(col, v);
                        casts.add("ARRAY".equals(type) ? "?::" + udt.substring(1) + "[]" : "?::" + castType(type, udt));
                    }
                }
            }
            if (values.isEmpty()) {
                throw new IllegalStateException("table " + table + " not found");
            }
            String sql = "insert into " + table + " (" + String.join(", ", values.keySet()) + ") values ("
                    + String.join(", ", casts) + ") returning id";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                for (Object v : values.values()) {
                    ps.setObject(i++, v == null ? null : v.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot insert a test row into " + table + ": " + e.getMessage(), e);
        }
    }

    private Map<String, String> foreignKeys(String table) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select a.attname, r.relname from pg_constraint k
                join pg_class t on t.oid = k.conrelid join pg_namespace n on n.oid = t.relnamespace
                join pg_class r on r.oid = k.confrelid
                join pg_attribute a on a.attrelid = t.oid and a.attnum = k.conkey[1]
                where k.contype = 'f' and n.nspname = current_schema() and t.relname = ? and array_length(k.conkey, 1) = 1""")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!rs.getString(2).equals(table)) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
        }
        return out;
    }

    private Map<String, String> enumChecks(String table) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select a.attname, pg_get_constraintdef(k.oid) from pg_constraint k
                join pg_class t on t.oid = k.conrelid join pg_namespace n on n.oid = t.relnamespace
                join pg_attribute a on a.attrelid = t.oid and a.attnum = k.conkey[1]
                where k.contype = 'c' and n.nspname = current_schema() and t.relname = ? and array_length(k.conkey, 1) = 1""")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Matcher m = FIRST_LITERAL.matcher(rs.getString(2));
                    if (rs.getString(2).contains("ANY") && m.find()) {
                        out.put(rs.getString(1), m.group(1));
                    }
                }
            }
        }
        return out;
    }

    private static Object sample(String type, String udt, Integer len) {
        String rnd = UUID.randomUUID().toString().replace("-", "");
        return switch (type) {
            case "uuid" -> UUID.randomUUID();
            case "character" -> len != null && len == 3 ? "EUR" : len != null && len == 2 ? "GB" : "X".repeat(len == null ? 1 : len);
            case "character varying", "text" -> rnd.substring(0, Math.min(rnd.length(), len == null ? 16 : Math.min(16, len)));
            case "smallint", "integer", "bigint", "numeric", "real", "double precision" -> 1;
            case "boolean" -> false;
            case "date" -> "2020-01-01";
            case "timestamp with time zone", "timestamp without time zone" -> "2020-01-01T00:00:00Z";
            case "jsonb", "json" -> "{}";
            case "inet" -> "10.0.0.1";
            case "ARRAY" -> "{}";
            case "bytea" -> "\\x00";
            default -> throw new IllegalStateException("no sample value for type " + type + " (" + udt + ")");
        };
    }

    private static String castType(String type, String udt) {
        return switch (type) {
            case "character varying" -> "varchar";
            case "character" -> "bpchar";
            case "timestamp with time zone" -> "timestamptz";
            case "USER-DEFINED" -> udt;
            default -> type;
        };
    }

    @Override
    public void close() {
        try {
            c.close();
        } catch (SQLException e) {
            // ignore
        }
    }
}
