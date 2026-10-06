package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.identity.infrastructure.IdentityStore;
import io.meridian.identity.infrastructure.WorkflowGateway;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Role assignments with segregation-of-duties checks. Privileged roles are never assigned directly: they are granted
 * just in time through an approved, time-bound elevation.
 */
@Service
public class UserRoleService {
    public static final int MAX_ELEVATION_MINUTES = 1440;

    private final IdentityStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final WorkflowGateway workflow;
    private final UserService users;
    private final JsonMapper json;

    public UserRoleService(IdentityStore store, CrudEngine crud, OutboxWriter outbox, WorkflowGateway workflow,
                           UserService users, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.workflow = workflow;
        this.users = users;
        this.json = json;
    }

    @Transactional
    public String assign(Object body) {
        ObjectNode v = crud.toColumns("user_role", body);
        UUID userId = UUID.fromString(v.get("user_id").asString());
        UUID roleId = UUID.fromString(v.get("role_id").asString());
        JsonNode user = store.find("app_user", userId).map(json::readTree)
                .orElseThrow(() -> ApiException.unprocessable("user_not_found", "User " + userId + " does not exist"));
        JsonNode role = store.find("role", roleId).map(json::readTree)
                .orElseThrow(() -> ApiException.unprocessable("role_not_found", "Role " + roleId + " does not exist"));
        requireAssignable(user);
        if (role.get("is_privileged").asBoolean()) {
            throw ApiException.unprocessable("privileged_role_requires_elevation",
                    "Privileged role " + role.get("code").asString() + " is granted only via POST /v1/user-roles/elevate");
        }
        v.remove("approval_request_id");
        String scopeType = v.hasNonNull("scope_type") ? v.get("scope_type").asString() : "TENANT";
        if ("TENANT".equals(scopeType) == v.hasNonNull("scope_id")) {
            throw ApiException.unprocessable("invalid_scope",
                    "scope_id is required for LEGAL_ENTITY / BRANCH scope and must be empty for TENANT scope");
        }
        if (v.hasNonNull("valid_from") && v.hasNonNull("valid_to")
                && !OffsetDateTime.parse(v.get("valid_to").asString()).isAfter(OffsetDateTime.parse(v.get("valid_from").asString()))) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must be after valid_from");
        }
        Integer dup = store.jdbc().sql("""
                select count(*) from user_role where user_id = ? and role_id = ? and scope_type = ?
                and scope_id is not distinct from ?::uuid and (valid_to is null or valid_to > now())""")
                .params(userId, roleId, scopeType, v.hasNonNull("scope_id") ? v.get("scope_id").asString() : null)
                .query(Integer.class).single();
        if (dup > 0) {
            throw ApiException.conflict("duplicate_assignment", "User already holds this role in this scope");
        }
        checkSegregationOfDuties(userId, role);
        v.put("granted_by", TenantContext.principal());
        String row = crud.insert("user_role", v);
        granted(row);
        return row;
    }

    @Transactional
    public void revoke(UUID id) {
        JsonNode ur = store.lock("user_role", id);
        store.jdbc().sql("delete from user_role where id = ?").param(id).update();
        outbox.publish("platform.user_role.revoked", "user_role", id, ur);
    }

    /** Raises a just-in-time elevation request for the calling user. */
    public JsonNode requestElevation(UUID roleId, Integer durationMinutes, String justification, String idempotencyKey) {
        if (justification == null || justification.isBlank()) {
            throw ApiException.unprocessable("justification_required", "A business justification is required");
        }
        JsonNode user = json.readTree(users.me());
        JsonNode role = store.find("role", roleId).map(json::readTree)
                .orElseThrow(() -> ApiException.unprocessable("role_not_found", "Role " + roleId + " does not exist"));
        if (!role.get("is_privileged").asBoolean()) {
            throw ApiException.unprocessable("role_not_privileged",
                    "Elevation is only for privileged roles; assign other roles via POST /v1/user-roles");
        }
        int max = Math.min(MAX_ELEVATION_MINUTES, role.get("max_session_minutes").asInt());
        if (durationMinutes == null || durationMinutes < 1 || durationMinutes > max) {
            throw ApiException.unprocessable("invalid_duration", "duration_minutes must be between 1 and " + max);
        }
        if (!"ACTIVE".equals(user.get("status").asString())) {
            throw ApiException.conflict("user_not_active", "Only ACTIVE users can request elevation");
        }
        UUID userId = UUID.fromString(user.get("id").asString());
        checkSegregationOfDuties(userId, role);
        ObjectNode payload = json.createObjectNode();
        payload.put("user_id", userId.toString());
        payload.put("role_id", roleId.toString());
        payload.put("duration_minutes", durationMinutes);
        payload.put("justification", justification);
        return workflow.requestApproval("user_role", userId, "elevate",
                "Elevate " + user.get("username").asString() + " to " + role.get("code").asString(),
                user.get("username").asString() + " requests " + role.get("code").asString() + " for "
                        + durationMinutes + " minutes: " + justification,
                payload, "HIGH", idempotencyKey);
    }

    /** Executes an approved elevation: a time-bound assignment starting now (idempotent per approval). */
    @Transactional
    public JsonNode grantElevation(UUID approvalId, JsonNode payload) {
        List<String> existing = store.rows("select row_to_json(t)::text from user_role t where t.approval_request_id = ?",
                approvalId);
        if (!existing.isEmpty()) {
            return json.readTree(existing.getFirst());
        }
        UUID userId = UUID.fromString(payload.get("user_id").asString());
        UUID roleId = UUID.fromString(payload.get("role_id").asString());
        JsonNode user = store.lock("app_user", userId);
        if (!"ACTIVE".equals(user.get("status").asString())) {
            throw ApiException.conflict("user_not_active", "User is " + user.get("status").asString());
        }
        JsonNode role = store.require("role", roleId);
        checkSegregationOfDuties(userId, role);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ObjectNode v = json.createObjectNode();
        v.put("user_id", userId.toString());
        v.put("role_id", roleId.toString());
        v.put("scope_type", "TENANT");
        v.put("valid_from", now.toString());
        v.put("valid_to", now.plusMinutes(payload.get("duration_minutes").asInt()).toString());
        v.put("approval_request_id", approvalId.toString());
        v.put("justification", payload.path("justification").asString(""));
        v.put("granted_by", TenantContext.principal());
        String row = crud.insert("user_role", v);
        granted(row);
        return json.readTree(row);
    }

    private void checkSegregationOfDuties(UUID userId, JsonNode newRole) {
        String newId = newRole.get("id").asString();
        List<String> newConflicts = JsonItems.of(newRole.get("incompatible_role_ids"));
        for (String r : store.rows("""
                select row_to_json(r)::text from role r where r.id in (
                  select role_id from user_role where user_id = ? and (valid_to is null or valid_to > now()))""", userId)) {
            JsonNode held = json.readTree(r);
            if (held.get("id").asString().equals(newId)) {
                continue;
            }
            if (newConflicts.contains(held.get("id").asString())
                    || JsonItems.of(held.get("incompatible_role_ids")).contains(newId)) {
                throw ApiException.unprocessable("sod_conflict", "Role " + newRole.get("code").asString()
                        + " conflicts with held role " + held.get("code").asString() + " (segregation of duties)");
            }
        }
    }

    private static void requireAssignable(JsonNode user) {
        String s = user.get("status").asString();
        if ("DEACTIVATED".equals(s)) {
            throw ApiException.conflict("user_deactivated", "Roles cannot be assigned to a deactivated user");
        }
    }

    private void granted(String row) {
        JsonNode n = json.readTree(row);
        outbox.publish("platform.user_role.granted", "user_role", UUID.fromString(n.get("id").asString()), n);
    }
}
