package io.meridian.collateral.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exposures secured by collateral live in loan-service (loans and credit lines). Reads their outstanding amounts
 * and applies liquidation proceeds as repayments (or post-write-off recoveries) with deterministic idempotency keys.
 */
@Component
public class ExposureClient {
    static final String LOANS = "loan-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public ExposureClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** A loan or credit line as seen by collateral management. */
    public record Exposure(String type, UUID id, String currency, String status, BigDecimal outstanding, UUID customerId) {}

    public Exposure fetch(String exposureType, UUID id) {
        return switch (exposureType) {
            case "LOAN", "BNPL" -> {
                JsonNode l = client.get(LOANS, "/v1/loans/" + id);
                yield new Exposure(exposureType, id, l.path("currency").asString(null), l.path("status").asString(null),
                        dec(l, "total_outstanding"), uuid(l, "customer_id"));
            }
            case "CREDIT_LINE" -> {
                JsonNode c = client.get(LOANS, "/v1/credit-lines/" + id);
                BigDecimal out = dec(c, "utilised_amount").add(dec(c, "instalment_balance"))
                        .add(dec(c, "outstanding_interest")).add(dec(c, "outstanding_fees"));
                yield new Exposure(exposureType, id, c.path("currency").asString(null), c.path("status").asString(null),
                        out, uuid(c, "customer_id"));
            }
            default -> throw ApiException.unprocessable("unsupported_exposure_type",
                    "Collateral can secure LOAN, BNPL or CREDIT_LINE exposures, not " + exposureType);
        };
    }

    /** Reference check for link creation: 422 when the exposure does not exist in loan-service. */
    public Exposure require(String exposureType, UUID id) {
        try {
            return fetch(exposureType, id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("exposure_not_found",
                        exposureType + " " + id + " does not exist in loan-service");
            }
            throw e;
        }
    }

    /** Applies liquidation proceeds; returns {kind, id} of the loan-service record created. */
    public ObjectNode applyProceeds(Exposure exposure, BigDecimal amount, String reference, String receivedAt, String key) {
        ObjectNode out = json.createObjectNode();
        ObjectNode body = json.createObjectNode();
        body.put("amount", amount.toPlainString());
        if ("CREDIT_LINE".equals(exposure.type())) {
            body.put("source", "COLLATERAL_LIQUIDATION");
            body.put("external_reference", reference);
            JsonNode r = client.post(LOANS, "/v1/credit-lines/" + exposure.id() + "/repay", body, key);
            out.put("kind", "CREDIT_LINE_REPAYMENT");
            out.put("id", r.path("id").asString(null));
        } else if ("WRITTEN_OFF".equals(exposure.status()) || "CHARGED_OFF".equals(exposure.status())) {
            body.put("loan_id", exposure.id().toString());
            body.put("currency", exposure.currency());
            body.put("source", "COLLATERAL_SALE");
            body.put("received_at", receivedAt);
            body.put("external_reference", reference);
            JsonNode r = client.post(LOANS, "/v1/loan-recoveries", body, key);
            out.put("kind", "LOAN_RECOVERY");
            out.put("id", r.path("id").asString(null));
        } else {
            body.put("source", "COLLATERAL_LIQUIDATION");
            body.put("external_reference", reference);
            JsonNode r = client.post(LOANS, "/v1/loans/" + exposure.id() + "/repay", body, key);
            out.put("kind", "LOAN_REPAYMENT");
            out.put("id", r.path("id").asString(null));
        }
        return out;
    }

    private static BigDecimal dec(JsonNode n, String f) {
        String s = n.path(f).asString(null);
        return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s);
    }

    private static UUID uuid(JsonNode n, String f) {
        String s = n.path(f).asString(null);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }
}
