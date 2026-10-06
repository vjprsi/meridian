package io.meridian.document.infrastructure;

import io.meridian.common.error.ApiException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Row access beyond generic CRUD: pessimistic locks and tenant discovery for background jobs. */
@Repository
public class DocumentStore {
    private static final Set<String> TABLES = Set.of("document", "document_template", "generated_document", "esign_request");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DocumentStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Locks the row (FOR UPDATE) and returns it as JSON; 404 if it does not exist for this tenant. */
    public JsonNode lock(String table, UUID id) {
        return json.readTree(jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ? for update")
                .param(id).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id)));
    }

    public String row(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ?")
                .param(id).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Tenants with recent API activity (the idempotency_key table is not tenant-scoped by RLS). */
    public List<UUID> activeTenants() {
        return jdbc.sql("select distinct tenant_id from idempotency_key").query(UUID.class).list();
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
