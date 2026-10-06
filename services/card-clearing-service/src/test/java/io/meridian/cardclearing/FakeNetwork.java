package io.meridian.cardclearing;

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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful stand-ins for card-authorization-service, card-service and ledger-service behind the mocked
 * ServiceClient. The ledger keeps per-account net balances (credit positive), holds with capture and release, and
 * idempotent journal entries; every call is recorded for assertions.
 */
public class FakeNetwork {
    public record Call(String method, String service, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    public final Map<UUID, ObjectNode> auths = new LinkedHashMap<>();
    public final Map<UUID, ObjectNode> cards = new HashMap<>();
    public final Map<UUID, ObjectNode> holds = new HashMap<>();
    public final Map<UUID, ObjectNode> journals = new LinkedHashMap<>();
    public final Map<UUID, BigDecimal> balances = new HashMap<>();
    private final Map<String, JsonNode> byKey = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    public volatile boolean ledgerDown;

    public FakeNetwork(JsonMapper json) {
        this.json = json;
    }

    // ------------------------------------------------------------------ setup helpers

    public synchronized void card(UUID id, UUID ledgerAccount, UUID program) {
        ObjectNode c = json.createObjectNode();
        c.put("id", id.toString());
        c.put("ledger_account_id", ledgerAccount.toString());
        c.put("card_program_id", program.toString());
        c.put("status", "ACTIVE");
        cards.put(id, c);
    }

    /** An approved authorisation with an ACTIVE hold on {@code account}. */
    public synchronized ObjectNode auth(UUID card, UUID program, UUID account, String amount, String currency,
                                        String ntid, String authCode, String rrn, String status) {
        UUID id = UUID.randomUUID();
        ObjectNode a = json.createObjectNode();
        a.put("id", id.toString());
        a.put("card_id", card.toString());
        a.put("card_program_id", program.toString());
        a.put("message_type", "AUTHORIZATION");
        a.put("status", status);
        a.put("billing_amount", amount);
        a.put("billing_currency", currency);
        a.put("approved_amount", amount);
        a.put("cleared_amount", "0");
        a.put("reversed_amount", "0");
        a.put("hold_account_id", account.toString());
        if (ntid != null) {
            a.put("network_transaction_id", ntid);
        }
        if (authCode != null) {
            a.put("auth_code", authCode);
        }
        if (rrn != null) {
            a.put("rrn", rrn);
        }
        if ("APPROVED".equals(status)) {
            UUID hold = UUID.randomUUID();
            ObjectNode h = json.createObjectNode();
            h.put("id", hold.toString());
            h.put("ledger_account_id", account.toString());
            h.put("amount", amount);
            h.put("captured_amount", "0");
            h.put("currency", currency);
            h.put("status", "ACTIVE");
            holds.put(hold, h);
            a.put("hold_id", hold.toString());
        }
        auths.put(id, a);
        return a;
    }

    public synchronized BigDecimal balance(UUID account) {
        return balances.getOrDefault(account, BigDecimal.ZERO);
    }

    public List<Call> calls(String method, String pathPart) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals(method) && c.path().contains(pathPart)).toList();
        }
    }

    // ------------------------------------------------------------------ ServiceClient surface

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call("GET", service, path, null, null));
        String[] pq = path.split("\\?", 2);
        String[] p = pq[0].split("/");
        switch (service) {
            case "card-authorization-service" -> {
                if (p.length == 3) {
                    return list(query(pq.length > 1 ? pq[1] : ""));
                }
                ObjectNode a = auths.get(UUID.fromString(p[3]));
                if (a == null) {
                    throw ApiException.notFound("card authorization", p[3]);
                }
                return a;
            }
            case "card-service" -> {
                ObjectNode c = cards.get(UUID.fromString(p[3]));
                if (c == null) {
                    throw ApiException.notFound("card", p[3]);
                }
                return c;
            }
            default -> throw new IllegalArgumentException("unexpected GET " + service + " " + path);
        }
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call("POST", service, path, b, key));
        String[] p = path.split("/");
        if ("card-authorization-service".equals(service) && path.endsWith("/clear")) {
            ObjectNode a = auths.get(UUID.fromString(p[3]));
            if (a == null) {
                throw ApiException.notFound("card authorization", p[3]);
            }
            if (byKey.containsKey(key)) {
                return a;
            }
            BigDecimal cleared = new BigDecimal(a.get("cleared_amount").asString())
                    .add(new BigDecimal(b.get("amount").asString()));
            a.put("cleared_amount", cleared.toPlainString());
            a.put("status", b.path("final").asBoolean(false) ? "CLEARED" : "PARTIALLY_CLEARED");
            byKey.put(key, a);
            return a;
        }
        if (!"ledger-service".equals(service)) {
            throw new IllegalArgumentException("unexpected POST " + service + " " + path);
        }
        if (ledgerDown) {
            throw new ApiException(502, "upstream_error", "ledger-service is not reachable");
        }
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "bad key " + key);
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        JsonNode result;
        if (path.equals("/v1/journal-entries")) {
            result = journal(b);
        } else if (path.endsWith("/capture")) {
            result = capture(UUID.fromString(p[3]), b);
        } else if (path.endsWith("/reverse")) {
            ObjectNode orig = journals.get(UUID.fromString(p[3]));
            if (orig == null) {
                throw ApiException.notFound("journal entry", p[3]);
            }
            ObjectNode rb = json.createObjectNode();
            rb.put("entry_type", "REVERSAL");
            ArrayNode lines = rb.putArray("entries");
            for (JsonNode e : orig.get("entries")) {
                ObjectNode l = lines.addObject();
                l.put("ledger_account_id", e.get("ledger_account_id").asString());
                l.put("direction", "DEBIT".equals(e.get("direction").asString()) ? "CREDIT" : "DEBIT");
                l.put("amount", e.get("amount").asString());
                l.put("currency", e.get("currency").asString());
            }
            ObjectNode rev = journal(rb);
            rev.put("reversal_of_id", p[3]);
            result = rev;
        } else {
            throw new IllegalArgumentException("unexpected POST " + path);
        }
        byKey.put(key, result);
        return result;
    }

    // ------------------------------------------------------------------ internals

    private ObjectNode list(Map<String, String> q) {
        ObjectNode page = json.createObjectNode();
        ArrayNode data = page.putArray("data");
        for (ObjectNode a : auths.values()) {
            boolean ok = true;
            for (String f : List.of("network_transaction_id", "card_id", "auth_code", "rrn")) {
                if (q.containsKey(f) && !q.get(f).equals(a.path(f).asString(null))) {
                    ok = false;
                }
            }
            if (ok) {
                data.add(a);
            }
        }
        page.put("has_more", false);
        return page;
    }

    private static Map<String, String> query(String qs) {
        Map<String, String> q = new HashMap<>();
        for (String kv : qs.split("&")) {
            if (kv.isEmpty()) {
                continue;
            }
            String[] x = kv.split("=", 2);
            q.put(x[0], URLDecoder.decode(x[1], StandardCharsets.UTF_8));
        }
        return q;
    }

    private ObjectNode capture(UUID holdId, JsonNode b) {
        ObjectNode h = holds.get(holdId);
        if (h == null) {
            throw ApiException.notFound("hold", holdId);
        }
        if (!"ACTIVE".equals(h.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "hold is " + h.get("status").asString());
        }
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        BigDecimal remaining = new BigDecimal(h.get("amount").asString())
                .subtract(new BigDecimal(h.get("captured_amount").asString()));
        if (amount.compareTo(remaining) > 0) {
            throw ApiException.unprocessable("capture_exceeds_hold", "capture exceeds the hold");
        }
        ObjectNode jb = json.createObjectNode();
        jb.put("entry_type", "CARD_SETTLEMENT");
        ArrayNode e = jb.putArray("entries");
        e.addObject().put("ledger_account_id", h.get("ledger_account_id").asString()).put("direction", "DEBIT")
                .put("amount", amount.toPlainString()).put("currency", h.get("currency").asString());
        e.addObject().put("ledger_account_id", b.get("counter_account_id").asString()).put("direction", "CREDIT")
                .put("amount", amount.toPlainString()).put("currency", h.get("currency").asString());
        ObjectNode j = journal(jb);
        BigDecimal captured = new BigDecimal(h.get("captured_amount").asString()).add(amount);
        h.put("captured_amount", captured.toPlainString());
        boolean release = b.path("release_remainder").asBoolean(false);
        h.put("status", captured.compareTo(new BigDecimal(h.get("amount").asString())) == 0 ? "CAPTURED"
                : release ? "CAPTURED" : "PARTIALLY_CAPTURED");
        h.put("released_remainder", release);
        ObjectNode resp = h.deepCopy();
        resp.put("journal_entry_id", j.get("id").asString());
        return resp;
    }

    private ObjectNode journal(JsonNode b) {
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
        for (JsonNode e : b.get("entries")) {
            UUID acc = UUID.fromString(e.get("ledger_account_id").asString());
            BigDecimal amt = new BigDecimal(e.get("amount").asString());
            balances.merge(acc, "DEBIT".equals(e.get("direction").asString()) ? amt.negate() : amt, BigDecimal::add);
        }
        ObjectNode j = ((ObjectNode) b).deepCopy();
        UUID id = UUID.randomUUID();
        j.put("id", id.toString());
        journals.put(id, j);
        return j;
    }
}
