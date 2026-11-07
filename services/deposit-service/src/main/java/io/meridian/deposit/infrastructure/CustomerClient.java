package io.meridian.deposit.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import org.springframework.stereotype.Component;

/**
 * Verifies a customer exists in the caller's tenant before a deposit account is opened for it (referential
 * integrity, F-REF). Only a definitive 404 from customer-service rejects the open; auth / 5xx / timeout are
 * allowed through so a transient downstream problem never blocks account opening.
 */
@Component
public class CustomerClient {
    static final String CUSTOMER = "customer-service";
    private final ServiceClient client;

    public CustomerClient(ServiceClient client) {
        this.client = client;
    }

    public void requireExists(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return;
        }
        try {
            client.get(CUSTOMER, "/v1/customers/" + customerId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("customer_not_found",
                        "customer_id " + customerId + " does not exist");
            }
            // transient / auth downstream issue: do not block account opening on it
        }
    }
}
