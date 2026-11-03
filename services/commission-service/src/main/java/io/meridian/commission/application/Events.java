package io.meridian.commission.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the commission-service events (names exactly as declared in spec/domains/wallets.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "wallets.commission_plan.submitted", "wallets.commission_plan.activated", "wallets.commission_plan.retired",
            "wallets.commission_earning.accrued", "wallets.joining_commission.activated",
            "wallets.pending_commission_reward.released", "wallets.commission_payout_run.approved",
            "wallets.commission_payout_run.completed");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the wallets domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
