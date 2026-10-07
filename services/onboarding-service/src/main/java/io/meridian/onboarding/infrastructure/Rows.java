package io.meridian.onboarding.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.crud.CrudEngine;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Row access by id for this service's tables (tenant-scoped by RLS). Table names are internal constants only. */
@Repository
public class Rows {
    private static final Set<String> TABLES =
            Set.of("onboarding_journey", "onboarding_application", "onboarding_step", "onboarding_offline_batch",
                    "onboarding_applicant_token", "onboarding_otp");

    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;

    public Rows(JdbcClient jdbc, CrudEngine crud, JsonMapper json) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
    }

    /** Locks the row for the rest of the transaction; 404 if absent (or owned by another tenant). */
    public ObjectNode lock(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ? for update")
                .param(id).query(String.class).optional().map(this::node)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<ObjectNode> find(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ?")
                .param(id).query(String.class).optional().map(this::node);
    }

    public ObjectNode get(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public ObjectNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null).map(this::node)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Update guarded by the client's If-Match version; 409 version_conflict when stale. */
    public ObjectNode update(String table, UUID id, ObjectNode values, int expectedVersion) {
        return crud.updateRow(table, Map.of("id", id), values, expectedVersion).map(this::node)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + expectedVersion + " does not match the current version; reload and retry"));
    }

    public ObjectNode insert(String table, ObjectNode values) {
        return node(crud.insert(table(table), values));
    }

    public List<ObjectNode> query(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).list().stream().map(this::node).toList();
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public ObjectNode node(String text) {
        return (ObjectNode) json.readTree(text);
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
