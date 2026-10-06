package io.meridian.deposit.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.application.LedgerPort;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** {@link LedgerPort} over ledger-service's HTTP API via {@link ServiceClient} (tenant, user and token propagated). */
@Component
public class LedgerClient implements LedgerPort {
    static final String LEDGER = "ledger-service";

    private final ServiceClient client;
    private final DepositProperties props;
    private final JsonMapper json;

    public LedgerClient(ServiceClient client, DepositProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    @Override
    public UUID post(Posting p) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId().toString());
        body.put("entry_type", p.entryType());
        body.put("description", p.description());
        if (p.sourceType() != null) {
            body.put("source_type", p.sourceType());
        }
        if (p.sourceId() != null) {
            body.put("source_id", p.sourceId().toString());
        }
        if (p.valueDate() != null) {
            body.put("value_date", p.valueDate().toString());
        }
        ArrayNode entries = body.putArray("entries");
        for (Leg leg : p.legs()) {
            if (leg.amount().signum() == 0) {
                continue;
            }
            if (leg.amount().signum() < 0) {
                throw new IllegalArgumentException("posting legs must be positive");
            }
            if (leg.account() == null) {
                throw new ApiException(500, "ledger_account_not_configured",
                        "A ledger account required for this posting is not configured");
            }
            ObjectNode e = entries.addObject();
            e.put("ledger_account_id", leg.account().toString());
            e.put("direction", leg.direction());
            e.put("amount", leg.amount().toPlainString());
            e.put("currency", p.currency());
            if (leg.description() != null) {
                e.put("description", leg.description());
            }
        }
        return id(client.post(LEDGER, "/v1/journal-entries", body, p.key()));
    }

    @Override
    public UUID reverse(UUID journalEntryId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason);
        return id(client.post(LEDGER, "/v1/journal-entries/" + journalEntryId + "/reverse", body, key));
    }

    @Override
    public UUID openAccount(String key, String code, String name, String currency, BigDecimal overdraftLimit,
                            UUID parentId, String ownerType, UUID ownerId) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId().toString());
        body.put("code", code);
        body.put("name", name.length() > 160 ? name.substring(0, 160) : name);
        body.put("account_type", "LIABILITY");
        body.put("normal_balance", "CREDIT");
        body.put("currency", currency);
        body.put("allow_negative", false);
        body.put("overdraft_limit", (overdraftLimit == null ? BigDecimal.ZERO : overdraftLimit).toPlainString());
        if (parentId != null) {
            body.put("parent_id", parentId.toString());
        }
        body.put("owner_type", ownerType);
        body.put("owner_id", ownerId.toString());
        return id(client.post(LEDGER, "/v1/ledger-accounts", body, key));
    }

    @Override
    public JsonNode balance(UUID ledgerAccountId) {
        return client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/balance");
    }

    @Override
    public BigDecimal balanceAt(UUID ledgerAccountId, OffsetDateTime at) {
        JsonNode b = client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/balance-at?at=" + enc(at.toString()));
        return LedgerPort.posted(b);
    }

    @Override
    public JsonNode entries(UUID ledgerAccountId, OffsetDateTime from, OffsetDateTime to, Integer limit, String cursor) {
        StringBuilder q = new StringBuilder("/v1/ledger-accounts/" + ledgerAccountId + "/entries?limit="
                + (limit == null ? 50 : limit));
        if (from != null) {
            q.append("&from=").append(enc(from.toString()));
        }
        if (to != null) {
            q.append("&to=").append(enc(to.toString()));
        }
        if (cursor != null && !cursor.isBlank()) {
            q.append("&cursor=").append(enc(cursor));
        }
        return client.get(LEDGER, q.toString());
    }

    @Override
    public UUID placeHold(String key, UUID ledgerAccountId, BigDecimal amount, String currency, String holdType,
                          String description, String sourceType, UUID sourceId) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_account_id", ledgerAccountId.toString());
        body.put("hold_type", holdType);
        body.put("amount", amount.toPlainString());
        body.put("currency", currency);
        if (description != null) {
            body.put("description", description.length() > 500 ? description.substring(0, 500) : description);
        }
        body.put("source_type", sourceType);
        body.put("source_id", sourceId.toString());
        return id(client.post(LEDGER, "/v1/holds", body, key));
    }

    @Override
    public void releaseHold(UUID holdId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason == null ? "released" : (reason.length() > 255 ? reason.substring(0, 255) : reason));
        client.post(LEDGER, "/v1/holds/" + holdId + "/release", body, key);
    }

    @Override
    public void restrict(UUID ledgerAccountId, String fromStatus, String toStatus, String reason, String key) {
        if (ledgerAccountId == null || toStatus.equals(fromStatus)) {
            return;
        }
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason == null ? toStatus : (reason.length() > 255 ? reason.substring(0, 255) : reason));
        String base = "/v1/ledger-accounts/" + ledgerAccountId;
        switch (toStatus) {
            case "ACTIVE" -> client.post(LEDGER, base + "/unfreeze", body, key);
            case "CLOSED" -> client.post(LEDGER, base + "/close", body, key);
            default -> {
                body.put("status", toStatus);
                client.post(LEDGER, base + "/freeze", body, key);
            }
        }
    }

    @Override
    public void setOverdraftLimit(UUID ledgerAccountId, BigDecimal limit) {
        JsonNode account = client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId);
        ObjectNode body = json.createObjectNode();
        body.put("overdraft_limit", limit.toPlainString());
        client.patch(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId, body, account.path("row_version").asString("1"));
    }

    private UUID ledgerId() {
        if (props.ledgerId() == null) {
            throw new ApiException(500, "ledger_not_configured", "meridian.deposit.ledger-id is not configured");
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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
