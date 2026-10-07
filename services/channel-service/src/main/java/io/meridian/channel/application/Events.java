package io.meridian.channel.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Publishes the channel-service events (names exactly as declared in spec/domains/wallets.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of("wallets.ussd_menu.published", "wallets.ussd_menu.submitted",
            "wallets.ussd_menu.rejected", "wallets.ussd_session.completed",
            "wallets.sms_message.received", "wallets.sms_message.delivery_updated");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the wallets domain spec");
        }
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        payload.remove("context");
        payload.remove("tenant_id");
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), payload);
    }
}
