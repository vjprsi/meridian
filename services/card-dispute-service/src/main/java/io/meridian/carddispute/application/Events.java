package io.meridian.carddispute.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the dispute events (names exactly as declared in spec/domains/cards.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "cards.card_dispute.opened", "cards.card_dispute.stage_changed",
            "cards.card_dispute.provisional_credit_granted", "cards.card_dispute.provisional_credit_reversed",
            "cards.card_dispute.deadline_approaching", "cards.card_dispute.closed");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, JsonNode dispute) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared for card-dispute-service");
        }
        outbox.publish(type, "card_dispute", UUID.fromString(dispute.get("id").asString()), dispute);
    }
}
