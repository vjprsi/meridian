package io.meridian.openbanking.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the openbanking domain events (names exactly as declared in spec/domains/openbanking.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "openbanking.tpp.registered", "openbanking.tpp.verified", "openbanking.tpp.suspended",
            "openbanking.tpp.reactivated", "openbanking.tpp_certificate.added", "openbanking.tpp_certificate.revoked",
            "openbanking.ob_consent.created", "openbanking.ob_consent.authorised", "openbanking.ob_consent.rejected",
            "openbanking.ob_consent.revoked", "openbanking.ob_consent.expired", "openbanking.ob_consent.consumed",
            "openbanking.ob_payment_initiation.received", "openbanking.ob_payment_initiation.status_changed",
            "openbanking.ob_funds_confirmation.checked");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the openbanking domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
