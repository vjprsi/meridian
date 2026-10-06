package io.meridian.card.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls to the other card services and the ledger through {@link ServiceClient} (tenant, user and token
 * propagated). PAN and PIN blocks pass through here only on their way to the vault and are never logged.
 */
@Component
public class Peers {
    static final String VAULT = "card-vault-service";
    static final String TOKENS = "card-tokenization-service";
    static final String AUTH = "card-authorization-service";
    static final String CLEARING = "card-clearing-service";
    static final String LEDGER = "ledger-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public Peers(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    // ------------------------------------------------------------------ vault

    /** Tokenises a PAN; the vault returns the record (token, fingerprint, bin, last4). */
    public JsonNode tokenize(String pan, int expiryMonth, int expiryYear, UUID cardId, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("pan", pan);
        b.put("expiry_month", expiryMonth);
        b.put("expiry_year", expiryYear);
        b.put("card_id", cardId.toString());
        return client.post(VAULT, "/v1/card-vault", b, key);
    }

    public JsonNode reissue(String panToken, UUID cardId, int expiryMonth, int expiryYear, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("pan_token", panToken);
        b.put("card_id", cardId.toString());
        b.put("expiry_month", expiryMonth);
        b.put("expiry_year", expiryYear);
        return client.post(VAULT, "/v1/card-vault/reissue", b, key);
    }

    public JsonNode setPin(String panToken, String encryptedPinBlock, String pinKeyId, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("pan_token", panToken);
        b.put("encrypted_pin_block", encryptedPinBlock);
        b.put("pin_key_id", pinKeyId);
        return client.post(VAULT, "/v1/card-vault/pin", b, key);
    }

    public JsonNode unblockPin(String panToken, String reason, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("pan_token", panToken);
        if (reason != null) {
            b.put("reason", reason);
        }
        return client.post(VAULT, "/v1/card-vault/pin/unblock", b, key);
    }

    // ------------------------------------------------------------------ other card services

    public JsonNode syncTokenStatus(UUID cardId, String status, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("card_id", cardId.toString());
        b.put("card_status", status);
        return client.post(TOKENS, "/v1/card-tokens/card-status-sync", b, key);
    }

    public JsonNode authorizations(UUID cardId, Integer limit, String cursor) {
        StringBuilder q = new StringBuilder("/v1/card-authorizations?card_id=").append(cardId);
        if (limit != null) {
            q.append("&limit=").append(limit);
        }
        if (cursor != null && !cursor.isBlank()) {
            q.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return client.get(AUTH, q.toString());
    }

    public JsonNode usage(UUID cardId) {
        return client.get(AUTH, "/v1/card-authorizations/usage?card_id=" + cardId);
    }

    public JsonNode chargeFee(UUID cardId, UUID ledgerAccountId, String feeType, BigDecimal amount, String currency,
                              String description, String key) {
        ObjectNode b = json.createObjectNode();
        b.put("card_id", cardId.toString());
        if (ledgerAccountId != null) {
            b.put("ledger_account_id", ledgerAccountId.toString());
        }
        b.put("fee_type", feeType);
        b.put("amount", amount.toPlainString());
        b.put("currency", currency);
        b.put("description", description);
        return client.post(CLEARING, "/v1/card-fees", b, key);
    }

    // ------------------------------------------------------------------ ledger

    public JsonNode ledgerEntries(UUID ledgerAccountId, String from, String to, String cursor) {
        StringBuilder q = new StringBuilder("/v1/ledger-accounts/" + ledgerAccountId + "/entries?limit=200&from=")
                .append(URLEncoder.encode(from, StandardCharsets.UTF_8))
                .append("&to=").append(URLEncoder.encode(to, StandardCharsets.UTF_8));
        if (cursor != null && !cursor.isBlank()) {
            q.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return client.get(LEDGER, q.toString());
    }

    /** Downstream idempotency key {@code <operation>:<entity id>[:<client key>]}, hashed beyond 128 chars. */
    public static String key(String operation, UUID entityId, String clientKey) {
        String raw = operation + ":" + entityId + (clientKey == null ? "" : ":" + clientKey);
        if (raw.length() <= 128) {
            return raw;
        }
        try {
            String h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
            return (operation + ":" + h).substring(0, Math.min(128, operation.length() + 1 + 64));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
