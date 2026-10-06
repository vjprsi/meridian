package io.meridian.businessday.infrastructure;

import io.meridian.web.client.ServiceClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Invokes a batch job in its owning service: {@code POST <endpoint>} with the job context as body and an
 * idempotency key stable per job execution, so a retried call after a lost response is executed once downstream.
 */
@Component
public class JobDispatcher {
    private final ServiceClient services;

    public JobDispatcher(ServiceClient services) {
        this.services = services;
    }

    public JsonNode dispatch(String service, String endpoint, JsonNode payload, String idempotencyKey) {
        return services.post(service, endpoint, payload, idempotencyKey);
    }
}
