package io.meridian.loanorigination.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the origination events (names exactly as declared in spec/domains/lending.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "lending.loan_application.created", "lending.loan_application.submitted",
            "lending.loan_application.status_changed", "lending.loan_application.assigned",
            "lending.loan_application.withdrawn", "lending.loan_application.booked",
            "lending.loan_application_document.verified", "lending.loan_application_document.rejected",
            "lending.bureau_report.requested", "lending.bureau_report.received",
            "lending.scorecard_version.activated", "lending.scorecard_version.retired",
            "lending.credit_score.calculated", "lending.affordability_assessment.completed",
            "lending.credit_decision.made", "lending.credit_decision_request.created",
            "lending.credit_decision_request.rejected", "lending.loan_offer.issued", "lending.loan_offer.accepted",
            "lending.loan_offer.declined", "lending.loan_offer.withdrawn", "lending.loan_offer.expired",
            "lending.e_signature_request.sent", "lending.e_signature_request.signed",
            "lending.e_signature_request.declined", "lending.e_signature_request.voided",
            "lending.e_signature_request.expired", "lending.loan_application.expired");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the lending domain spec");
        }
        JsonNode payload = row;
        if (row instanceof tools.jackson.databind.node.ObjectNode o
                && (o.has("tenant_id") || o.has("ip_address") || o.has("device_id"))) {
            tools.jackson.databind.node.ObjectNode copy = o.deepCopy();
            for (String hidden : new String[] {"tenant_id", "ip_address", "device_id"}) { // never in API payloads
                copy.remove(hidden);
            }
            payload = copy;
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), payload);
    }
}
