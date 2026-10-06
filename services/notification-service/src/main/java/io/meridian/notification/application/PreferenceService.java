package io.meridian.notification.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.notification.domain.DeliveryPolicy;
import io.meridian.notification.infrastructure.NotificationStore;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Recipient preferences: opt-in/out per category and channel, quiet hours, preferred locale. */
@Service
public class PreferenceService {
    private final NotificationStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public PreferenceService(NotificationStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("notification_preference", body);
        validate(values);
        String row = crud.insert("notification_preference", values);
        JsonNode node = json.readTree(row);
        outbox.publish("platform.notification_preference.changed", "notification_preference",
                UUID.fromString(node.get("id").asString()), node);
        return row;
    }

    @Transactional
    public String update(UUID id, Object body, int expectedVersion) {
        JsonNode current = json.readTree(store.json("notification_preference", id)
                .orElseThrow(() -> ApiException.notFound("notification preference", id)));
        ObjectNode changes = crud.toColumns("notification_preference", body);
        ObjectNode merged = ((ObjectNode) current).deepCopy();
        merged.setAll(changes);
        validate(merged);
        String row = crud.updateRow("notification_preference", Map.of("id", id), changes, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + expectedVersion + " does not match the current version; reload and retry"));
        JsonNode node = json.readTree(row);
        outbox.publish("platform.notification_preference.changed", "notification_preference", id, node);
        return row;
    }

    private static void validate(JsonNode v) {
        String category = v.path("category").asString(null);
        if (category != null && DeliveryPolicy.mandatory(category) && !v.path("is_enabled").asBoolean(true)) {
            throw ApiException.unprocessable("category_mandatory",
                    category + " notifications cannot be disabled by the recipient");
        }
        String start = v.path("quiet_hours_start").asString(null);
        String end = v.path("quiet_hours_end").asString(null);
        if ((start == null) != (end == null)) {
            throw ApiException.unprocessable("invalid_quiet_hours", "quiet_hours_start and quiet_hours_end go together");
        }
        if (start != null && (!DeliveryPolicy.validClock(start) || !DeliveryPolicy.validClock(end))) {
            throw ApiException.unprocessable("invalid_quiet_hours", "Quiet hours must be HH:MM (00:00-23:59)");
        }
        String tz = v.path("timezone").asString(null);
        if (tz != null && DeliveryPolicy.zone(tz).isEmpty()) {
            throw ApiException.unprocessable("invalid_timezone", "Unknown timezone " + tz + " (use an IANA id, e.g. Africa/Nairobi)");
        }
    }
}
