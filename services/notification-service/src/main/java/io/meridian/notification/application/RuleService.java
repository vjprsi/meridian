package io.meridian.notification.application;

import io.meridian.common.error.ApiException;
import io.meridian.notification.domain.DeliveryPolicy;
import io.meridian.notification.domain.RuleConditions;
import io.meridian.notification.infrastructure.NotificationStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Event-driven notifications: domain events are matched against active rules (event type, optional condition) and
 * turned into templated sends. Also routes maker-checker outcomes for notification templates.
 *
 * <p>Rule fields: {@code channels} = {@code {"channels": ["PUSH","SMS"]}} (preference order; the first channel with an
 * active template, a destination and no opt-out wins); {@code recipient_expression} = {@code TYPE:path} into the
 * event payload, e.g. {@code CUSTOMER:customer_id} (type defaults to CUSTOMER); {@code conditions} = the safe CEL
 * subset of {@link RuleConditions}. Destinations come from the payload (destinations map, destination, email,
 * phone, device_token) as in {@link NotificationService#resolveDestination}.
 */
@Service
public class RuleService {
    private static final Logger log = LoggerFactory.getLogger(RuleService.class);
    private static final Set<String> CHANNELS = Set.of("PUSH", "SMS", "EMAIL", "WHATSAPP", "IN_APP", "USSD", "VOICE");
    private static final Set<String> RECIPIENT_TYPES = Set.of("CUSTOMER", "STAFF", "AGENT", "MERCHANT", "EXTERNAL");

    private final NotificationStore store;
    private final NotificationService notifications;
    private final TemplateService templates;
    private final JsonMapper json;

    public RuleService(NotificationStore store, NotificationService notifications, TemplateService templates, JsonMapper json) {
        this.store = store;
        this.notifications = notifications;
        this.templates = templates;
        this.json = json;
    }

    /** Validates rule fields on create/update (merged view). */
    public static void validate(JsonNode rule) {
        JsonNode channels = rule.path("channels").path("channels");
        if (rule.has("channels")) {
            if (!channels.isArray() || channels.isEmpty()) {
                throw ApiException.unprocessable("invalid_channels", "channels must be {\"channels\": [\"EMAIL\", ...]}");
            }
            for (JsonNode c : channels) {
                if (!CHANNELS.contains(c.asString())) {
                    throw ApiException.unprocessable("invalid_channels", "Unknown channel " + c.asString());
                }
            }
        }
        if (rule.hasNonNull("recipient_expression")) {
            String expr = rule.get("recipient_expression").asString();
            String type = expr.contains(":") ? expr.substring(0, expr.indexOf(':')) : "CUSTOMER";
            String path = expr.contains(":") ? expr.substring(expr.indexOf(':') + 1) : expr;
            if (!RECIPIENT_TYPES.contains(type) || !path.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                throw ApiException.unprocessable("invalid_recipient_expression",
                        "recipient_expression must be TYPE:payload.path, e.g. CUSTOMER:customer_id");
            }
        }
        if (rule.hasNonNull("conditions")) {
            try {
                RuleConditions.validate(rule.get("conditions").asString());
            } catch (RuleConditions.InvalidConditionException e) {
                throw ApiException.unprocessable("invalid_conditions", e.getMessage());
            }
        }
    }

    /**
     * Handles one domain event (CloudEvent id/type + JSON payload) for the current tenant. Returns the ids of the
     * notifications created. Rules that fail (missing variables, no destination ...) are logged and skipped.
     */
    public List<UUID> onEvent(String eventId, String eventType, JsonNode payload) {
        List<UUID> created = new ArrayList<>();
        if (eventType == null || eventType.startsWith("platform.notification")) {
            return created; // never notify about our own notifications (loops)
        }
        if (eventType.startsWith("platform.approval_request.")
                && TemplateService.RESOURCE_TYPE.equals(payload.path("resource_type").asString(null))
                && payload.hasNonNull("resource_id")) {
            templates.onApprovalDecided(UUID.fromString(payload.get("resource_id").asString()),
                    payload.path("status").asString(eventType.substring(eventType.lastIndexOf('.') + 1).toUpperCase()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = payload.isObject() ? json.convertValue(payload, LinkedHashMap.class) : new LinkedHashMap<>();
        for (String r : store.activeRules()) {
            JsonNode rule = json.readTree(r);
            if (!RuleConditions.eventMatches(rule.get("event_type").asString(), eventType)) {
                continue;
            }
            try {
                if (!RuleConditions.matches(rule.path("conditions").asString(null), data)) {
                    continue;
                }
                String expr = rule.get("recipient_expression").asString();
                String type = expr.contains(":") ? expr.substring(0, expr.indexOf(':')) : "CUSTOMER";
                Object rid = RuleConditions.lookup(data, expr.contains(":") ? expr.substring(expr.indexOf(':') + 1) : expr);
                UUID recipientId = rid == null ? null : UUID.fromString(String.valueOf(rid));
                Map<String, Object> vars = new LinkedHashMap<>(data);
                vars.put("event_type", eventType);
                vars.put("event_id", eventId);
                List<String> channels = new ArrayList<>();
                rule.path("channels").path("channels").forEach(c -> channels.add(c.asString()));
                UUID id = sendFirstViable(rule, channels, type, recipientId, vars, eventType, eventId);
                if (id != null) {
                    created.add(id);
                }
            } catch (RuntimeException e) {
                log.warn("Notification rule {} skipped for event {} ({}): {}", rule.get("id").asString(), eventId,
                        eventType, e.getMessage());
            }
        }
        return created;
    }

    private UUID sendFirstViable(JsonNode rule, List<String> channels, String recipientType, UUID recipientId,
                                 Map<String, Object> vars, String eventType, String eventId) {
        String category = rule.get("category").asString();
        RuntimeException last = null;
        // Pass 1: channels the recipient has not opted out of; pass 2: anything (recorded as SUPPRESSED).
        for (int pass = 0; pass < 2; pass++) {
            for (String channel : channels) {
                boolean optedOut = !DeliveryPolicy.mandatory(category) && recipientId != null
                        && store.preference(recipientType, recipientId, category, channel)
                                .map(p -> !json.readTree(p).path("is_enabled").asBoolean(true)).orElse(false);
                if (pass == 0 && optedOut) {
                    continue;
                }
                try {
                    NotificationService.Submitted s = notifications.sendTemplated(new NotificationService.TemplatedSend(
                            rule.get("template_code").asString(), recipientType, recipientId, channel, vars, category,
                            eventType, eventId, null));
                    return UUID.fromString(json.readTree(s.row()).get("id").asString());
                } catch (ApiException e) {
                    last = e;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }
}
