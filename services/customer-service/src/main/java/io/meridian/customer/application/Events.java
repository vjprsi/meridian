package io.meridian.customer.application;

import io.meridian.customer.infrastructure.Rows;
import io.meridian.events.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes domain events through the transactional outbox. The payload is always the entity's API
 * representation (the generated model), so hidden and secret columns never leave the service.
 */
@Component
public class Events {
    private final OutboxWriter outbox;
    private final Rows rows;

    public Events(OutboxWriter outbox, Rows rows) {
        this.outbox = outbox;
        this.rows = rows;
    }

    public void publish(String eventType, String aggregateType, ObjectNode row, Class<?> model) {
        outbox.publish(eventType, aggregateType, UUID.fromString(row.get("id").asString()), rows.read(row, model));
    }

    /** For events whose payload is a composed document rather than a single row. */
    public void publishPayload(String eventType, String aggregateType, UUID id, Object payload) {
        outbox.publish(eventType, aggregateType, id, payload);
    }
}
