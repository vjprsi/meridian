package io.meridian.agentnetwork.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes agent-network-service events (names exactly as declared in spec/domains/wallets.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "wallets.agent.onboarded", "wallets.agent.status_changed", "wallets.agent.commission_plan_assigned",
            "wallets.agent_outlet.closed", "wallets.agent_float.low_balance", "wallets.agent_float_request.approved",
            "wallets.agent_float_request.rejected", "wallets.agent_till.opened", "wallets.agent_till.closed",
            "wallets.agent_device.lost", "wallets.agent_kyc_document.verified",
            "wallets.cash_management_request.completed");

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
