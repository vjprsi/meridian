package io.meridian.cardtokenization;

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
 * Stateful stand-in for card-service, card-vault-service and notification-service behind the mocked ServiceClient:
 * cards with status and expiry, vault records by PAN, and captured step-up OTP notifications. Every call is recorded.
 */
public class FakeCardPlatform {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    private final Map<UUID, ObjectNode> cards = new HashMap<>();
    private final Map<String, UUID> panToCard = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    public final List<JsonNode> notifications = Collections.synchronizedList(new ArrayList<>());

    public FakeCardPlatform(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID addCard(String pan, String status, int month, int year) {
        UUID id = UUID.randomUUID();
        ObjectNode c = json.createObjectNode();
        c.put("id", id.toString());
        c.put("status", status);
        c.put("customer_id", UUID.randomUUID().toString());
        c.put("last4", pan.substring(pan.length() - 4));
        c.put("expiry_month", month);
        c.put("expiry_year", year);
        c.put("pan_token", "tok_" + id);
        cards.put(id, c);
        panToCard.put(pan, id);
        return id;
    }

    public synchronized void setStatus(UUID card, String status) {
        cards.get(card).put("status", status);
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        if ("card-service".equals(service) && path.startsWith("/v1/cards/")) {
            UUID id = UUID.fromString(path.substring("/v1/cards/".length()));
            ObjectNode c = cards.get(id);
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

    /** OTP of the last step-up notification. */
    public String lastOtp() {
        synchronized (notifications) {
            return notifications.getLast().get("variables").get("otp").asString();
        }
    }
}
