package io.meridian.cardauthorization.infrastructure;

import io.meridian.cardauthorization.application.Ports;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** card-service, ledger-service, fraud-service, card-vault-service and card-clearing-service over ServiceClient. */
@Component
public class DownstreamClients implements Ports.CardDirectory, Ports.Ledger, Ports.Fraud, Ports.Vault, Ports.Clearing {
    static final String CARDS = "card-service";
    static final String LEDGER = "ledger-service";
    static final String FRAUD = "fraud-service";
    static final String VAULT = "card-vault-service";
    static final String CLEARING = "card-clearing-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public DownstreamClients(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public JsonNode authContext(UUID cardId) {
        return client.get(CARDS, "/v1/cards/" + cardId + "/auth-context");
    }

    @Override
    public UUID placeHold(String key, UUID account, BigDecimal amount, String currency, UUID authorizationId,
                          OffsetDateTime expiresAt, String description) {
        ObjectNode b = json.createObjectNode();
        b.put("ledger_account_id", account.toString());
        b.put("hold_type", "CARD_AUTHORIZATION");
        b.put("amount", amount.toPlainString());
        b.put("currency", currency);
        b.put("source_type", "CARD_AUTHORIZATION");
        b.put("source_id", authorizationId.toString());
        if (description != null) {
            b.put("description", description.length() > 500 ? description.substring(0, 500) : description);
        }
        if (expiresAt != null) {
            b.put("expires_at", expiresAt.toString());
        }
        return UUID.fromString(client.post(LEDGER, "/v1/holds", b, key).get("id").asString());
    }

    @Override
    public void incrementHold(UUID holdId, BigDecimal amount, String reason, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("amount", amount.toPlainString());
        b.put("reason", reason);
        client.post(LEDGER, "/v1/holds/" + holdId + "/increment", b, key);
    }

    @Override
    public void releaseHold(UUID holdId, String reason, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("reason", reason == null ? "released" : reason.length() > 255 ? reason.substring(0, 255) : reason);
        client.post(LEDGER, "/v1/holds/" + holdId + "/release", b, key);
    }

    @Override
    public BigDecimal available(UUID account) {
        JsonNode b = client.get(LEDGER, "/v1/ledger-accounts/" + account + "/balance");
        return new BigDecimal(b.path("available_balance").asString("0"));
    }

    @Override
    public JsonNode evaluate(JsonNode body, String key) {
        return client.post(FRAUD, "/v1/risk/fraud/scores/evaluate", body, key);
    }

    @Override
    public JsonNode verify(JsonNode body, String key) {
        return client.post(VAULT, "/v1/card-vault/verify", body, key);
    }

    @Override
    public JsonNode submitFile(JsonNode body, String key) {
        return client.post(CLEARING, "/v1/card-clearing/files", body, key);
    }

    @Override
    public JsonNode records(UUID fileId) {
        return client.get(CLEARING, "/v1/card-clearing/files/" + fileId + "/records?limit=50");
    }
}
