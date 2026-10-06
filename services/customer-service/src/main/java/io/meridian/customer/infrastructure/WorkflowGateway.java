package io.meridian.customer.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Adapter to workflow-service, which owns maker-checker approval requests ({@code POST /v1/approval-requests}).
 * The decision comes back as {@code platform.approval_request.approved|rejected|expired|cancelled} events.
 */
@Component
public class WorkflowGateway {
    static final String SERVICE = "workflow-service";
    private final ServiceClient client;

    public WorkflowGateway(ServiceClient client) {
        this.client = client;
    }

    /** Creates an approval request; the key makes the remote call idempotent on retries. Returns the request. */
    public JsonNode requestApproval(String resourceType, UUID resourceId, String action, String title, String summary,
                                    Map<String, Object> payload, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource_type", resourceType);
        body.put("resource_id", resourceId.toString());
        body.put("action", action);
        body.put("title", title);
        body.put("summary", summary);
        body.put("payload", payload);
        body.put("priority", "NORMAL");
        String key = "customer-service:" + idempotencyKey;
        return client.post(SERVICE, "/v1/approval-requests", body, key.length() > 128 ? key.substring(0, 128) : key);
    }
}
