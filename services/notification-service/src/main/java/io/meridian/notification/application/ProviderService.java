package io.meridian.notification.application;

import io.meridian.common.error.ApiException;
import io.meridian.notification.domain.DeliveryPolicy;
import io.meridian.notification.infrastructure.NotificationStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Provider operations beyond CRUD: test message through one specific provider. */
@Service
public class ProviderService {
    private final NotificationStore store;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final io.meridian.web.crud.CrudEngine crud;

    public ProviderService(NotificationStore store, NotificationService notifications, JsonMapper json,
                           io.meridian.web.crud.CrudEngine crud) {
        this.crud = crud;
        this.store = store;
        this.notifications = notifications;
        this.json = json;
    }

    /**
     * Creates a provider. {@code credentials_ref} is a {@code secret} column, so the contract omits it from every
     * payload (spec defect: it is also NOT NULL). It is taken from {@code config.credentials_ref} (and removed from
     * the stored config) or defaults to the conventional vault path {@code vault:notification/providers/<id>}.
     */
    @org.springframework.transaction.annotation.Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("notification_provider", body);
        validate(values);
        String id = io.meridian.common.id.Ids.newId().toString();
        values.put("id", id);
        String ref = "vault:notification/providers/" + id;
        if (values.get("config") instanceof ObjectNode config && config.hasNonNull("credentials_ref")) {
            ref = config.get("credentials_ref").asString();
            config.remove("credentials_ref");
        }
        values.put("credentials_ref", ref);
        return crud.insert("notification_provider", values);
    }

    /** Validates provider fields (create/update merged view). */
    public static void validate(JsonNode p) {
        if (p.has("priority") && p.get("priority").asInt() < 1) {
            throw ApiException.unprocessable("invalid_priority", "priority must be >= 1 (lower is tried first)");
        }
        JsonNode config = p.get("config");
        if (config != null && config.has("smtp_port")
                && (config.get("smtp_port").asInt() < 1 || config.get("smtp_port").asInt() > 65535)) {
            throw ApiException.unprocessable("invalid_config", "config.smtp_port must be a TCP port");
        }
    }

    /**
     * Sends a test message through exactly this provider (no failover, no retry). The send is recorded as an
     * EXTERNAL / SERVICE notification with its delivery attempt so it shows in the delivery logs.
     */
    public Map<String, Object> test(UUID providerId, String destination) {
        JsonNode p = json.readTree(store.json("notification_provider", providerId)
                .orElseThrow(() -> ApiException.notFound("notification provider", providerId)));
        if ("INACTIVE".equals(p.get("status").asString())) {
            throw ApiException.conflict("provider_inactive", "Provider is INACTIVE; set it to TESTING or ACTIVE first");
        }
        String channel = p.get("channel").asString();
        String problem = DeliveryPolicy.destinationProblem(channel, destination);
        if (problem != null) {
            throw ApiException.unprocessable("invalid_destination", problem);
        }
        ObjectNode values = json.createObjectNode();
        values.put("recipient_type", "EXTERNAL");
        values.put("channel", channel);
        values.put("destination", destination);
        values.put("category", "SERVICE");
        values.put("subject", "Meridian provider test");
        values.put("body", "Test message from Meridian via " + p.get("provider_name").asString() + ".");
        values.put("source_event_type", "notification_provider.test");
        values.set("data", json.createObjectNode().put("test", true).put("notification_provider_id", providerId.toString()));
        JsonNode n = json.readTree(notifications.submit(values, providerId).row());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("notification_provider_id", providerId.toString());
        out.put("notification_id", n.get("id").asString());
        out.put("success", "SENT".equals(n.get("status").asString()) || "DELIVERED".equals(n.get("status").asString()));
        out.put("status", n.get("status").asString());
        out.put("provider_message_id", n.path("provider_message_id").asString(null));
        out.put("failure_reason", n.path("failure_reason").asString(null));
        return out;
    }
}
