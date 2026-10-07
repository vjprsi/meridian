package io.meridian.payment.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes payment-service events (names exactly as declared in spec/domains/payments.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "payments.payment.initiated", "payments.payment.pending_approval", "payments.payment.approved",
            "payments.payment.submitted", "payments.payment.accepted", "payments.payment.settled",
            "payments.payment.rejected", "payments.payment.failed", "payments.payment.cancelled",
            "payments.payment.received", "payments.payment.returned", "payments.payment.held",
            "payments.payment.released", "payments.payment.fraud_degraded", "payments.payment_approval.decided",
            "payments.payment_return.completed", "payments.payment_recall.requested",
            "payments.payment_recall.received", "payments.payment_recall.resolved", "payments.payment_repair.opened",
            "payments.payment_repair.resolved", "payments.payee.created", "payments.payee.blocked",
            "payments.payee.verified", "payments.payee_alias.registered", "payments.payee_alias.verified",
            "payments.payee_alias.port_requested", "payments.bulk_payment.validated",
            "payments.bulk_payment.submitted", "payments.bulk_payment.approved", "payments.bulk_payment.cancelled",
            "payments.bulk_payment.completed", "payments.payment_mandate.activated",
            "payments.payment_mandate.suspended", "payments.payment_mandate.resumed",
            "payments.payment_mandate.cancelled", "payments.direct_debit_collection.collected",
            "payments.direct_debit_collection.failed", "payments.direct_debit_collection.cancelled",
            "payments.request_to_pay.sent", "payments.request_to_pay.paid", "payments.request_to_pay.declined",
            "payments.payment_link.paid", "payments.payment_link.disabled", "payments.payment_qr_code.created",
            "payments.payment_qr_code.paid");

    /** Columns never exposed by the API (spec modifier hide / secret); stripped from event payloads. */
    private static final Set<String> HIDDEN = Set.of("otp_hash", "otp_expires_at", "otp_attempts", "tenant_id");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the payments domain spec");
        }
        JsonNode payload = row;
        if (row instanceof tools.jackson.databind.node.ObjectNode o && HIDDEN.stream().anyMatch(o::has)) {
            tools.jackson.databind.node.ObjectNode copy = o.deepCopy();
            HIDDEN.forEach(copy::remove);
            payload = copy;
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), payload);
    }
}
