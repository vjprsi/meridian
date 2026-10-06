package io.meridian.alm.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the alm-service events (names exactly as declared in spec/domains/treasury.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "treasury.alm_assumption.approved", "treasury.alm_gap_run.completed", "treasury.alm_gap_run.failed",
            "treasury.alm_sensitivity.completed", "treasury.alm_sensitivity.outlier_breached",
            "treasury.ftp_curve.published", "treasury.ftp_assignment.computed");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared for alm-service");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
