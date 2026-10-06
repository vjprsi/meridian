package io.meridian.cardclearing.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes card-clearing-service events (names exactly as declared in spec/domains/cards.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "cards.clearing_file.processed", "cards.clearing_file.failed", "cards.clearing_record.posted",
            "cards.clearing_record.unmatched", "cards.clearing_record.force_matched",
            "cards.card_settlement.calculated", "cards.card_settlement.confirmed", "cards.card_fee.charged",
            "cards.card_fee.waived");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the cards domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
