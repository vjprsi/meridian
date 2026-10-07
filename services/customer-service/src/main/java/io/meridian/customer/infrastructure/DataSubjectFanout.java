package io.meridian.customer.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Data subject requests across the platform (GAP-08): customer-service owns the privacy request and asks every service
 * that holds personal data of customers ({@code meridian.privacy.services}) through its internal data-lifecycle API
 * ({@code /v1/internal/data-lifecycle/export} and {@code /erase}), calling with its own service identity. Legal holds
 * are checked in archive-service ({@code GET /v1/archive/legal-holds/check}) before anything is erased.
 */
@Component
public class DataSubjectFanout {
    private static final Logger log = LoggerFactory.getLogger(DataSubjectFanout.class);
    static final String EXPORT = "/v1/internal/data-lifecycle/export";
    static final String ERASE = "/v1/internal/data-lifecycle/erase";

    private final ServiceClient client;
    private final List<String> services;
    private final String holdService;

    public DataSubjectFanout(ServiceClient client,
                             @Value("${meridian.privacy.services:}") String[] services,
                             @Value("${meridian.privacy.legal-hold-service:archive-service}") String holdService) {
        this.client = client;
        this.services = List.of(services).stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        this.holdService = holdService;
    }

    public List<String> services() {
        return services;
    }

    /** One service's answer, or the reason it could not answer. */
    public record Outcome(String service, JsonNode body, String error) {
        public boolean failed() {
            return error != null;
        }
    }

    /** Active legal holds covering the customer: {@code {on_hold, holds:[{legal_hold_id, name}]}} (fails closed). */
    public JsonNode legalHolds(UUID customerId) {
        try {
            return ServiceClient.asService(() -> client.get(holdService,
                    "/v1/archive/legal-holds/check?scope_type=CUSTOMER&id=" + customerId));
        } catch (ApiException e) {
            // erasure must not proceed when holds cannot be checked
            throw new ApiException(503, "legal_hold_check_unavailable",
                    "Legal holds cannot be checked (" + e.getMessage() + "); erasure is not executed");
        }
    }

    /** DSAR access: every service's rows of the customer; services that could not answer are reported. */
    public List<Outcome> export(UUID customerId, UUID requestId) {
        return each(service -> ServiceClient.asService(() -> client.post(service, EXPORT,
                Map.of("customer_id", customerId.toString()), "prq-export:" + requestId + ":" + service)));
    }

    /**
     * DSAR erasure in every service. {@code withinRetention}: the statutory retention period of the relationship
     * has not ended, so services keep (and report) the records they must retain.
     */
    public List<Outcome> erase(UUID customerId, UUID requestId, boolean withinRetention) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_id", customerId.toString());
        body.put("privacy_request_id", requestId.toString());
        body.put("within_retention", withinRetention);
        return each(service -> ServiceClient.asService(() -> client.post(service, ERASE, body,
                "prq-erase:" + requestId + ":" + service)));
    }

    private List<Outcome> each(java.util.function.Function<String, JsonNode> call) {
        List<Outcome> out = new ArrayList<>();
        for (String s : services) {
            try {
                out.add(new Outcome(s, call.apply(s), null));
            } catch (ApiException e) {
                log.warn("data subject request: {} failed: {}", s, e.getMessage());
                out.add(new Outcome(s, null, e.code() + ": " + e.getMessage()));
            }
        }
        return out;
    }
}
