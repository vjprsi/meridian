package io.meridian.config.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Adapter to workflow-service, which owns maker-checker approval requests. */
@Component
public class WorkflowGateway {
    static final String SERVICE = "workflow-service";
    private final ServiceClient client;

    public WorkflowGateway(ServiceClient client) {
        this.client = client;
    }

    /** Creates an approval request; the key makes the remote call idempotent on retries. */
    public JsonNode requestApproval(Object request, String idempotencyKey) {
        return client.post(SERVICE, "/v1/approval-requests", request, idempotencyKey);
    }

    public JsonNode approval(UUID approvalRequestId) {
        return client.get(SERVICE, "/v1/approval-requests/" + approvalRequestId);
    }
}
