package io.meridian.cardtokenization.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/** Publishes the card-tokenization events (names exactly as declared in spec/domains/cards.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of("cards.card_token.provisioned", "cards.card_token.step_up_required",
            "cards.card_token.declined", "cards.card_token.status_changed");

    /** Columns never published: step-up secrets. */
    static final Set<String> HIDDEN = Set.of("otp_hash", "otp_expires_at");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the payload is the token's API representation. */
    public void publish(String type, ObjectNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the cards domain spec");
        }
        ObjectNode payload = row.deepCopy();
        HIDDEN.forEach(payload::remove);
        payload.remove("provisioning_payload");
        outbox.publish(type, "card_token", UUID.fromString(row.get("id").asString()), payload);
    }
}
