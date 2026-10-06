package io.meridian.campaign.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapters to other Meridian services: segment membership and marketing consents (customer-service) and message
 * delivery (notification-service). Consent checks fail closed: an unreadable consent means no contact.
 */
@Component
public class ExternalServices {
    private static final Logger log = LoggerFactory.getLogger(ExternalServices.class);
    static final String CUSTOMER = "customer-service";
    static final String NOTIFICATION = "notification-service";

    /** Campaign channels that notification-service delivers; BRANCH and CALL_CENTER are offline lead lists. */
    private static final Map<String, String> CHANNELS = Map.of("PUSH", "PUSH", "SMS", "SMS", "EMAIL", "EMAIL",
            "IN_APP", "IN_APP", "WHATSAPP", "WHATSAPP", "USSD", "USSD");

    private final ServiceClient client;
    private final JsonMapper json;

    public ExternalServices(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** Outcome of a marketing-consent check. */
    public record Consent(boolean permitted, UUID consentId, String reason) {}

    /** Segments the customer is currently assigned to (valid on {@code today}). Throws when unavailable. */
    public Set<UUID> activeSegments(UUID customerId, LocalDate today) {
        JsonNode page = client.get(CUSTOMER, "/v1/customers/" + customerId + "/segments?limit=200");
        Set<UUID> out = new HashSet<>();
        JsonNode data = page == null ? null : page.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode a : data) {
                if (!a.hasNonNull("customer_segment_id")) {
                    continue;
                }
                LocalDate from = date(a, "valid_from");
                LocalDate to = date(a, "valid_to");
                if ((from == null || !from.isAfter(today)) && (to == null || !to.isBefore(today))) {
                    out.add(UUID.fromString(a.get("customer_segment_id").asString()));
                }
            }
        }
        return out;
    }

    /** Marketing consent: permitted when a GIVEN MARKETING consent exists that has not expired at {@code now}. */
    public Consent marketingConsent(UUID customerId, OffsetDateTime now) {
        try {
            JsonNode page = client.get(CUSTOMER, "/v1/consents?customer_id=" + customerId
                    + "&purpose=MARKETING&status=GIVEN&limit=50");
            JsonNode data = page == null ? null : page.get("data");
            boolean expiredSeen = false;
            if (data != null && data.isArray()) {
                for (JsonNode c : data) {
                    if (!"GIVEN".equals(c.path("status").asString(null)) || !"MARKETING".equals(c.path("purpose").asString("MARKETING"))) {
                        continue;
                    }
                    String exp = c.hasNonNull("expires_at") ? c.get("expires_at").asString() : null;
                    if (exp != null && !OffsetDateTime.parse(exp).isAfter(now)) {
                        expiredSeen = true;
                        continue;
                    }
                    return new Consent(true, c.hasNonNull("id") ? UUID.fromString(c.get("id").asString()) : null, null);
                }
            }
            return new Consent(false, null, expiredSeen ? "marketing_consent_expired" : "no_marketing_consent");
        } catch (RuntimeException e) {
            log.warn("Marketing consent check failed for customer {}: {}", customerId, e.getMessage());
            return new Consent(false, null, "consent_check_failed");
        }
    }

    public static boolean deliverable(String campaignChannel) {
        return CHANNELS.containsKey(campaignChannel);
    }

    /** Sends a templated marketing message to a customer; returns the notification id. */
    public UUID sendToCustomer(UUID customerId, String campaignChannel, String templateCode, ObjectNode variables,
                               ObjectNode data, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("template_code", templateCode);
        body.put("recipient_type", "CUSTOMER");
        body.put("recipient_id", customerId.toString());
        body.put("channel", CHANNELS.get(campaignChannel));
        body.put("category", "MARKETING");
        body.put("priority", "LOW");
        body.set("variables", variables);
        body.set("data", data);
        return id(client.post(NOTIFICATION, "/v1/notifications/send-templated", body, key));
    }

    /** Sends a rendered test message to an explicit destination. */
    public UUID sendTest(String recipient, String campaignChannel, String subject, String text, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("recipient_type", "EXTERNAL");
        body.put("channel", CHANNELS.get(campaignChannel));
        body.put("destination", recipient);
        body.put("category", "MARKETING");
        if (subject != null) {
            body.put("subject", subject);
        }
        body.put("body", text == null ? "" : text);
        return id(client.post(NOTIFICATION, "/v1/notifications", body, key));
    }

    private static UUID id(JsonNode r) {
        if (r == null || !r.hasNonNull("id")) {
            throw new ApiException(502, "upstream_error", "notification-service returned no notification id");
        }
        return UUID.fromString(r.get("id").asString());
    }

    private static LocalDate date(JsonNode n, String f) {
        if (!n.hasNonNull(f)) {
            return null;
        }
        String s = n.get(f).asString();
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }
}
