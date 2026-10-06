package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.identity.infrastructure.IdentityStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Roles and their permission grants. SYSTEM roles are read-only; grants go through maker-checker. */
@Service
public class RoleService {
    private final IdentityStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final IdentityApprovals workflow;
    private final JsonMapper json;

    public RoleService(IdentityStore store, CrudEngine crud, OutboxWriter outbox, IdentityApprovals workflow,
                       JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.workflow = workflow;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns("role", body);
        validate(v, null);
        String row = crud.insert("role", v);
        changed(row);
        return row;
    }

    @Transactional
    public <T> ResponseEntity<T> update(UUID id, String ifMatch, Object body, Class<T> type) {
        JsonNode role = store.require("role", id);
        requireCustom(role);
        ObjectNode v = crud.toColumns("role", body);
        validate(v, id);
        ResponseEntity<T> r = crud.update("role", type, body, ifMatch, Crud.params().with("id", id));
        changed(store.find("role", id).orElseThrow());
        return r;
    }

    @Transactional
    public void delete(UUID id) {
        JsonNode role = store.lock("role", id);
        requireCustom(role);
        Integer assigned = store.jdbc().sql("""
                select count(*) from user_role where role_id = ? and (valid_to is null or valid_to > now())""")
                .param(id).query(Integer.class).single();
        if (assigned > 0) {
            throw ApiException.conflict("role_in_use", "Role is assigned to " + assigned + " user(s); revoke first");
        }
        Integer sso = store.jdbc().sql("select count(*) from sso_provider where default_role_id = ?")
                .param(id).query(Integer.class).single();
        if (sso > 0) {
            throw ApiException.conflict("role_in_use", "Role is the default role of an SSO provider");
        }
        store.jdbc().sql("delete from user_role where role_id = ?").param(id).update();
        store.jdbc().sql("delete from role_permission where role_id = ?").param(id).update();
        store.jdbc().sql("delete from role where id = ?").param(id).update();
        outbox.publish("platform.role.changed", "role", id, role);
    }

    /** Copies a role (attributes and permission grants) as a new CUSTOM role. */
    @Transactional
    public String cloneRole(UUID sourceId, String code, String name) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw ApiException.unprocessable("validation_failed", "code and name are required");
        }
        JsonNode src = store.require("role", sourceId);
        ObjectNode v = json.createObjectNode();
        UUID newId = Ids.newId();
        v.put("id", newId.toString());
        v.put("code", code);
        v.put("name", name);
        v.put("role_type", "CUSTOM");
        for (String f : List.of("description", "is_privileged", "requires_mfa", "max_session_minutes",
                "incompatible_role_ids")) {
            if (src.hasNonNull(f)) {
                v.set(f, src.get(f));
            }
        }
        String row = crud.insert("role", v);
        store.jdbc().sql("""
                insert into role_permission (tenant_id, role_id, permission_id, conditions, created_by, updated_by)
                select tenant_id, ?, permission_id, conditions, ?, ? from role_permission where role_id = ?""")
                .params(newId, io.meridian.common.tenancy.TenantContext.principal(),
                        io.meridian.common.tenancy.TenantContext.principal(), sourceId)
                .update();
        changed(row);
        return row;
    }

    /** Raises a maker-checker request to grant permissions to a role; executed by {@link #grant} once approved. */
    public JsonNode requestGrant(UUID roleId, JsonNode permissionIds, JsonNode conditions, String idempotencyKey) {
        JsonNode role = store.require("role", roleId);
        requireCustom(role);
        List<UUID> ids = permissionIds(permissionIds);
        List<String> codes = new ArrayList<>();
        for (UUID pid : ids) {
            codes.add(store.find("permission", pid).map(json::readTree)
                    .orElseThrow(() -> ApiException.unprocessable("unknown_permission", "Permission " + pid + " does not exist"))
                    .get("code").asString());
        }
        List<UUID> missing = ids.stream().filter(pid -> store.jdbc()
                .sql("select count(*) from role_permission where role_id = ? and permission_id = ?")
                .params(roleId, pid).query(Integer.class).single() == 0).toList();
        if (missing.isEmpty()) {
            throw ApiException.unprocessable("already_granted", "All permissions are already granted to the role");
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("role_id", roleId.toString());
        payload.set("permission_ids", JsonItems.canonical(json, ids.stream().map(UUID::toString).toList()));
        if (conditions != null && !conditions.isNull()) {
            payload.set("conditions", conditions);
        }
        return workflow.requestApproval("role", roleId, "add_permissions",
                "Grant " + ids.size() + " permission(s) to " + role.get("code").asString(),
                "Grant " + String.join(", ", codes) + " to role " + role.get("code").asString(),
                payload, "NORMAL", idempotencyKey);
    }

    /** Executes an approved grant: inserts missing role_permission rows (idempotent). */
    @Transactional
    public JsonNode grant(UUID roleId, JsonNode permissionIds, JsonNode conditions) {
        JsonNode role = store.lock("role", roleId);
        requireCustom(role);
        int added = 0;
        for (UUID pid : permissionIds(permissionIds)) {
            added += store.jdbc().sql("""
                    insert into role_permission (tenant_id, role_id, permission_id, conditions, created_by, updated_by)
                    select ?::uuid, ?, ?, ?::jsonb, ?, ? where exists (select 1 from permission where id = ?)
                    on conflict (role_id, permission_id) do nothing""")
                    .params(io.meridian.common.tenancy.TenantContext.require().toString(), roleId, pid,
                            conditions == null || conditions.isNull() ? null : conditions.toString(),
                            io.meridian.common.tenancy.TenantContext.principal(),
                            io.meridian.common.tenancy.TenantContext.principal(), pid)
                    .update();
        }
        String row = store.find("role", roleId).orElseThrow();
        if (added > 0) {
            changed(row);
        }
        ObjectNode out = json.createObjectNode();
        out.put("role_id", roleId.toString());
        out.put("permissions_added", added);
        return out;
    }

    @Transactional
    public void deleteRolePermission(UUID id) {
        JsonNode rp = store.lock("role_permission", id);
        UUID roleId = UUID.fromString(rp.get("role_id").asString());
        requireCustom(store.require("role", roleId));
        store.jdbc().sql("delete from role_permission where id = ?").param(id).update();
        changed(store.find("role", roleId).orElseThrow());
    }

    public JsonNode require(UUID id) {
        return store.require("role", id);
    }

    private void validate(ObjectNode v, UUID self) {
        if (v.has("max_session_minutes")) {
            int m = v.get("max_session_minutes").asInt();
            if (m < 5 || m > 1440) {
                throw ApiException.unprocessable("invalid_session_length", "max_session_minutes must be 5..1440");
            }
        }
        if (v.hasNonNull("incompatible_role_ids")) {
            List<String> ids = JsonItems.of(v.get("incompatible_role_ids"));
            Set<String> seen = new LinkedHashSet<>();
            for (String s : ids) {
                UUID rid;
                try {
                    rid = UUID.fromString(s);
                } catch (IllegalArgumentException e) {
                    throw ApiException.unprocessable("invalid_incompatible_role", s + " is not a role id");
                }
                if (rid.equals(self)) {
                    throw ApiException.unprocessable("invalid_incompatible_role", "A role cannot conflict with itself");
                }
                if (store.find("role", rid).isEmpty()) {
                    throw ApiException.unprocessable("invalid_incompatible_role", "Role " + rid + " does not exist");
                }
                seen.add(rid.toString());
            }
            v.set("incompatible_role_ids", JsonItems.canonical(json, new ArrayList<>(seen)));
        }
    }

    private List<UUID> permissionIds(JsonNode node) {
        List<String> raw = JsonItems.of(node);
        if (raw.isEmpty()) {
            throw ApiException.unprocessable("permission_ids_required",
                    "permission_ids must list at least one permission id");
        }
        List<UUID> out = new ArrayList<>();
        for (String s : new LinkedHashSet<>(raw)) {
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("invalid_permission_id", s + " is not a permission id");
            }
        }
        return out;
    }

    static void requireCustom(JsonNode role) {
        if ("SYSTEM".equals(role.get("role_type").asString())) {
            throw ApiException.conflict("system_role_immutable", "SYSTEM role " + role.get("code").asString()
                    + " cannot be changed; clone it into a custom role");
        }
    }

    private void changed(String row) {
        JsonNode n = json.readTree(row);
        outbox.publish("platform.role.changed", "role", UUID.fromString(n.get("id").asString()), n);
    }
}
