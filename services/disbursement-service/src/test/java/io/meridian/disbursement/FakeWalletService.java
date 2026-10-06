package io.meridian.disbursement;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * In-memory stand-in for wallet-service behind the mocked ServiceClient: wallets with balances and statuses, lookup by
 * msisdn (ACTIVE / DORMANT only, like wallet-service), wallet creation and activation, and wallet transactions that move
 * money between wallets idempotently per Idempotency-Key with insufficient-funds and status checks. Payouts to selected
 * payee wallets can be made to fail. Every call is recorded.
 */
public class FakeWalletService {
    public record Call(String method, String path, JsonNode body, String key) {}

    static final class Wallet {
        UUID id;
        String msisdn;
        String currency;
        String status;
        String displayName;
        int kycTier;
        BigDecimal balance = BigDecimal.ZERO;
    }

    private final JsonMapper json;
    private final Map<UUID, Wallet> wallets = new LinkedHashMap<>();
    private final Map<String, JsonNode> byKey = new HashMap<>();
    public final Set<UUID> failingPayees = Collections.synchronizedSet(new HashSet<>());
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeWalletService(JsonMapper json) {
        this.json = json;
    }

    // ------------------------------------------------------------------ setup / inspection

    public synchronized UUID wallet(String msisdn, String name, String currency, String status, int tier, String balance) {
        Wallet w = new Wallet();
        w.id = UUID.randomUUID();
        w.msisdn = msisdn;
        w.displayName = name;
        w.currency = currency;
        w.status = status;
        w.kycTier = tier;
        w.balance = new BigDecimal(balance);
        wallets.put(w.id, w);
        return w.id;
    }

    public synchronized BigDecimal balance(UUID id) {
        return wallets.get(id).balance;
    }

    public synchronized void setBalance(UUID id, String amount) {
        wallets.get(id).balance = new BigDecimal(amount);
    }

    public synchronized UUID byMsisdn(String msisdn) {
        return wallets.values().stream().filter(w -> msisdn.equals(w.msisdn)).map(w -> w.id).findFirst().orElse(null);
    }

    public List<Call> payouts() {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/wallet-transactions")).toList();
        }
    }

    // ------------------------------------------------------------------ ServiceClient surface

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call("GET", path, null, null));
        check(service);
        String[] pq = path.split("\\?", 2);
        String[] p = pq[0].split("/");
        if (pq[0].equals("/v1/wallets/lookup")) {
            Map<String, String> q = new HashMap<>();
            for (String kv : pq[1].split("&")) {
                String[] x = kv.split("=", 2);
                q.put(x[0], URLDecoder.decode(x[1], StandardCharsets.UTF_8));
            }
            return wallets.values().stream()
                    .filter(w -> w.msisdn != null && w.msisdn.equals(q.get("msisdn")) && w.currency.equals(q.get("currency"))
                            && (w.status.equals("ACTIVE") || w.status.equals("DORMANT")))
                    .findFirst().map(this::row)
                    .orElseThrow(() -> new ApiException(404, "wallet_not_found", "No wallet for " + q.get("msisdn")));
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
        calls.add(new Call("POST", path, b, key));
        check(service);
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required", "key");
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        JsonNode result;
        if (path.equals("/v1/wallets")) {
            Wallet w = new Wallet();
            w.id = UUID.randomUUID();
            w.msisdn = b.path("msisdn").asString(null);
            w.displayName = b.get("display_name").asString();
            w.currency = b.get("currency").asString();
            w.kycTier = b.path("kyc_tier").asInt(0);
            w.status = "PENDING";
            wallets.put(w.id, w);
            result = row(w);
        } else if (path.startsWith("/v1/wallets/") && path.endsWith("/activate")) {
            Wallet w = find(path.split("/")[3]);
            w.status = "ACTIVE";
            result = row(w);
        } else if (path.equals("/v1/wallet-transactions")) {
            result = transfer(b);
        } else {
            throw new IllegalArgumentException("unexpected POST " + path);
        }
        byKey.put(key, result);
        return result;
    }

    // ------------------------------------------------------------------ internals

    private JsonNode transfer(JsonNode b) {
        Wallet payer = find(b.get("payer_wallet_id").asString());
        Wallet payee = find(b.get("payee_wallet_id").asString());
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        if (failingPayees.contains(payee.id)) {
            throw ApiException.unprocessable("wallet_not_active", "Payee wallet " + payee.id + " cannot receive funds");
        }
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
        t.put("reference", "WTX" + Math.abs(t.get("id").asString().hashCode()));
        t.put("status", "COMPLETED");
        t.put("fee_amount", "0");
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
        n.put("wallet_type", "CUSTOMER");
        n.put("status", w.status);
        n.put("currency", w.currency);
        n.put("display_name", w.displayName);
        if (w.msisdn != null) {
            n.put("msisdn", w.msisdn);
        }
        n.put("kyc_tier", w.kycTier);
        return n;
    }

    private static void check(String service) {
        if (!"wallet-service".equals(service)) {
            throw new IllegalArgumentException("unexpected service " + service);
        }
    }
}
