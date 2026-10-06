package io.meridian.cardvault.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the vault's events (names exactly as declared in spec/domains/cards.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of("cards.card_vault.revealed", "cards.card_vault.keys_rotated");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Inside the caller's transaction. Payloads are access-log / key rows: never card data. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared for card-vault-service");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
