package io.meridian.cardthreeds.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls to card-vault-service (PAN to card), card-service (card status, scheme, cardholder) and notification-service
 * (challenge OTP delivery). The PAN only ever travels to the vault and is never logged.
 */
@Component
public class Downstream {
    static final String CARDS = "card-service";
    static final String VAULT = "card-vault-service";
    static final String NOTIFICATIONS = "notification-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public Downstream(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** Card id of the active vault record of the PAN; 404 {@code card_not_found} when the PAN is not issued here. */
    public UUID cardIdForPan(String pan, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("pan", pan);
        body.put("purpose", "THREEDS");
        JsonNode record;
        try {
            record = client.post(VAULT, "/v1/card-vault/lookup", body, key);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw new ApiException(404, "card_not_found", "No card is issued for this PAN");
            }
            throw e;
        }
        String cardId = record.path("card_id").asString(null);
        if (cardId == null || cardId.isBlank()) {
            throw new ApiException(404, "card_not_found", "The PAN is not linked to a card");
        }
        return UUID.fromString(cardId);
    }

    /** Authorisation context of the card (card, program incl. scheme, product, controls, limits). */
    public JsonNode authContext(UUID cardId) {
        try {
            return client.get(CARDS, "/v1/cards/" + cardId + "/auth-context");
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw new ApiException(404, "card_not_found", "Card " + cardId + " was not found");
            }
            throw e;
        }
    }

    /** Sends the challenge OTP to the cardholder (SMS or e-mail). */
    public void sendOtp(UUID customerId, String channel, String otp, String merchantName, String amount, String currency,
                        String key) {
        ObjectNode body = json.createObjectNode();
        body.put("template_code", "CARD_3DS_OTP");
        body.put("recipient_type", "CUSTOMER");
        body.put("recipient_id", customerId.toString());
        body.put("channel", channel);
        body.put("category", "SECURITY");
        ObjectNode vars = body.putObject("variables");
        vars.put("otp", otp);
        if (merchantName != null) {
            vars.put("merchant_name", merchantName);
        }
        if (amount != null) {
            vars.put("amount", amount);
        }
        if (currency != null) {
            vars.put("currency", currency);
        }
        client.post(NOTIFICATIONS, "/v1/notifications/send-templated", body, key);
    }
}
