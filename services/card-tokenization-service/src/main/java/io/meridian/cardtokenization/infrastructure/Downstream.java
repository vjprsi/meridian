package io.meridian.cardtokenization.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls to card-service (card status, expiry, cardholder), card-vault-service (PAN to card resolution) and
 * notification-service (step-up OTP delivery). The PAN only ever travels to the vault; it is never logged.
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

    /** The card from card-service; 404 becomes 422 {@code card_not_found}. */
    public JsonNode card(UUID cardId) {
        try {
            return client.get(CARDS, "/v1/cards/" + cardId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("card_not_found", "Card " + cardId + " was not found");
            }
            throw e;
        }
    }

    /** Card id of the active vault record of a PAN, or 404 {@code card_not_found} when the PAN is not ours. */
    public UUID cardIdForPan(String pan, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("pan", pan);
        body.put("purpose", "TOKENIZATION");
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

    /** Sends the step-up OTP to the cardholder by SMS (the only place the OTP leaves the service). */
    public void sendStepUpOtp(UUID customerId, UUID tokenId, String otp, String tokenRequestor, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("template_code", "CARD_TOKEN_STEP_UP_OTP");
        body.put("recipient_type", "CUSTOMER");
        body.put("recipient_id", customerId.toString());
        body.put("channel", "SMS");
        body.put("category", "SECURITY");
        ObjectNode vars = body.putObject("variables");
        vars.put("otp", otp);
        vars.put("token_requestor", tokenRequestor);
        vars.put("card_token_id", tokenId.toString());
        client.post(NOTIFICATIONS, "/v1/notifications/send-templated", body, key);
    }
}
