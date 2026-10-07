package io.meridian.businesschannel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * Stateful stand-in for the domain services behind a mocked {@link ServiceClient}: collections of JSON rows per
 * service and path, generic list (query parameters filter on equal fields), get, create, patch, delete and the
 * actions the channel uses. Writes are idempotent per Idempotency-Key (a replay returns the first response, like the
 * real services). payment-service approvals apply four-eyes against the acting principal. Every call is recorded with
 * the principal it was made as, so tests can assert what reached (or never reached) a service and as whom.
 */
final class FakeBank {
    record Call(String service, String method, String path, JsonNode body, String key, String principal) {
        boolean writes() {
            return !method.equals("GET");
        }
    }

    private static final Set<String> NOT_FILTERS = Set.of("limit", "cursor", "from", "to", "created_after",
            "created_before");

    final JsonMapper json;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Map<String, Map<String, ObjectNode>> collections = new ConcurrentHashMap<>();
    /** Downstream responses already given per Idempotency-Key ("service key"). */
    final Map<String, JsonNode> replays = new ConcurrentHashMap<>();
    /** One-shot failures: "METHOD service path-prefix" -> exception thrown once. */
    final Map<String, ApiException> failOnce = new ConcurrentHashMap<>();
    /** Status of a payment created in payment-service. */
    volatile String paymentStatusOnCreate = "SUBMITTED";
    /** payment-service approvals needed (when created PENDING_APPROVAL). */
    volatile int bankApprovals = 1;

    FakeBank(JsonMapper json) {
        this.json = json;
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
                k -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
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

    Call lastWrite(String service, String pathPrefix) {
        List<Call> w = writes(service, pathPrefix);
        if (w.isEmpty()) {
            throw new AssertionError("no write to " + service + " " + pathPrefix + "; calls: " + calls);
        }
        return w.get(w.size() - 1);
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
        if (key != null && !method.equals("GET")) {
            JsonNode prior = replays.get(service + " " + key);
            if (prior != null) {
                return prior;
            }
        }
        JsonNode result = route(service, method, fullPath, b);
        if (key != null && !method.equals("GET")) {
            replays.put(service + " " + key, result);
        }
        return result;
    }

    private JsonNode route(String service, String method, String fullPath, JsonNode b) {
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
        if (service.equals("payment-service") && path.equals("/v1/bulk-payments/upload")) {
            ObjectNode bulk = ((ObjectNode) b).deepCopy();
            bulk.put("status", "VALIDATED").put("failed_count", 0);
            return create(rows(service, "/v1/bulk-payments"), bulk);
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
                case "POST" -> create(rows, (ObjectNode) b, service, collection);
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
                    b.properties().forEach(e -> row.set(e.getKey(), e.getValue()));
                    row.put("row_version", row.path("row_version").asInt() + 1);
                    yield row;
                }
                case "DELETE" -> {
                    row.put("status", "DELETED");
                    yield json.createObjectNode();
                }
                default -> throw new IllegalStateException(method + " " + path);
            };
        }
        return sub(service, collection, row, seg[1], method, b);
    }

    private String collectionOf(String service, String path) {
        String best = null;
        for (String key : collections.keySet()) {
            String svc = key.substring(0, key.indexOf(' '));
            String col = key.substring(key.indexOf(' ') + 1);
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
        return page(out);
    }

    private ObjectNode page(List<ObjectNode> rows) {
        ObjectNode p = json.createObjectNode();
        ArrayNode data = p.putArray("data");
        rows.forEach(data::add);
        p.putNull("next_cursor");
        p.put("has_more", false);
        return p;
    }

    private JsonNode create(Map<String, ObjectNode> rows, ObjectNode body) {
        ObjectNode row = body == null ? json.createObjectNode() : body.deepCopy();
        row.put("id", UUID.randomUUID().toString());
        row.put("row_version", 1);
        if (!row.has("status")) {
            row.put("status", "ACTIVE");
        }
        rows.put(row.get("id").asString(), row);
        return row;
    }

    private JsonNode create(Map<String, ObjectNode> rows, ObjectNode body, String service, String collection) {
        ObjectNode row = body == null ? json.createObjectNode() : body.deepCopy();
        switch (collection) {
            case "/v1/payments" -> {
                row.put("status", paymentStatusOnCreate);
                row.put("initiated_by", TenantContext.principal());
                row.put("bank_approvals_needed", bankApprovals);
                row.putArray("bank_approved_by");
            }
            case "/v1/payment-mandates" -> row.put("status", "ACTIVE");
            case "/v1/direct-debit-collections" -> row.put("status", "SCHEDULED");
            case "/v1/statements" -> row.put("status", "PENDING");
            case "/v1/fx/quotes" -> {
                row.put("status", "ACTIVE").put("customer_rate", "0.0075")
                        .put("expires_at", java.time.OffsetDateTime.now().plusMinutes(10).toString());
                if (!row.hasNonNull("buy_amount")) {
                    row.put("buy_amount", new java.math.BigDecimal(row.path("sell_amount").asString())
                            .multiply(new java.math.BigDecimal("0.0075")).setScale(2, java.math.RoundingMode.HALF_EVEN)
                            .toPlainString());
                }
                if (!row.hasNonNull("sell_amount")) {
                    row.put("sell_amount", new java.math.BigDecimal(row.path("buy_amount").asString())
                            .divide(new java.math.BigDecimal("0.0075"), 2, java.math.RoundingMode.HALF_EVEN).toPlainString());
                }
            }
            case "/v1/cards" -> row.put("status", "ACTIVE").put("last4", "4242");
            default -> {
                if (collection.endsWith("/keys")) {
                    row.put("key_prefix", "mk_test_1234").put("api_key", "mk_test_1234_secret_shown_once");
                }
            }
        }
        return create(rows, row);
    }

    /** Sub-resources and actions on one row. */
    private JsonNode sub(String service, String collection, ObjectNode row, String name, String method, JsonNode body) {
        String id = row.get("id").asString();
        if (method.equals("GET")) {
            return switch (name) {
                case "balance" -> json.readTree("{\"ledger_account_id\":\"" + UUID.randomUUID() + "\",\"currency\":\""
                        + row.path("currency").asString("KES") + "\",\"available_balance\":\""
                        + row.path("test_available").asString("1000.00") + "\",\"posted_balance\":\""
                        + row.path("test_posted").asString("1200.00") + "\"}");
                case "transactions" -> list(rows(service, collection + "/" + id + "/transactions"), Map.of());
                case "schedule" -> page(List.of((ObjectNode) json.readTree("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"installment_number\":1,\"amount\":\"10.00\"}")));
                case "controls" -> list(rows(service, "/v1/card-controls"), Map.of("card_id", id));
                case "limits" -> list(rows(service, "/v1/card-spend-limits"), Map.of("card_id", id));
                case "authorizations" -> page(List.of((ObjectNode) json.readTree("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"card_id\":\"" + id + "\",\"amount\":\"12.00\",\"currency\":\"KES\"}")));
                case "collections" -> list(rows(service, "/v1/direct-debit-collections"), Map.of("mandate_id", id));
                case "payments" -> list(rows(service, "/v1/payments"), Map.of("payment_link_id", id));
                case "drawdowns" -> list(rows(service, "/v1/credit-line-drawdowns"), Map.of("credit_line_id", id));
                case "keys" -> list(rows(service, collection + "/" + id + "/keys"), Map.of());
                case "approvals" -> page(List.of());
                default -> throw new IllegalStateException("GET sub-resource " + name);
            };
        }
        switch (name) {
            case "freeze" -> row.put("status", "FROZEN");
            case "unfreeze", "enable", "reactivate" -> row.put("status", "ACTIVE");
            case "close" -> row.put("status", "CLOSED");
            case "cancel" -> row.put("status", "CANCELLED");
            case "disable" -> row.put("status", "DISABLED");
            case "suspend" -> row.put("status", "SUSPENDED");
            case "revoke" -> row.put("status", "REVOKED");
            case "submit" -> {
                row.put("status", "PENDING_APPROVAL").put("submitted_by", TenantContext.principal());
                row.put("bank_approvals_needed", bankApprovals);
                row.putArray("bank_approved_by");
            }
            case "approve" -> {
                String who = TenantContext.principal();
                String maker = row.has("submitted_by") ? row.path("submitted_by").asString() : row.path("initiated_by").asString();
                if (who.equals(maker)) {
                    throw ApiException.unprocessable("four_eyes_violation", "maker cannot approve");
                }
                ArrayNode by = (ArrayNode) row.get("bank_approved_by");
                for (JsonNode x : by) {
                    if (x.asString().equals(who)) {
                        throw ApiException.unprocessable("already_approved", "approved already");
                    }
                }
                by.add(who);
                if (by.size() >= row.path("bank_approvals_needed").asInt(1)) {
                    row.put("status", "APPROVED");
                }
            }
            case "keys" -> {
                ObjectNode k = ((ObjectNode) body).deepCopy();
                k.put("api_client_id", id);
                return create(rows(service, collection + "/" + id + "/keys"), k, service, collection + "/" + id + "/keys");
            }
            case "repay" -> {
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"amount\":\"" + body.path("amount").asString()
                        + "\",\"source_account_id\":\"" + body.path("source_account_id").asString() + "\"}");
            }
            case "draw" -> {
                ObjectNode d = ((ObjectNode) body).deepCopy();
                d.put("credit_line_id", id).put("status", "POSTED");
                return create(rows(service, "/v1/credit-line-drawdowns"), d);
            }
            default -> throw new IllegalStateException("action " + name);
        }
        return row;
    }

    private ServiceClient.Download bytes(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null, TenantContext.principal()));
        String[] seg = path.split("/");
        String id = seg[seg.length - 2];
        String collection = collectionOf(service, path.substring(0, path.lastIndexOf('/')));
        if (collection == null || rows(service, collection).get(id) == null) {
            throw ApiException.notFound("row", id);
        }
        if (path.endsWith("/report")) {
            return new ServiceClient.Download(("line_no,status\n1,SETTLED\n").getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
        }
        return new ServiceClient.Download(("%PDF-1.4 " + id).getBytes(StandardCharsets.US_ASCII), "application/pdf",
                "attachment; filename=\"" + id + ".pdf\"");
    }
}
