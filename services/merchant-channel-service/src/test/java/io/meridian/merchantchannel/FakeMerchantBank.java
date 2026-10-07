package io.meridian.merchantchannel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful stand-in for merchant-service, wallet-service, deposit-service, identity-service and webhook-service behind
 * a mocked {@link ServiceClient}: collections of JSON rows per service and path, generic list (query parameters filter
 * on equal fields), get, create, patch (row_version checked when given), delete and the actions the channel uses.
 * Writes are idempotent per Idempotency-Key (a replay returns the first response, like the real services).
 * merchant-service refunds apply its four-eyes rule (approver != creator) and its cumulative cap; QR codes are paid by
 * {@link #payQr}. Every call is recorded with the principal it was made as.
 */
final class FakeMerchantBank {
    record Call(String service, String method, String path, JsonNode body, String key, String principal) {
        boolean writes() {
            return !method.equals("GET");
        }
    }

    static final String MS = "merchant-service";
    static final String WS = "wallet-service";
    private static final Set<String> NOT_FILTERS = Set.of("limit", "cursor", "from", "to");

    final JsonMapper json;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Map<String, Map<String, ObjectNode>> collections = new ConcurrentHashMap<>();
    final Map<String, JsonNode> replays = new ConcurrentHashMap<>();
    final Map<String, ApiException> failOnce = new ConcurrentHashMap<>();

    FakeMerchantBank(JsonMapper json) {
        this.json = json;
        for (String c : List.of("/v1/merchants", "/v1/merchant-stores", "/v1/merchant-terminals", "/v1/merchant-qr-codes",
                "/v1/merchant-payment-links", "/v1/merchant-settlements", "/v1/merchant-refunds",
                "/v1/merchant-fee-plans")) {
            rows(MS, c);
        }
        rows(WS, "/v1/wallet-transactions");
        rows("deposit-service", "/v1/deposit-accounts");
        rows("identity-service", "/v1/api-clients");
        rows("webhook-service", "/v1/webhook-endpoints");
        rows("webhook-service", "/v1/webhook-subscriptions");
        rows("webhook-service", "/v1/webhook-deliveries");
        rows("workflow-service", "/v1/approval-requests");
    }

    void wire(ServiceClient client) {
        when(client.get(anyString(), anyString())).thenAnswer(i -> handle(i.getArgument(0), "GET", i.getArgument(1), null, null));
        when(client.post(anyString(), anyString(), any(), any())).thenAnswer(
                i -> handle(i.getArgument(0), "POST", i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(client.patch(anyString(), anyString(), any(), any())).thenAnswer(
                i -> handle(i.getArgument(0), "PATCH", i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(client.send(anyString(), anyString(), anyString(), any(), any())).thenAnswer(
                i -> handle(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3), i.getArgument(4)));
        when(client.getBytes(anyString(), anyString())).thenAnswer(i -> bytes(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ data

    ObjectNode add(String service, String collection, String text) {
        ObjectNode row = (ObjectNode) json.readTree(text);
        if (!row.has("id")) {
            row.put("id", UUID.randomUUID().toString());
        }
        row.put("row_version", row.path("row_version").asInt(1));
        rows(service, collection).put(row.get("id").asString(), row);
        return row;
    }

    Map<String, ObjectNode> rows(String service, String collection) {
        return collections.computeIfAbsent(service + " " + collection,
                k -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    ObjectNode row(String service, String collection, String id) {
        return rows(service, collection).get(id);
    }

    List<Call> writes() {
        return calls.stream().filter(Call::writes).toList();
    }

    List<Call> writes(String service, String pathPrefix) {
        return calls.stream().filter(c -> c.writes() && c.service().equals(service) && c.path().startsWith(pathPrefix))
                .toList();
    }

    /** Refunds approved by a merchant user above this amount wait for the bank's checkers (null = never). */
    volatile BigDecimal refundBankReviewAbove;

    private void completeRefund(ObjectNode row, String approver) {
        ObjectNode original = row(WS, "/v1/wallet-transactions", row.path("original_transaction_id").asString());
        ObjectNode refund = json.createObjectNode();
        refund.put("id", UUID.randomUUID().toString()).put("txn_type", "REFUND").put("status", "COMPLETED")
                .put("merchant_id", row.path("merchant_id").asString()).put("amount", row.path("amount").asString())
                .put("currency", row.path("currency").asString())
                .put("original_transaction_id", original.path("id").asString());
        rows(WS, "/v1/wallet-transactions").put(refund.get("id").asString(), refund);
        row.put("status", "COMPLETED").put("approved_by", approver)
                .put("wallet_transaction_id", refund.get("id").asString());
    }

    /** A bank checker decides a refund waiting for the bank (what merchant-service does on the central decision). */
    void bankDecidesRefund(String refundId, boolean approve, String checker) {
        ObjectNode row = row(MS, "/v1/merchant-refunds", refundId);
        if (!"PENDING_BANK_APPROVAL".equals(row.path("status").asString())) {
            throw new IllegalStateException("refund is " + row.path("status").asString());
        }
        if (approve) {
            completeRefund(row, checker);
        } else {
            row.put("status", "REJECTED").put("approved_by", checker).put("rejection_reason", "Refund not justified");
        }
    }

    /** A customer pays a QR code: the QR is used (dynamic: deactivated) and a MERCHANT_PAYMENT is recorded. */
    ObjectNode payQr(String qrId) {
        ObjectNode qr = row(MS, "/v1/merchant-qr-codes", qrId);
        qr.put("scan_count", qr.path("scan_count").asInt() + 1).put("last_paid_at", OffsetDateTime.now().toString());
        if ("DYNAMIC".equals(qr.path("qr_type").asString())) {
            qr.put("is_active", false);
        }
        return payment(qr.path("merchant_id").asString(), qr.path("amount").asString(), qrId);
    }

    /** A completed MERCHANT_PAYMENT to the merchant (through the QR when given). */
    ObjectNode payment(String merchantId, String amount, String qrId) {
        ObjectNode t = json.createObjectNode();
        t.put("id", UUID.randomUUID().toString()).put("txn_type", "MERCHANT_PAYMENT").put("status", "COMPLETED")
                .put("merchant_id", merchantId).put("amount", amount).put("currency", "KES")
                .put("payer_wallet_id", UUID.randomUUID().toString()).put("reference", "WTX" + System.nanoTime())
                .put("created_at", OffsetDateTime.now().toString());
        if (qrId != null) {
            t.putObject("metadata").put("merchant_qr_id", qrId);
        }
        rows(WS, "/v1/wallet-transactions").put(t.get("id").asString(), t);
        return t;
    }

    // ------------------------------------------------------------------ routing

    private JsonNode handle(String service, String method, String fullPath, Object body, String key) {
        JsonNode b = body == null ? null : json.valueToTree(body);
        String principal = TenantContext.current().isPresent() ? TenantContext.principal() : null;
        calls.add(new Call(service, method, fullPath, b, key, principal));
        for (Map.Entry<String, ApiException> f : failOnce.entrySet()) {
            if ((method + " " + service + " " + fullPath).startsWith(f.getKey())) {
                failOnce.remove(f.getKey());
                throw f.getValue();
            }
        }
        String replayKey = key == null || method.equals("PATCH") ? null : service + " " + key;
        if (replayKey != null && !method.equals("GET")) {
            JsonNode prior = replays.get(replayKey);
            if (prior != null) {
                return prior;
            }
        }
        JsonNode result = route(service, method, fullPath, b, key);
        if (replayKey != null && !method.equals("GET")) {
            replays.put(replayKey, result);
        }
        return result;
    }

    private JsonNode route(String service, String method, String fullPath, JsonNode b, String key) {
        String path = fullPath;
        Map<String, String> query = new LinkedHashMap<>();
        int q = fullPath.indexOf('?');
        if (q >= 0) {
            path = fullPath.substring(0, q);
            for (String kv : fullPath.substring(q + 1).split("&")) {
                String[] p = kv.split("=", 2);
                query.put(p[0], p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            }
        }
        String collection = collectionOf(service, path);
        if (collection == null) {
            throw new IllegalStateException("fake has no collection for " + service + " " + path);
        }
        Map<String, ObjectNode> rows = rows(service, collection);
        String rest = path.substring(collection.length());
        String[] seg = rest.isEmpty() ? new String[0] : rest.substring(1).split("/");
        if (seg.length == 0) {
            return switch (method) {
                case "GET" -> list(rows, query);
                case "POST" -> create(service, collection, rows, (ObjectNode) b, key);
                default -> throw new IllegalStateException(method + " " + path);
            };
        }
        ObjectNode row = rows.get(seg[0]);
        if (row == null) {
            throw ApiException.notFound("row", seg[0]);
        }
        if (seg.length == 1) {
            return switch (method) {
                case "GET" -> row;
                case "PATCH" -> {
                    if (key != null && Integer.parseInt(key) != row.path("row_version").asInt()) {
                        throw new ApiException(412, "precondition_failed", "row_version mismatch");
                    }
                    b.properties().forEach(e -> row.set(e.getKey(), e.getValue()));
                    row.put("row_version", row.path("row_version").asInt() + 1);
                    yield row;
                }
                case "DELETE" -> {
                    rows.remove(seg[0]);
                    yield json.createObjectNode();
                }
                default -> throw new IllegalStateException(method + " " + path);
            };
        }
        return sub(service, collection, row, seg, method, b);
    }

    private String collectionOf(String service, String path) {
        String best = null;
        for (String k : collections.keySet()) {
            String svc = k.substring(0, k.indexOf(' '));
            String col = k.substring(k.indexOf(' ') + 1);
            if (svc.equals(service) && (path.equals(col) || path.startsWith(col + "/"))
                    && (best == null || col.length() > best.length())) {
                best = col;
            }
        }
        return best;
    }

    ObjectNode list(Map<String, ObjectNode> rows, Map<String, String> query) {
        List<ObjectNode> out = new ArrayList<>();
        synchronized (rows) {
            for (ObjectNode r : rows.values()) {
                boolean ok = true;
                for (Map.Entry<String, String> f : query.entrySet()) {
                    if (NOT_FILTERS.contains(f.getKey())) {
                        continue;
                    }
                    if (!f.getValue().equals(r.path(f.getKey()).asString(null))) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    out.add(r);
                }
            }
        }
        out.sort(Comparator.comparing((ObjectNode r) -> r.path("id").asString()).reversed());
        ObjectNode p = json.createObjectNode();
        ArrayNode data = p.putArray("data");
        out.forEach(data::add);
        p.putNull("next_cursor");
        p.put("has_more", false);
        return p;
    }

    private JsonNode create(String service, String collection, Map<String, ObjectNode> rows, ObjectNode body, String key) {
        ObjectNode row = body == null ? json.createObjectNode() : body.deepCopy();
        row.put("id", UUID.randomUUID().toString());
        row.put("row_version", 1);
        row.put("created_by", TenantContext.principal());
        switch (collection) {
            case "/v1/merchant-qr-codes" -> row.put("payload", "000201010212" + row.get("id").asString().replace("-", "")
                            + "5303404540" + row.path("amount").asString() + "6304ABCD")
                    .put("scan_count", 0).put("is_active", true)
                    .put("expires_at", OffsetDateTime.now().plusMinutes(15).toString());
            case "/v1/merchant-payment-links" -> row.put("link_code", "L" + Math.abs(row.get("id").asString().hashCode()))
                    .put("url", "https://pay.example/L").put("use_count", 0).put("is_active", true);
            case "/v1/merchant-refunds" -> {
                ObjectNode original = row(WS, "/v1/wallet-transactions", row.path("original_transaction_id").asString());
                BigDecimal already = BigDecimal.ZERO;
                for (ObjectNode r : rows.values()) {
                    if (r.path("original_transaction_id").asString().equals(row.path("original_transaction_id").asString())
                            && Set.of("REQUESTED", "PENDING_BANK_APPROVAL", "APPROVED", "COMPLETED")
                            .contains(r.path("status").asString())) {
                        already = already.add(new BigDecimal(r.path("amount").asString()));
                    }
                }
                if (already.add(new BigDecimal(row.path("amount").asString()))
                        .compareTo(new BigDecimal(original.path("amount").asString())) > 0) {
                    throw ApiException.unprocessable("refund_exceeds_amount", "Refunds would exceed the payment");
                }
                row.put("status", "REQUESTED").put("idempotency_key", key);
            }
            case "/v1/api-clients" -> row.put("status", "ACTIVE");
            case "/v1/webhook-endpoints" -> row.put("status", "ACTIVE");
            default -> {
                if (collection.endsWith("/keys")) {
                    row.put("key_prefix", "mk_test_1234").put("api_key", "mk_test_1234_secret_shown_once")
                            .put("status", "ACTIVE");
                }
            }
        }
        rows.put(row.get("id").asString(), row);
        return row;
    }

    /** Sub-resources and actions on one row. */
    private JsonNode sub(String service, String collection, ObjectNode row, String[] seg, String method, JsonNode body) {
        String id = row.get("id").asString();
        String name = seg[1];
        if (method.equals("GET")) {
            return switch (name) {
                case "stores" -> list(rows(MS, "/v1/merchant-stores"), Map.of("merchant_id", id));
                case "transactions" -> list(rows(WS, "/v1/wallet-transactions"), Map.of("merchant_id", id));
                case "keys" -> {
                    if (seg.length > 2) {
                        ObjectNode k = rows(service, collection + "/" + id + "/keys").get(seg[2]);
                        if (k == null) {
                            throw ApiException.notFound("key", seg[2]);
                        }
                        yield k;
                    }
                    yield list(rows(service, collection + "/" + id + "/keys"), Map.of());
                }
                default -> throw new IllegalStateException("GET sub-resource " + name);
            };
        }
        switch (collection + " " + name) {
            case "/v1/merchant-terminals deactivate" -> row.put("status", "INACTIVE")
                    .put("status_reason", body.path("reason").asString(null));
            case "/v1/merchant-qr-codes deactivate", "/v1/merchant-payment-links deactivate" -> row.put("is_active", false);
            case "/v1/merchant-refunds approve" -> {
                String who = TenantContext.principal();
                boolean merchantUser = who.startsWith("merchant-user:");
                if (merchantUser && "PENDING_BANK_APPROVAL".equals(row.path("status").asString())) {
                    throw ApiException.conflict("awaiting_bank_approval", "The refund waits for the bank's checkers");
                }
                if (who.equals(row.path("created_by").asString())) {
                    throw ApiException.unprocessable("four_eyes_violation", "A refund must be approved by another user");
                }
                if (!"REQUESTED".equals(row.path("status").asString())) {
                    throw ApiException.conflict("invalid_transition", "The refund is " + row.path("status").asString());
                }
                BigDecimal reviewAbove = refundBankReviewAbove;
                if (merchantUser && reviewAbove != null && new BigDecimal(row.path("amount").asString()).compareTo(reviewAbove) > 0) {
                    // like merchant-service: the merchant's approval hands it to the bank's checkers
                    row.put("status", "PENDING_BANK_APPROVAL").put("merchant_approved_by", who)
                            .put("bank_review_reason", "AMOUNT_THRESHOLD");
                    break;
                }
                completeRefund(row, who);
            }
            case "/v1/api-clients suspend" -> row.put("status", "SUSPENDED");
            case "/v1/api-clients keys" -> {
                if (seg.length > 3 && seg[3].equals("revoke")) {
                    ObjectNode k = rows(service, collection + "/" + id + "/keys").get(seg[2]);
                    k.put("status", "REVOKED");
                    return k;
                }
                ObjectNode k = ((ObjectNode) body).deepCopy();
                k.put("api_client_id", id);
                return create(service, collection + "/" + id + "/keys", rows(service, collection + "/" + id + "/keys"), k,
                        null);
            }
            case "/v1/webhook-endpoints disable" -> row.put("status", "DISABLED");
            case "/v1/webhook-endpoints enable" -> row.put("status", "ACTIVE");
            default -> throw new IllegalStateException("action " + collection + " " + name);
        }
        return row;
    }

    private ServiceClient.Download bytes(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null, TenantContext.principal()));
        String[] seg = path.split("/");
        String id = seg[seg.length - 2];
        if (row(MS, "/v1/merchant-settlements", id) == null) {
            throw ApiException.notFound("row", id);
        }
        return new ServiceClient.Download(("settlement_id,payment_id,amount\n" + id + ",p1,100.00\n")
                .getBytes(StandardCharsets.UTF_8), "text/csv", null);
    }
}
