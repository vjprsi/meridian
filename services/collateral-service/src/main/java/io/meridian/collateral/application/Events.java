package io.meridian.collateral.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes collateral-service events (names exactly as declared in spec/domains/lending.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "lending.collateral.registered", "lending.collateral.verified", "lending.collateral.rejected",
            "lending.collateral.pledged", "lending.collateral.revalued", "lending.collateral.revaluation_due",
            "lending.collateral.released", "lending.collateral.liquidated", "lending.collateral_link.created",
            "lending.collateral_link.released", "lending.collateral_link.ltv_breached",
            "lending.collateral_insurance.expiring");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the lending domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
