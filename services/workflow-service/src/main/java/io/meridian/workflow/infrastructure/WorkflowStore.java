package io.meridian.workflow.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
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

/** SQL beyond generic CRUD: row locking, partial updates and the approvals inbox query. */
@Repository
public class WorkflowStore {
    private static final Set<String> TABLES = Set.of(
            "approval_policy", "approval_request", "approval_decision", "workflow_definition", "workflow_instance",
            "work_task");

    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;

    public WorkflowStore(JdbcClient jdbc, CrudEngine crud, JsonMapper json) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
    }

    /** Locks the row for the rest of the transaction; 404 when it does not exist for this tenant. */
    public ObjectNode lock(String table, UUID id) {
        return (ObjectNode) json.readTree(jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ? for update")
                .param(id).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id)));
    }

    public ObjectNode get(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<ObjectNode> find(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ?")
                .param(id).query(String.class).optional().map(s -> (ObjectNode) json.readTree(s));
    }

    /** Applies the given column values (nulls clear the column) and returns the updated row. */
    public ObjectNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null)
                .map(s -> (ObjectNode) json.readTree(s))
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public ObjectNode insert(String table, ObjectNode values) {
        return (ObjectNode) json.readTree(crud.insert(table, values));
    }

    public Optional<ObjectNode> activePolicy(String resourceType, String action) {
        return jdbc.sql("""
                select row_to_json(p)::text from approval_policy p
                where resource_type = ? and action = ? and is_active""")
                .params(resourceType, action).query(String.class).optional().map(s -> (ObjectNode) json.readTree(s));
    }

    public boolean hasPendingRequest(String resourceType, UUID resourceId, String action) {
        return jdbc.sql("""
                select count(*) from approval_request
                where resource_type = ? and resource_id = ? and action = ? and status = 'PENDING'""")
                .params(resourceType, resourceId, action).query(Long.class).single() > 0;
    }

    public List<JsonNode> decisions(UUID requestId) {
        return jdbc.sql("select row_to_json(d)::text from approval_decision d where approval_request_id = ? order by id")
                .param(requestId).query(String.class).list().stream().map(json::readTree).toList();
    }

    public int approvalsAtLevel(UUID requestId, int level) {
        return jdbc.sql("""
                select count(*) from approval_decision
                where approval_request_id = ? and level = ? and decision = 'APPROVE'""")
                .params(requestId, level).query(Integer.class).single();
    }

    public boolean hasDecided(UUID requestId, String approver) {
        return jdbc.sql("select count(*) from approval_decision where approval_request_id = ? and approver = ?")
                .params(requestId, approver).query(Long.class).single() > 0;
    }

    /**
     * Pending, unexpired requests the caller may decide now: not their own, not already decided by them, and
     * the current level's approver roles (from the route snapshot) intersect the caller's roles.
     */
    public String inbox(String principal, Set<String> roles, Integer limitParam, String cursor) {
        int limit = Cursor.limit(limitParam);
        StringBuilder sql = new StringBuilder("""
                select row_to_json(r)::text from approval_request r
                left join approval_policy p on p.id = r.approval_policy_id
                where r.status = 'PENDING'
                  and (r.expires_at is null or r.expires_at > now())
                  and (r.maker <> ? or (coalesce(p.allow_self_approval, false) and r.amount is null))
                  and not exists (select 1 from approval_decision d
                                  where d.approval_request_id = r.id and d.approver = ?)
                  and (coalesce(jsonb_array_length(
                           r.approval_route -> (r.current_level - 1) -> 'approver_roles'), 0) = 0
                       or exists (select 1 from jsonb_array_elements_text(
                           r.approval_route -> (r.current_level - 1) -> 'approver_roles') role
                           where role = any(?)))""");
        List<Object> args = new ArrayList<>(List.of(principal, principal, roles.toArray(String[]::new)));
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" and r.id < ?");
            args.add(Cursor.decode(cursor));
        }
        sql.append(" order by r.id desc limit ?");
        args.add(limit + 1);
        List<String> rows = jdbc.sql(sql.toString()).params(args).query(String.class).list();
        return page(rows, limit);
    }

    public List<ObjectNode> openTasksOfInstance(UUID instanceId) {
        return jdbc.sql("""
                select row_to_json(t)::text from work_task t
                where workflow_instance_id = ? and status in ('OPEN', 'CLAIMED', 'IN_PROGRESS', 'ESCALATED')
                order by id for update""")
                .param(instanceId).query(String.class).list().stream().map(s -> (ObjectNode) json.readTree(s)).toList();
    }

    public int nextDefinitionVersion(String code) {
        return jdbc.sql("select coalesce(max(version), 0) + 1 from workflow_definition where code = ?")
                .param(code).query(Integer.class).single();
    }

    public List<UUID> publishedVersionsOf(String code, UUID except) {
        return jdbc.sql("select id from workflow_definition where code = ? and status = 'PUBLISHED' and id <> ? for update")
                .params(code, except).query(UUID.class).list();
    }

    public List<UUID> overdueTasks() {
        return jdbc.sql("""
                select id from work_task
                where not sla_breached and due_at < now() and status in ('OPEN', 'CLAIMED', 'IN_PROGRESS', 'ESCALATED')
                order by id limit 500 for update skip locked""")
                .query(UUID.class).list();
    }

    public List<UUID> expiredRequests() {
        return jdbc.sql("""
                select id from approval_request where status = 'PENDING' and expires_at < now()
                order by id limit 500 for update skip locked""")
                .query(UUID.class).list();
    }

    /** Tenants with recent API activity (the idempotency table and outbox are not tenant-scoped). */
    public List<UUID> activeTenants() {
        return jdbc.sql("select distinct tenant_id from idempotency_key").query(UUID.class).list();
    }

    private String page(List<String> rows, int limit) {
        boolean more = rows.size() > limit;
        List<String> data = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            next = Cursor.encode(UUID.fromString(json.readTree(data.getLast()).get("id").asString()));
        }
        return "{\"data\":[" + String.join(",", data) + "],\"has_more\":" + more
                + ",\"next_cursor\":" + (next == null ? "null" : "\"" + next + "\"") + "}";
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
