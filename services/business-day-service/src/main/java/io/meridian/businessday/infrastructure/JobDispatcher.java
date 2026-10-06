package io.meridian.businessday.infrastructure;

import io.meridian.web.client.ServiceClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Invokes a batch job in its owning service: {@code <method> <path>} with the job body, the current tenant as
 * {@code X-Tenant-Id} (propagated by {@link ServiceClient}) and an idempotency key stable per job execution, so a
 * retried call after a lost response is executed once downstream.
 */
@Component
public class JobDispatcher {
    private final ServiceClient services;

    public JobDispatcher(ServiceClient services) {
        this.services = services;
    }

    public JsonNode dispatch(String service, String method, String path, JsonNode body, String idempotencyKey) {
        return services.send(service, method, path, "GET".equals(method) ? null : body, idempotencyKey);
    }
}
