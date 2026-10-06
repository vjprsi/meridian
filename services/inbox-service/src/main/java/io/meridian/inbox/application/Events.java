package io.meridian.inbox.application;

import io.meridian.inbox.infrastructure.Rows;
import io.meridian.events.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/** Publishes domain events through the transactional outbox; the payload is the entity's API representation. */
@Component
public class Events {
    private final OutboxWriter outbox;
    private final Rows rows;

    public Events(OutboxWriter outbox, Rows rows) {
        this.outbox = outbox;
        this.rows = rows;
    }

    public void publish(String eventType, String table, ObjectNode row, Class<?> model) {
        outbox.publish(eventType, table, UUID.fromString(row.get("id").asString()), rows.read(row, model));
    }
}
