package io.meridian.tenant.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rows of starter pack runs and the pack item registry. Both tables are tenant-scoped (RLS): callers bind the
 * target tenant in {@code TenantContext} before opening the transaction.
 */
@Repository
public class StarterPackStore {
    public static final String RUN = "starter_pack_run";
    public static final String ITEM = "starter_pack_item";

    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;

    public StarterPackStore(JdbcClient jdbc, CrudEngine crud, JsonMapper json) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
    }

    /** Registry of a pack for the current tenant, by item key. */
    public Map<String, ObjectNode> items(String packCode) {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        jdbc.sql("select row_to_json(i)::text from starter_pack_item i where pack_code = ? order by id")
                .param(packCode).query(String.class).list()
                .forEach(s -> {
                    ObjectNode row = (ObjectNode) json.readTree(s);
                    out.put(row.get("item_key").asString(), row);
                });
        return out;
    }

    /** Inserts the registry row, or applies {@code values} to the existing one; returns the stored row. */
    public ObjectNode saveItem(ObjectNode existing, ObjectNode values) {
        if (existing == null) {
            return (ObjectNode) json.readTree(crud.insert(ITEM, values));
        }
        return update(ITEM, UUID.fromString(existing.get("id").asString()), values);
    }

    public ObjectNode insertRun(ObjectNode values) {
        return (ObjectNode) json.readTree(crud.insert(RUN, values));
    }

    public ObjectNode updateRun(UUID id, ObjectNode values) {
        return update(RUN, id, values);
    }

    public Optional<ObjectNode> run(UUID id) {
        return jdbc.sql("select row_to_json(r)::text from starter_pack_run r where id = ?")
                .param(id).query(String.class).optional().map(s -> (ObjectNode) json.readTree(s));
    }

    /**
     * Serialises run creation per tenant and pack (transaction-scoped advisory lock), then returns a run of the pack
     * that is still RUNNING and was touched in the last {@code staleMinutes} minutes (a crashed run goes stale and no
     * longer blocks a resume).
     */
    public Optional<ObjectNode> lockAndFindActive(String packCode, int staleMinutes) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(?))::text")
                .param("starter-pack:" + TenantContext.require() + ":" + packCode).query(String.class).single();
        List<String> rows = jdbc.sql("""
                select row_to_json(r)::text from starter_pack_run r
                where pack_code = ? and status = 'RUNNING' and not dry_run
                  and coalesce(updated_at, created_at) > now() - make_interval(mins => ?)
                order by id desc limit 1""").params(packCode, staleMinutes).query(String.class).list();
        return rows.stream().findFirst().map(s -> (ObjectNode) json.readTree(s));
    }

    private ObjectNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null)
                .map(s -> (ObjectNode) json.readTree(s))
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }
}
