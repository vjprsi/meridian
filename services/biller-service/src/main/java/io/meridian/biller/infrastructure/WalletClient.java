package io.meridian.biller.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * wallet-service adapter: bill payments and top-ups reserve the payer's funds (two-phase wallet transaction), are
 * confirmed when the biller accepts or cancelled when it refuses; settlements move money out of the biller's
 * collection wallet. Every call carries a deterministic Idempotency-Key.
 */
@Component
public class WalletClient {
    static final String WALLET = "wallet-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public WalletClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public JsonNode wallet(UUID id) {
        return client.get(WALLET, "/v1/wallets/" + id);
    }

    public JsonNode create(ObjectNode body, String key) {
        return client.post(WALLET, "/v1/wallet-transactions", body, key);
    }

    public JsonNode confirm(UUID txn, String key) {
        return client.post(WALLET, "/v1/wallet-transactions/" + txn + "/confirm", json.createObjectNode(), key);
    }

    public JsonNode cancel(UUID txn, String reason, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("reason", reason == null ? "Cancelled" : reason.length() > 255 ? reason.substring(0, 255) : reason);
        return client.post(WALLET, "/v1/wallet-transactions/" + txn + "/cancel", b, key);
    }

    public JsonNode reverse(UUID txn, String reason, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        return client.post(WALLET, "/v1/wallet-transactions/" + txn + "/reverse", b, key);
    }

    public ObjectNode body() {
        return json.createObjectNode();
    }
}
