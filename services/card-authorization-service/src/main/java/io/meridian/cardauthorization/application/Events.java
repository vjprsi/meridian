package io.meridian.cardauthorization.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the card-authorization-service events (names exactly as in spec/domains/cards.yaml) via the outbox. */
@Component
public class Events {
    public static final String APPROVED = "cards.card_authorization.approved";
    public static final String DECLINED = "cards.card_authorization.declined";
    public static final String INCREMENTED = "cards.card_authorization.incremented";
    public static final String REVERSED = "cards.card_authorization.reversed";
    public static final String EXPIRED = "cards.card_authorization.expired";
    public static final String CLEARED = "cards.card_authorization.cleared";
    public static final String STAND_IN_APPROVED = "cards.card_authorization.stand_in_approved";
    public static final String STAND_IN_REPLAYED = "cards.card_stand_in_record.replayed";
    public static final String JIT_REQUESTED = "cards.card_jit_funding.requested";

    static final Set<String> DECLARED = Set.of(APPROVED, DECLINED, INCREMENTED, REVERSED, EXPIRED, CLEARED,
            STAND_IN_APPROVED, STAND_IN_REPLAYED, JIT_REQUESTED);

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the cards domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
