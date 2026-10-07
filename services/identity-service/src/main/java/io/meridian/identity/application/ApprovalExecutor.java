package io.meridian.identity.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.identity.infrastructure.IdentityStore;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes identity operations once their maker-checker approval request (workflow-service) is APPROVED:
 * MFA reset, role permission grants and just-in-time elevations. Execution is idempotent.
 *
 * <p>SEC-03: what is applied comes only from identity-service's own {@code privileged_change} record, created when the
 * maker submitted and bound to the approval request id; the approval payload is never trusted. Four-eyes (the checker
 * is neither the maker nor, for an elevation, the elevated user) and the elevation policy (privileged role, maximum
 * duration, requester = elevated user, active user, segregation of duties) are re-checked at execution time.
 */
@Service
public class ApprovalExecutor {
    /** Resource types whose approvals this service executes. */
    public static final Set<String> RESOURCE_TYPES = Set.of("app_user", "role", "user_role");
    private static final Set<String> SYSTEM_PRINCIPALS = Set.of("system", "workflow-service", "identity-service");

    private final MfaService mfa;
    private final RoleService roles;
    private final UserRoleService userRoles;
    private final IdentityStore store;
    private final JsonMapper json;

    public ApprovalExecutor(MfaService mfa, RoleService roles, UserRoleService userRoles, IdentityStore store,
                            JsonMapper json) {
        this.mfa = mfa;
        this.roles = roles;
        this.userRoles = userRoles;
        this.store = store;
        this.json = json;
    }

    public static boolean handles(JsonNode approval) {
        return RESOURCE_TYPES.contains(approval.path("resource_type").asString(""));
    }

    @Transactional
    public JsonNode applyApproved(JsonNode approval) {
        TenantContext.require();
        String status = approval.path("status").asString("");
        if (!"APPROVED".equals(status)) {
            throw ApiException.conflict("approval_not_approved", "Approval request is " + status + ", not APPROVED");
        }
        UUID approvalId = uuid(approval, "id");
        JsonNode change = lockChange(approvalId).orElseThrow(() -> ApiException.unprocessable("unknown_change",
                "No privileged change of identity-service is bound to approval request " + approvalId));
        String operation = change.get("operation").asString();
        String key = approval.path("resource_type").asString("") + "/" + approval.path("action").asString("");
        if (!operation.equals(key)) {
            throw ApiException.unprocessable("approval_mismatch", "Approval " + approvalId + " is for " + key
                    + " but the submitted change is " + operation);
        }
        if ("APPLIED".equals(change.get("status").asString())) {
            return change; // idempotent: at-least-once delivery of the decision
        }
        if (!"PENDING".equals(change.get("status").asString())) {
            throw ApiException.conflict("change_not_pending", "The change is " + change.get("status").asString());
        }
        String principal = TenantContext.principal();
        String maker = change.get("requested_by").asString();
        if (isHuman(principal) && principal.equals(maker)) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The maker of an approval request cannot execute it as checker");
        }
        JsonNode details = change.get("details");
        UUID target = UUID.fromString(change.get("resource_id").asString());
        JsonNode result = switch (operation) {
            case "app_user/reset_mfa" -> {
                requireSameId(details, "user_id", target);
                yield mfa.resetAll(target, details.path("reason").asString(null));
            }
            case "role/add_permissions" -> {
                requireSameId(details, "role_id", target);
                yield roles.grant(target, details.get("permission_ids"), details.get("conditions"));
            }
            case "user_role/elevate" -> {
                requireSameId(details, "user_id", target);
                JsonNode user = store.require("app_user", target);
                if (!isUser(user, maker)) {
                    throw ApiException.unprocessable("elevation_not_self", "An elevation is only for the user who requested it");
                }
                if (isHuman(principal) && isUser(user, principal)) {
                    throw ApiException.unprocessable("four_eyes_violation", "The elevated user cannot approve the elevation");
                }
                yield userRoles.grantElevation(approvalId, details);
            }
            default -> throw ApiException.unprocessable("unsupported_approval", "identity-service cannot execute " + operation);
        };
        store.jdbc().sql("""
                update privileged_change set status = 'APPLIED', decided_by = ?, decided_at = ?, updated_by = ?
                where approval_request_id = ?""")
                .params(principal, Clocks.now(), principal, approvalId).update();
        return result;
    }

    /** A final decision other than APPROVED closes the recorded change (no effect). */
    @Transactional
    public void closeUnapproved(ApprovalDecision d) {
        if (d.approvalRequestId() == null) {
            return;
        }
        String status = switch (d.outcome()) {
            case REJECTED, CHANGES_REQUESTED -> "REJECTED";
            case EXPIRED, CANCELLED -> "CANCELLED";
            case APPROVED -> null;
        };
        if (status == null) {
            return;
        }
        store.jdbc().sql("""
                update privileged_change set status = ?, decided_by = ?, decided_at = ?, updated_by = ?
                where approval_request_id = ? and status = 'PENDING'""")
                .params(status, TenantContext.principal(), Clocks.now(), TenantContext.principal(), d.approvalRequestId())
                .update();
    }

    private Optional<JsonNode> lockChange(UUID approvalId) {
        return store.jdbc().sql("select row_to_json(t)::text from privileged_change t where approval_request_id = ? for update")
                .param(approvalId).query(String.class).optional().map(json::readTree);
    }

    private static boolean isUser(JsonNode user, String principal) {
        return principal != null && (principal.equals(user.path("username").asString(null))
                || principal.equalsIgnoreCase(user.path("email").asString(""))
                || principal.equals(user.path("external_subject").asString(null))
                || principal.equals(user.path("id").asString(null)));
    }

    private static void requireSameId(JsonNode details, String field, UUID target) {
        if (!details.hasNonNull(field) || !target.toString().equals(details.get(field).asString())) {
            throw ApiException.unprocessable("invalid_change", "The recorded change does not target " + target);
        }
    }

    static boolean isHuman(String principal) {
        return !SYSTEM_PRINCIPALS.contains(principal) && !principal.startsWith("service:") && !principal.startsWith("svc:");
    }

    private static UUID uuid(JsonNode n, String f) {
        if (!n.hasNonNull(f)) {
            throw ApiException.unprocessable("invalid_approval", "approval request lacks " + f);
        }
        return UUID.fromString(n.get(f).asString());
    }
}
