package io.meridian.agentnetwork;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory wallet-service (and commission-service) behind the mocked ServiceClient: wallets with type, owner, status
 * and balance; create / activate / suspend / reactivate; FLOAT_TRANSFER transactions idempotent per key with
 * insufficient-funds and status checks; canned transaction summaries per agent or outlet; commission plans and
 * earnings summaries. Every call is recorded.
 */
public class FakeWalletService {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    static final class Wallet {
        UUID id;
        String type;
        String agentId;
        String currency;
        String status;
        String name;
        BigDecimal balance = BigDecimal.ZERO;
    }

    private final JsonMapper json;
    private final Map<UUID, Wallet> wallets = new LinkedHashMap<>();
    private final Map<String, JsonNode> byKey = new HashMap<>();
    public final Map<String, String> summaries = Collections.synchronizedMap(new HashMap<>());
    public final Map<UUID, String> plans = Collections.synchronizedMap(new HashMap<>());
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeWalletService(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID wallet(UUID id, String type, String currency, String balance) {
        Wallet w = new Wallet();
        w.id = id;
        w.type = type;
        w.currency = currency;
        w.status = "ACTIVE";
        w.name = type;
        w.balance = new BigDecimal(balance);
        wallets.put(id, w);
        return id;
    }

    public synchronized BigDecimal balance(UUID id) {
        return wallets.get(id).balance;
    }

    public synchronized String status(UUID id) {
        return wallets.get(id).status;
    }

    public synchronized String type(UUID id) {
        return wallets.get(id).type;
    }

    public synchronized void setBalance(UUID id, String amount) {
        wallets.get(id).balance = new BigDecimal(amount);
    }

    public List<Call> calls(String method, String pathPrefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals(method) && c.path().startsWith(pathPrefix)).toList();
        }
    }

    // ------------------------------------------------------------------ ServiceClient surface

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        String[] pq = path.split("\\?", 2);
        Map<String, String> q = new HashMap<>();
        if (pq.length > 1) {
            for (String kv : pq[1].split("&")) {
                String[] x = kv.split("=", 2);
                q.put(x[0], URLDecoder.decode(x[1], StandardCharsets.UTF_8));
            }
        }
        String[] p = pq[0].split("/");
        if ("commission-service".equals(service)) {
            if (pq[0].equals("/v1/commission-earnings/summary")) {
                return json.readTree("{\"groups\":[{\"currency\":\"KES\",\"earned\":\"120.00\",\"net\":\"108.00\"}]}");
            }
            String plan = plans.get(UUID.fromString(p[3]));
            if (plan == null) {
                throw ApiException.notFound("commission plan", p[3]);
            }
            return json.readTree(plan);
        }
        check(service);
        if (pq[0].equals("/v1/wallet-transactions/summary")) {
            String k = q.containsKey("agent_outlet_id") ? "outlet:" + q.get("agent_outlet_id") : "agent:" + q.get("agent_id");
            return json.readTree(summaries.getOrDefault(k, "{\"groups\":[]}"));
        }
        Wallet w = find(p[3]);
        if (p.length == 4) {
            return row(w);
        }
        if (p[4].equals("balance")) {
            ObjectNode b = json.createObjectNode();
            b.put("currency", w.currency);
            b.put("posted_balance", w.balance.toPlainString());
            b.put("available_balance", w.balance.toPlainString());
            b.put("pending_debits", "0");
            return b;
        }
        throw new IllegalArgumentException("unexpected GET " + path);
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        check(service);
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required", "key");
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        JsonNode result;
        if (path.equals("/v1/wallets")) {
            if (!b.hasNonNull("agent_id")) {
                throw ApiException.unprocessable("owner_required", "agent wallets need agent_id");
            }
            Wallet w = new Wallet();
            w.id = UUID.randomUUID();
            w.type = b.get("wallet_type").asString();
            w.agentId = b.get("agent_id").asString();
            w.name = b.get("display_name").asString();
            w.currency = b.get("currency").asString();
            w.status = "PENDING";
            wallets.put(w.id, w);
            result = row(w);
        } else if (path.startsWith("/v1/wallets/")) {
            Wallet w = find(path.split("/")[3]);
            String action = path.split("/")[4];
            switch (action) {
                case "activate" -> w.status = "ACTIVE";
                case "suspend" -> {
                    if (!"ACTIVE".equals(w.status)) {
                        throw ApiException.conflict("invalid_transition", "not active");
                    }
                    w.status = "SUSPENDED";
                }
                case "reactivate" -> {
                    if (!"SUSPENDED".equals(w.status)) {
                        throw ApiException.conflict("invalid_transition", "not suspended");
                    }
                    w.status = "ACTIVE";
                }
                default -> throw new IllegalArgumentException("unexpected POST " + path);
            }
            result = row(w);
        } else if (path.equals("/v1/wallet-transactions")) {
            result = transfer(b);
        } else {
            throw new IllegalArgumentException("unexpected POST " + path);
        }
        byKey.put(key, result);
        return result;
    }

    private JsonNode transfer(JsonNode b) {
        Wallet payer = find(b.get("payer_wallet_id").asString());
        Wallet payee = find(b.get("payee_wallet_id").asString());
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        if (!payer.status.equals("ACTIVE") || !payee.status.equals("ACTIVE")) {
            throw ApiException.unprocessable("wallet_not_active", "wallet not active");
        }
        if (!payer.currency.equals(b.get("currency").asString()) || !payee.currency.equals(payer.currency)) {
            throw ApiException.unprocessable("currency_mismatch", "currency");
        }
        if (payer.balance.compareTo(amount) < 0) {
            throw ApiException.unprocessable("insufficient_funds", "insufficient funds");
        }
        payer.balance = payer.balance.subtract(amount);
        payee.balance = payee.balance.add(amount);
        ObjectNode t = ((ObjectNode) b).deepCopy();
        t.put("id", UUID.randomUUID().toString());
        t.put("status", "COMPLETED");
        t.put("completed_at", Instant.now().toString());
        return t;
    }

    private Wallet find(String id) {
        Wallet w = wallets.get(UUID.fromString(id));
        if (w == null) {
            throw ApiException.notFound("wallet", id);
        }
        return w;
    }

    private ObjectNode row(Wallet w) {
        ObjectNode n = json.createObjectNode();
        n.put("id", w.id.toString());
        n.put("wallet_type", w.type);
        n.put("status", w.status);
        n.put("currency", w.currency);
        n.put("display_name", w.name);
        if (w.agentId != null) {
            n.put("agent_id", w.agentId);
        }
        return n;
    }

    private static void check(String service) {
        if (!"wallet-service".equals(service)) {
            throw new IllegalArgumentException("unexpected service " + service);
        }
    }
}
