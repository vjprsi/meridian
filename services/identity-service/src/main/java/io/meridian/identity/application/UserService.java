package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.identity.domain.UserLifecycle;
import io.meridian.identity.infrastructure.IdentityStore;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Staff user lifecycle: invitation, lock/unlock, deactivation, MFA reset request, self lookup. */
@Service
public class UserService {
    private final IdentityStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final SessionService sessions;
    private final IdentityApprovals workflow;
    private final JsonMapper json;

    public UserService(IdentityStore store, CrudEngine crud, OutboxWriter outbox, SessionService sessions,
                       IdentityApprovals workflow, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.sessions = sessions;
        this.workflow = workflow;
        this.json = json;
    }

    /** New users start INVITED (service accounts, which never accept an invitation, start ACTIVE). */
    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns("app_user", body);
        String type = v.hasNonNull("user_type") ? v.get("user_type").asString() : "STAFF";
        String initial = "SERVICE_ACCOUNT".equals(type) ? "ACTIVE" : "INVITED";
        if (v.hasNonNull("sso_provider_id")) {
            JsonNode sso = store.find("sso_provider", UUID.fromString(v.get("sso_provider_id").asString()))
                    .map(json::readTree)
                    .orElseThrow(() -> ApiException.unprocessable("sso_provider_not_found", "SSO provider does not exist"));
            if ("INACTIVE".equals(sso.get("status").asString())) {
                throw ApiException.unprocessable("sso_provider_inactive", "SSO provider is inactive");
            }
        }
        v.put("status", initial);
        if ("INVITED".equals(initial)) {
            v.put("invited_at", io.meridian.common.time.Clocks.now().toString());
        }
        String row = crud.insert("app_user", v);
        ObjectNode pub = store.publicView(row);
        outbox.publish("platform.app_user.created", "app_user", UUID.fromString(pub.get("id").asString()), pub);
        return row;
    }

    @Transactional
    public String invite(UUID id) {
        JsonNode u = store.lock("app_user", id);
        transition(u, UserLifecycle.Action.INVITE);
        return store.updateReturning("update app_user t set invited_at = now() where t.id = ? returning row_to_json(t)::text", id);
    }

    @Transactional
    public String lock(UUID id, String reason) {
        SessionService.requireReason(reason);
        JsonNode u = store.lock("app_user", id);
        String to = transition(u, UserLifecycle.Action.LOCK);
        String row = store.updateReturning("""
                update app_user t set status = ?, locked_until = null where t.id = ? returning row_to_json(t)::text""", to, id);
        sessions.revokeActive(id, "user locked: " + reason);
        return changed(row);
    }

    @Transactional
    public String unlock(UUID id, String reason) {
        SessionService.requireReason(reason);
        JsonNode u = store.lock("app_user", id);
        String to = transition(u, UserLifecycle.Action.UNLOCK);
        String row = store.updateReturning("""
                update app_user t set status = ?, locked_until = null, failed_login_count = 0
                where t.id = ? returning row_to_json(t)::text""", to, id);
        return changed(row);
    }

    /** Deactivation is terminal: revokes all sessions and ends every role assignment. */
    @Transactional
    public String deactivate(UUID id, String reason) {
        SessionService.requireReason(reason);
        JsonNode u = store.lock("app_user", id);
        String to = transition(u, UserLifecycle.Action.DEACTIVATE);
        String row = store.updateReturning("""
                update app_user t set status = ?, deactivated_at = now() where t.id = ? returning row_to_json(t)::text""",
                to, id);
        sessions.revokeActive(id, "user deactivated: " + reason);
        for (String ur : store.rows("""
                update user_role t set valid_to = now()
                where t.user_id = ? and (t.valid_to is null or t.valid_to > now())
                returning row_to_json(t)::text""", id)) {
            JsonNode n = json.readTree(ur);
            outbox.publish("platform.user_role.revoked", "user_role", UUID.fromString(n.get("id").asString()), n);
        }
        return changed(row);
    }

    /** INVITED users become ACTIVE when they complete MFA enrolment (accepting the invitation). */
    @Transactional
    public void activateIfInvited(UUID id) {
        JsonNode u = store.lock("app_user", id);
        if (UserLifecycle.allowed(u.get("status").asString(), UserLifecycle.Action.ACTIVATE)) {
            changed(store.updateReturning("update app_user t set status = 'ACTIVE' where t.id = ? returning row_to_json(t)::text", id));
        }
    }

    /** Raises a maker-checker request to remove all MFA factors of the user. */
    public JsonNode requestMfaReset(UUID id, String reason, String idempotencyKey) {
        SessionService.requireReason(reason);
        JsonNode u = store.require("app_user", id);
        if ("DEACTIVATED".equals(u.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "User " + id + " is deactivated");
        }
        Integer factors = store.jdbc().sql("select count(*) from mfa_factor where user_id = ? and status <> 'DISABLED'")
                .param(id).query(Integer.class).single();
        if (factors == 0) {
            throw ApiException.unprocessable("no_mfa_factors", "User has no MFA factors to reset");
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("user_id", id.toString());
        payload.put("reason", reason);
        payload.put("factor_count", factors);
        return workflow.requestApproval("app_user", id, "reset_mfa",
                "Reset MFA for " + u.get("username").asString(),
                "Remove all " + factors + " MFA factor(s) of " + u.get("username").asString() + ": " + reason,
                payload, "HIGH", idempotencyKey);
    }

    /** The app_user matching the calling principal by username, email or IdP subject. */
    public String me() {
        String principal = TenantContext.principal();
        TenantContext.require();
        return store.jdbc().sql("""
                select row_to_json(t)::text from app_user t
                where t.username = ? or lower(t.email) = lower(?) or t.external_subject = ?
                order by (t.username = ?) desc limit 1""")
                .params(principal, principal, principal, principal)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("user", principal));
    }

    public JsonNode require(UUID id) {
        return store.require("app_user", id);
    }

    private String changed(String row) {
        ObjectNode pub = store.publicView(row);
        outbox.publish("platform.app_user.status_changed", "app_user", UUID.fromString(pub.get("id").asString()), pub);
        return row;
    }

    private static String transition(JsonNode user, UserLifecycle.Action action) {
        try {
            return UserLifecycle.next(user.get("status").asString(), action);
        } catch (IllegalStateException e) {
            throw ApiException.conflict("invalid_transition", e.getMessage());
        }
    }
}
