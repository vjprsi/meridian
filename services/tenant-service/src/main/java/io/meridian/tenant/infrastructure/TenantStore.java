package io.meridian.tenant.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.crud.CrudEngine;
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

/** Row access beyond generic CRUD: locking reads, partial updates and tenant-scoping switches. */
@Repository
public class TenantStore {
    private static final Set<String> TABLES = Set.of("cell", "tenant", "tenant_move", "tenant_placement", "tenant_module",
            "legal_entity", "branch", "tenant_setting", "feature_flag", "tenant_branding");

    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;

    public TenantStore(JdbcClient jdbc, CrudEngine crud, JsonMapper json) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
    }

    /** Reads and row-locks one row (FOR UPDATE); 404 when absent or invisible under RLS. */
    public ObjectNode lock(String table, UUID id) {
        return (ObjectNode) json.readTree(jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ? for update")
                .param(id).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id)));
    }

    public Optional<ObjectNode> find(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ?")
                .param(id).query(String.class).optional().map(s -> (ObjectNode) json.readTree(s));
    }

    public ObjectNode require(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Applies column values (explicit JSON nulls clear a column) and returns the updated row. */
    public ObjectNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null)
                .map(s -> (ObjectNode) json.readTree(s))
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public ObjectNode insert(String table, ObjectNode values) {
        return (ObjectNode) json.readTree(crud.insert(table, values));
    }

    public List<ObjectNode> query(String sql, Object... params) {
        return jdbc.sql(sql).params(List.of(params)).query(String.class).list().stream()
                .map(s -> (ObjectNode) json.readTree(s)).toList();
    }

    public int execute(String sql, Object... params) {
        return jdbc.sql(sql).params(List.of(params)).update();
    }

    /**
     * Re-scopes row-level security for the rest of the current transaction (set_config local), used when the
     * platform operator seeds rows that belong to another tenant (provisioning).
     */
    public void scopeTransactionTo(UUID tenantId) {
        jdbc.sql("select set_config('app.tenant_id', ?, true)").param(tenantId.toString()).query(String.class).single();
    }

    public JsonNode tree(String s) {
        return json.readTree(s);
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
