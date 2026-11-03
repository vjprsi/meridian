package io.meridian.payment.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Risk services: sanctions screening of payments and limits reservation (check / commit / release). */
@Component
public class RiskClient {
    private final ServiceClient client;
    private final JsonMapper json;

    public RiskClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** Synchronous screening of the payment's parties and free text; returns the screening result. */
    public JsonNode screenPayment(UUID paymentId, ObjectNode payload, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("payment_id", paymentId.toString());
        body.set("payload", payload);
        return client.post("sanctions-service", "/v1/risk/sanctions/screenings/screen-payment", body, LedgerClient.key(key));
    }

    /**
     * Checks the limits and reserves usage: {@code {id, allowed, status, action, breached_rule_ids}}. WE-LIMIT-1:
     * the payer side is always a DEBIT; the TCP dimensions (regulatory_profile_code, domain/category/grade_code,
     * payment_instrument) and the payee credit block are forwarded only when the caller set them, so when no
     * Transfer Control Profiles are configured the request and its result are identical to before.
     */
    public JsonNode checkLimits(ObjectNode check, String key) {
        if (!check.hasNonNull("direction")) {
            check.put("direction", "DEBIT");
        }
        return client.post("limits-service", "/v1/risk/limits/checks", check, LedgerClient.key(key));
    }

    public void commitLimits(UUID checkId, BigDecimal amount, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("amount", amount.toPlainString());
        client.post("limits-service", "/v1/risk/limits/checks/" + checkId + "/commit", body, LedgerClient.key(key));
    }

    public void releaseLimits(UUID checkId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason == null ? "payment not completed" : reason.length() > 255 ? reason.substring(0, 255) : reason);
        client.post("limits-service", "/v1/risk/limits/checks/" + checkId + "/release", body, LedgerClient.key(key));
    }

    /** Accepts an FX quote for a payment; returns the booked deal ({@code rate, buy_amount, ...}). */
    /**
     * Accepts the quote for this payment. The binding (customer, currency pair, sold amount, bought amount when the
     * payment fixes it) is re-checked by fx-service under the quote's row lock (SEC-04); a replay for the same payment
     * returns the same deal.
     */
    public JsonNode acceptFxQuote(UUID quoteId, UUID paymentId, String key, JsonNode payment) {
        ObjectNode body = json.createObjectNode();
        body.put("payment_id", paymentId.toString());
        if (payment.hasNonNull("customer_id")) {
            body.put("customer_id", payment.get("customer_id").asString());
        }
        body.put("sell_currency", payment.path("currency").asString());
        body.put("sell_amount", payment.path("amount").asString());
        body.put("buy_currency", payment.path("instructed_currency").asString());
        if (payment.hasNonNull("instructed_amount")) {
            body.put("buy_amount", payment.get("instructed_amount").asString());
        }
        return client.post("fx-service", "/v1/fx/quotes/" + quoteId + "/accept", body, LedgerClient.key(key));
    }

    public JsonNode fxQuote(UUID quoteId) {
        return client.get("fx-service", "/v1/fx/quotes/" + quoteId);
    }
}
