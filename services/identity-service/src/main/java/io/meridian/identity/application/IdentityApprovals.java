package io.meridian.identity.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * Maker-checker for identity operations through workflow-service (G11, libs/meridian-approvals): MFA reset
 * ({@code app_user}), role permission grants ({@code role}) and just-in-time elevations ({@code user_role}).
 * These operations have no local decision endpoint, so they are always routed to workflow-service whatever
 * {@code meridian.approvals.mode} says; approved requests come back through one {@link ApprovalOutcomeHandler} per
 * resource type and are executed by {@link ApprovalExecutor} with the deciding approver as principal.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityApprovals {
    private final Approvals approvals;

    public IdentityApprovals(ServiceClient client, @Value("${spring.application.name}") String application) {
        this.approvals = new Approvals(client, application, "workflow");
    }

    /** Opens an approval request in workflow-service (idempotent per operation and request idempotency key). */
    public JsonNode requestApproval(String resourceType, UUID resourceId, String action, String title, String summary,
                                    JsonNode payload, String priority, String idempotencyKey) {
        ApprovalSpec spec = new ApprovalSpec(resourceType, resourceId, action, title, summary, payload, null, null, priority);
        JsonNode created = approvals.request(spec, resourceType + ":" + action + ":" + resourceId + ":" + idempotencyKey);
        if (created == null || !created.hasNonNull("id")) {
            throw new ApiException(502, "upstream_error", Approvals.WORKFLOW + " did not return the created approval request");
        }
        return created;
    }

    @Bean
    ApprovalOutcomeHandler appUserApprovalHandler(ApprovalExecutor executor) {
        return handler("app_user", executor);
    }

    @Bean
    ApprovalOutcomeHandler roleApprovalHandler(ApprovalExecutor executor) {
        return handler("role", executor);
    }

    @Bean
    ApprovalOutcomeHandler userRoleApprovalHandler(ApprovalExecutor executor) {
        return handler("user_role", executor);
    }

    /**
     * Executes APPROVED requests (idempotent: grants use on-conflict inserts, elevations are keyed by the approval
     * request id, MFA reset of already-removed factors is a no-op). Other outcomes changed nothing locally.
     */
    private static ApprovalOutcomeHandler handler(String resourceType, ApprovalExecutor executor) {
        return new ApprovalOutcomeHandler() {
            @Override
            public String resourceType() {
                return resourceType;
            }

            @Override
            public void onDecided(ApprovalDecision d) {
                if (d.approved()) {
                    executor.applyApproved(d.request());
                }
            }
        };
    }
}
