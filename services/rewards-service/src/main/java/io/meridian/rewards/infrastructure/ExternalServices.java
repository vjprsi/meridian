package io.meridian.rewards.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapters to other Meridian services through {@link ServiceClient} (tenant, user and token propagated):
 * ledger-service (payout postings and reversals) and deposit-service (customer account to ledger account).
 */
@Component
public class ExternalServices {
    static final String LEDGER = "ledger-service";
    static final String DEPOSIT = "deposit-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public ExternalServices(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** A customer deposit account's ledger account and currency (422 when it cannot receive credits). */
    public CustomerAccount customerAccount(UUID depositAccountId) {
        JsonNode a = client.get(DEPOSIT, "/v1/deposit-accounts/" + depositAccountId);
        String ledgerAccount = a.path("ledger_account_id").asString(null);
        if (ledgerAccount == null || ledgerAccount.isBlank()) {
            throw ApiException.unprocessable("account_not_postable",
                    "Deposit account " + depositAccountId + " has no ledger account");
        }
        String status = a.path("status").asString("");
        if ("CLOSED".equals(status) || "FROZEN".equals(status) || "REJECTED".equals(status)) {
            throw ApiException.unprocessable("account_not_postable", "Deposit account " + depositAccountId + " is " + status);
        }
        return new CustomerAccount(UUID.fromString(ledgerAccount), a.path("currency").asString(null));
    }

    public record CustomerAccount(UUID ledgerAccountId, String currency) {}

    /**
     * Posts a two-leg journal entry (debit the funding account, credit the customer's ledger account); the key makes
     * retries exactly-once at the ledger. Returns the journal entry id.
     */
    public UUID payout(UUID ledgerId, UUID fundingAccount, UUID customerLedgerAccount, BigDecimal amount, String currency,
                       String sourceType, UUID sourceId, String description, String key) {
        if (ledgerId == null || fundingAccount == null) {
            throw ApiException.unprocessable("funding_not_configured",
                    "The program has no ledger_id / funding_ledger_account_id for payouts");
        }
        BigDecimal value = amount.stripTrailingZeros();
        if (value.scale() < 2) {
            value = value.setScale(2);
        }
        String amountText = value.toPlainString();
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId.toString());
        body.put("entry_type", "STANDARD");
        body.put("description", description);
        body.put("source_type", sourceType);
        body.put("source_id", sourceId.toString());
        ArrayNode entries = body.putArray("entries");
        ObjectNode debit = entries.addObject();
        debit.put("ledger_account_id", fundingAccount.toString());
        debit.put("direction", "DEBIT");
        debit.put("amount", amountText);
        debit.put("currency", currency);
        debit.put("description", description);
        ObjectNode credit = entries.addObject();
        credit.put("ledger_account_id", customerLedgerAccount.toString());
        credit.put("direction", "CREDIT");
        credit.put("amount", amountText);
        credit.put("currency", currency);
        credit.put("description", description);
        JsonNode r = client.post(LEDGER, "/v1/journal-entries", body, key);
        return UUID.fromString(r.path("id").asString());
    }

    public UUID reverse(UUID journalEntryId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason);
        JsonNode r = client.post(LEDGER, "/v1/journal-entries/" + journalEntryId + "/reverse", body, key);
        return UUID.fromString(r.path("id").asString());
    }
}
