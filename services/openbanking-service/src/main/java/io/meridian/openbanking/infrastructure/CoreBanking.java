package io.meridian.openbanking.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Core banking APIs behind the open banking profiles, called through {@link ServiceClient} (tenant, user and
 * token propagated): deposit accounts (ownership, currency), ledger balances (funds confirmation) and
 * payment-service (payment initiation, status, cancellation).
 */
@Component
public class CoreBanking {
    static final String DEPOSITS = "deposit-service";
    static final String LEDGER = "ledger-service";
    static final String PAYMENTS = "payment-service";

    private final ServiceClient client;

    public CoreBanking(ServiceClient client) {
        this.client = client;
    }

    public JsonNode depositAccount(UUID accountId) {
        return client.get(DEPOSITS, "/v1/deposit-accounts/" + accountId);
    }

    /** Available balance of a deposit account (ledger available = posted - holds + overdraft). */
    public BigDecimal availableBalance(JsonNode depositAccount) {
        String ledgerAccount = depositAccount.path("ledger_account_id").asString(null);
        if (ledgerAccount == null) {
            throw ApiException.unprocessable("account_not_active", "The account has no ledger account yet");
        }
        JsonNode balance = client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccount + "/balance");
        return new BigDecimal(balance.path("available_balance").asString("0"));
    }

    public JsonNode createPayment(ObjectNode body, String idempotencyKey) {
        return client.post(PAYMENTS, "/v1/payments", body, idempotencyKey);
    }

    public JsonNode payment(UUID paymentId) {
        return client.get(PAYMENTS, "/v1/payments/" + paymentId);
    }

    public JsonNode cancelPayment(UUID paymentId, ObjectNode body, String idempotencyKey) {
        return client.post(PAYMENTS, "/v1/payments/" + paymentId + "/cancel", body, idempotencyKey);
    }
}
