package io.meridian.wallet.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ledger-service adapter: every wallet balance lives in a ledger account and every money movement is a balanced
 * journal entry or a hold that is later captured or released. All writes carry deterministic idempotency keys so a
 * retried business action never posts twice.
 */
@Component
public class LedgerClient {
    static final String LEDGER = "ledger-service";

    private final ServiceClient client;
    private final WalletProperties props;
    private final JsonMapper json;

    public LedgerClient(ServiceClient client, WalletProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    /** One posting line. */
    public record Line(UUID account, String direction, BigDecimal amount, String description) {}

    public UUID openWalletAccount(String key, String code, String name, String currency, UUID walletId) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId().toString());
        body.put("code", code);
        body.put("name", name.length() > 160 ? name.substring(0, 160) : name);
        body.put("account_type", "LIABILITY");
        body.put("normal_balance", "CREDIT");
        body.put("currency", currency);
        body.put("allow_negative", false);
        body.put("owner_type", "WALLET");
        body.put("owner_id", walletId.toString());
        return id(client.post(LEDGER, "/v1/ledger-accounts", body, key));
    }

    /** Posts a balanced journal entry (zero lines dropped) and returns its id. */
    public UUID post(String key, String entryType, String description, String currency, String reference,
                     String sourceType, UUID sourceId, List<Line> lines) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId().toString());
        body.put("entry_type", entryType);
        body.put("description", trim(description, 500));
        if (reference != null) {
            body.put("reference", reference);
        }
        body.put("source_type", sourceType);
        body.put("source_id", sourceId.toString());
        ArrayNode entries = body.putArray("entries");
        for (Line l : lines) {
            if (l.amount().signum() == 0) {
                continue;
            }
            if (l.account() == null) {
                throw new ApiException(422, "ledger_account_not_configured",
                        "A ledger account required for this posting is not configured");
            }
            ObjectNode e = entries.addObject();
            e.put("ledger_account_id", l.account().toString());
            e.put("direction", l.direction());
            e.put("amount", l.amount().toPlainString());
            e.put("currency", currency);
            if (l.description() != null) {
                e.put("description", trim(l.description(), 500));
            }
        }
        return id(client.post(LEDGER, "/v1/journal-entries", body, key));
    }

    public UUID placeHold(String key, UUID account, BigDecimal amount, String currency, String description,
                          UUID transactionId) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_account_id", account.toString());
        body.put("hold_type", "PAYMENT");
        body.put("amount", amount.toPlainString());
        body.put("currency", currency);
        body.put("description", trim(description, 500));
        body.put("source_type", "WALLET_TRANSACTION");
        body.put("source_id", transactionId.toString());
        return id(client.post(LEDGER, "/v1/holds", body, key));
    }

    public void capture(String key, UUID holdId, BigDecimal amount, UUID counterAccount, String description,
                        boolean releaseRemainder) {
        ObjectNode body = json.createObjectNode();
        body.put("amount", amount.toPlainString());
        body.put("counter_account_id", counterAccount.toString());
        body.put("description", trim(description, 500));
        body.put("release_remainder", releaseRemainder);
        client.post(LEDGER, "/v1/holds/" + holdId + "/capture", body, key);
    }

    public void release(String key, UUID holdId, String reason) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", trim(reason == null ? "released" : reason, 255));
        client.post(LEDGER, "/v1/holds/" + holdId + "/release", body, key);
    }

    /** Aligns the ledger account's posting restriction with the wallet status (idempotent at the ledger). */
    public void restrict(String key, UUID account, String ledgerStatus, String reason) {
        if (account == null) {
            return;
        }
        ObjectNode body = json.createObjectNode();
        body.put("reason", trim(reason == null ? ledgerStatus : reason, 255));
        String base = "/v1/ledger-accounts/" + account;
        switch (ledgerStatus) {
            case "ACTIVE" -> client.post(LEDGER, base + "/unfreeze", body, key);
            case "CLOSED" -> client.post(LEDGER, base + "/close", body, key);
            default -> {
                body.put("status", ledgerStatus);
                client.post(LEDGER, base + "/freeze", body, key);
            }
        }
    }

    public JsonNode balance(UUID account) {
        return client.get(LEDGER, "/v1/ledger-accounts/" + account + "/balance");
    }

    public BigDecimal balanceAt(UUID account, OffsetDateTime at) {
        JsonNode b = client.get(LEDGER, "/v1/ledger-accounts/" + account + "/balance-at?at=" + enc(at.toString()));
        return dec(b, "posted_balance");
    }

    /** Ledger entries of an account in [from, to), newest first, following cursors up to {@code max} rows. */
    public List<JsonNode> entries(UUID account, OffsetDateTime from, OffsetDateTime to, int max) {
        List<JsonNode> out = new java.util.ArrayList<>();
        String cursor = null;
        do {
            StringBuilder q = new StringBuilder("/v1/ledger-accounts/" + account + "/entries?limit=200");
            if (from != null) {
                q.append("&from=").append(enc(from.toString()));
            }
            if (to != null) {
                q.append("&to=").append(enc(to.toString()));
            }
            if (cursor != null) {
                q.append("&cursor=").append(enc(cursor));
            }
            JsonNode page = client.get(LEDGER, q.toString());
            page.path("data").forEach(out::add);
            cursor = page.path("has_more").asBoolean(false) ? page.path("next_cursor").asString(null) : null;
        } while (cursor != null && out.size() < max);
        return out;
    }

    public static BigDecimal dec(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asString());
    }

    private UUID ledgerId() {
        if (props.ledgerId() == null) {
            throw new ApiException(422, "ledger_not_configured", "meridian.wallet.ledger-id is not configured");
        }
        return props.ledgerId();
    }

    private static UUID id(JsonNode node) {
        String id = node.path("id").asString(null);
        if (id == null) {
            throw new ApiException(502, "upstream_error", "ledger-service returned no id");
        }
        return UUID.fromString(id);
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
