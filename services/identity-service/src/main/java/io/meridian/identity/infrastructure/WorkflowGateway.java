package io.meridian.identity.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Maker-checker: raises approval requests in workflow-service (which enforces four-eyes on the decision). */
@Component
public class WorkflowGateway {
    public static final String SERVICE = "workflow-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public WorkflowGateway(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public JsonNode requestApproval(String resourceType, UUID resourceId, String action, String title, String summary,
                                    JsonNode payload, String priority, String idempotencyKey) {
        ObjectNode body = json.createObjectNode();
        body.put("resource_type", resourceType);
        if (resourceId != null) {
            body.put("resource_id", resourceId.toString());
        }
        body.put("action", action);
        body.put("title", title);
        body.put("summary", summary);
        body.set("payload", payload);
        body.put("priority", priority);
        String key = ("identity-service:" + idempotencyKey);
        JsonNode created = client.post(SERVICE, "/v1/approval-requests", body, key.substring(0, Math.min(128, key.length())));
        if (created == null || !created.hasNonNull("id")) {
            throw new ApiException(502, "upstream_error", SERVICE + " did not return the created approval request");
        }
        return created;
    }
}
