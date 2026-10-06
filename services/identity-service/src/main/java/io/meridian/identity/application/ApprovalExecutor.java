package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Executes identity operations once their maker-checker approval request (workflow-service) is APPROVED:
 * MFA reset, role permission grants and just-in-time elevations. Execution is idempotent.
 */
@Service
public class ApprovalExecutor {
    /** Resource types whose approvals this service executes. */
    public static final Set<String> RESOURCE_TYPES = Set.of("app_user", "role", "user_role");
    private static final Set<String> SYSTEM_PRINCIPALS = Set.of("system", "workflow-service", "identity-service");

    private final MfaService mfa;
    private final RoleService roles;
    private final UserRoleService userRoles;

    public ApprovalExecutor(MfaService mfa, RoleService roles, UserRoleService userRoles) {
        this.mfa = mfa;
        this.roles = roles;
        this.userRoles = userRoles;
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
        String principal = TenantContext.principal();
        String maker = approval.path("maker").asString("");
        if (isHuman(principal) && principal.equals(maker)) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The maker of an approval request cannot execute it as checker");
        }
        JsonNode payload = approval.path("payload");
        String key = approval.path("resource_type").asString("") + "/" + approval.path("action").asString("");
        return switch (key) {
            case "app_user/reset_mfa" -> mfa.resetAll(uuid(payload, "user_id"), payload.path("reason").asString(null));
            case "role/add_permissions" -> roles.grant(uuid(payload, "role_id"), payload.get("permission_ids"),
                    payload.get("conditions"));
            case "user_role/elevate" -> userRoles.grantElevation(uuid(approval, "id"), payload);
            default -> throw ApiException.unprocessable("unsupported_approval", "identity-service cannot execute " + key);
        };
    }

    static boolean isHuman(String principal) {
        return !SYSTEM_PRINCIPALS.contains(principal) && !principal.startsWith("service:");
    }

    private static UUID uuid(JsonNode n, String f) {
        if (!n.hasNonNull(f)) {
            throw ApiException.unprocessable("invalid_approval_payload", "approval payload lacks " + f);
        }
        return UUID.fromString(n.get(f).asString());
    }
}
