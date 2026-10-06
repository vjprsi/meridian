package io.meridian.loan.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.loan.application.LedgerPort;
import io.meridian.loan.config.LoanProperties;
import io.meridian.web.client.ServiceClient;
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
    private final LoanProperties props;
    private final JsonMapper json;

    public LedgerClient(ServiceClient client, LoanProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    @Override
    public UUID post(Posting p) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId().toString());
        body.put("entry_type", p.entryType());
        body.put("description", p.description().length() > 500 ? p.description().substring(0, 500) : p.description());
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
            if (leg.amount() == null || leg.amount().signum() == 0) {
                continue;
            }
            if (leg.amount().signum() < 0) {
                throw new IllegalArgumentException("posting legs must be positive");
            }
            if (leg.account() == null) {
                throw new ApiException(500, "ledger_account_not_configured",
                        "A ledger account required for this posting is not configured (" + leg.description() + ")");
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
        body.put("reason", reason == null ? "reversal" : (reason.length() > 255 ? reason.substring(0, 255) : reason));
        return id(client.post(LEDGER, "/v1/journal-entries/" + journalEntryId + "/reverse", body, key));
    }

    @Override
    public UUID openReceivable(String key, String code, String name, String currency, String ownerType, UUID ownerId) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId().toString());
        body.put("code", code);
        body.put("name", name.length() > 160 ? name.substring(0, 160) : name);
        body.put("account_type", "ASSET");
        body.put("normal_balance", "DEBIT");
        body.put("currency", currency);
        body.put("allow_negative", true);
        body.put("owner_type", ownerType);
        body.put("owner_id", ownerId.toString());
        return id(client.post(LEDGER, "/v1/ledger-accounts", body, key));
    }

    @Override
    public void close(UUID ledgerAccountId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason == null ? "closed" : reason);
        client.post(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/close", body, key);
    }

    @Override
    public JsonNode balance(UUID ledgerAccountId) {
        return client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/balance");
    }

    private UUID ledgerId() {
        if (props.ledgerId() == null) {
            throw new ApiException(500, "ledger_not_configured", "meridian.loan.ledger-id is not configured");
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
}
