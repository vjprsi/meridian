package io.meridian.interop;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * In-memory stand-in for wallet-service (and the ledger balance of internal accounts) behind the mocked
 * ServiceClient: wallets with posted and held balances, two-phase transactions (hold, confirm, cancel), credits
 * from internal counterparty accounts, idempotency by key. Every call is recorded for assertions.
 */
public class FakeWalletService {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    static final class Wallet {
        UUID id;
        String status = "ACTIVE";
        String currency;
        String msisdn;
        String name;
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal held = BigDecimal.ZERO;
    }

    private final JsonMapper json;
    private final Map<UUID, Wallet> wallets = new LinkedHashMap<>();
    private final Map<UUID, ObjectNode> txns = new HashMap<>();
    private final Map<String, ObjectNode> byKey = new HashMap<>();
    /** Net balance of internal ledger accounts (credits positive). */
    private final Map<UUID, BigDecimal> accounts = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeWalletService(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID wallet(String msisdn, String name, String currency, String balance, String status) {
        Wallet w = new Wallet();
        w.id = UUID.randomUUID();
        w.msisdn = msisdn;
        w.name = name;
        w.currency = currency;
        w.balance = new BigDecimal(balance);
        w.status = status;
        wallets.put(w.id, w);
        return w.id;
    }

    public synchronized BigDecimal balance(UUID wallet) {
        return wallets.get(wallet).balance;
    }

    public synchronized BigDecimal held(UUID wallet) {
        return wallets.get(wallet).held;
    }

    public synchronized BigDecimal account(UUID account) {
        return accounts.getOrDefault(account, BigDecimal.ZERO);
    }

    public synchronized ObjectNode txn(UUID id) {
        return txns.get(id);
    }

    public List<Call> calls(String method, String pathPrefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals(method) && c.path().startsWith(pathPrefix)).toList();
        }
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        if ("ledger-service".equals(service)) {
            UUID acc = UUID.fromString(path.split("/")[3]);
            ObjectNode b = json.createObjectNode();
            b.put("ledger_account_id", acc.toString());
            b.put("posted_balance", account(acc).toPlainString());
            b.put("available_balance", account(acc).toPlainString());
            return b;
        }
        String[] pq = path.split("\\?", 2);
        if (pq[0].equals("/v1/wallets/lookup")) {
            Map<String, String> q = new HashMap<>();
            for (String kv : pq[1].split("&")) {
                String[] x = kv.split("=", 2);
                q.put(x[0], URLDecoder.decode(x[1], StandardCharsets.UTF_8));
            }
            return wallets.values().stream()
                    .filter(w -> q.get("msisdn").equals(w.msisdn) && "ACTIVE".equals(w.status)
                            && (q.get("currency") == null || q.get("currency").equals(w.currency)))
                    .findFirst().map(this::row).orElseThrow(() -> new ApiException(404, "not_found", "wallet"));
        }
        String[] p = pq[0].split("/");
        if (pq[0].startsWith("/v1/wallets/")) {
            Wallet w = wallets.get(UUID.fromString(p[3]));
            if (w == null) {
                throw new ApiException(404, "not_found", "wallet");
            }
            return row(w);
        }
        if (pq[0].startsWith("/v1/wallet-transactions/")) {
            ObjectNode t = txns.get(UUID.fromString(p[3]));
            if (t == null) {
                throw new ApiException(404, "not_found", "wallet transaction");
            }
            return t;
        }
        throw new IllegalArgumentException("unexpected GET " + path);
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if (key != null && byKey.containsKey(path + "|" + key)) {
            return byKey.get(path + "|" + key);
        }
        ObjectNode out;
        if (path.equals("/v1/wallet-transactions")) {
            out = create(b);
        } else if (path.endsWith("/confirm")) {
            ObjectNode t = existing(path);
            requireStatus(t, "PENDING_CONFIRMATION");
            Wallet w = wallets.get(UUID.fromString(t.get("payer_wallet_id").asString()));
            BigDecimal amt = new BigDecimal(t.get("amount").asString());
            w.held = w.held.subtract(amt);
            w.balance = w.balance.subtract(amt);
            accounts.merge(UUID.fromString(t.get("counterparty_ledger_account_id").asString()), amt, BigDecimal::add);
            t.put("status", "COMPLETED");
            out = t.deepCopy();
        } else if (path.endsWith("/cancel")) {
            ObjectNode t = existing(path);
            requireStatus(t, "PENDING_CONFIRMATION");
            Wallet w = wallets.get(UUID.fromString(t.get("payer_wallet_id").asString()));
            w.held = w.held.subtract(new BigDecimal(t.get("amount").asString()));
            t.put("status", "CANCELLED");
            out = t.deepCopy();
        } else {
            throw new IllegalArgumentException("unexpected POST " + path);
        }
        if (key != null) {
            byKey.put(path + "|" + key, out);
        }
        return out;
    }

    private ObjectNode existing(String path) {
        ObjectNode t = txns.get(UUID.fromString(path.split("/")[3]));
        if (t == null) {
            throw new ApiException(404, "not_found", "wallet transaction");
        }
        return t;
    }

    private static void requireStatus(ObjectNode t, String status) {
        if (!status.equals(t.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "transaction is " + t.get("status").asString());
        }
    }

    private ObjectNode create(JsonNode b) {
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        UUID counterparty = UUID.fromString(b.get("counterparty_ledger_account_id").asString());
        ObjectNode t = ((ObjectNode) b).deepCopy();
        t.remove("two_phase");
        UUID id = UUID.randomUUID();
        t.put("id", id.toString());
        switch (b.get("txn_type").asString()) {
            case "INTEROP_OUT" -> {
                Wallet w = wallets.get(UUID.fromString(b.get("payer_wallet_id").asString()));
                if (!"ACTIVE".equals(w.status)) {
                    throw ApiException.unprocessable("wallet_not_active", "payer wallet is " + w.status);
                }
                if (w.balance.subtract(w.held).compareTo(amount) < 0) {
                    throw ApiException.unprocessable("insufficient_funds", "insufficient available balance");
                }
                w.held = w.held.add(amount);
                t.put("status", "PENDING_CONFIRMATION");
                t.put("hold_id", UUID.randomUUID().toString());
                t.put("confirmation_method", "SYSTEM");
            }
            case "INTEROP_IN" -> {
                Wallet w = wallets.get(UUID.fromString(b.get("payee_wallet_id").asString()));
                if (!"ACTIVE".equals(w.status)) {
                    throw ApiException.unprocessable("payee_not_active", "payee wallet is " + w.status);
                }
                w.balance = w.balance.add(amount);
                accounts.merge(counterparty, amount.negate(), BigDecimal::add);
                t.put("status", "COMPLETED");
            }
            default -> throw new IllegalArgumentException("unexpected txn type " + b.get("txn_type"));
        }
        txns.put(id, t);
        return t.deepCopy();
    }

    private ObjectNode row(Wallet w) {
        ObjectNode n = json.createObjectNode();
        n.put("id", w.id.toString());
        n.put("status", w.status);
        n.put("currency", w.currency);
        n.put("msisdn", w.msisdn);
        n.put("display_name", w.name);
        n.put("wallet_type", "CUSTOMER");
        return n;
    }
}
