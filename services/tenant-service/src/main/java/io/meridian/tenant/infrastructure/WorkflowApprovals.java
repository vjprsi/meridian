package io.meridian.tenant.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Adapter to workflow-service's maker-checker approvals ({@code POST /v1/approval-requests}). */
@Component
public class WorkflowApprovals {
    private final ServiceClient client;
    private final JsonMapper json;

    public WorkflowApprovals(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public JsonNode request(String resourceType, UUID resourceId, String action, String title, String summary,
                            JsonNode payload, String idempotencyKey) {
        ObjectNode body = json.createObjectNode();
        body.put("resource_type", resourceType);
        body.put("resource_id", resourceId.toString());
        body.put("action", action);
        body.put("title", title);
        body.put("summary", summary);
        body.set("payload", payload);
        body.put("priority", "NORMAL");
        String key = "tenant-service:" + idempotencyKey;
        return client.post("workflow-service", "/v1/approval-requests", body,
                key.length() > 128 ? key.substring(0, 128) : key);
    }
}
