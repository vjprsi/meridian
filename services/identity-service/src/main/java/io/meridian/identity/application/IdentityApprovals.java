package io.meridian.identity.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.identity.infrastructure.IdentityStore;
import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Maker-checker for identity operations through workflow-service (G11, libs/meridian-approvals): MFA reset
 * ({@code app_user}), role permission grants ({@code role}), just-in-time elevations ({@code user_role}) and access
 * review decisions ({@code access_review}, GAP-13).
 * These operations have no local decision endpoint, so they are always routed to workflow-service whatever
 * {@code meridian.approvals.mode} says; approved requests come back through one {@link ApprovalOutcomeHandler} per
 * resource type and are executed by {@link ApprovalExecutor} with the deciding approver as principal.
 *
 * <p>SEC-03: every submission is also recorded locally as a {@code privileged_change} (what the maker asked for, as
 * validated here, bound to the approval request id). The executor applies only such a record, never the approval
 * payload, so a request opened elsewhere (or a payload edited in transit) can never grant anything.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityApprovals {
    private final Approvals approvals;
    private final IdentityStore store;

    public IdentityApprovals(ServiceClient client, @Value("${spring.application.name}") String application,
                             IdentityStore store) {
        this.approvals = new Approvals(client, application, "workflow");
        this.store = store;
    }

    /**
     * Opens an approval request in workflow-service (idempotent per operation and request idempotency key) and records
     * the pending change it decides, in the same transaction.
     */
    @Transactional
    public JsonNode requestApproval(String resourceType, UUID resourceId, String action, String title, String summary,
                                    JsonNode payload, String priority, String idempotencyKey) {
        ApprovalSpec spec = new ApprovalSpec(resourceType, resourceId, action, title, summary, payload, null, null, priority);
        JsonNode created = approvals.request(spec, resourceType + ":" + action + ":" + resourceId + ":" + idempotencyKey);
        if (created == null || !created.hasNonNull("id")) {
            throw new ApiException(502, "upstream_error", Approvals.WORKFLOW + " did not return the created approval request");
        }
        store.jdbc().sql("""
                insert into privileged_change (tenant_id, operation, resource_id, details, requested_by, approval_request_id,
                                               created_by, updated_by)
                values (?::uuid, ?, ?, ?::jsonb, ?, ?, ?, ?)
                on conflict (tenant_id, approval_request_id) do nothing""")
                .params(TenantContext.require().toString(), resourceType + "/" + action, resourceId, payload.toString(),
                        TenantContext.principal(), UUID.fromString(created.get("id").asString()),
                        TenantContext.principal(), TenantContext.principal())
                .update();
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

    /** GAP-13: second-person approval of an access review's attest / revoke decisions. */
    @Bean
    ApprovalOutcomeHandler accessReviewApprovalHandler(ApprovalExecutor executor) {
        return handler(AccessReviewService.RESOURCE_TYPE, executor);
    }

    /**
     * Executes APPROVED requests from the recorded change (idempotent: an APPLIED change is not applied again); other
     * final outcomes close the recorded change.
     */
    private static ApprovalOutcomeHandler handler(String resourceType, ApprovalExecutor executor) {
        return new ApprovalOutcomeHandler() {
            @Override
            public boolean centralOnly() {
                return true; // no local approve endpoint: decided in workflow-service only
            }

            @Override
            public String resourceType() {
                return resourceType;
            }

            @Override
            public void onDecided(ApprovalDecision d) {
                if (d.approved()) {
                    executor.applyApproved(d.request());
                } else {
                    executor.closeUnapproved(d);
                }
            }
        };
    }
}
