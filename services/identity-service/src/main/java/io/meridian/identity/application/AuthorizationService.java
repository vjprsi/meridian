package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.identity.domain.AccessEvaluator;
import io.meridian.identity.domain.PolicyExpression;
import io.meridian.identity.infrastructure.IdentityStore;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Effective permissions (RBAC union over currently valid role assignments) and ABAC access simulation. */
@Service
public class AuthorizationService {
    private final IdentityStore store;
    private final JsonMapper json;

    public AuthorizationService(IdentityStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    private record Grant(String permissionId, String code, String domain, String resource, String action,
                         boolean sensitive, boolean requiresApproval, String roleCode, String conditions,
                         String scopeType, String scopeId) {}

    private List<Grant> grants(UUID userId) {
        return store.jdbc().sql("""
                select p.id, p.code, p.domain, p.resource, p.action, p.is_sensitive, p.requires_approval, r.code,
                       rp.conditions::text, ur.scope_type, ur.scope_id
                from user_role ur
                join role r on r.id = ur.role_id
                join role_permission rp on rp.role_id = r.id
                join permission p on p.id = rp.permission_id
                where ur.user_id = ? and ur.valid_from <= now() and (ur.valid_to is null or ur.valid_to > now())
                order by p.code, r.code""")
                .param(userId)
                .query((rs, i) -> new Grant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11)))
                .list();
    }

    private List<String> activeRoleCodes(UUID userId) {
        return store.jdbc().sql("""
                select distinct r.code from user_role ur join role r on r.id = ur.role_id
                where ur.user_id = ? and ur.valid_from <= now() and (ur.valid_to is null or ur.valid_to > now())
                order by r.code""").param(userId).query(String.class).list();
    }

    public JsonNode effectivePermissions(UUID userId) {
        JsonNode user = store.require("app_user", userId);
        String status = user.get("status").asString();
        ObjectNode out = json.createObjectNode();
        out.put("user_id", userId.toString());
        out.put("status", status);
        ArrayNode roles = out.putArray("roles");
        ArrayNode perms = out.putArray("permissions");
        if (!"ACTIVE".equals(status)) {
            out.put("note", "user is " + status + ": no permissions are effective");
            return out;
        }
        activeRoleCodes(userId).forEach(roles::add);
        Map<String, ObjectNode> byCode = new LinkedHashMap<>();
        for (Grant g : grants(userId)) {
            ObjectNode p = byCode.computeIfAbsent(g.code(), c -> {
                ObjectNode n = json.createObjectNode();
                n.put("permission_id", g.permissionId());
                n.put("code", g.code());
                n.put("domain", g.domain());
                n.put("resource", g.resource());
                n.put("action", g.action());
                n.put("is_sensitive", g.sensitive());
                n.put("requires_approval", g.requiresApproval());
                n.putArray("granted_by_roles");
                n.putArray("scopes");
                n.putArray("conditions");
                return n;
            });
            addDistinct((ArrayNode) p.get("granted_by_roles"), json.getNodeFactory().stringNode(g.roleCode()));
            ObjectNode scope = json.createObjectNode();
            scope.put("scope_type", g.scopeType());
            if (g.scopeId() != null) {
                scope.put("scope_id", g.scopeId());
            }
            addDistinct((ArrayNode) p.get("scopes"), scope);
            if (g.conditions() != null) {
                addDistinct((ArrayNode) p.get("conditions"), json.readTree(g.conditions()));
            }
        }
        byCode.values().forEach(perms::add);
        out.put("permission_count", perms.size());
        return out;
    }

    private static void addDistinct(ArrayNode arr, JsonNode v) {
        for (JsonNode e : arr) {
            if (e.equals(v)) {
                return;
            }
        }
        arr.add(v);
    }

    @SuppressWarnings("unchecked")
    public JsonNode simulate(UUID userId, String action, JsonNode resource) {
        if (action == null || action.isBlank()) {
            throw ApiException.unprocessable("action_required", "action is required");
        }
        if (resource == null || !resource.isObject()) {
            throw ApiException.unprocessable("invalid_resource", "resource must be a JSON object");
        }
        JsonNode user = store.find("app_user", userId).map(json::readTree)
                .orElseThrow(() -> ApiException.unprocessable("user_not_found", "User " + userId + " does not exist"));
        Map<String, Object> subject = json.convertValue(store.publicView(user.toString()), Map.class);
        List<String> roleCodes = activeRoleCodes(userId);
        subject.put("roles", roleCodes);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("subject", subject);
        ctx.put("resource", json.convertValue(resource, Map.class));
        ctx.put("action", action);
        ctx.put("context", Map.of("now", now.toString(), "hour", now.getHour(), "weekday", now.getDayOfWeek().name()));

        ObjectNode out = json.createObjectNode();
        out.put("user_id", userId.toString());
        out.put("action", action);
        if (!"ACTIVE".equals(user.get("status").asString())) {
            out.put("decision", "DENY");
            out.put("reason", "user_not_active");
            out.put("rbac_granted", false);
            out.putArray("evaluated_policies");
            return out;
        }
        String matched = null;
        Set<String> conditionFailures = new LinkedHashSet<>();
        for (Grant g : grants(userId)) {
            if (!AccessEvaluator.matches(g.code(), action)) {
                continue;
            }
            if (g.conditions() != null && json.readTree(g.conditions()).hasNonNull("expression")) {
                String expr = json.readTree(g.conditions()).get("expression").asString();
                boolean holds;
                try {
                    holds = PolicyExpression.parse(expr).evaluate(ctx);
                } catch (IllegalArgumentException e) {
                    holds = false;
                }
                if (!holds) {
                    conditionFailures.add(g.roleCode() + ": " + expr);
                    continue;
                }
            }
            matched = g.code();
            break;
        }
        List<AccessEvaluator.Policy> policies = new ArrayList<>();
        store.jdbc().sql("""
                select id, name, effect, priority, resource_pattern, actions::text, condition_expression
                from access_policy where is_active order by priority, name""")
                .query((rs, i) -> policies.add(new AccessEvaluator.Policy(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getString(5), toList(rs.getString(6)), rs.getString(7))))
                .list();
        AccessEvaluator.Decision d = AccessEvaluator.decide(action, matched != null, policies, ctx);
        out.put("decision", d.decision());
        out.put("reason", d.reason());
        out.put("rbac_granted", d.rbacGranted());
        if (matched != null) {
            out.put("matched_permission", matched);
        }
        if (!conditionFailures.isEmpty()) {
            ArrayNode f = out.putArray("grant_conditions_not_met");
            conditionFailures.forEach(f::add);
        }
        if (d.decidingPolicyId() != null) {
            out.put("deciding_policy_id", d.decidingPolicyId());
        }
        out.set("evaluated_policies", json.valueToTree(d.trace()));
        return out;
    }

    private List<String> toList(String jsonText) {
        return JsonItems.of(json.readTree(jsonText));
    }

    /** Validates a policy condition and action list on create/update. */
    public void validatePolicy(JsonNode v) {
        if (v.hasNonNull("condition_expression")) {
            try {
                PolicyExpression.parse(v.get("condition_expression").asString());
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("invalid_condition", "condition_expression: " + e.getMessage());
            }
        }
        if (v.has("actions") && JsonItems.of(v.get("actions")).isEmpty()) {
            throw ApiException.unprocessable("invalid_actions",
                    "actions must list at least one action, e.g. [\"approve\"]");
        }
        if (v.has("priority") && v.get("priority").asInt() < 0) {
            throw ApiException.unprocessable("invalid_priority", "priority must be >= 0");
        }
    }
}
