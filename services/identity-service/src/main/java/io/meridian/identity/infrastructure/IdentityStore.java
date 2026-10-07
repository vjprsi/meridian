package io.meridian.identity.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Identity SQL beyond generic CRUD. All statements run under row-level security (tenant-scoped). Table names are
 * compile-time constants from the application layer; all values are bind parameters.
 */
@Repository
public class IdentityStore {
    /** Columns flagged {@code secret} in the spec: never leave the service (API responses or events). */
    public static final Set<String> SECRET_COLUMNS =
            Set.of("password_hash", "totp_seed_enc", "public_key", "key_hash", "client_secret_hash");
    private static final Set<String> TABLES = Set.of("app_user", "role", "permission", "role_permission", "user_role",
            "access_policy", "mfa_factor", "user_session", "api_client", "api_key", "sso_provider", "oauth_scope",
            "rate_limit_policy", "api_request_log", "access_review", "access_review_item");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IdentityStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public Optional<String> find(String table, UUID id) {
        TenantContext.require();
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where t.id = ?")
                .param(id).query(String.class).optional();
    }

    /** Row locked FOR UPDATE, or 404. */
    public JsonNode lock(String table, UUID id) {
        TenantContext.require();
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where t.id = ? for update")
                .param(id).query(String.class).optional()
                .map(json::readTree)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public JsonNode require(String table, UUID id) {
        return find(table, id).map(json::readTree).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Runs an UPDATE ... RETURNING row_to_json(t)::text statement and returns the row. */
    public String updateReturning(String sql, Object... params) {
        return jdbc.sql(sql).params(java.util.Arrays.asList(params)).query(String.class).single();
    }

    public List<String> rows(String sql, Object... params) {
        return jdbc.sql(sql).params(java.util.Arrays.asList(params)).query(String.class).list();
    }

    /** The row without secret columns, safe for events and API bodies. */
    public ObjectNode publicView(String row) {
        ObjectNode n = (ObjectNode) json.readTree(row);
        SECRET_COLUMNS.forEach(n::remove);
        return n;
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
