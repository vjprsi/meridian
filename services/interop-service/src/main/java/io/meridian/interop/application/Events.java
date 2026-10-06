package io.meridian.interop.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Publishes the interop-service events (names exactly as declared in spec/domains/wallets.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of("wallets.interop_quote.received", "wallets.interop_transfer.reserved",
            "wallets.interop_transfer.committed", "wallets.interop_transfer.aborted");
    /** Never published: the fulfilment is our secret until revealed to the switch; connection config holds secrets refs. */
    private static final Set<String> HIDDEN = Set.of("fulfilment", "connection_config", "tenant_id");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the wallets domain spec");
        }
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        HIDDEN.forEach(payload::remove);
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), payload);
    }
}
