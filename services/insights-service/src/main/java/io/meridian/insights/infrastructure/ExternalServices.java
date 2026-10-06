package io.meridian.insights.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapters to other Meridian services: notification-service (insight and budget alerts, fail-open), deposit-service
 * (account and balance for forecasts) and card-service (merchant blocks for cancelled subscriptions).
 */
@Component
public class ExternalServices {
    private static final Logger log = LoggerFactory.getLogger(ExternalServices.class);
    static final String NOTIFICATION = "notification-service";
    static final String DEPOSIT = "deposit-service";
    static final String CARD = "card-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public ExternalServices(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /**
     * Sends a templated notification to a customer. Fail-open: an unavailable or rejecting notification-service never
     * fails the business operation; the caller keeps the delivery PENDING (null result) and may retry later.
     */
    public UUID notifyCustomer(UUID customerId, String templateCode, String channel, Map<String, Object> variables,
                               String idempotencyKey) {
        ObjectNode body = json.createObjectNode();
        body.put("template_code", templateCode);
        body.put("recipient_type", "CUSTOMER");
        body.put("recipient_id", customerId.toString());
        body.put("channel", channel);
        body.put("category", "SERVICE");
        body.set("variables", json.valueToTree(variables));
        try {
            JsonNode r = client.post(NOTIFICATION, "/v1/notifications/send-templated", body, idempotencyKey);
            String id = r == null ? null : r.path("id").asString(null);
            return id == null || id.isBlank() ? null : UUID.fromString(id);
        } catch (RuntimeException e) {
            log.warn("notification {} for customer {} not sent: {}", templateCode, customerId, e.getMessage());
            return null;
        }
    }

    /** Deposit account (owner check for forecasts). Errors propagate (404 / 502). */
    public JsonNode depositAccount(UUID accountId) {
        return client.get(DEPOSIT, "/v1/deposit-accounts/" + accountId);
    }

    /** Ledger balances of a deposit account: available_balance + currency. Errors propagate. */
    public JsonNode balance(UUID accountId) {
        return client.get(DEPOSIT, "/v1/deposit-accounts/" + accountId + "/balance");
    }

    /** Creates a MERCHANT_DENY control on a card; the key makes the call exactly-once at card-service. */
    public UUID blockMerchantOnCard(UUID cardId, String merchantRef, String reason, String idempotencyKey) {
        ObjectNode body = json.createObjectNode();
        body.put("card_id", cardId.toString());
        body.put("control_type", "MERCHANT_DENY");
        body.put("channel", "ANY");
        body.put("is_active", true);
        ObjectNode values = body.putObject("values");
        values.putArray("merchant_ids").add(merchantRef);
        values.put("reason", reason);
        JsonNode r = client.post(CARD, "/v1/card-controls", body, idempotencyKey);
        String id = r == null ? null : r.path("id").asString(null);
        if (id == null || id.isBlank()) {
            throw new ApiException(502, "upstream_error", "card-service returned no control id");
        }
        return UUID.fromString(id);
    }
}
