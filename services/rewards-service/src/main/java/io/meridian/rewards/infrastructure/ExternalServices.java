package io.meridian.rewards.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.config.TenantSettings;
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
    static final String SERVICE = "rewards-service";

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
     *
     * <p>The program's (or campaign's) own {@code ledger_id} / {@code funding_ledger_account_id} win; when unset, the
     * tenant's config-service settings apply (namespace {@code ledger}, key {@code rewards-service}: {@code ledger_id}
     * (or {@code default.ledger_id}), {@code funding_account_id}). With neither, nothing is posted (422).
     */
    public UUID payout(UUID ledgerId, UUID fundingAccount, UUID customerLedgerAccount, BigDecimal amount, String currency,
                       String sourceType, UUID sourceId, String description, String key) {
        ledgerId = ledgerId != null ? ledgerId : TenantSettings.uuid(SERVICE, "ledger_id", null);
        fundingAccount = fundingAccount != null ? fundingAccount : TenantSettings.uuid(SERVICE, "funding_account_id", null);
        if (ledgerId == null) {
            throw ApiException.unprocessable("ledger_not_configured", "No ledger book for payouts: set ledger_id on the "
                    + "program or the tenant's config-service ledger/rewards-service.ledger_id");
        }
        if (fundingAccount == null) {
            throw ApiException.unprocessable("funding_not_configured", "No funding account for payouts: set "
                    + "funding_ledger_account_id on the program or the tenant's config-service ledger/rewards-service.funding_account_id");
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
