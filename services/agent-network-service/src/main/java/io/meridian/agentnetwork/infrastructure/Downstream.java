package io.meridian.agentnetwork.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapters to wallet-service (agent float and commission wallets, float transfers, transaction summaries) and
 * commission-service (plan status, earnings summaries). All money moves through wallet-service, which posts to the
 * ledger.
 */
@Component
public class Downstream {
    static final String WALLET = "wallet-service";
    static final String COMMISSION = "commission-service";
    static final String LEDGER = "ledger-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public Downstream(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    // ------------------------------------------------------------------ wallets

    /** Opens (idempotently per key) and activates a wallet owned by the agent; returns its id. */
    public UUID openActiveWallet(String key, String walletType, UUID agentId, String name, String currency) {
        ObjectNode w = json.createObjectNode();
        w.put("wallet_type", walletType);
        w.put("agent_id", agentId.toString());
        w.put("display_name", name.length() > 160 ? name.substring(0, 160) : name);
        w.put("currency", currency);
        w.put("opened_channel", "SYSTEM");
        w.put("is_default", "SUPER_AGENT".equals(walletType) || "AGENT".equals(walletType));
        UUID id = id(client.post(WALLET, "/v1/wallets", w, key + "-open"));
        ObjectNode r = json.createObjectNode();
        r.put("reason", "Agent activated");
        JsonNode activated = client.post(WALLET, "/v1/wallets/" + id + "/activate", r, key + "-activate");
        if (!"ACTIVE".equals(activated.path("status").asString())) {
            throw new ApiException(502, "upstream_error", "wallet-service did not activate wallet " + id);
        }
        return id;
    }

    /** Suspends or reactivates a wallet; a wallet already in the target state is left alone. */
    public void setWalletState(UUID walletId, boolean active, String reason, String key) {
        if (walletId == null) {
            return;
        }
        JsonNode w = client.get(WALLET, "/v1/wallets/" + walletId);
        String status = w.path("status").asString("");
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason == null ? (active ? "Agent reactivated" : "Agent suspended") : reason);
        if (active && "SUSPENDED".equals(status)) {
            client.post(WALLET, "/v1/wallets/" + walletId + "/reactivate", body, key);
        } else if (!active && "ACTIVE".equals(status)) {
            client.post(WALLET, "/v1/wallets/" + walletId + "/suspend", body, key);
        }
    }

    public JsonNode wallet(UUID walletId) {
        return client.get(WALLET, "/v1/wallets/" + walletId);
    }

    /** Available balance of a wallet (posted when available is absent). */
    public BigDecimal balance(UUID walletId) {
        JsonNode b = client.get(WALLET, "/v1/wallets/" + walletId + "/balance");
        String v = b.hasNonNull("available_balance") ? b.get("available_balance").asString()
                : b.path("posted_balance").asString("0");
        return new BigDecimal(v);
    }

    public BigDecimal postedBalance(UUID walletId) {
        return new BigDecimal(client.get(WALLET, "/v1/wallets/" + walletId + "/balance").path("posted_balance").asString("0"));
    }

    /** A FLOAT_TRANSFER between two wallets through wallet-service (posted to the ledger there). */
    public JsonNode floatTransfer(String key, UUID from, UUID to, BigDecimal amount, String currency, UUID agentId,
                                  String description) {
        ObjectNode t = json.createObjectNode();
        t.put("txn_type", "FLOAT_TRANSFER");
        t.put("channel", "SYSTEM");
        t.put("initiator_type", "SYSTEM");
        t.put("payer_wallet_id", from.toString());
        t.put("payee_wallet_id", to.toString());
        t.put("amount", amount.toPlainString());
        t.put("currency", currency);
        if (agentId != null) {
            t.put("agent_id", agentId.toString());
        }
        t.put("description", description.length() > 255 ? description.substring(0, 255) : description);
        return client.post(WALLET, "/v1/wallet-transactions", t, key);
    }

    /**
     * WE-FLOAT-1: a wallet-to-GL posting through wallet-service. Exactly one side is a wallet; the other is an
     * internal ledger account ({@code counterparty_ledger_account_id}, which wallet-service accepts in place of one
     * wallet). {@code walletIsPayer} = true debits the wallet and credits the GL account; false credits the wallet.
     */
    public JsonNode walletToGl(String key, String txnType, UUID wallet, boolean walletIsPayer, UUID glAccount,
                               BigDecimal amount, String currency, UUID agentId, String description) {
        ObjectNode t = json.createObjectNode();
        t.put("txn_type", txnType);
        t.put("channel", "SYSTEM");
        t.put("initiator_type", "SYSTEM");
        t.put(walletIsPayer ? "payer_wallet_id" : "payee_wallet_id", wallet.toString());
        t.put("counterparty_ledger_account_id", glAccount.toString());
        t.put("amount", amount.toPlainString());
        t.put("currency", currency);
        if (agentId != null) {
            t.put("agent_id", agentId.toString());
        }
        t.put("description", description.length() > 255 ? description.substring(0, 255) : description);
        return client.post(WALLET, "/v1/wallet-transactions", t, key);
    }

    /**
     * WE-FLOAT-1: a balanced GL&lt;-&gt;GL journal through ledger-service (wallet-service rejects a two-GL-side
     * transaction). One debit and one credit line of {@code amount}; returns the journal entry.
     */
    public JsonNode postJournal(String key, UUID ledgerId, String sourceType, UUID sourceId, String description,
                                UUID debitAccount, UUID creditAccount, BigDecimal amount, String currency) {
        ObjectNode je = json.createObjectNode();
        je.put("ledger_id", ledgerId.toString());
        je.put("entry_type", "TRANSFER");
        je.put("description", description.length() > 500 ? description.substring(0, 500) : description);
        je.put("source_type", sourceType);
        je.put("source_id", sourceId.toString());
        je.put("reference", sourceId.toString());
        var entries = je.putArray("entries");
        ObjectNode d = entries.addObject();
        d.put("ledger_account_id", debitAccount.toString());
        d.put("direction", "DEBIT");
        d.put("amount", amount.toPlainString());
        d.put("currency", currency);
        ObjectNode c = entries.addObject();
        c.put("ledger_account_id", creditAccount.toString());
        c.put("direction", "CREDIT");
        c.put("amount", amount.toPlainString());
        c.put("currency", currency);
        return client.post(LEDGER, "/v1/journal-entries", je, key);
    }

    /** WE-FLOAT-1: posted balance of an internal ledger account now (ledger-service). */
    public BigDecimal ledgerBalance(UUID ledgerAccountId) {
        return new BigDecimal(client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/balance")
                .path("posted_balance").asString("0"));
    }

    /** WE-FLOAT-1: posted balance of an internal ledger account at a point in time (for unclaimed aging cut-offs). */
    public BigDecimal ledgerBalanceAt(UUID ledgerAccountId, OffsetDateTime at) {
        return new BigDecimal(client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/balance-at?at=" + enc(at.toString()))
                .path("posted_balance").asString("0"));
    }

    /** wallet-service summary of COMPLETED transactions for an agent or an outlet in [from, to). */
    public JsonNode summary(UUID agentId, UUID outletId, OffsetDateTime from, OffsetDateTime to) {
        StringBuilder q = new StringBuilder("/v1/wallet-transactions/summary?status=COMPLETED");
        if (agentId != null) {
            q.append("&agent_id=").append(agentId);
        }
        if (outletId != null) {
            q.append("&agent_outlet_id=").append(outletId);
        }
        q.append("&from=").append(enc(from.toString())).append("&to=").append(enc(to.toString()));
        return client.get(WALLET, q.toString());
    }

    // ------------------------------------------------------------------ commissions

    public JsonNode commissionPlan(UUID planId) {
        return client.get(COMMISSION, "/v1/commission-plans/" + planId);
    }

    public JsonNode commissionSummary(UUID agentId, LocalDate from, LocalDate to) {
        return client.get(COMMISSION, "/v1/commission-earnings/summary?beneficiary_agent_id=" + agentId
                + "&from=" + from + "&to=" + to);
    }

    private static UUID id(JsonNode n) {
        String id = n.path("id").asString(null);
        if (id == null) {
            throw new ApiException(502, "upstream_error", "wallet-service returned no id");
        }
        return UUID.fromString(id);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
