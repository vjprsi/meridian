package io.meridian.disbursement.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes disbursement events (names exactly as declared in spec/domains/wallets.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "wallets.disbursement_program.status_changed", "wallets.disbursement_batch.validated",
            "wallets.disbursement_batch.submitted", "wallets.disbursement_batch.approved",
            "wallets.disbursement_batch.rejected", "wallets.disbursement_batch.cancelled",
            "wallets.disbursement_batch.completed", "wallets.disbursement_beneficiary.paid",
            "wallets.disbursement_beneficiary.failed");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the wallets domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
