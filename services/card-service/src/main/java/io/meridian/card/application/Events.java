package io.meridian.card.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Publishes card-service events (names exactly as declared in spec/domains/cards.yaml) via the outbox. Payloads are
 * the rows in API shape: tokens and masked data only, never a PAN, CVV or PIN block.
 */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "cards.card_program.activated", "cards.card_program.status_changed", "cards.card_product.status_changed",
            "cards.card.created", "cards.card.activated", "cards.card.status_changed", "cards.card.replaced",
            "cards.card.renewed", "cards.card.expiring", "cards.card_order.created", "cards.card_order.status_changed",
            "cards.card_order.shipped", "cards.card_order.delivered", "cards.card_control.changed",
            "cards.card_spend_limit.changed", "cards.card_pin.changed", "cards.card_statement_cycle.closed");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the cards domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
