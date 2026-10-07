package io.meridian.customerchannel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * actions the channel uses. Every call is recorded so tests can assert what reached (or never reached) a service.
 */
final class FakeBank {
    record Call(String service, String method, String path, JsonNode body, String key) {
        boolean writes() {
            return !method.equals("GET");
        }
    }

    private static final java.util.Set<String> NOT_FILTERS = java.util.Set.of("limit", "cursor", "include_internal",
            "from", "to", "created_after", "created_before");

    final JsonMapper json;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    /** "service path" of a collection -> rows by id (insertion ordered). */
    final Map<String, Map<String, ObjectNode>> collections = new ConcurrentHashMap<>();

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

    ObjectNode add(String service, String collection, String json) {
        ObjectNode row = (ObjectNode) this.json.readTree(json);
        if (!row.has("id")) {
            row.put("id", UUID.randomUUID().toString());
        }
        row.put("row_version", row.path("row_version").asInt(1));
        rows(service, collection).put(row.get("id").asString(), row);
        return row;
    }

    Map<String, ObjectNode> rows(String service, String collection) {
        return collections.computeIfAbsent(service + " " + collection, k -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    ObjectNode row(String service, String collection, String id) {
        return rows(service, collection).get(id);
    }

    List<Call> writes() {
        return calls.stream().filter(Call::writes).toList();
    }

    Call lastWrite(String service, String pathPrefix) {
        List<Call> w = calls.stream().filter(c -> c.writes() && c.service().equals(service)
                && c.path().startsWith(pathPrefix)).toList();
        if (w.isEmpty()) {
            throw new AssertionError("no write to " + service + " " + pathPrefix + "; calls: " + calls);
        }
        return w.get(w.size() - 1);
    }

    // ------------------------------------------------------------------ routing

    private JsonNode handle(String service, String method, String fullPath, Object body, String key) {
        JsonNode b = body == null ? null : json.valueToTree(body);
        calls.add(new Call(service, method, fullPath, b, key));
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
        // special computed endpoints
        if (path.equals("/v1/insights/spending/by-category")) {
            return json.readTree("{\"customer_id\":\"" + query.get("customer_id") + "\",\"from\":\"" + query.get("from")
                    + "\",\"to\":\"" + query.get("to") + "\",\"categories\":[{\"category_name\":\"Groceries\","
                    + "\"spend\":\"120.00\",\"income\":\"0\",\"is_income\":false,\"currency\":\"KES\"}]}");
        }
        if (path.equals("/v1/inbox/announcements/active")) {
            return page(List.of((ObjectNode) json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"title\":\"Hello\"}")));
        }
        if (path.equals("/v1/payments/validate")) {
            return json.readTree("{\"valid\":true,\"fee\":{\"amount\":\"0.00\",\"currency\":\"KES\"},\"echo\":"
                    + json.writeValueAsString(b) + "}");
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
                case "POST" -> create(rows, (ObjectNode) b);
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
        return sub(service, row, seg[1], method, b);
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

    private JsonNode list(Map<String, ObjectNode> rows, Map<String, String> query) {
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
            row.put("status", initialStatus(rows));
        }
        rows.put(row.get("id").asString(), row);
        return row;
    }

    /** Status a created row starts in (valid for the collection's model). */
    private String initialStatus(Map<String, ObjectNode> rows) {
        String col = collections.entrySet().stream().filter(e -> e.getValue() == rows).map(Map.Entry::getKey)
                .findFirst().orElse("");
        if (col.endsWith("/v1/payments")) {
            return "INITIATED";
        }
        if (col.endsWith("/v1/inbox/threads")) {
            return "OPEN";
        }
        if (col.endsWith("/v1/documents")) {
            return "UPLOADING";
        }
        if (col.endsWith("/v1/kyc/documents")) {
            return "UPLOADED";
        }
        if (col.endsWith("/v1/statements")) {
            return "PENDING";
        }
        return "ACTIVE";
    }

    /** Sub-resources and actions on one row. */
    private JsonNode sub(String service, ObjectNode row, String name, String method, JsonNode body) {
        String id = row.get("id").asString();
        if (method.equals("GET")) {
            return switch (name) {
                case "balance" -> json.readTree("{\"account_id\":\"" + id + "\",\"currency\":\"KES\","
                        + "\"available\":\"1000.00\",\"ledger\":\"1000.00\"}");
                case "transactions", "entries", "schedule" -> page(List.of(
                        (ObjectNode) json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"amount\":\"10.00\"}")));
                case "repayments" -> page(List.of((ObjectNode) json.readTree(
                        "{\"id\":\"" + UUID.randomUUID() + "\",\"loan_id\":\"" + id + "\",\"amount\":\"50.00\"}")));
                case "current-period" -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"budget_id\":\"" + id
                        + "\",\"spent\":\"10.00\"}");
                case "controls" -> list(rows(service, "/v1/card-controls"), Map.of("card_id", id));
                case "limits" -> list(rows(service, "/v1/card-spend-limits"), Map.of("card_id", id));
                case "messages" -> list(rows(service, "/v1/inbox/messages"), Map.of("secure_message_thread_id", id));
                default -> throw new IllegalStateException("GET sub-resource " + name);
            };
        }
        switch (name) {
            case "freeze" -> row.put("status", "FROZEN");
            case "unfreeze", "resume" -> row.put("status", "ACTIVE");
            case "block" -> row.put("status", body.path("status").asString());
            case "pause" -> row.put("status", "PAUSED");
            case "cancel" -> row.put("status", "CANCELLED");
            case "archive" -> row.put("status", "ARCHIVED");
            case "dismiss" -> row.put("delivery_status", "DISMISSED");
            case "trust" -> row.put("trusted", true);
            case "mark-read" -> row.put("status", "READ");
            case "revoke" -> row.put("status", "REVOKED");
            case "complete-upload" -> row.put("status", "SCANNING");
            case "read" -> row.put("customer_unread_count", 0);
            case "replace" -> {
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"old_card_id\":\"" + id
                        + "\",\"new_card_id\":\"" + UUID.randomUUID() + "\",\"reason\":\"" + body.path("reason").asString() + "\"}");
            }
            case "verify" -> {
                if (row.has("contact_type")) {
                    row.put("is_verified", true);
                } else {
                    return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"payee_id\":\"" + id
                            + "\",\"result\":\"MATCH\"}");
                }
            }
            case "send-verification" -> {
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"customer_contact_id\":\"" + id + "\"}");
            }
            case "repay" -> {
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"loan_id\":\"" + id + "\",\"amount\":\""
                        + body.path("amount").asString() + "\"}");
            }
            case "upload-url" -> {
                return json.readTree("{\"method\":\"PUT\",\"url\":\"http://s3.local/upload/" + id + "\"}");
            }
            case "messages" -> {
                ObjectNode m = ((ObjectNode) body).deepCopy();
                m.put("secure_message_thread_id", id);
                return create(rows(service, "/v1/inbox/messages"), m);
            }
            default -> throw new IllegalStateException("action " + name);
        }
        return row;
    }

    private ServiceClient.Download bytes(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        String[] seg = path.split("/");
        String id = seg[seg.length - 2];
        String collection = collectionOf(service, path.substring(0, path.lastIndexOf('/')));
        if (collection == null || rows(service, collection).get(id) == null) {
            throw ApiException.notFound("row", id);
        }
        if (path.endsWith("/confirmation-letter")) {
            return new ServiceClient.Download(("Confirmation for " + id).getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=UTF-8", null);
        }
        return new ServiceClient.Download(("%PDF-1.4 " + id).getBytes(StandardCharsets.US_ASCII), "application/pdf",
                "attachment; filename=\"" + id + ".pdf\"");
    }
}
