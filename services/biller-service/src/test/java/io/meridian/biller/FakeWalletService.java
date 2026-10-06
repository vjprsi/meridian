package io.meridian.biller;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
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
 * In-memory wallet-service behind the mocked ServiceClient: wallets with posted and held balances, generic wallet
 * transactions (single or two-phase with confirm / cancel), reversal, a flat BILL_PAYMENT fee, PIN check against
 * {@link #PIN}, insufficient-funds and status checks, idempotency per Idempotency-Key, credits to non-wallet ledger
 * counterparties. Every call is recorded.
 */
public class FakeWalletService {
    public static final String PIN = "1357";
    public static final BigDecimal BILL_FEE = new BigDecimal("2.50");

    public record Call(String method, String path, JsonNode body, String key) {}

    static final class Wallet {
        UUID id;
        String currency;
        String status = "ACTIVE";
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal held = BigDecimal.ZERO;
    }

    private final JsonMapper json;
    private final Map<UUID, Wallet> wallets = new LinkedHashMap<>();
    private final Map<UUID, ObjectNode> txns = new HashMap<>();
    private final Map<String, JsonNode> byKey = new HashMap<>();
    private final Map<UUID, BigDecimal> counterparties = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeWalletService(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID wallet(String currency, String balance) {
        Wallet w = new Wallet();
        w.id = UUID.randomUUID();
        w.currency = currency;
        w.balance = new BigDecimal(balance);
        wallets.put(w.id, w);
        return w.id;
    }

    public synchronized BigDecimal balance(UUID id) {
        return wallets.get(id).balance;
    }

    public synchronized BigDecimal held(UUID id) {
        return wallets.get(id).held;
    }

    public synchronized BigDecimal counterparty(UUID account) {
        return counterparties.getOrDefault(account, BigDecimal.ZERO);
    }

    public synchronized JsonNode txn(UUID id) {
        return txns.get(id);
    }

    public List<Call> posts(String suffix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals("POST") && c.path().endsWith(suffix)).toList();
        }
    }

    public synchronized JsonNode get(String service, String path) {
        check(service);
        calls.add(new Call("GET", path, null, null));
        String[] p = path.split("\\?")[0].split("/");
        if (p.length == 4 && p[2].equals("wallets")) {
            Wallet w = find(p[3]);
            return json.createObjectNode().put("id", w.id.toString()).put("status", w.status).put("currency", w.currency);
        }
        throw new IllegalArgumentException("unexpected GET " + path);
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        check(service);
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call("POST", path, b, key));
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required", "key");
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        JsonNode result;
        if (path.equals("/v1/wallet-transactions")) {
            result = create(b);
        } else {
            String[] p = path.split("/");
            ObjectNode t = txns.get(UUID.fromString(p[3]));
            if (t == null) {
                throw ApiException.notFound("wallet transaction", p[3]);
            }
            result = switch (p[4]) {
                case "confirm" -> confirm(t);
                case "cancel" -> cancel(t);
                case "reverse" -> reverse(t);
                default -> throw new IllegalArgumentException("unexpected POST " + path);
            };
        }
        byKey.put(key, result.deepCopy());
        return result;
    }

    private ObjectNode create(JsonNode b) {
        Wallet payer = find(b.get("payer_wallet_id").asString());
        Wallet payee = b.hasNonNull("payee_wallet_id") ? find(b.get("payee_wallet_id").asString()) : null;
        if (payee == null && !b.hasNonNull("counterparty_ledger_account_id")) {
            throw ApiException.unprocessable("payee_required", "payee");
        }
        if (b.hasNonNull("pin") && !PIN.equals(b.get("pin").asString())) {
            throw ApiException.unprocessable("invalid_pin", "Incorrect PIN");
        }
        if (!"ACTIVE".equals(payer.status)) {
            throw ApiException.unprocessable("wallet_not_active", "payer");
        }
        if (!payer.currency.equals(b.get("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch", "currency");
        }
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        BigDecimal fee = "BILL_PAYMENT".equals(b.get("txn_type").asString()) ? BILL_FEE : BigDecimal.ZERO;
        BigDecimal total = amount.add(fee);
        if (payer.balance.subtract(payer.held).compareTo(total) < 0) {
            throw ApiException.unprocessable("insufficient_funds", "Insufficient available balance");
        }
        ObjectNode t = ((ObjectNode) b).deepCopy();
        t.remove("pin");
        t.remove("two_phase");
        UUID id = UUID.randomUUID();
        t.put("id", id.toString());
        t.put("fee_amount", fee.toPlainString());
        t.put("total_debit_amount", total.toPlainString());
        if (b.path("two_phase").asBoolean(false)) {
            payer.held = payer.held.add(total);
            t.put("status", "PENDING_CONFIRMATION");
            t.put("confirmation_method", "SYSTEM");
            t.put("hold_id", UUID.randomUUID().toString());
        } else {
            move(t);
            t.put("status", "COMPLETED");
        }
        txns.put(id, t);
        return t.deepCopy();
    }

    private void move(ObjectNode t) {
        Wallet payer = find(t.get("payer_wallet_id").asString());
        BigDecimal amount = new BigDecimal(t.get("amount").asString());
        payer.balance = payer.balance.subtract(new BigDecimal(t.get("total_debit_amount").asString()));
        if (t.hasNonNull("payee_wallet_id")) {
            Wallet payee = find(t.get("payee_wallet_id").asString());
            payee.balance = payee.balance.add(amount);
        } else {
            counterparties.merge(UUID.fromString(t.get("counterparty_ledger_account_id").asString()), amount, BigDecimal::add);
        }
        t.put("completed_at", Instant.now().toString());
    }

    private ObjectNode confirm(ObjectNode t) {
        if (!"PENDING_CONFIRMATION".equals(t.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "not pending");
        }
        Wallet payer = find(t.get("payer_wallet_id").asString());
        payer.held = payer.held.subtract(new BigDecimal(t.get("total_debit_amount").asString()));
        move(t);
        t.put("status", "COMPLETED");
        return t.deepCopy();
    }

    private ObjectNode cancel(ObjectNode t) {
        if (!"PENDING_CONFIRMATION".equals(t.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "not pending");
        }
        Wallet payer = find(t.get("payer_wallet_id").asString());
        payer.held = payer.held.subtract(new BigDecimal(t.get("total_debit_amount").asString()));
        t.put("status", "CANCELLED");
        return t.deepCopy();
    }

    private ObjectNode reverse(ObjectNode t) {
        if (!"COMPLETED".equals(t.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "not completed");
        }
        Wallet payer = find(t.get("payer_wallet_id").asString());
        Wallet payee = find(t.get("payee_wallet_id").asString());
        BigDecimal amount = new BigDecimal(t.get("amount").asString());
        if (payee.balance.compareTo(amount) < 0) {
            throw ApiException.unprocessable("insufficient_funds", "payee spent the funds");
        }
        payee.balance = payee.balance.subtract(amount);
        payer.balance = payer.balance.add(new BigDecimal(t.get("total_debit_amount").asString()));
        t.put("status", "REVERSED");
        ObjectNode r = json.createObjectNode();
        r.put("id", UUID.randomUUID().toString());
        r.put("txn_type", "REVERSAL");
        r.put("status", "COMPLETED");
        r.put("original_transaction_id", t.get("id").asString());
        r.put("amount", t.get("amount").asString());
        return r;
    }

    private Wallet find(String id) {
        Wallet w = wallets.get(UUID.fromString(id));
        if (w == null) {
            throw ApiException.unprocessable("wallet_not_found", "wallet " + id);
        }
        return w;
    }

    private static void check(String service) {
        if (!"wallet-service".equals(service)) {
            throw new IllegalArgumentException("unexpected service " + service);
        }
    }
}
