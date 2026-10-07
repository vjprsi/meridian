package io.meridian.approvals;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Client for central maker-checker approvals owned by workflow-service (backlog G11).
 *
 * <p>Mode {@code meridian.approvals.mode}: {@code workflow} sends approval requests to workflow-service and the
 * decision returns through {@link ApprovalOutcomeHandler}; {@code local} (the default, used by tests) keeps the
 * service's own four-eyes endpoints as the only path, and {@link #request} returns null.
 */
public class Approvals {
    public static final String WORKFLOW = "workflow-service";
    private static final int MAX_KEY = 128;

    private final ServiceClient client;
    private final String application;
    private final boolean workflowMode;

    public Approvals(ServiceClient client, String application, String mode) {
        this.client = client;
        this.application = application;
        this.workflowMode = "workflow".equalsIgnoreCase(mode);
    }

    /** True when approvals are routed to workflow-service. */
    public boolean centralised() {
        return workflowMode;
    }

    /**
     * Opens an approval request in workflow-service, idempotent per {@code idempotencyKey}. Returns the request
     * row, or null in local mode. The maker is the current principal (propagated by {@link ServiceClient}).
     */
    public JsonNode request(ApprovalSpec spec, String idempotencyKey) {
        if (!workflowMode) {
            return null;
        }
        return requestCentral(spec, idempotencyKey);
    }

    /**
     * Opens the request whatever the mode, for resources that can only be decided centrally (pair with a
     * {@link ApprovalOutcomeHandler#centralOnly() central-only} handler). Fails with 502 when workflow-service
     * returns no request id.
     */
    public JsonNode requestCentral(ApprovalSpec spec, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource_type", spec.resourceType());
        if (spec.resourceId() != null) {
            body.put("resource_id", spec.resourceId().toString());
        }
        body.put("action", spec.action());
        body.put("title", spec.title());
        if (spec.summary() != null) {
            body.put("summary", spec.summary());
        }
        body.put("payload", spec.payload() == null ? Map.of() : spec.payload());
        if (spec.amount() != null) {
            body.put("amount", spec.amount().toPlainString());
            body.put("currency", spec.currency());
        }
        body.put("priority", spec.priority());
        // SEC-03: opened with this service's own identity (X-User stays the maker) so workflow-service can check that
        // the resource type belongs to the calling service; user tokens may not open service-owned request types.
        String key = key(idempotencyKey);
        JsonNode created = ServiceClient.asService(() -> client.post(WORKFLOW, "/v1/approval-requests", body, key));
        if (idOf(created) == null) {
            throw new io.meridian.common.error.ApiException(502, "upstream_error",
                    "workflow-service did not return an approval request id");
        }
        return created;
    }

    /** The id of a request returned by {@link #request}, or null. */
    public static UUID idOf(JsonNode request) {
        return request == null || !request.hasNonNull("id") ? null : UUID.fromString(request.get("id").asString());
    }

    /**
     * Cancels an open request, e.g. when the maker withdraws or the change was decided directly in the owning
     * service. No-op in local mode or for a null id.
     */
    public void cancel(UUID approvalRequestId, String reason) {
        if (!workflowMode || approvalRequestId == null) {
            return;
        }
        client.post(WORKFLOW, "/v1/approval-requests/" + approvalRequestId + "/cancel",
                Map.of("reason", reason == null ? "withdrawn" : reason), key("cancel:" + approvalRequestId));
    }

    /**
     * Cancels every PENDING request for the resource (optionally one action), e.g. after the owning service decided
     * the change directly. Inside a transaction this runs after commit (and returns 0); otherwise returns how many were cancelled. 0 in local mode.
     */
    public int cancelOpen(String resourceType, UUID resourceId, String action, String reason) {
        return cancelOpenExcept(resourceType, resourceId, action, null, reason);
    }

    /**
     * Like {@link #cancelOpen} but keeps {@code keepRequestId}: use after a resubmission to retire the superseded
     * request(s) without cancelling the one just opened.
     */
    public int cancelOpenExcept(String resourceType, UUID resourceId, String action, UUID keepRequestId, String reason) {
        if (!workflowMode || resourceId == null) {
            return 0;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            // Only once the local decision is committed; best effort (a stale inbox item is harmless: the handler
            // finds the resource no longer waiting and ignores the decision).
            UUID tenant = TenantContext.current().orElse(null);
            String principal = TenantContext.principal();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                TenantContext.callAs(tenant, principal, () -> cancelOpenNow(resourceType, resourceId, action, keepRequestId, reason));
                            } catch (Exception e) {
                                org.slf4j.LoggerFactory.getLogger(Approvals.class).warn(
                                        "Could not cancel open approvals for {} {}: {}", resourceType, resourceId, e.getMessage());
                            }
                        }
                    });
            return 0;
        }
        return cancelOpenNow(resourceType, resourceId, action, keepRequestId, reason);
    }

    private int cancelOpenNow(String resourceType, UUID resourceId, String action, UUID keepRequestId, String reason) {
        JsonNode page = client.get(WORKFLOW, "/v1/approval-requests?status=PENDING&resource_type="
                + java.net.URLEncoder.encode(resourceType, java.nio.charset.StandardCharsets.UTF_8)
                + "&resource_id=" + resourceId + "&limit=50");
        int n = 0;
        for (JsonNode r : page == null ? JsonNodeHolder.EMPTY : page.path("data")) {
            boolean kept = keepRequestId != null && keepRequestId.toString().equals(r.path("id").asString(null));
            if (!kept && (action == null || action.equals(r.path("action").asString(null)))) {
                cancel(UUID.fromString(r.get("id").asString()), reason);
                n++;
            }
        }
        return n;
    }

    private static final class JsonNodeHolder {
        static final JsonNode EMPTY = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    /** Idempotency key scoped to this service and tenant; long keys are shortened with a stable hash. */
    String key(String raw) {
        String k = application + ":" + TenantContext.current().map(UUID::toString).orElse("-") + ":" + raw;
        if (k.length() <= MAX_KEY) {
            return k;
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(k.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return application + ":" + HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
