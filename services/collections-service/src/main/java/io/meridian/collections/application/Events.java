package io.meridian.collections.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the collections events (names exactly as declared in spec/domains/lending.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "lending.collection_case.opened", "lending.collection_case.updated", "lending.collection_case.assigned",
            "lending.collection_case.disputed", "lending.collection_case.sent_to_agency", "lending.collection_case.closed",
            "lending.collection_action.performed", "lending.promise_to_pay.made", "lending.promise_to_pay.kept",
            "lending.promise_to_pay.broken", "lending.promise_to_pay.cancelled", "lending.payment_plan.proposed",
            "lending.payment_plan.activated", "lending.payment_plan.completed", "lending.payment_plan.broken",
            "lending.payment_plan.cancelled", "lending.next_best_action.suggested", "lending.legal_proceeding.requested",
            "lending.legal_proceeding.started", "lending.legal_proceeding.status_changed");

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
