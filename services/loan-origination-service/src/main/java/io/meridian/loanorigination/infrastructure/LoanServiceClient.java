package io.meridian.loanorigination.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Books loans in loan-service ({@code POST /v1/loans}, idempotent per key). */
@Component
public class LoanServiceClient {
    static final String LOAN_SERVICE = "loan-service";

    private final ServiceClient client;

    public LoanServiceClient(ServiceClient client) {
        this.client = client;
    }

    public JsonNode createLoan(ObjectNode body, String idempotencyKey) {
        JsonNode loan = client.post(LOAN_SERVICE, "/v1/loans", body, idempotencyKey);
        if (loan == null || !loan.hasNonNull("id")) {
            throw new ApiException(502, "upstream_error", "loan-service returned no loan id");
        }
        return loan;
    }
}
