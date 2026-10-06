package io.meridian.merchant.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * wallet-service (the wallets money engine) and deposit-service adapter. Every money movement of a merchant is a
 * wallet transaction created with a deterministic Idempotency-Key, so a retried business action never pays twice.
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

    /** Opens (idempotently) and activates the merchant's collection wallet; returns its id. */
    public UUID openMerchantWallet(UUID merchantId, String name, String currency) {
        ObjectNode w = json.createObjectNode();
        w.put("wallet_type", "MERCHANT");
        w.put("display_name", name.length() > 160 ? name.substring(0, 160) : name);
        w.put("currency", currency);
        w.put("merchant_id", merchantId.toString());
        w.put("opened_channel", "SYSTEM");
        JsonNode created = client.post(WALLET, "/v1/wallets", w, "mer-" + merchantId + "-wallet");
        UUID id = UUID.fromString(created.get("id").asString());
        if (!"ACTIVE".equals(created.path("status").asString())) {
            ObjectNode body = json.createObjectNode().put("reason", "Merchant onboarded");
            client.post(WALLET, "/v1/wallets/" + id + "/activate", body, "mer-" + merchantId + "-wallet-activate");
        }
        return id;
    }

    public JsonNode wallet(UUID id) {
        return client.get(WALLET, "/v1/wallets/" + id);
    }

    public JsonNode transaction(UUID id) {
        return client.get(WALLET, "/v1/wallet-transactions/" + id);
    }

    /** POST /v1/wallet-transactions (generic create) with the given body and key. */
    public JsonNode transact(ObjectNode body, String key) {
        return client.post(WALLET, "/v1/wallet-transactions", body, key);
    }

    public ObjectNode txn(String type, String channel, String initiator, BigDecimal amount, String currency) {
        ObjectNode b = json.createObjectNode();
        b.put("txn_type", type);
        b.put("channel", channel);
        b.put("initiator_type", initiator);
        b.put("amount", amount.toPlainString());
        b.put("currency", currency);
        return b;
    }

    /** One page of the merchant's wallet transactions (raw page JSON). */
    public JsonNode page(UUID merchantId, String txnType, String status, String createdAfter, String createdBefore,
                         Integer limit, String cursor) {
        StringBuilder q = new StringBuilder("/v1/wallet-transactions?merchant_id=" + merchantId);
        if (txnType != null) {
            q.append("&txn_type=").append(txnType);
        }
        if (status != null) {
            q.append("&status=").append(status);
        }
        if (createdAfter != null) {
            q.append("&created_after=").append(enc(createdAfter));
        }
        if (createdBefore != null) {
            q.append("&created_before=").append(enc(createdBefore));
        }
        q.append("&limit=").append(limit == null ? 50 : limit);
        if (cursor != null && !cursor.isBlank()) {
            q.append("&cursor=").append(enc(cursor));
        }
        return client.get(WALLET, q.toString());
    }

    /** All COMPLETED payments of a type for the merchant created in [after, before), following cursors. */
    public List<JsonNode> all(UUID merchantId, String txnType, String status, String after, String before) {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode p = page(merchantId, txnType, status, after, before, 200, cursor);
            p.path("data").forEach(out::add);
            cursor = p.path("has_more").asBoolean(false) ? p.path("next_cursor").asString(null) : null;
        } while (cursor != null && out.size() < 100_000);
        return out;
    }

    /** Sum of COMPLETED merchant payments since {@code from} (from the summary read model). */
    public BigDecimal collectedSince(UUID merchantId, String currency, String from) {
        JsonNode s = client.get(WALLET, "/v1/wallet-transactions/summary?merchant_id=" + merchantId
                + "&txn_type=MERCHANT_PAYMENT&status=COMPLETED&from=" + enc(from));
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode g : s.path("groups")) {
            if (currency.equals(g.path("currency").asString())) {
                total = total.add(new BigDecimal(g.path("amount").asString("0")));
            }
        }
        return total;
    }

    /** Ledger account of an on-us deposit account (must be ACTIVE). */
    public UUID depositLedgerAccount(UUID depositAccountId, String currency) {
        JsonNode acc = client.get("deposit-service", "/v1/deposit-accounts/" + depositAccountId);
        if (!"ACTIVE".equals(acc.path("status").asString())) {
            throw ApiException.unprocessable("settlement_account_not_active",
                    "The settlement bank account is " + acc.path("status").asString());
        }
        if (!currency.equals(acc.path("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch", "The settlement bank account is in "
                    + acc.path("currency").asString());
        }
        String ledger = acc.path("ledger_account_id").asString(null);
        if (ledger == null) {
            throw ApiException.unprocessable("settlement_account_not_active", "The settlement account has no ledger account");
        }
        return UUID.fromString(ledger);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
