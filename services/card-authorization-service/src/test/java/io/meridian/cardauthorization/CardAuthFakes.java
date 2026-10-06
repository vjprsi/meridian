package io.meridian.cardauthorization;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
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
 * Stateful in-memory stand-ins for card-service (auth-context), ledger-service (holds and available balance),
 * fraud-service, card-vault-service (verify) and card-clearing-service, behind the mocked ServiceClient.
 * Every call is recorded so tests can assert exactly what the authorisation service sent.
 */
public class CardAuthFakes {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    // card-service
    public final Map<UUID, ObjectNode> contexts = new HashMap<>();
    // ledger-service
    public final Map<UUID, BigDecimal> balances = new HashMap<>();
    public final Map<UUID, ObjectNode> holds = new LinkedHashMap<>();
    private final Map<String, ObjectNode> holdsByKey = new HashMap<>();
    public volatile boolean ledgerDown;
    // fraud-service
    public volatile int fraudScore = 100;
    public volatile String fraudDecision = "ALLOW";
    public volatile long fraudDelayMs;
    // card-vault-service
    public volatile String cvvResult = "M";
    public volatile String expiryResult = "M";
    public volatile String pinResult = "VERIFIED";
    // card-clearing-service
    public final List<JsonNode> clearingFiles = Collections.synchronizedList(new ArrayList<>());

    public CardAuthFakes(JsonMapper json) {
        this.json = json;
    }

    // ------------------------------------------------------------------ ServiceClient surface

    public JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        String p = path.split("\\?")[0];
        String[] seg = p.split("/");
        switch (service) {
            case "card-service" -> {
                ObjectNode ctx;
                synchronized (this) {
                    ctx = contexts.get(UUID.fromString(seg[3]));
                }
                if (ctx == null) {
                    throw ApiException.notFound("card", seg[3]);
                }
                ObjectNode copy = ctx.deepCopy();
                copy.put("as_of", java.time.OffsetDateTime.now().toString());
                return copy;
            }
            case "ledger-service" -> {
                ledgerCheck();
                synchronized (this) {
                    UUID acc = UUID.fromString(seg[3]);
                    ObjectNode b = json.createObjectNode();
                    b.put("ledger_account_id", acc.toString());
                    b.put("available_balance", available(acc).toPlainString());
                    return b;
                }
            }
            case "card-clearing-service" -> {
                ObjectNode page = json.createObjectNode();
                ArrayNode data = page.putArray("data");
                synchronized (clearingFiles) {
                    for (JsonNode f : clearingFiles) {
                        if (f.get("id").asString().equals(seg[4])) {
                            ObjectNode rec = ((ObjectNode) f.get("records").get(0)).deepCopy();
                            rec.put("id", UUID.nameUUIDFromBytes(seg[4].getBytes()).toString());
                            rec.put("clearing_file_id", seg[4]);
                            rec.put("match_status", "MATCHED");
                            data.add(rec);
                        }
                    }
                }
                page.put("has_more", false);
                return page;
            }
            default -> throw new IllegalArgumentException("unexpected GET " + service + " " + path);
        }
    }

    public JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        return switch (service) {
            case "ledger-service" -> ledger(path, b, key);
            case "fraud-service" -> fraud(b);
            case "card-vault-service" -> {
                ObjectNode r = json.createObjectNode();
                r.put("cvv_result", b.hasNonNull("cvv") ? cvvResult : "P");
                r.put("expiry_result", b.hasNonNull("expiry_month") ? expiryResult : "P");
                r.put("pin_result", b.hasNonNull("encrypted_pin_block") ? pinResult : "NOT_PROCESSED");
                r.put("pin_try_count", 0);
                yield r;
            }
            case "card-clearing-service" -> {
                ObjectNode f = ((ObjectNode) b).deepCopy();
                f.put("id", UUID.randomUUID().toString());
                f.put("status", "POSTED");
                clearingFiles.add(f);
                yield f;
            }
            default -> throw new IllegalArgumentException("unexpected POST " + service + " " + path);
        };
    }

    // ------------------------------------------------------------------ fakes

    private JsonNode fraud(JsonNode b) {
        if (fraudDelayMs > 0) {
            try {
                Thread.sleep(fraudDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(502, "upstream_error", "interrupted");
            }
        }
        ObjectNode r = json.createObjectNode();
        r.put("id", UUID.randomUUID().toString());
        r.put("subject_id", b.get("subject_id").asString());
        r.put("score", fraudScore);
        r.put("decision", fraudDecision);
        return r;
    }

    private void ledgerCheck() {
        if (ledgerDown) {
            throw new ApiException(502, "upstream_error", "ledger-service is not reachable");
        }
    }

    private synchronized JsonNode ledger(String path, JsonNode b, String key) {
        ledgerCheck();
        if (key != null && holdsByKey.containsKey(key)) {
            return holdsByKey.get(key);
        }
        ObjectNode result;
        if (path.equals("/v1/holds")) {
            UUID acc = UUID.fromString(b.get("ledger_account_id").asString());
            BigDecimal amount = new BigDecimal(b.get("amount").asString());
            if (available(acc).compareTo(amount) < 0) {
                throw ApiException.unprocessable("insufficient_funds", "insufficient available balance for the hold");
            }
            result = ((ObjectNode) b).deepCopy();
            UUID id = UUID.randomUUID();
            result.put("id", id.toString());
            result.put("status", "ACTIVE");
            result.put("captured_amount", "0");
            holds.put(id, result);
        } else {
            String[] seg = path.split("/");
            ObjectNode h = holds.get(UUID.fromString(seg[3]));
            if (h == null) {
                throw ApiException.notFound("hold", seg[3]);
            }
            switch (seg[4]) {
                case "increment" -> {
                    BigDecimal add = new BigDecimal(b.get("amount").asString());
                    UUID acc = UUID.fromString(h.get("ledger_account_id").asString());
                    if (!"ACTIVE".equals(h.get("status").asString())) {
                        throw ApiException.conflict("invalid_transition", "hold not active");
                    }
                    if (available(acc).compareTo(add) < 0) {
                        throw ApiException.unprocessable("insufficient_funds", "insufficient available balance");
                    }
                    h.put("amount", new BigDecimal(h.get("amount").asString()).add(add).toPlainString());
                }
                case "release" -> h.put("status", "RELEASED");
                default -> throw new IllegalArgumentException(path);
            }
            result = h;
        }
        if (key != null) {
            holdsByKey.put(key, result);
        }
        return result;
    }

    /** Balance minus active holds. */
    public synchronized BigDecimal available(UUID account) {
        BigDecimal held = BigDecimal.ZERO;
        for (ObjectNode h : holds.values()) {
            if ("ACTIVE".equals(h.get("status").asString()) && h.get("ledger_account_id").asString().equals(account.toString())) {
                held = held.add(new BigDecimal(h.get("amount").asString()));
            }
        }
        return balances.getOrDefault(account, BigDecimal.ZERO).subtract(held);
    }

    public synchronized ObjectNode hold(String id) {
        return holds.get(UUID.fromString(id));
    }

    public List<Call> calls(String service, String pathPrefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.service().equals(service) && c.path().startsWith(pathPrefix)).toList();
        }
    }

    // ------------------------------------------------------------------ auth-context builder

    public ObjectNode program(UUID id, String fundingModel, UUID ledgerAccount) {
        ObjectNode p = json.createObjectNode();
        p.put("id", id.toString());
        p.put("status", "ACTIVE");
        p.put("scheme", "VISA");
        p.put("funding_model", fundingModel);
        p.put("currency", "USD");
        p.put("country", "US");
        if (ledgerAccount != null) {
            p.put("ledger_account_id", ledgerAccount.toString());
        }
        p.put("jit_timeout_ms", 1500);
        p.put("stand_in_enabled", true);
        p.put("stand_in_max_amount", "100");
        p.put("fraud_policy", "FAIL_OPEN");
        p.put("fraud_timeout_ms", 150);
        p.put("fraud_decline_score", 800);
        p.put("fx_markup_rate", "0.02");
        p.put("auth_hold_days", 7);
        return p;
    }

    /** Registers a card; returns its context for further tweaking (controls, limits, status). */
    public synchronized ObjectNode card(UUID cardId, ObjectNode program, UUID ledgerAccount, BigDecimal balance) {
        ObjectNode ctx = json.createObjectNode();
        ObjectNode c = ctx.putObject("card");
        c.put("id", cardId.toString());
        c.put("status", "ACTIVE");
        c.put("card_program_id", program.get("id").asString());
        c.put("card_product_id", UUID.randomUUID().toString());
        c.put("customer_id", UUID.randomUUID().toString());
        c.put("ledger_account_id", ledgerAccount.toString());
        c.put("linked_account_type", "JIT".equals(program.get("funding_model").asString()) ? "JIT_PROGRAM" : "DEPOSIT_ACCOUNT");
        c.put("form_factor", "VIRTUAL");
        c.put("pan_token", "tok_" + cardId.toString().replace("-", ""));
        c.put("last4", "4242");
        c.put("expiry_month", 12);
        c.put("expiry_year", 2030);
        c.put("pin_set", true);
        ctx.set("program", program);
        ObjectNode product = ctx.putObject("product");
        product.put("id", UUID.randomUUID().toString());
        product.put("status", "ACTIVE");
        product.put("pin_policy", "REQUIRED");
        product.put("contactless_enabled", true);
        product.put("atm_enabled", true);
        product.put("ecom_enabled", true);
        product.put("international_enabled", true);
        ctx.putArray("controls");
        ctx.putArray("limits");
        contexts.put(cardId, ctx);
        if (balance != null) {
            balances.put(ledgerAccount, balance);
        }
        return ctx;
    }

    public ObjectNode control(ObjectNode ctx, String type, String... values) {
        ObjectNode k = ((ArrayNode) ctx.get("controls")).addObject();
        k.put("id", UUID.randomUUID().toString());
        k.put("control_type", type);
        k.put("channel", "ANY");
        ArrayNode v = k.putArray("values");
        for (String s : values) {
            v.add(s);
        }
        k.put("is_active", true);
        return k;
    }

    public ObjectNode limit(ObjectNode ctx, String period, String maxAmount, Integer maxCount) {
        ObjectNode l = ((ArrayNode) ctx.get("limits")).addObject();
        l.put("id", UUID.randomUUID().toString());
        l.put("period", period);
        l.put("channel", "ANY");
        if (maxAmount != null) {
            l.put("max_amount", maxAmount);
        }
        if (maxCount != null) {
            l.put("max_count", maxCount);
        }
        l.put("currency", "USD");
        l.put("is_active", true);
        return l;
    }
}
