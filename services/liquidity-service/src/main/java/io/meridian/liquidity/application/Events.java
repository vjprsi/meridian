package io.meridian.liquidity.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes liquidity events (names exactly as declared in spec/domains/treasury.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "treasury.liquidity_position.projected", "treasury.liquidity_position.shortfall",
            "treasury.liquidity_ladder.completed", "treasury.lcr.calculated", "treasury.lcr.breached",
            "treasury.lcr.approved", "treasury.nsfr.calculated", "treasury.nsfr.breached", "treasury.nsfr.approved",
            "treasury.liquidity_stress_scenario.approved", "treasury.liquidity_stress.completed",
            "treasury.liquidity_stress.breached", "treasury.intraday_liquidity.recorded",
            "treasury.liquidity_eod_run.completed");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the treasury domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
