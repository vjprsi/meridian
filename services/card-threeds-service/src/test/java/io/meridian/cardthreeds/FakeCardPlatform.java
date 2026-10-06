package io.meridian.cardthreeds;

import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful stand-in for card-vault-service (PAN lookup), card-service (auth-context) and notification-service
 * (captured challenge OTPs) behind the mocked ServiceClient. Every call is recorded.
 */
public class FakeCardPlatform {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    private final Map<UUID, ObjectNode> contexts = new HashMap<>();
    private final Map<String, UUID> panToCard = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    public final List<JsonNode> notifications = Collections.synchronizedList(new ArrayList<>());

    public FakeCardPlatform(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID addCard(String pan, String status, String scheme) {
        UUID id = UUID.randomUUID();
        ObjectNode ctx = json.createObjectNode();
        ctx.put("as_of", "2026-10-06T10:00:00Z");
        ObjectNode card = ctx.putObject("card");
        card.put("id", id.toString());
        card.put("status", status);
        card.put("customer_id", UUID.randomUUID().toString());
        card.put("last4", pan.substring(pan.length() - 4));
        ObjectNode program = ctx.putObject("program");
        program.put("id", UUID.randomUUID().toString());
        program.put("scheme", scheme);
        program.put("status", "ACTIVE");
        ctx.putObject("product");
        ctx.putArray("controls");
        ctx.putArray("limits");
        contexts.put(id, ctx);
        panToCard.put(pan, id);
        return id;
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        if ("card-service".equals(service) && path.startsWith("/v1/cards/") && path.endsWith("/auth-context")) {
            UUID id = UUID.fromString(path.split("/")[3]);
            ObjectNode c = contexts.get(id);
            if (c == null) {
                throw ApiException.notFound("card", id);
            }
            return c.deepCopy();
        }
        throw new IllegalArgumentException("unexpected GET " + service + " " + path);
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = json.valueToTree(body);
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "Idempotency-Key header (1-128 chars) is required");
        }
        calls.add(new Call(service, "POST", path, b, key));
        if ("card-vault-service".equals(service) && path.equals("/v1/card-vault/lookup")) {
            UUID card = panToCard.get(b.get("pan").asString());
            if (card == null) {
                throw ApiException.notFound("card data vault", "pan");
            }
            ObjectNode r = json.createObjectNode();
            r.put("id", UUID.randomUUID().toString());
            r.put("card_id", card.toString());
            r.put("pan_token", "tok_" + card);
            r.put("is_active", true);
            return r;
        }
        if ("notification-service".equals(service) && path.equals("/v1/notifications/send-templated")) {
            notifications.add(b);
            ObjectNode n = json.createObjectNode();
            n.put("id", UUID.randomUUID().toString());
            n.put("status", "QUEUED");
            return n;
        }
        throw new IllegalArgumentException("unexpected POST " + service + " " + path);
    }

    public String lastOtp() {
        synchronized (notifications) {
            return notifications.getLast().get("variables").get("otp").asString();
        }
    }
}
