package io.meridian.merchant;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory stand-in for wallet-service (and deposit-service account lookups) behind the mocked ServiceClient:
 * wallets with balances and PINs, wallet creation/activation, wallet transactions between wallets or against internal
 * ledger accounts (counterparty), idempotent per Idempotency-Key, with PIN, status and insufficient-funds checks;
 * transaction list with filters and the summary read model. Every call is recorded.
 */
public class FakeWalletService {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    static final class Wallet {
        UUID id;
        String type;
        String currency;
        String status;
        String name;
        String pin;
        String merchantId;
        BigDecimal balance = BigDecimal.ZERO;
    }

    private final JsonMapper json;
    private final Map<UUID, Wallet> wallets = new LinkedHashMap<>();
    private final Map<String, JsonNode> byKey = new HashMap<>();
    private final Map<UUID, ObjectNode> txns = new LinkedHashMap<>();
    private final Map<UUID, BigDecimal> accounts = new HashMap<>();
    public final Map<UUID, JsonNode> depositAccounts = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeWalletService(JsonMapper json) {
        this.json = json;
    }

    // ------------------------------------------------------------------ setup / inspection

    public synchronized UUID customer(String name, String currency, String balance, String pin) {
        Wallet w = new Wallet();
        w.id = UUID.randomUUID();
        w.type = "CUSTOMER";
        w.name = name;
        w.currency = currency;
        w.status = "ACTIVE";
        w.pin = pin;
        w.balance = new BigDecimal(balance);
        wallets.put(w.id, w);
        return w.id;
    }

    public synchronized BigDecimal balance(UUID id) {
        return wallets.get(id).balance;
    }

    /** Net credits to an internal ledger account (fee income, deposit account). */
    public synchronized BigDecimal account(UUID id) {
        return accounts.getOrDefault(id, BigDecimal.ZERO);
    }

    public synchronized void setStatus(UUID wallet, String status) {
        wallets.get(wallet).status = status;
    }

    public List<Call> transactions() {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/wallet-transactions")).toList();
        }
    }

    // ------------------------------------------------------------------ ServiceClient surface

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        String[] pq = path.split("\\?", 2);
        Map<String, String> q = query(pq.length > 1 ? pq[1] : "");
        String[] p = pq[0].split("/");
        if ("deposit-service".equals(service)) {
            JsonNode a = depositAccounts.get(UUID.fromString(p[3]));
            if (a == null) {
                throw new ApiException(404, "not_found", path);
            }
            return a;
        }
        if (!"wallet-service".equals(service)) {
            throw new IllegalArgumentException("unexpected service " + service);
        }
        if (pq[0].equals("/v1/wallet-transactions")) {
            ObjectNode page = json.createObjectNode();
            ArrayNode data = page.putArray("data");
            List<ObjectNode> list = new ArrayList<>(txns.values());
            Collections.reverse(list);
            for (ObjectNode t : list) {
                if (matches(t, q)) {
                    data.add(t);
                }
            }
            page.put("has_more", false);
            page.putNull("next_cursor");
            return page;
        }
        if (pq[0].equals("/v1/wallet-transactions/summary")) {
            Map<String, BigDecimal> sums = new LinkedHashMap<>();
            Map<String, Integer> counts = new HashMap<>();
            OffsetDateTime from = q.containsKey("from") ? OffsetDateTime.parse(q.get("from")) : null;
            for (ObjectNode t : txns.values()) {
                if (!matches(t, q) || from != null && OffsetDateTime.parse(t.get("completed_at").asString()).isBefore(from)) {
                    continue;
                }
                String k = t.get("txn_type").asString() + "|" + t.get("status").asString() + "|" + t.get("currency").asString();
                sums.merge(k, new BigDecimal(t.get("amount").asString()), BigDecimal::add);
                counts.merge(k, 1, Integer::sum);
            }
            ObjectNode out = json.createObjectNode();
            ArrayNode groups = out.putArray("groups");
            sums.forEach((k, v) -> {
                String[] x = k.split("\\|");
                groups.addObject().put("txn_type", x[0]).put("status", x[1]).put("currency", x[2])
                        .put("count", counts.get(k)).put("amount", v.toPlainString());
            });
            return out;
        }
        if (pq[0].startsWith("/v1/wallet-transactions/")) {
            ObjectNode t = txns.get(UUID.fromString(p[3]));
            if (t == null) {
                throw new ApiException(404, "not_found", "wallet transaction " + p[3]);
            }
            return t;
        }
        if (p.length == 4 && pq[0].startsWith("/v1/wallets/")) {
            return row(find(p[3]));
        }
        throw new IllegalArgumentException("unexpected GET " + path);
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if (!"wallet-service".equals(service)) {
            throw new IllegalArgumentException("unexpected service " + service);
        }
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
            w.type = b.get("wallet_type").asString();
            w.name = b.get("display_name").asString();
            w.currency = b.get("currency").asString();
            w.merchantId = b.path("merchant_id").asString(null);
            w.status = "PENDING";
            wallets.put(w.id, w);
            result = row(w);
        } else if (path.startsWith("/v1/wallets/") && path.endsWith("/activate")) {
            Wallet w = find(path.split("/")[3]);
            w.status = "ACTIVE";
            result = row(w);
        } else if (path.equals("/v1/wallet-transactions")) {
            result = transfer(b, key);
        } else {
            throw new IllegalArgumentException("unexpected POST " + path);
        }
        byKey.put(key, result);
        return result;
    }

    // ------------------------------------------------------------------ internals

    private JsonNode transfer(JsonNode b, String key) {
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        String currency = b.get("currency").asString();
        Wallet payer = b.hasNonNull("payer_wallet_id") ? find(b.get("payer_wallet_id").asString()) : null;
        Wallet payee = b.hasNonNull("payee_wallet_id") ? find(b.get("payee_wallet_id").asString()) : null;
        UUID counterparty = b.hasNonNull("counterparty_ledger_account_id")
                ? UUID.fromString(b.get("counterparty_ledger_account_id").asString()) : null;
        if (payer == null && counterparty == null || payee == null && counterparty == null) {
            throw ApiException.unprocessable("payee_required", "two sides needed");
        }
        if (payer != null) {
            if (!"ACTIVE".equals(payer.status)) {
                throw ApiException.unprocessable("wallet_not_active", "payer " + payer.status);
            }
            if (!payer.currency.equals(currency)) {
                throw ApiException.unprocessable("currency_mismatch", "currency");
            }
            if (b.hasNonNull("pin") && !b.get("pin").asString().equals(payer.pin)) {
                throw ApiException.unprocessable("invalid_pin", "Incorrect PIN");
            }
            if (payer.balance.compareTo(amount) < 0) {
                throw ApiException.unprocessable("insufficient_funds", "insufficient funds");
            }
        }
        if (payee != null && !"ACTIVE".equals(payee.status)) {
            throw ApiException.unprocessable("payee_not_active", "payee " + payee.status);
        }
        if (payer != null) {
            payer.balance = payer.balance.subtract(amount);
        } else {
            accounts.merge(counterparty, amount.negate(), BigDecimal::add);
        }
        if (payee != null) {
            payee.balance = payee.balance.add(amount);
        } else {
            accounts.merge(counterparty, amount, BigDecimal::add);
        }
        ObjectNode t = ((ObjectNode) b).deepCopy();
        t.remove("pin");
        UUID id = UUID.randomUUID();
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        t.put("id", id.toString());
        t.put("reference", "WT" + Integer.toHexString(id.hashCode()).toUpperCase());
        t.put("status", "COMPLETED");
        t.put("fee_amount", "0");
        t.put("tax_amount", "0");
        t.put("total_debit_amount", amount.toPlainString());
        t.put("reversed_amount", "0");
        t.put("idempotency_key", key);
        t.put("created_at", now);
        t.put("updated_at", now);
        t.put("completed_at", now);
        t.put("row_version", 1);
        if (payee != null) {
            t.put("payee_name", payee.name);
        }
        txns.put(id, t);
        return t;
    }

    private static boolean matches(ObjectNode t, Map<String, String> q) {
        for (String f : List.of("merchant_id", "txn_type", "status")) {
            if (q.containsKey(f) && !q.get(f).equals(t.path(f).asString(null))) {
                return false;
            }
        }
        OffsetDateTime c = OffsetDateTime.parse(t.get("created_at").asString());
        if (q.containsKey("created_after") && c.isBefore(OffsetDateTime.parse(q.get("created_after")))) {
            return false;
        }
        return !q.containsKey("created_before") || c.isBefore(OffsetDateTime.parse(q.get("created_before")));
    }

    private static Map<String, String> query(String qs) {
        Map<String, String> q = new HashMap<>();
        for (String kv : qs.split("&")) {
            if (!kv.isEmpty()) {
                String[] x = kv.split("=", 2);
                q.put(x[0], URLDecoder.decode(x.length > 1 ? x[1] : "", StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    private Wallet find(String id) {
        Wallet w = wallets.get(UUID.fromString(id));
        if (w == null) {
            throw ApiException.unprocessable("wallet_not_found", "wallet " + id);
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
        if (w.merchantId != null) {
            n.put("merchant_id", w.merchantId);
        }
        return n;
    }
}
