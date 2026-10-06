package io.meridian.cardthreeds.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/** Publishes the 3DS events (names exactly as declared in spec/domains/cards.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of("cards.threeds_authentication.challenge_required",
            "cards.threeds_authentication.completed");

    /** Never published: authentication value (CAVV) and the OTP hash. */
    static final Set<String> HIDDEN = Set.of("authentication_value", "otp_hash", "cavv");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, ObjectNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the cards domain spec");
        }
        ObjectNode payload = row.deepCopy();
        HIDDEN.forEach(payload::remove);
        outbox.publish(type, "threeds_authentication", UUID.fromString(row.get("id").asString()), payload);
    }
}
