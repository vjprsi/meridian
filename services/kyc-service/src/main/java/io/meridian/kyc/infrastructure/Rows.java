package io.meridian.kyc.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Row access beyond generic CRUD: locking reads and partial updates as JSON. Table names passed here are
 * internal constants only; every value is a bind parameter. Row-level security scopes all queries to the tenant.
 */
@Repository
public class Rows {
    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;

    public Rows(JdbcClient jdbc, CrudEngine crud, JsonMapper json) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
    }

    /** Selects a row for update; 404 when it does not exist in the current tenant. */
    public ObjectNode lock(String table, UUID id) {
        return query("select row_to_json(t)::text from " + table + " t where t.id = ? for update", id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<ObjectNode> find(String table, UUID id) {
        return query("select row_to_json(t)::text from " + table + " t where t.id = ?", id).stream().findFirst();
    }

    public ObjectNode require(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public ObjectNode update(String table, UUID id, ObjectNode values) {
        String row = crud.updateRow(table, Map.of("id", id), values, null)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
        return (ObjectNode) json.readTree(row);
    }

    public ObjectNode insert(String table, ObjectNode values) {
        return (ObjectNode) json.readTree(crud.insert(table, values));
    }

    /** Runs a query whose single column is {@code row_to_json(...)::text}. */
    public List<ObjectNode> query(String sql, Object... args) {
        List<String> rows = jdbc.sql(sql).params(List.of(args)).query(String.class).list();
        List<ObjectNode> out = new ArrayList<>(rows.size());
        rows.forEach(r -> out.add((ObjectNode) json.readTree(r)));
        return out;
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }
}
