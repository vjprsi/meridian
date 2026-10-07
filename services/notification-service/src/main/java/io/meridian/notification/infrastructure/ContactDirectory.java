package io.meridian.notification.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Destination of a customer on a channel from customer-service contact points, used when neither the request nor
 * the event payload carries one (domain events such as {@code payments.payment.settled} name the customer, not their
 * phone or e-mail). EMAIL uses EMAIL contacts; SMS / VOICE / USSD use MOBILE; WHATSAPP prefers WHATSAPP, then
 * MOBILE. Current contacts only; verified before unverified, then primary first. Lookup failures mean "no
 * destination" (the caller tries the next channel).
 */
@Component
public class ContactDirectory {
    private static final Logger log = LoggerFactory.getLogger(ContactDirectory.class);
    static final String CUSTOMER_SERVICE = "customer-service";

    private final ServiceClient services;

    public ContactDirectory(ServiceClient services) {
        this.services = services;
    }

    public Optional<String> destination(String recipientType, UUID recipientId, String channel) {
        if (!"CUSTOMER".equals(recipientType) || recipientId == null || channel == null) {
            return Optional.empty();
        }
        List<String> types = switch (channel) {
            case "EMAIL" -> List.of("EMAIL");
            case "SMS", "VOICE", "USSD" -> List.of("MOBILE");
            case "WHATSAPP" -> List.of("WHATSAPP", "MOBILE");
            default -> List.of();
        };
        if (types.isEmpty()) {
            return Optional.empty();
        }
        JsonNode page;
        try {
            page = services.get(CUSTOMER_SERVICE, "/v1/customers/" + recipientId + "/contacts?limit=50");
        } catch (RuntimeException e) {
            log.debug("contacts of customer {} unavailable: {}", recipientId, e.getMessage());
            return Optional.empty();
        }
        if (page == null) {
            return Optional.empty();
        }
        String field = "EMAIL".equals(channel) ? "email" : "phone";
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode c : page.path("data")) {
            String validTo = c.path("valid_to").asString(null);
            if (types.contains(c.path("contact_type").asString("")) && !c.path(field).asString("").isBlank()
                    && (validTo == null || !LocalDate.parse(validTo).isBefore(today))) {
                candidates.add(c);
            }
        }
        return candidates.stream()
                .min(Comparator.<JsonNode>comparingInt(c -> c.path("is_verified").asBoolean(false) ? 0 : 1)
                        .thenComparingInt(c -> c.path("is_primary").asBoolean(false) ? 0 : 1)
                        .thenComparingInt(c -> types.indexOf(c.path("contact_type").asString())))
                .map(c -> c.path(field).asString());
    }
}
