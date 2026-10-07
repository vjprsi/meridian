package io.meridian.agentchannel.application;

import io.meridian.web.client.ServiceClient;
import io.meridian.web.security.AgentContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls to the owning domain services. {@link ServiceClient} uses the channel's service identity for the agent's
 * tenant (an agent's token is never forwarded). Every downstream Idempotency-Key is derived from the agent, the
 * operation and the client's key ({@link #key}), so two agents using the same key can never replay each other's
 * results, and the app can find the outcome of a request later from its own key.
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

    public ObjectNode object() {
        return json.createObjectNode();
    }

    public JsonNode get(String service, String path, Query query) {
        return client.get(service, path + query.render());
    }

    public JsonNode get(String service, String path) {
        return client.get(service, path);
    }

    /** POST with the derived Idempotency-Key of (agent, operation, client key). */
    public JsonNode post(String service, String path, Object body, String clientKey, String operation) {
        return client.post(service, path, body, key(clientKey, operation));
    }

    public ServiceClient.Download bytes(String service, String path) {
        return client.getBytes(service, path);
    }

    /** A downstream page keeping only the rows the predicate accepts (defence in depth); cursor and has_more pass. */
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
    public ObjectNode page(List<JsonNode> rows) {
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

    /**
     * Downstream Idempotency-Key: a hash of agent, operation and the client's key. Replaying the same agent request
     * replays the downstream call; another agent's identical key maps elsewhere.
     */
    public static String key(String clientKey, String operation) {
        String material = AgentContext.agentId() + "|" + operation + "|" + (clientKey == null ? "" : clientKey);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "ag-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
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
