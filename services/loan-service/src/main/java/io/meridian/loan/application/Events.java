package io.meridian.loan.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the loan-service events (names exactly as declared in spec/domains/lending.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "lending.loan.created", "lending.loan.disbursed", "lending.loan.disbursement_failed",
            "lending.loan.repayment_received", "lending.loan.repayment_reversed", "lending.loan.installment_due",
            "lending.loan.installment_overdue", "lending.loan.installment_waived", "lending.loan.fee_charged",
            "lending.loan.fee_waived", "lending.loan.delinquency_changed", "lending.loan.restructure_proposed",
            "lending.loan.restructure_approved", "lending.loan.restructure_rejected", "lending.loan.restructured",
            "lending.loan.rate_changed", "lending.loan.prepayment_quoted", "lending.loan.paid_off", "lending.loan.closed",
            "lending.loan.cancelled", "lending.loan.write_off_requested", "lending.loan.written_off",
            "lending.loan.recovery_received", "lending.credit_line.created", "lending.credit_line.activated",
            "lending.credit_line.drawn", "lending.credit_line.draw_reversed", "lending.credit_line.payment_received",
            "lending.credit_line.limit_changed", "lending.credit_line.status_changed",
            "lending.credit_line.statement_issued", "lending.bnpl_plan.created", "lending.bnpl_plan.refunded",
            "lending.bnpl_plan.cancelled", "lending.bnpl_plan.completed");

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
