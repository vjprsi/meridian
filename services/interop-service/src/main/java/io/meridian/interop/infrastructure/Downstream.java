package io.meridian.interop.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapters to wallet-service (the money engine: holds, confirm, cancel, credits) and ledger-service (read-only
 * settlement position). Every write carries a deterministic idempotency key derived from the transfer.
 */
@Component
public class Downstream {
    static final String WALLET = "wallet-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public Downstream(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public JsonNode wallet(UUID id) {
        try {
            return client.get(WALLET, "/v1/wallets/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("wallet_not_found", "Wallet " + id + " does not exist");
            }
            throw e;
        }
    }

    /** Active wallet registered for a mobile number, if any. */
    public Optional<JsonNode> walletByMsisdn(String msisdn, String currency) {
        try {
            return Optional.of(client.get(WALLET, "/v1/wallets/lookup?msisdn=" + enc(msisdn)
                    + (currency == null ? "" : "&currency=" + enc(currency))));
        } catch (ApiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Outbound: reserve the payer's funds (two-phase INTEROP_OUT towards the switch settlement account). */
    public JsonNode reserve(String key, UUID transferId, UUID payerWallet, UUID settlementAccount, BigDecimal amount,
                            String currency, String description) {
        ObjectNode b = json.createObjectNode();
        b.put("txn_type", "INTEROP_OUT");
        b.put("channel", "API");
        b.put("initiator_type", "CUSTOMER");
        b.put("amount", amount.toPlainString());
        b.put("currency", currency);
        b.put("payer_wallet_id", payerWallet.toString());
        b.put("counterparty_ledger_account_id", settlementAccount.toString());
        b.put("interop_transfer_id", transferId.toString());
        b.put("two_phase", true);
        b.put("description", description);
        return client.post(WALLET, "/v1/wallet-transactions", b, key);
    }

    /** Inbound: credit the payee wallet from the switch settlement account. */
    public JsonNode credit(String key, UUID transferId, UUID payeeWallet, UUID settlementAccount, BigDecimal amount,
                           String currency, String description) {
        ObjectNode b = json.createObjectNode();
        b.put("txn_type", "INTEROP_IN");
        b.put("channel", "API");
        b.put("initiator_type", "SYSTEM");
        b.put("amount", amount.toPlainString());
        b.put("currency", currency);
        b.put("payee_wallet_id", payeeWallet.toString());
        b.put("counterparty_ledger_account_id", settlementAccount.toString());
        b.put("interop_transfer_id", transferId.toString());
        b.put("description", description);
        return client.post(WALLET, "/v1/wallet-transactions", b, key);
    }

    public JsonNode confirm(String key, UUID walletTransactionId) {
        return client.post(WALLET, "/v1/wallet-transactions/" + walletTransactionId + "/confirm", json.createObjectNode(), key);
    }

    /** Releases the reserved funds; a transaction already cancelled or expired in wallet-service counts as released. */
    public void cancel(String key, UUID walletTransactionId, String reason) {
        ObjectNode b = json.createObjectNode();
        b.put("reason", reason == null ? "aborted" : reason.length() > 255 ? reason.substring(0, 255) : reason);
        try {
            client.post(WALLET, "/v1/wallet-transactions/" + walletTransactionId + "/cancel", b, key);
        } catch (ApiException e) {
            if (e.status() != 409) {
                throw e;
            }
            String status = client.get(WALLET, "/v1/wallet-transactions/" + walletTransactionId).path("status").asString("");
            if (!status.equals("CANCELLED") && !status.equals("EXPIRED") && !status.equals("FAILED")) {
                throw e;
            }
        }
    }

    public JsonNode ledgerBalance(UUID account) {
        return client.get("ledger-service", "/v1/ledger-accounts/" + account + "/balance");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
