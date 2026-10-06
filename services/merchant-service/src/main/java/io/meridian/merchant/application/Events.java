package io.meridian.merchant.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Publishes the merchant-service events (names exactly as declared in spec/domains/wallets.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "wallets.merchant.onboarded", "wallets.merchant.status_changed", "wallets.merchant_qr.paid",
            "wallets.merchant_payment_link.paid", "wallets.merchant_settlement.approved",
            "wallets.merchant_settlement.paid", "wallets.merchant_refund.completed", "wallets.merchant_refund.rejected");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the wallets domain spec");
        }
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        payload.remove("tenant_id");
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), payload);
    }
}
