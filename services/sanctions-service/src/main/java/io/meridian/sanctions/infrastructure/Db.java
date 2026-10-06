package io.meridian.sanctions.infrastructure;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small JSON-row helpers over JdbcClient (rows come back as row_to_json). */
@Component
public class Db {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Db(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public Optional<JsonNode> one(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).optional().map(json::readTree);
    }

    public List<JsonNode> list(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).list().stream().map(json::readTree).toList();
    }

    public int update(String sql, Object... args) {
        return jdbc.sql(sql).params(args).update();
    }

    /** Loads a row by id (optionally locking it) or throws 404. */
    public JsonNode require(String table, UUID id, boolean lock) {
        if (!IDENT.matcher(table).matches()) {
            throw new IllegalArgumentException(table);
        }
        return one("select row_to_json(t)::text from " + table + " t where id = ?" + (lock ? " for update" : ""), id)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public String text(JsonNode row) {
        return json.writeValueAsString(row);
    }

    public static String str(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    public static UUID uuid(JsonNode n, String f) {
        String s = str(n, f);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    public static BigDecimal dec(JsonNode n, String f) {
        String s = str(n, f);
        return s == null || s.isBlank() ? null : new BigDecimal(s);
    }

    public static Integer integer(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asInt() : null;
    }

    public static boolean bool(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && n.get(f).asBoolean();
    }

    /** PostgreSQL array literal for safe tokens (uuids, hex hashes). */
    public static String array(java.util.Collection<?> values) {
        StringBuilder sb = new StringBuilder("{");
        for (Object v : values) {
            String s = v.toString();
            if (!s.matches("[0-9A-Za-z_-]+")) {
                throw new IllegalArgumentException("unsafe array token");
            }
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.append('}').toString();
    }
}
