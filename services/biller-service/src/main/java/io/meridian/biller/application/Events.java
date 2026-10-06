package io.meridian.biller.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Publishes the biller-service events (names exactly as declared in spec/domains/wallets.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "wallets.biller.status_changed", "wallets.bill_payment.completed", "wallets.bill_payment.failed",
            "wallets.bill_payment.reversed", "wallets.airtime_topup.completed", "wallets.airtime_topup.failed",
            "wallets.biller_settlement.approved", "wallets.biller_settlement.paid");

    private static final Set<String> HIDDEN = Set.of("tenant_id", "connection_config", "raw_response");

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
