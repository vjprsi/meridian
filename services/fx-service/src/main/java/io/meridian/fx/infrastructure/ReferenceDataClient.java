package io.meridian.fx.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Market mid rates from reference-data-service (tenant, user and token propagated by {@link ServiceClient}). */
@Component
public class ReferenceDataClient {
    static final String SERVICE = "reference-data-service";

    private final ServiceClient client;

    public ReferenceDataClient(ServiceClient client) {
        this.client = client;
    }

    /** Mid rate: units of {@code buy} per 1 unit of {@code sell}. */
    public BigDecimal midRate(String sell, String buy) {
        JsonNode r;
        try {
            r = client.get(SERVICE, "/v1/reference/fx-rates/latest?base=" + sell + "&quote=" + buy + "&type=MID");
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("rate_unavailable", "No current MID rate for " + sell + "/" + buy);
            }
            throw e;
        }
        String rate = r == null ? null : r.path("rate").asString(null);
        if (rate == null) {
            throw new ApiException(502, "upstream_error", "reference-data-service returned no rate");
        }
        BigDecimal mid = new BigDecimal(rate);
        if (mid.signum() <= 0) {
            throw new ApiException(502, "upstream_error", "reference-data-service returned a non-positive rate");
        }
        return mid;
    }
}
