package io.meridian.agentchannel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * Stateful stand-in for the domain services behind a mocked {@link ServiceClient}: JSON rows per service and
 * collection; generic list (query parameters filter on equal fields; created_after honoured), get, create and the
 * actions the agent channel uses. POSTs are idempotent per (service, Idempotency-Key) like the real services: a replay
 * returns the stored response and creates nothing; created rows record the key in {@code idempotency_key}. Every call
 * is recorded so tests can assert what reached (or never reached) a service.
 */
final class FakeNetwork {
    record Call(String service, String method, String path, JsonNode body, String key) {
        boolean writes() {
            return !method.equals("GET");
        }
    }

    private static final Set<String> NOT_FILTERS = Set.of("limit", "cursor", "from", "to", "created_after",
            "created_before", "currency");

    final JsonMapper json;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Map<String, Map<String, ObjectNode>> collections = new ConcurrentHashMap<>();
    /** "service key" -> stored response of an idempotent POST. */
    final Map<String, JsonNode> replays = new ConcurrentHashMap<>();

    FakeNetwork(JsonMapper json) {
        this.json = json;
    }

    void wire(ServiceClient client) {
        when(client.get(anyString(), anyString())).thenAnswer(i -> handle(i.getArgument(0), "GET", i.getArgument(1), null, null));
        when(client.post(anyString(), anyString(), any(), any())).thenAnswer(
                i -> handle(i.getArgument(0), "POST", i.getArgument(1), i.getArgument(2), i.getArgument(3)));
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
        if (!row.has("created_at")) {
            row.put("created_at", Instant.now().toString());
        }
        rows(service, collection).put(row.get("id").asString(), row);
        return row;
    }

    Map<String, ObjectNode> rows(String service, String collection) {
        return collections.computeIfAbsent(service + " " + collection, k -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    ObjectNode row(String service, String collection, String id) {
        return rows(service, collection).get(id);
    }

    List<Call> writes() {
        return calls.stream().filter(Call::writes).toList();
    }

    List<Call> writesTo(String service, String pathPrefix) {
        return calls.stream().filter(c -> c.writes() && c.service().equals(service) && c.path().startsWith(pathPrefix))
                .toList();
    }

    Call lastWrite(String service, String pathPrefix) {
        List<Call> w = writesTo(service, pathPrefix);
        if (w.isEmpty()) {
            throw new AssertionError("no write to " + service + " " + pathPrefix + "; calls: " + calls);
        }
        return w.get(w.size() - 1);
    }

    // ------------------------------------------------------------------ routing

    private JsonNode handle(String service, String method, String fullPath, Object body, String key) {
        JsonNode b = body == null ? null : json.valueToTree(body);
        calls.add(new Call(service, method, fullPath, b, key));
        if (method.equals("POST") && key != null && replays.containsKey(service + " " + key)) {
            return replays.get(service + " " + key);
        }
        JsonNode out = route(service, method, fullPath, b, key);
        if (method.equals("POST") && key != null) {
            replays.put(service + " " + key, out);
        }
        return out;
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
        // computed endpoints
        switch (path) {
            case "/v1/wallets/lookup" -> {
                for (ObjectNode w : rows("wallet-service", "/v1/wallets").values()) {
                    if ((query.containsKey("msisdn") && query.get("msisdn").equals(w.path("msisdn").asString(null)))
                            || (query.containsKey("wallet_number")
                            && query.get("wallet_number").equals(w.path("wallet_number").asString(null)))) {
                        return w;
                    }
                }
                throw ApiException.notFound("wallet", query);
            }
            case "/v1/wallet-transactions/quote" -> {
                return json.readTree("{\"txn_type\":\"" + b.path("txn_type").asString() + "\",\"amount\":\""
                        + b.path("amount").asString() + "\",\"currency\":\"" + b.path("currency").asString()
                        + "\",\"fee_amount\":\"1.00\",\"tax_amount\":\"0.00\",\"limits_ok\":true,\"echo\":"
                        + json.writeValueAsString(b) + "}");
            }
            case "/v1/wallet-transactions/cash-in", "/v1/wallet-transactions/cash-out" -> {
                boolean in = path.endsWith("cash-in");
                ObjectNode agentWallet = row("wallet-service", "/v1/wallets", b.path("agent_wallet_id").asString());
                ObjectNode t = json.createObjectNode();
                t.put("reference", "WTX" + System.nanoTime());
                t.put("txn_type", in ? "CASH_IN" : "CASH_OUT");
                t.put("status", in ? "COMPLETED" : "PENDING_CONFIRMATION");
                t.put("channel", b.path("channel").asString());
                t.put("agent_id", agentWallet == null ? null : agentWallet.path("agent_id").asString(null));
                t.put("agent_outlet_id", b.path("agent_outlet_id").asString(null));
                if (in) {
                    t.put("payer_wallet_id", b.path("agent_wallet_id").asString());
                    t.put("payee_msisdn", b.path("payee_msisdn").asString());
                } else {
                    t.put("payer_wallet_id", b.path("payer_wallet_id").asString());
                    t.put("payee_wallet_id", b.path("agent_wallet_id").asString());
                }
                t.put("amount", b.path("amount").asString());
                t.put("currency", b.path("currency").asString());
                t.put("initiator_type", "AGENT");
                return create(rows("wallet-service", "/v1/wallet-transactions"), t, key);
            }
            case "/v1/commission-earnings/summary" -> {
                return json.readTree("{\"groups\":[{\"currency\":\"KES\",\"earned\":\"10.00\",\"unpaid\":\"4.00\","
                        + "\"beneficiary_agent_id\":\"" + query.get("beneficiary_agent_id") + "\"}]}");
            }
            default -> { }
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
                case "POST" -> create(rows, (ObjectNode) b, key);
                default -> throw new IllegalStateException(method + " " + path);
            };
        }
        ObjectNode row = rows.get(seg[0]);
        if (row == null) {
            throw ApiException.notFound("row", seg[0]);
        }
        if (seg.length == 1) {
            if (method.equals("GET")) {
                return row;
            }
            throw new IllegalStateException(method + " " + path);
        }
        return sub(service, row, seg, method, b, query, key);
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
                if (ok && query.containsKey("created_after") && r.has("created_at")
                        && Instant.parse(r.get("created_at").asString()).isBefore(
                        java.time.OffsetDateTime.parse(query.get("created_after")).toInstant())) {
                    ok = false;
                }
                if (ok) {
                    out.add(r);
                }
            }
        }
        out.sort(Comparator.comparing((ObjectNode r) -> r.path("id").asString()).reversed());
        return page(out);
    }

    ObjectNode page(List<ObjectNode> rows) {
        ObjectNode p = json.createObjectNode();
        ArrayNode data = p.putArray("data");
        rows.forEach(data::add);
        p.putNull("next_cursor");
        p.put("has_more", false);
        return p;
    }

    private JsonNode create(Map<String, ObjectNode> rows, ObjectNode body, String key) {
        ObjectNode row = body == null ? json.createObjectNode() : body.deepCopy();
        row.put("id", UUID.randomUUID().toString());
        row.put("row_version", 1);
        row.put("created_at", Instant.now().toString());
        if (key != null) {
            row.put("idempotency_key", key);
        }
        if (!row.has("status")) {
            row.put("status", initialStatus(rows));
        }
        rows.put(row.get("id").asString(), row);
        return row;
    }

    private String initialStatus(Map<String, ObjectNode> rows) {
        String col = collections.entrySet().stream().filter(e -> e.getValue() == rows).map(Map.Entry::getKey)
                .findFirst().orElse("");
        if (col.endsWith("/v1/agent-float-requests") || col.endsWith("/v1/cash-management-requests")) {
            return "REQUESTED";
        }
        if (col.endsWith("/v1/agent-tills")) {
            return "CLOSED";
        }
        if (col.endsWith("/v1/onboarding/applications")) {
            return "STARTED";
        }
        if (col.endsWith("/v1/onboarding/offline-batches")) {
            return "APPLIED";
        }
        if (col.endsWith("/v1/documents")) {
            return "UPLOADING";
        }
        if (col.endsWith("/v1/bill-payments") || col.endsWith("/v1/airtime-topups")) {
            return "COMPLETED";
        }
        return "ACTIVE";
    }

    private JsonNode sub(String service, ObjectNode row, String[] seg, String method, JsonNode body,
                         Map<String, String> query, String key) {
        String id = row.get("id").asString();
        String name = seg[1];
        if (method.equals("GET")) {
            return switch (name) {
                case "balance" -> json.readTree("{\"ledger_account_id\":\"" + UUID.randomUUID() + "\",\"currency\":\"KES\","
                        + "\"posted_balance\":\"1000.00\",\"available_balance\":\"900.00\"}");
                case "transactions" -> {
                    List<ObjectNode> out = new ArrayList<>();
                    for (ObjectNode t : rows("wallet-service", "/v1/wallet-transactions").values()) {
                        if (id.equals(t.path("payer_wallet_id").asString(null))
                                || id.equals(t.path("payee_wallet_id").asString(null))) {
                            out.add(t);
                        }
                    }
                    out.sort(Comparator.comparing((ObjectNode r) -> r.path("id").asString()).reversed());
                    yield page(out);
                }
                case "float" -> list(rows(service, "/v1/agent-floats"), Map.of("agent_id", id));
                case "performance" -> list(rows(service, "/v1/agent-performance"), Map.of("agent_id", id));
                case "sub-agents" -> list(rows(service, "/v1/agents"), Map.of("parent_agent_id", id));
                case "products" -> list(rows(service, "/v1/biller-products"), Map.of("biller_id", id));
                case "steps" -> list(rows(service, "/v1/onboarding/steps"), Map.of("onboarding_application_id", id));
                case "next-step" -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"onboarding_application_id\":\""
                        + id + "\",\"step_code\":\"PERSONAL\",\"step_type\":\"PERSONAL_DETAILS\",\"sequence\":1,\"status\":\"PENDING\","
                        + "\"attempts\":0}");
                default -> throw new IllegalStateException("GET sub-resource " + name);
            };
        }
        switch (name) {
            case "cancel" -> row.put("status", "CANCELLED");
            case "open" -> {
                row.put("status", "OPEN");
                row.put("operator_user_id", body.path("operator_user_id").asString());
                row.put("opening_cash", body.path("opening_cash").asString());
            }
            case "close" -> {
                row.put("status", "CLOSED");
                row.put("declared_cash", body.path("declared_cash").asString());
            }
            case "confirm" -> row.put("status", "COMPLETED");
            case "submit" -> row.put("status", "SUBMITTED");
            case "report-lost" -> row.put("status", "LOST");
            case "heartbeat" -> row.put("last_seen_at", Instant.now().toString());
            case "steps" -> {
                ObjectNode step = json.createObjectNode();
                step.put("onboarding_application_id", id);
                step.put("step_code", seg[2]);
                step.put("step_type", "PERSONAL_DETAILS");
                step.put("sequence", 1);
                step.put("status", "COMPLETED");
                step.put("attempts", 1);
                step.set("input", body.get("input"));
                return create(rows(service, "/v1/onboarding/steps"), step, null);
            }
            case "validate-reference" -> {
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"biller_id\":\"" + id
                        + "\",\"account_reference\":\"" + body.path("account_reference").asString()
                        + "\",\"response_code\":\"00\"}");
            }
            case "upload-url" -> {
                return json.readTree("{\"method\":\"PUT\",\"url\":\"http://s3.local/upload/" + id + "\"}");
            }
            case "complete-upload" -> row.put("status", "SCANNING");
            default -> throw new IllegalStateException("action " + name);
        }
        row.put("row_version", row.path("row_version").asInt() + 1);
        return row.deepCopy();
    }

    private ServiceClient.Download bytes(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        String[] seg = path.split("/");
        String id = seg[seg.length - 2];
        String collection = collectionOf(service, path.substring(0, path.lastIndexOf('/')));
        if (collection == null || rows(service, collection).get(id) == null) {
            throw ApiException.notFound("row", id);
        }
        return new ServiceClient.Download(("RECEIPT " + id).getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=UTF-8", null);
    }
}
