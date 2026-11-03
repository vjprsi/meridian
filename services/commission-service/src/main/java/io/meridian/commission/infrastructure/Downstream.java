package io.meridian.commission.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** agent-network-service (hierarchy) and wallet-service (payouts) adapters. */
@Component
public class Downstream {
    private final ServiceClient client;
    private final JsonMapper json;

    public Downstream(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public JsonNode agent(UUID id) {
        return client.get("agent-network-service", "/v1/agents/" + id);
    }

    /** The agent followed by its parents up to the top of the hierarchy (cycle-safe, max 10 levels). */
    public List<JsonNode> upline(UUID agentId) {
        List<JsonNode> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        UUID next = agentId;
        while (next != null && chain.size() < 10 && seen.add(next.toString())) {
            JsonNode a = agent(next);
            chain.add(a);
            JsonNode parent = a.get("parent_agent_id");
            next = parent == null || parent.isNull() || parent.asString().isBlank() ? null : UUID.fromString(parent.asString());
        }
        return chain;
    }

    /** COMMISSION_PAYOUT from the pool wallet to a beneficiary wallet; returns the wallet transaction. */
    public JsonNode payout(String key, UUID pool, UUID beneficiaryWallet, UUID agentId, BigDecimal amount, String currency,
                           UUID runId) {
        ObjectNode b = json.createObjectNode();
        b.put("txn_type", "COMMISSION_PAYOUT");
        b.put("channel", "SYSTEM");
        b.put("initiator_type", "SYSTEM");
        b.put("amount", amount.toPlainString());
        b.put("currency", currency);
        b.put("payer_wallet_id", pool.toString());
        b.put("payee_wallet_id", beneficiaryWallet.toString());
        if (agentId != null) {
            b.put("agent_id", agentId.toString());
        }
        b.put("description", "Commission payout");
        b.putObject("metadata").put("commission_payout_run_id", runId.toString());
        JsonNode r = client.post("wallet-service", "/v1/wallet-transactions", b, key);
        if (!"COMPLETED".equals(r.path("status").asString())) {
            throw ApiException.unprocessable("payout_not_completed", "Payout transaction is " + r.path("status").asString());
        }
        return r;
    }

    /** One GL posting line. */
    public record Line(UUID account, String direction, BigDecimal amount, String description) {}

    /**
     * WE-PRICE-1 (PE-14): posts one balanced GL journal (ledger-service) for a commission-sharing split (the CP/CR
     * entries). Carries a deterministic idempotency key so a retry never double-posts; returns the journal entry id.
     */
    public UUID postJournal(String key, UUID ledgerId, String description, String currency, String reference,
                            String sourceType, UUID sourceId, List<Line> lines) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId.toString());
        body.put("entry_type", "COMMISSION");
        body.put("description", trim(description, 500));
        if (reference != null) {
            body.put("reference", reference);
        }
        body.put("source_type", sourceType);
        body.put("source_id", sourceId.toString());
        var entries = body.putArray("entries");
        for (Line l : lines) {
            if (l.amount().signum() == 0) {
                continue;
            }
            if (l.account() == null) {
                throw ApiException.unprocessable("ledger_account_not_configured",
                        "A commission-sharing GL account (CP/CR) is not configured");
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
        JsonNode r = client.post("ledger-service", "/v1/journal-entries", body, key);
        String id = r.path("id").asString(null);
        if (id == null) {
            throw new ApiException(502, "upstream_error", "ledger-service returned no id");
        }
        return UUID.fromString(id);
    }

    /** Reverses a previously posted commission-sharing journal (full rollback on clawback). */
    public void reverseJournal(String key, UUID journalEntryId, String reason) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", trim(reason == null ? "clawback" : reason, 255));
        client.post("ledger-service", "/v1/journal-entries/" + journalEntryId + "/reverse", body, key);
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
