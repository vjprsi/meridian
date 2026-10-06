package io.meridian.commission;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful stand-ins behind the mocked ServiceClient: wallet-service (wallets with balances, wallet transactions that
 * move money idempotently per Idempotency-Key, injectable payee failures) and agent-network-service (agents with
 * their hierarchy and commission wallets). Every call is recorded.
 */
public class FakeNetwork {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    private final Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
    private final Map<UUID, String> currencies = new HashMap<>();
    private final Map<UUID, ObjectNode> agents = new HashMap<>();
    private final Map<String, JsonNode> byKey = new HashMap<>();
    public final Set<UUID> failingPayees = Collections.synchronizedSet(new HashSet<>());
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeNetwork(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID wallet(UUID id, String currency, String balance) {
        balances.put(id, new BigDecimal(balance));
        currencies.put(id, currency);
        return id;
    }

    public synchronized BigDecimal balance(UUID id) {
        return balances.get(id);
    }

    public synchronized void agent(UUID id, String level, UUID parent, UUID territory, UUID commissionWallet) {
        ObjectNode a = json.createObjectNode();
        a.put("id", id.toString());
        a.put("agent_level", level);
        if (parent != null) {
            a.put("parent_agent_id", parent.toString());
        } else {
            a.putNull("parent_agent_id");
        }
        if (territory != null) {
            a.put("agent_territory_id", territory.toString());
        }
        a.put("commission_wallet_id", commissionWallet.toString());
        a.putNull("commission_plan_id");
        a.put("status", "ACTIVE");
        agents.put(id, a);
    }

    public List<Call> payouts() {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/wallet-transactions")).toList();
        }
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        if ("agent-network-service".equals(service) && path.startsWith("/v1/agents/")) {
            ObjectNode a = agents.get(UUID.fromString(path.substring("/v1/agents/".length())));
            if (a == null) {
                throw ApiException.notFound("agent", path);
            }
            return a;
        }
        throw new IllegalArgumentException("unexpected GET " + service + path);
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if (!"wallet-service".equals(service) || !path.equals("/v1/wallet-transactions")) {
            throw new IllegalArgumentException("unexpected POST " + service + path);
        }
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required", "key");
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        UUID payer = UUID.fromString(b.get("payer_wallet_id").asString());
        UUID payee = UUID.fromString(b.get("payee_wallet_id").asString());
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        if (!balances.containsKey(payer) || !balances.containsKey(payee)) {
            throw ApiException.unprocessable("payee_not_found", "unknown wallet");
        }
        if (failingPayees.contains(payee)) {
            throw ApiException.unprocessable("payee_not_active", "The payee wallet is SUSPENDED");
        }
        if (!currencies.get(payer).equals(b.get("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch", "currency");
        }
        if (balances.get(payer).compareTo(amount) < 0) {
            throw ApiException.unprocessable("insufficient_funds", "insufficient funds");
        }
        balances.merge(payer, amount.negate(), BigDecimal::add);
        balances.merge(payee, amount, BigDecimal::add);
        ObjectNode t = ((ObjectNode) b).deepCopy();
        t.put("id", UUID.randomUUID().toString());
        t.put("status", "COMPLETED");
        t.put("completed_at", Instant.now().toString());
        byKey.put(key, t);
        return t;
    }
}
