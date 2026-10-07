package io.meridian.payment;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * Stateful stand-in for every service payment-service calls through the mocked ServiceClient: ledger-service
 * (balances in normal-balance terms, holds with partial capture and event history, idempotent journal entries,
 * reversals), deposit-service accounts, customer-service names, routing-service (route, pacs.008, rails, bank
 * directory), sanctions screening, limits reservations, fraud scoring, notifications and fx-service. Every call is
 * recorded.
 *
 * <p>Fraud scoring answers ALLOW (score 120) unless the amount is {@link #FRAUD_REVIEW_AMOUNT} (REVIEW, with an alert)
 * or {@link #FRAUD_BLOCK_AMOUNT} (BLOCK); like fraud-service, a repeated idempotency key replays the stored evaluation.
 * {@link #fraudDown} fails the call (502); {@link #fraudDelayMs} stores the evaluation, then answers late.
 */
public class FakeBank {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    static final class Account {
        UUID id;
        String currency;
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
    }

    public static final UUID RAIL = UUID.fromString("0199b000-0000-7000-8000-0000000000a1");
    public static final UUID RAIL_SETTLEMENT = UUID.fromString("0199b000-0000-7000-8000-0000000000a2");

    private final JsonMapper json;
    private final Map<UUID, Account> ledger = new LinkedHashMap<>();
    private final Map<UUID, ObjectNode> deposits = new LinkedHashMap<>();
    private final Map<UUID, ObjectNode> customers = new HashMap<>();
    private final Map<String, ObjectNode> byKey = new HashMap<>();
    private final Map<UUID, ObjectNode> journals = new HashMap<>();
    private final Map<UUID, ObjectNode> holds = new HashMap<>();
    private final Map<UUID, List<ObjectNode>> holdEvents = new HashMap<>();
    private final Map<UUID, UUID> reversed = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    public volatile boolean routingDown;
    public static final String FRAUD_REVIEW_AMOUNT = "66.60";
    public static final String FRAUD_BLOCK_AMOUNT = "66.66";
    public volatile boolean fraudDown;
    public volatile long fraudDelayMs;
    private final Map<String, ObjectNode> fraudByKey = new HashMap<>();

    public FakeBank(JsonMapper json) {
        this.json = json;
    }

    // ------------------------------------------------------------------ test setup

    /** A customer deposit account with its ledger account and opening balance. */
    public synchronized UUID openAccount(UUID customer, String currency, String iban, String balance) {
        Account a = new Account();
        a.id = UUID.randomUUID();
        a.currency = currency;
        a.balance = new BigDecimal(balance);
        ledger.put(a.id, a);
        ObjectNode row = json.createObjectNode();
        UUID id = UUID.randomUUID();
        row.put("id", id.toString());
        row.put("customer_id", customer.toString());
        row.put("currency", currency);
        row.put("status", "ACTIVE");
        row.put("ledger_account_id", a.id.toString());
        row.put("iban", iban);
        row.put("account_number", iban.substring(iban.length() - 8));
        deposits.put(id, row);
        return id;
    }

    public synchronized void customer(UUID id, String first, String last) {
        ObjectNode c = json.createObjectNode();
        c.put("id", id.toString());
        c.put("first_name", first);
        c.put("last_name", last);
        c.put("display_name", first + " " + last);
        customers.put(id, c);
    }

    public synchronized void setAccountStatus(UUID depositId, String status) {
        deposits.get(depositId).put("status", status);
    }

    public synchronized BigDecimal balance(UUID depositId) {
        return ledger.get(UUID.fromString(deposits.get(depositId).get("ledger_account_id").asString())).balance;
    }

    public synchronized BigDecimal pending(UUID depositId) {
        return ledger.get(UUID.fromString(deposits.get(depositId).get("ledger_account_id").asString())).pending;
    }

    public synchronized UUID ledgerAccount(UUID depositId) {
        return UUID.fromString(deposits.get(depositId).get("ledger_account_id").asString());
    }

    public List<Call> calls(String service, String pathPrefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.service().equals(service) && c.path().startsWith(pathPrefix)).toList();
        }
    }

    public synchronized ObjectNode journal(UUID id) {
        return journals.get(id);
    }

    /** Journal entries posted for a given source (payment / return id). */
    public synchronized List<ObjectNode> journalsWithLine(UUID ledgerAccount) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode j : journals.values()) {
            for (JsonNode e : j.get("entries")) {
                if (e.get("ledger_account_id").asString().equals(ledgerAccount.toString())) {
                    out.add(j);
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ServiceClient surface

    public JsonNode post(String service, String path, Object body, String key) {
        if ("fraud-service".equals(service)) {
            JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
            calls.add(new Call(service, "POST", path, b, key));
            return fraud(path, b, key);
        }
        return postLocked(service, path, body, key);
    }

    /** fraud-service evaluate; not synchronized so a slow answer does not block the other fakes. */
    private JsonNode fraud(String path, JsonNode b, String key) {
        if (!path.equals("/v1/risk/fraud/scores/evaluate")) {
            throw new IllegalArgumentException("unexpected fraud POST " + path);
        }
        if (fraudDown) {
            throw new ApiException(502, "upstream_error", "fraud-service is not reachable");
        }
        ObjectNode r;
        synchronized (fraudByKey) {
            ObjectNode stored = fraudByKey.get(key);
            if (stored != null) {
                r = stored.deepCopy().put("replayed", true);
            } else {
                BigDecimal amount = new BigDecimal(b.path("amount").asString("0"));
                String decision = amount.compareTo(new BigDecimal(FRAUD_BLOCK_AMOUNT)) == 0 ? "BLOCK"
                        : amount.compareTo(new BigDecimal(FRAUD_REVIEW_AMOUNT)) == 0 ? "REVIEW" : "ALLOW";
                r = json.createObjectNode();
                r.put("fraud_score_id", UUID.randomUUID().toString());
                r.put("subject_type", b.path("subject_type").asString());
                r.put("subject_id", b.path("subject_id").asString());
                r.put("decision", decision);
                r.put("step_up_required", false);
                r.put("score", switch (decision) { case "BLOCK" -> 910; case "REVIEW" -> 712; default -> 120; });
                r.putArray("reason_codes").add("ALLOW".equals(decision) ? "LOW_RISK" : "NEW_PAYEE");
                if (!"ALLOW".equals(decision)) {
                    r.put("fraud_alert_id", UUID.randomUUID().toString());
                } else {
                    r.putNull("fraud_alert_id");
                }
                r.put("degraded", false);
                r.put("replayed", false);
                fraudByKey.put(key, r.deepCopy());
            }
        }
        long delay = fraudDelayMs;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(502, "upstream_error", "interrupted");
            }
        }
        return r;
    }

    private synchronized JsonNode postLocked(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        return switch (service) {
            case "ledger-service" -> ledgerPost(path, b, key);
            case "routing-service" -> routingPost(path, b, key);
            case "sanctions-service" -> screen(b);
            case "limits-service" -> limits(path, b);
            case "notification-service" -> json.createObjectNode().put("id", UUID.randomUUID().toString()).put("status", "QUEUED");
            case "fx-service" -> fxAccept(path, b);
            default -> throw new IllegalArgumentException("unexpected POST " + service + " " + path);
        };
    }

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
        switch (service) {
            case "deposit-service" -> {
                if (p.length == 3) {
                    ObjectNode page = json.createObjectNode();
                    ArrayNode data = page.putArray("data");
                    for (ObjectNode d : deposits.values()) {
                        if (q.containsKey("iban") && q.get("iban").equals(d.get("iban").asString())
                                || q.containsKey("account_number") && q.get("account_number").equals(d.get("account_number").asString())) {
                            data.add(d);
                        }
                    }
                    page.put("has_more", false);
                    return page;
                }
                ObjectNode d = deposits.get(UUID.fromString(p[3]));
                if (d == null) {
                    throw ApiException.notFound("deposit account", p[3]);
                }
                return d;
            }
            case "customer-service" -> {
                ObjectNode c = customers.get(UUID.fromString(p[3]));
                if (c == null) {
                    throw ApiException.notFound("customer", p[3]);
                }
                return c;
            }
            case "routing-service" -> {
                if (path.startsWith("/v1/payment-rails?")) {
                    ObjectNode page = json.createObjectNode();
                    page.putArray("data").add(rail());
                    page.put("has_more", false);
                    return page;
                }
                return rail();
            }
            case "ledger-service" -> {
                if (path.contains("/events")) {
                    ObjectNode page = json.createObjectNode();
                    ArrayNode data = page.putArray("data");
                    List<ObjectNode> ev = new ArrayList<>(holdEvents.getOrDefault(UUID.fromString(p[3]), List.of()));
                    Collections.reverse(ev);
                    ev.forEach(data::add);
                    return page;
                }
                if (path.startsWith("/v1/holds/")) {
                    return holds.get(UUID.fromString(p[3]));
                }
                Account a = ledger.get(UUID.fromString(p[3]));
                ObjectNode bal = json.createObjectNode();
                bal.put("posted_balance", a.balance.toPlainString());
                bal.put("available_balance", a.balance.subtract(a.pending).toPlainString());
                return bal;
            }
            case "fx-service" -> {
                ObjectNode fq = fxQuotes.get(p[4]);
                return fq != null ? fq.deepCopy() : json.createObjectNode().put("id", p[4]).put("status", "ACTIVE");
            }
            default -> throw new IllegalArgumentException("unexpected GET " + service + " " + path);
        }
    }

    // ------------------------------------------------------------------ routing / risk / fx

    private ObjectNode rail() {
        ObjectNode r = json.createObjectNode();
        r.put("id", RAIL.toString());
        r.put("code", "FPS");
        r.put("is_instant", true);
        r.put("settlement_account_id", RAIL_SETTLEMENT.toString());
        return r;
    }

    private JsonNode routingPost(String path, JsonNode b, String key) {
        if (routingDown) {
            throw new ApiException(502, "upstream_error", "routing-service is not reachable");
        }
        if (path.equals("/v1/routing-rules/route")) {
            JsonNode p = b.get("payment");
            ObjectNode r = json.createObjectNode();
            if ("CHF".equals(p.path("currency").asString())) {
                r.put("routable", false);
                r.put("reason_code", "NO_ROUTE");
                r.put("reason", "No active rail supports CHF");
                return r;
            }
            r.put("routable", true);
            r.put("payment_rail_id", RAIL.toString());
            r.put("rail_code", "FPS");
            r.put("rail_type", "INSTANT");
            r.put("is_instant", true);
            r.put("settlement_account_id", RAIL_SETTLEMENT.toString());
            r.put("cost", "0.05");
            r.put("execution_date", LocalDate.now(ZoneOffset.UTC).toString());
            r.put("after_cutoff", false);
            return r;
        }
        if (path.equals("/v1/rail-messages/pacs008")) {
            if (byKey.containsKey(key)) {
                return byKey.get(key);
            }
            ObjectNode m = json.createObjectNode();
            m.put("id", UUID.randomUUID().toString());
            m.put("message_id", "MSG" + UUID.randomUUID().toString().substring(0, 8));
            m.put("message_type", "pacs.008.001.08");
            m.put("status", "SENT");
            m.put("payment_id", b.path("payment").path("id").asString());
            byKey.put(key, m);
            return m;
        }
        if (path.equals("/v1/rail-participants/lookup")) {
            ObjectNode r = json.createObjectNode();
            String iban = b.path("iban").asString("");
            if (iban.startsWith("DE")) {
                r.put("found", true);
                r.put("bic", "DEUTDEFFXXX");
                r.put("name", "Deutsche Bank");
            } else {
                r.put("found", false);
            }
            return r;
        }
        throw new IllegalArgumentException("unexpected routing POST " + path);
    }

    private JsonNode screen(JsonNode b) {
        String name = b.path("payload").path("creditor_name").asString("");
        ObjectNode r = json.createObjectNode();
        r.put("id", UUID.randomUUID().toString());
        r.put("status", name.contains("Blocked Entity") ? "CONFIRMED_MATCH" : name.contains("Sanctioned") ? "POTENTIAL_MATCH" : "CLEAR");
        return r;
    }

    private JsonNode limits(String path, JsonNode b) {
        ObjectNode r = json.createObjectNode();
        if (path.equals("/v1/risk/limits/checks")) {
            BigDecimal amount = new BigDecimal(b.get("amount").asString());
            r.put("id", UUID.randomUUID().toString());
            boolean allowed = amount.compareTo(new BigDecimal("500000")) <= 0;
            r.put("allowed", allowed);
            r.put("status", allowed ? "RESERVED" : "REJECTED");
            if (!allowed) {
                r.put("action", "REJECT");
            }
            return r;
        }
        r.put("status", path.endsWith("/commit") ? "COMMITTED" : "RELEASED");
        return r;
    }

    private final Map<String, ObjectNode> fxQuotes = new HashMap<>();

    /** An ACTIVE fx-service quote (expires in 10 minutes); customer may be null (unbound quote). */
    public synchronized String fxQuote(UUID customer, String sell, String sellAmount, String buy, String buyAmount) {
        ObjectNode q = json.createObjectNode();
        String id = UUID.randomUUID().toString();
        q.put("id", id);
        q.put("status", "ACTIVE");
        if (customer != null) {
            q.put("customer_id", customer.toString());
        }
        q.put("sell_currency", sell);
        q.put("sell_amount", sellAmount);
        q.put("buy_currency", buy);
        q.put("buy_amount", buyAmount);
        q.put("customer_rate", new BigDecimal(buyAmount).divide(new BigDecimal(sellAmount), 10, java.math.RoundingMode.HALF_EVEN)
                .toPlainString());
        q.put("expires_at", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(10).toString());
        fxQuotes.put(id, q);
        return id;
    }

    private JsonNode fxAccept(String path, JsonNode b) {
        ObjectNode q = fxQuotes.get(path.split("/")[4]);
        if (q != null) { // like fx-service: single use, bound to the payment's currencies and amount
            if (!"ACTIVE".equals(q.path("status").asString())) {
                throw ApiException.conflict("quote_already_accepted", "The quote was already accepted");
            }
            if (!q.get("sell_currency").asString().equals(b.path("sell_currency").asString())
                    || new BigDecimal(q.get("sell_amount").asString()).compareTo(new BigDecimal(b.path("sell_amount").asString("0"))) != 0) {
                throw ApiException.unprocessable("fx_quote_amount_mismatch", "quote/payment mismatch");
            }
            q.put("status", "ACCEPTED");
            ObjectNode d = json.createObjectNode();
            d.put("id", UUID.randomUUID().toString());
            d.put("fx_quote_id", q.get("id").asString());
            d.put("payment_id", b.path("payment_id").asString());
            d.put("rate", q.get("customer_rate").asString());
            d.put("sell_amount", q.get("sell_amount").asString());
            d.put("sell_currency", q.get("sell_currency").asString());
            d.put("buy_amount", q.get("buy_amount").asString());
            d.put("buy_currency", q.get("buy_currency").asString());
            d.put("status", "BOOKED");
            return d;
        }
        ObjectNode d = json.createObjectNode();
        d.put("id", UUID.randomUUID().toString());
        d.put("fx_quote_id", path.split("/")[4]);
        d.put("rate", "1.1500000000");
        d.put("buy_amount", "115.00");
        d.put("buy_currency", "USD");
        d.put("status", "BOOKED");
        return d;
    }

    // ------------------------------------------------------------------ ledger

    private JsonNode ledgerPost(String path, JsonNode b, String key) {
        String[] p = path.split("/");
        if (path.equals("/v1/journal-entries")) {
            return postEntry(b, key);
        }
        if (path.equals("/v1/holds")) {
            if (byKey.containsKey(key)) {
                return byKey.get(key);
            }
            Account a = ledger.get(UUID.fromString(b.get("ledger_account_id").asString()));
            BigDecimal amount = new BigDecimal(b.get("amount").asString());
            if (a.balance.subtract(a.pending).subtract(amount).signum() < 0) {
                throw ApiException.unprocessable("insufficient_funds", "insufficient available balance for the hold");
            }
            a.pending = a.pending.add(amount);
            ObjectNode h = ((ObjectNode) b).deepCopy();
            UUID id = UUID.randomUUID();
            h.put("id", id.toString());
            h.put("status", "ACTIVE");
            h.put("captured_amount", "0");
            holds.put(id, h);
            holdEvents.computeIfAbsent(id, k -> new ArrayList<>()).add(event("PLACED", amount, null));
            byKey.put(key, h);
            return h;
        }
        if (path.startsWith("/v1/holds/") && path.endsWith("/capture")) {
            if (byKey.containsKey(key)) {
                return byKey.get(key);
            }
            UUID holdId = UUID.fromString(p[3]);
            ObjectNode h = holds.get(holdId);
            if (!h.get("status").asString().matches("ACTIVE|PARTIALLY_CAPTURED")) {
                throw ApiException.conflict("hold_not_active", "hold is " + h.get("status").asString());
            }
            BigDecimal amount = new BigDecimal(b.get("amount").asString());
            BigDecimal held = new BigDecimal(h.get("amount").asString());
            BigDecimal captured = new BigDecimal(h.get("captured_amount").asString());
            BigDecimal remaining = held.subtract(captured);
            if (amount.compareTo(remaining) > 0) {
                throw ApiException.unprocessable("invalid_capture_amount", "capture exceeds remaining");
            }
            UUID accountId = UUID.fromString(h.get("ledger_account_id").asString());
            Account a = ledger.get(accountId);
            boolean release = b.path("release_remainder").asBoolean(false);
            a.pending = a.pending.subtract(release ? remaining : amount);
            ObjectNode entry = json.createObjectNode();
            entry.put("ledger_id", "hold");
            entry.put("entry_type", "STANDARD");
            entry.put("description", b.path("description").asString());
            ArrayNode lines = entry.putArray("entries");
            lines.addObject().put("ledger_account_id", accountId.toString()).put("direction", "DEBIT")
                    .put("amount", amount.toPlainString()).put("currency", h.get("currency").asString());
            lines.addObject().put("ledger_account_id", b.get("counter_account_id").asString()).put("direction", "CREDIT")
                    .put("amount", amount.toPlainString()).put("currency", h.get("currency").asString());
            JsonNode je = postEntry(entry, "hc:" + key);
            BigDecimal nowCaptured = captured.add(amount);
            boolean done = release || nowCaptured.compareTo(held) == 0;
            h.put("captured_amount", nowCaptured.toPlainString());
            h.put("status", done ? "CAPTURED" : "PARTIALLY_CAPTURED");
            holdEvents.get(holdId).add(event(done ? "CAPTURED" : "PARTIALLY_CAPTURED", amount,
                    UUID.fromString(je.get("id").asString())));
            ObjectNode out = h.deepCopy();
            byKey.put(key, out);
            return out;
        }
        if (path.startsWith("/v1/holds/") && path.endsWith("/release")) {
            UUID holdId = UUID.fromString(p[3]);
            ObjectNode h = holds.get(holdId);
            if (h.get("status").asString().matches("ACTIVE|PARTIALLY_CAPTURED")) {
                Account a = ledger.get(UUID.fromString(h.get("ledger_account_id").asString()));
                BigDecimal remaining = new BigDecimal(h.get("amount").asString())
                        .subtract(new BigDecimal(h.get("captured_amount").asString()));
                a.pending = a.pending.subtract(remaining);
                h.put("status", "RELEASED");
                holdEvents.get(holdId).add(event("RELEASED", remaining, null));
            } else if (!"RELEASED".equals(h.get("status").asString())) {
                throw ApiException.conflict("hold_not_active", "hold is " + h.get("status").asString());
            }
            return h;
        }
        if (path.startsWith("/v1/journal-entries/") && path.endsWith("/reverse")) {
            if (byKey.containsKey(key)) {
                return byKey.get(key);
            }
            UUID original = UUID.fromString(p[3]);
            if (reversed.containsKey(original)) {
                throw ApiException.conflict("already_reversed", "already reversed");
            }
            ObjectNode o = journals.get(original);
            ObjectNode entry = json.createObjectNode();
            entry.put("entry_type", "REVERSAL");
            ArrayNode lines = entry.putArray("entries");
            for (JsonNode e : o.get("entries")) {
                lines.addObject().put("ledger_account_id", e.get("ledger_account_id").asString())
                        .put("direction", "DEBIT".equals(e.get("direction").asString()) ? "CREDIT" : "DEBIT")
                        .put("amount", e.get("amount").asString()).put("currency", e.get("currency").asString());
            }
            ObjectNode rev = postEntry(entry, key);
            rev.put("reversal_of_id", original.toString());
            reversed.put(original, UUID.fromString(rev.get("id").asString()));
            return rev;
        }
        throw new IllegalArgumentException("unexpected ledger POST " + path);
    }

    private ObjectNode event(String type, BigDecimal amount, UUID journal) {
        ObjectNode e = json.createObjectNode();
        e.put("id", UUID.randomUUID().toString());
        e.put("event_type", type);
        e.put("amount", amount.toPlainString());
        if (journal != null) {
            e.put("journal_entry_id", journal.toString());
        }
        return e;
    }

    private ObjectNode postEntry(JsonNode b, String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "bad key " + key);
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (JsonNode e : b.get("entries")) {
            BigDecimal amt = new BigDecimal(e.get("amount").asString());
            if (amt.signum() <= 0) {
                throw ApiException.unprocessable("invalid_amount", "non-positive line");
            }
            if ("DEBIT".equals(e.get("direction").asString())) {
                debits = debits.add(amt);
            } else {
                credits = credits.add(amt);
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw ApiException.unprocessable("unbalanced_entry", "debits " + debits + " != credits " + credits);
        }
        Map<UUID, BigDecimal> delta = new HashMap<>();
        for (JsonNode e : b.get("entries")) {
            UUID id = UUID.fromString(e.get("ledger_account_id").asString());
            BigDecimal amt = new BigDecimal(e.get("amount").asString());
            if (ledger.containsKey(id)) {
                delta.merge(id, "DEBIT".equals(e.get("direction").asString()) ? amt.negate() : amt, BigDecimal::add);
            }
        }
        for (Map.Entry<UUID, BigDecimal> d : delta.entrySet()) {
            Account a = ledger.get(d.getKey());
            boolean fromHold = key.startsWith("hc:");
            BigDecimal available = fromHold ? a.balance : a.balance.subtract(a.pending);
            if (d.getValue().signum() < 0 && available.add(d.getValue()).signum() < 0) {
                throw ApiException.unprocessable("insufficient_funds", "Account " + a.id + " has insufficient available balance");
            }
        }
        delta.forEach((id, d) -> ledger.get(id).balance = ledger.get(id).balance.add(d));
        ObjectNode entry = ((ObjectNode) b).deepCopy();
        UUID id = UUID.randomUUID();
        entry.put("id", id.toString());
        entry.put("idempotency_key", key);
        journals.put(id, entry);
        byKey.put(key, entry);
        return entry;
    }
}
