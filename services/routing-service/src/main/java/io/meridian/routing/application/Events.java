package io.meridian.routing.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Publishes routing-service events (names exactly as declared in spec/domains/payments.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "payments.payment_rail.status_changed", "payments.rail_adapter.credentials_rotated",
            "payments.rail_message.received", "payments.rail_message.sent",
            "payments.rail_settlement_batch.closed", "payments.rail_settlement_batch.settled",
            "payments.rail_liquidity_position.breached", "payments.nostro_account.topped_up");

    /** Never leave the service in an event: secrets, vault paths and raw payloads. */
    private static final Set<String> STRIPPED = Set.of("tenant_id", "credentials_ref", "previous_credentials_ref",
            "payload");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the payments domain spec");
        }
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        STRIPPED.forEach(payload::remove);
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), payload);
    }
}
