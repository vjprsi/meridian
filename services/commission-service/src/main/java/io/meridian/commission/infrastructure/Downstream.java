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
}
