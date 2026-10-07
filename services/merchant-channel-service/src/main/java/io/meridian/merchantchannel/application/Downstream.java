package io.meridian.merchantchannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.security.MerchantContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls to the owning domain services. {@link ServiceClient} uses the channel's service identity for the merchant's
 * tenant (a merchant user's token is never forwarded) with {@code X-User: merchant-user:<id>}. Downstream
 * Idempotency-Keys of user actions are derived from the merchant, the user, the operation and the client's key, so two
 * users (or merchants) using the same key never replay each other's results. Approved refunds and payout changes are
 * executed with keys derived from the request only ({@link #postAs}), so a retry by anyone never refunds twice.
 */
@Component
public class Downstream {
    public static final int MAX_PAGE = 200;

    private final ServiceClient client;
    private final JsonMapper json;

    public Downstream(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public JsonMapper json() {
        return json;
    }

    public JsonNode get(String service, String path, Query query) {
        return client.get(service, path + query.render());
    }

    public JsonNode get(String service, String path) {
        return client.get(service, path);
    }

    /** POST with the derived Idempotency-Key of (merchant, user, operation, step, client key). */
    public JsonNode post(String service, String path, Object body, String clientKey, String step) {
        return client.post(service, path, body, key(clientKey, service + " " + path, step));
    }

    /**
     * POST acting as another app user of the merchant (the requester of a refund, or its approver) with an exact,
     * deterministic Idempotency-Key. merchant-service sees that user as {@code X-User}, so its own four-eyes rules
     * compare the real people.
     */
    public JsonNode postAs(UUID merchantUserId, String service, String path, Object body, String exactKey) {
        return as(merchantUserId, () -> client.post(service, path, body, exactKey));
    }

    public JsonNode patch(String service, String path, Object body, Object rowVersion) {
        return client.patch(service, path, body, rowVersion == null ? null : String.valueOf(rowVersion));
    }

    public JsonNode delete(String service, String path) {
        return client.send(service, "DELETE", path, null, null);
    }

    public ServiceClient.Download bytes(String service, String path) {
        return client.getBytes(service, path);
    }

    /** Runs {@code body} with {@code merchant-user:<id>} as the acting principal (tenant unchanged). */
    public static <T> T as(UUID merchantUserId, Supplier<T> body) {
        String principal = MerchantContext.MerchantPrincipal.principalName(merchantUserId);
        try {
            return TenantContext.callAs(TenantContext.require(), principal, body::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A downstream page, keeping only rows the predicate accepts (defence in depth). Cursor and has_more pass through. */
    public ObjectNode page(JsonNode downstream, Predicate<JsonNode> keep) {
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        downstream.path("data").forEach(r -> {
            if (keep.test(r)) {
                data.add(r);
            }
        });
        JsonNode cursor = downstream.get("next_cursor");
        if (cursor != null && !cursor.isNull()) {
            out.put("next_cursor", cursor.asString());
        } else {
            out.putNull("next_cursor");
        }
        out.put("has_more", downstream.path("has_more").asBoolean(false));
        return out;
    }

    /** A complete (single) page built from rows gathered by the channel. */
    public ObjectNode page(List<? extends JsonNode> rows) {
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        rows.forEach(data::add);
        out.putNull("next_cursor");
        out.put("has_more", false);
        return out;
    }

    /** All rows of a downstream list (following cursors, at most {@code maxRows}). */
    public List<JsonNode> all(String service, String path, Query query, int maxRows) {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        do {
            Query q = query.copy().with("limit", MAX_PAGE).with("cursor", cursor);
            JsonNode page = get(service, path, q);
            page.path("data").forEach(out::add);
            cursor = page.path("has_more").asBoolean(false) && page.hasNonNull("next_cursor")
                    ? page.get("next_cursor").asString() : null;
        } while (cursor != null && out.size() < maxRows);
        return out;
    }

    /** True when the problem is a 4xx answer of the downstream service (as opposed to an outage / 5xx). */
    public static boolean rejected(ApiException e) {
        return e.status() >= 400 && e.status() < 500;
    }

    /**
     * Idempotency-Key for a downstream write: a hash of merchant, user, operation, step and the client's key. Replaying
     * the same request replays the downstream call; another user's identical key maps elsewhere.
     */
    public static String key(String clientKey, String operation, String step) {
        MerchantContext.MerchantPrincipal p = MerchantContext.require();
        String material = p.merchantId() + "|" + p.merchantUserId() + "|" + operation + "|"
                + (step == null ? "" : step) + "|" + (clientKey == null ? "" : clientKey);
        return "mc-" + sha256(material);
    }

    static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Query string builder; null values are left out, values are URL-encoded. */
    public static final class Query {
        private final Map<String, Object> params = new LinkedHashMap<>();

        public static Query of(String name, Object value) {
            return new Query().with(name, value);
        }

        public static Query none() {
            return new Query();
        }

        public Query with(String name, Object value) {
            if (value == null) {
                params.remove(name);
            } else {
                params.put(name, value);
            }
            return this;
        }

        public Query paging(Integer limit, String cursor) {
            return with("limit", limit).with("cursor", cursor == null || cursor.isBlank() ? null : cursor);
        }

        Query copy() {
            Query q = new Query();
            q.params.putAll(params);
            return q;
        }

        String render() {
            if (params.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            params.forEach((k, v) -> sb.append(sb.isEmpty() ? '?' : '&').append(k).append('=')
                    .append(URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)));
            return sb.toString();
        }
    }
}
