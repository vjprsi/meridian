package io.meridian.tenant;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stateful stand-in for the platform services behind the mocked {@code ServiceClient}: per-tenant collections with
 * create / get / list (query filters) / PATCH (If-Match), Idempotency-Key replay (same key + same body = stored
 * response, different body = 422), the maker-checker actions the starter packs use (submit / publish open approval
 * requests in a fake workflow-service, publish after approval, wallet activation, the EOD pack install) and failure
 * injection. Every call records the tenant and principal bound in {@link TenantContext}.
 */
final class FakePlatform {
    static final Pattern UUID_RE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final Map<String, String> INITIAL_STATUS = Map.ofEntries(
            Map.entry("/v1/config/entries", "DRAFT"), Map.entry("/v1/product-versions", "DRAFT"),
            Map.entry("/v1/products", "DRAFT"), Map.entry("/v1/commission-plans", "DRAFT"),
            Map.entry("/v1/notification-templates", "DRAFT"), Map.entry("/v1/risk/limits/rules", "DRAFT"),
            Map.entry("/v1/wallets", "PENDING"), Map.entry("/v1/onboarding/journeys", "DRAFT"),
            // WE-LIMIT-1: the generic pack seeds transfer control profiles that need checker activation
            Map.entry("/v1/risk/limits/tcp/cumulative", "DRAFT"), Map.entry("/v1/risk/limits/tcp/instrument", "DRAFT"));
    static final Map<String, String> APPROVAL_TYPE = Map.of("/v1/config/entries", "config_entry",
            "/v1/product-versions", "product_version", "/v1/commission-plans", "commission_plan",
            "/v1/notification-templates", "notification_template");
    static final Map<String, String> APPROVED_STATUS = Map.of("config_entry", "ACTIVE", "product_version", "APPROVED",
            "commission_plan", "ACTIVE", "notification_template", "ACTIVE");
    static final String APPROVALS = "/v1/approval-requests";

    record Call(UUID tenant, String principal, String service, String method, String path, JsonNode body, String key,
                boolean replay) {}

    private record Failure(String method, Predicate<String> path, int[] remaining, boolean afterSuccess) {}

    private final JsonMapper json;
    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    /** tenant -> collection path -> id -> row. */
    private final Map<UUID, Map<String, Map<String, ObjectNode>>> data = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> idempotent = new ConcurrentHashMap<>();
    private final List<Failure> failures = Collections.synchronizedList(new ArrayList<>());

    FakePlatform(JsonMapper json) {
        this.json = json;
    }

    // ------------------------------------------------------------------ test controls

    /** The {@code nth} matching call fails with 502; {@code afterSuccess}: the change is made, only the response is lost. */
    void failOn(String method, Predicate<String> path, int nth, boolean afterSuccess) {
        failures.add(new Failure(method, path, new int[] {nth}, afterSuccess));
    }

    ObjectNode seed(UUID tenant, String collection, String body) {
        ObjectNode row = (ObjectNode) json.readTree(body);
        row.put("id", UUID.randomUUID().toString());
        row.put("row_version", 1);
        rows(tenant, collection).put(row.get("id").asString(), row);
        return row;
    }

    /** A checker approves every pending request of the tenant; the resource moves as the owning service would. */
    synchronized int approveAll(UUID tenant, String checker) {
        int n = 0;
        for (ObjectNode req : rows(tenant, APPROVALS).values()) {
            if (!"PENDING".equals(req.get("status").asString())) {
                continue;
            }
            if (req.get("maker").asString().equals(checker)) {
                throw new IllegalStateException("four-eyes: " + checker + " made request " + req.get("id").asString());
            }
            req.put("status", "APPROVED");
            req.put("approved_by", checker);
            String collection = APPROVAL_TYPE.entrySet().stream().filter(e -> e.getValue().equals(req.get("resource_type").asString()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            ObjectNode resource = rows(tenant, collection).get(req.get("resource_id").asString());
            resource.put("status", APPROVED_STATUS.get(req.get("resource_type").asString()));
            bump(resource);
            n++;
        }
        return n;
    }

    synchronized int activateLimitRules(UUID tenant) {
        int n = 0;
        for (ObjectNode r : rows(tenant, "/v1/risk/limits/rules").values()) {
            if (!"ACTIVE".equals(r.get("status").asString())) {
                r.put("status", "ACTIVE");
                n++;
            }
        }
        return n;
    }

    /** WE-LIMIT-1: a checker activates the DRAFT transfer control profiles (cumulative + instrument) in limits-service. */
    synchronized int activateTcp(UUID tenant) {
        int n = 0;
        for (String collection : new String[] {"/v1/risk/limits/tcp/cumulative", "/v1/risk/limits/tcp/instrument"}) {
            for (ObjectNode r : rows(tenant, collection).values()) {
                if (!"ACTIVE".equals(r.path("status").asString())) {
                    r.put("status", "ACTIVE");
                    bump(r);
                    n++;
                }
            }
        }
        return n;
    }

    /** A checker publishes the DRAFT onboarding journeys (onboarding-service four-eyes publication). */
    synchronized int publishJourneys(UUID tenant, String checker) {
        int n = 0;
        for (ObjectNode j : rows(tenant, "/v1/onboarding/journeys").values()) {
            if ("DRAFT".equals(j.get("status").asString())) {
                if (checker.equals(j.path("created_by").asString())) {
                    throw new IllegalStateException("four-eyes: " + checker + " created journey " + j.get("id").asString());
                }
                j.put("status", "PUBLISHED");
                j.put("published_by", checker);
                bump(j);
                n++;
            }
        }
        return n;
    }

    List<ObjectNode> all(UUID tenant, String collection) {
        return new ArrayList<>(rows(tenant, collection).values());
    }

    List<Call> calls(UUID tenant) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.tenant().equals(tenant)).toList();
        }
    }

    /** Mutating calls that reached a handler (not replays). */
    List<Call> writes(UUID tenant) {
        return calls(tenant).stream().filter(c -> !"GET".equals(c.method()) && !c.replay()).toList();
    }

    // ------------------------------------------------------------------ ServiceClient

    synchronized JsonNode handle(String service, String method, String fullPath, Object body, String key) {
        UUID tenant = TenantContext.current().orElseThrow(() -> new AssertionError("no tenant bound for " + method + " " + fullPath));
        String principal = TenantContext.principal();
        JsonNode b = body == null ? null : json.valueToTree(body);
        String idemKey = key != null && key.startsWith("if-match:") ? null : key;
        String ifMatch = key != null && key.startsWith("if-match:") ? key.substring(9) : null;
        if (idemKey != null) {
            JsonNode prior = idempotent.get(tenant + "|" + idemKey);
            if (prior != null) {
                if (!prior.get("request").equals(b == null ? json.nullNode() : b)) {
                    throw new ApiException(422, "idempotency_key_reused", "Idempotency-Key was already used with a different body");
                }
                calls.add(new Call(tenant, principal, service, method, fullPath, b, idemKey, true));
                return prior.get("response").deepCopy();
            }
        }
        calls.add(new Call(tenant, principal, service, method, fullPath, b, idemKey, false));
        Failure failure = null;
        for (Failure f : failures) {
            if (f.method().equals(method) && f.path().test(fullPath) && f.remaining()[0] > 0 && --f.remaining()[0] == 0) {
                failure = f;
            }
        }
        if (failure != null && !failure.afterSuccess()) {
            throw new ApiException(502, "upstream_error", service + " failed (injected)");
        }
        JsonNode response = dispatch(tenant, principal, method, fullPath, b, ifMatch);
        if (idemKey != null) {
            ObjectNode stored = json.createObjectNode();
            stored.set("request", b == null ? json.nullNode() : b);
            stored.set("response", response.deepCopy());
            idempotent.put(tenant + "|" + idemKey, stored);
        }
        if (failure != null) {
            throw new ApiException(502, "upstream_error", service + " timed out after committing (injected)");
        }
        return response;
    }

    private JsonNode dispatch(UUID tenant, String principal, String method, String fullPath, JsonNode body, String ifMatch) {
        String path = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf('?')) : fullPath;
        Map<String, String> query = query(fullPath);
        String[] seg = path.split("/");
        int idAt = -1;
        for (int i = 0; i < seg.length; i++) {
            if (UUID_RE.matcher(seg[i]).matches()) {
                idAt = i;
            }
        }
        if (idAt < 0) {
            if ("GET".equals(method)) {
                return list(tenant, path, query);
            }
            if (path.endsWith("/install-standard-pack")) {
                return installEodPack(tenant);
            }
            return create(tenant, principal, path, body);
        }
        String collection = String.join("/", java.util.Arrays.copyOfRange(seg, 0, idAt));
        String id = seg[idAt];
        ObjectNode row = rows(tenant, collection).get(id);
        if (row == null) {
            throw ApiException.notFound(collection, UUID.fromString(id));
        }
        if (idAt == seg.length - 1) {
            return switch (method) {
                case "GET" -> row.deepCopy();
                case "PATCH" -> {
                    if (ifMatch != null && !ifMatch.equals(row.get("row_version").asString())) {
                        throw ApiException.conflict("version_conflict", "stale If-Match " + ifMatch);
                    }
                    row.setAll((ObjectNode) body);
                    bump(row);
                    yield row.deepCopy();
                }
                default -> throw new ApiException(405, "method_not_allowed", method + " " + path);
            };
        }
        return action(tenant, principal, collection, row, seg[idAt + 1]);
    }

    private JsonNode create(UUID tenant, String principal, String collection, JsonNode body) {
        ObjectNode row = body == null ? json.createObjectNode() : ((ObjectNode) body).deepCopy();
        row.put("id", UUID.randomUUID().toString());
        row.put("row_version", 1);
        row.put("created_by", principal);
        String initial = INITIAL_STATUS.get(collection);
        if (initial != null) {
            row.put("status", initial);
        }
        if ("/v1/legal-entities".equals(collection) || "/v1/products".equals(collection)) {
            for (ObjectNode r : rows(tenant, collection).values()) {
                if (r.path("code").asString().equals(row.path("code").asString())) {
                    throw ApiException.conflict("duplicate", "code " + row.path("code").asString() + " exists");
                }
            }
        }
        rows(tenant, collection).put(row.get("id").asString(), row);
        return row.deepCopy();
    }

    private JsonNode action(UUID tenant, String principal, String collection, ObjectNode row, String action) {
        String status = row.path("status").asString();
        String approvalType = APPROVAL_TYPE.get(collection);
        boolean submits = approvalType != null && ("submit".equals(action)
                || ("publish".equals(action) && "/v1/notification-templates".equals(collection)));
        if (submits) {
            require(status, "DRAFT", action);
            row.put("status", "PENDING_APPROVAL");
            bump(row);
            ObjectNode req = json.createObjectNode();
            req.put("id", UUID.randomUUID().toString());
            req.put("resource_type", approvalType);
            req.put("resource_id", row.get("id").asString());
            req.put("action", action);
            req.put("status", "PENDING");
            req.put("maker", principal);
            rows(tenant, APPROVALS).put(req.get("id").asString(), req);
            return "/v1/config/entries".equals(collection) || "/v1/notification-templates".equals(collection)
                    ? req.deepCopy() : row.deepCopy();
        }
        if ("publish".equals(action) && "/v1/product-versions".equals(collection)) {
            require(status, "APPROVED", action);
            row.put("status", "PUBLISHED");
            bump(row);
            return row.deepCopy();
        }
        if ("activate".equals(action)) {
            require(status, "/v1/wallets".equals(collection) ? "PENDING" : "DRAFT", action);
            row.put("status", "ACTIVE");
            bump(row);
            return row.deepCopy();
        }
        throw new ApiException(404, "not_found", "no action " + action + " on " + collection);
    }

    private JsonNode installEodPack(UUID tenant) {
        Map<String, ObjectNode> jobs = rows(tenant, "/v1/operations/eod-jobs");
        int created = 0;
        if (jobs.values().stream().noneMatch(j -> "LEDGER_EOD".equals(j.path("code").asString()))) {
            seed(tenant, "/v1/operations/eod-jobs", """
                    {"code":"LEDGER_EOD","owning_service":"ledger-service","is_active":false,"parameters":{}}""");
            created = 1;
        }
        ObjectNode out = json.createObjectNode();
        out.put("pack_code", "MERIDIAN_STANDARD_EOD");
        out.put("created", created);
        return out;
    }

    private JsonNode list(UUID tenant, String collection, Map<String, String> query) {
        ObjectNode page = json.createObjectNode();
        ArrayNode data = page.putArray("data");
        for (ObjectNode r : rows(tenant, collection).values()) {
            boolean ok = true;
            for (Map.Entry<String, String> q : query.entrySet()) {
                if (Set.of("limit", "cursor").contains(q.getKey())) {
                    continue;
                }
                JsonNode v = r.get(q.getKey());
                ok &= v != null && (v.isString() ? v.asString() : v.toString()).equals(q.getValue());
            }
            if (ok) {
                data.add(r.deepCopy());
            }
        }
        page.put("has_more", false);
        return page;
    }

    private static void require(String status, String expected, String action) {
        if (!expected.equals(status)) {
            throw ApiException.conflict("invalid_transition", action + " needs " + expected + " but is " + status);
        }
    }

    private static void bump(ObjectNode row) {
        row.put("row_version", row.path("row_version").asInt(0) + 1);
    }

    private Map<String, ObjectNode> rows(UUID tenant, String collection) {
        return data.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(collection, c -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private static Map<String, String> query(String path) {
        Map<String, String> out = new LinkedHashMap<>();
        int q = path.indexOf('?');
        if (q < 0) {
            return out;
        }
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            out.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
