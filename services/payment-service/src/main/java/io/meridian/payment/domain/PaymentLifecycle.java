package io.meridian.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ISO 20022-aligned payment state machine. INITIATED → SCREENING → PENDING_APPROVAL → APPROVED → SUBMITTED →
 * ACCEPTED → SETTLED, with IN_REPAIR for repairable validation failures and REJECTED / RETURNED / FAILED / CANCELLED
 * as outcomes. Internal (book) transfers settle straight from APPROVED.
 */
public final class PaymentLifecycle {
    public enum Status { INITIATED, SCREENING, PENDING_APPROVAL, APPROVED, SUBMITTED, ACCEPTED, SETTLED, REJECTED,
        RETURNED, FAILED, CANCELLED, IN_REPAIR }

    private static final Map<Status, Set<Status>> NEXT = Map.ofEntries(
            Map.entry(Status.INITIATED, EnumSet.of(Status.SCREENING, Status.IN_REPAIR, Status.PENDING_APPROVAL,
                    Status.APPROVED, Status.REJECTED, Status.CANCELLED, Status.FAILED, Status.SETTLED)),
            Map.entry(Status.IN_REPAIR, EnumSet.of(Status.INITIATED, Status.CANCELLED)),
            Map.entry(Status.SCREENING, EnumSet.of(Status.PENDING_APPROVAL, Status.APPROVED, Status.REJECTED,
                    Status.CANCELLED, Status.SETTLED, Status.FAILED)),
            Map.entry(Status.PENDING_APPROVAL, EnumSet.of(Status.APPROVED, Status.REJECTED, Status.CANCELLED)),
            Map.entry(Status.APPROVED, EnumSet.of(Status.SUBMITTED, Status.SETTLED, Status.REJECTED, Status.CANCELLED,
                    Status.FAILED)),
            Map.entry(Status.SUBMITTED, EnumSet.of(Status.ACCEPTED, Status.SETTLED, Status.REJECTED, Status.FAILED)),
            Map.entry(Status.ACCEPTED, EnumSet.of(Status.SETTLED, Status.REJECTED, Status.FAILED)),
            Map.entry(Status.SETTLED, EnumSet.of(Status.RETURNED)),
            Map.entry(Status.REJECTED, EnumSet.noneOf(Status.class)),
            Map.entry(Status.RETURNED, EnumSet.noneOf(Status.class)),
            Map.entry(Status.FAILED, EnumSet.noneOf(Status.class)),
            Map.entry(Status.CANCELLED, EnumSet.noneOf(Status.class)));

    /** Statuses in which the payment can still be cancelled by the customer or an operator. */
    public static final Set<Status> CANCELLABLE = EnumSet.of(Status.INITIATED, Status.SCREENING,
            Status.PENDING_APPROVAL, Status.APPROVED, Status.IN_REPAIR);

    public static final Set<Status> FINAL = EnumSet.of(Status.SETTLED, Status.REJECTED, Status.RETURNED,
            Status.FAILED, Status.CANCELLED);

    private PaymentLifecycle() {}

    public static boolean allowed(String from, String to) {
        return NEXT.getOrDefault(Status.valueOf(from), Set.of()).contains(Status.valueOf(to));
    }

    public static boolean isFinal(String status) {
        return FINAL.contains(Status.valueOf(status));
    }

    /** Outbox event published when a payment enters {@code status}; empty for intermediate statuses. */
    public static Optional<String> event(String status, String direction) {
        return Optional.ofNullable(switch (Status.valueOf(status)) {
            case INITIATED -> "payments.payment.initiated";
            case PENDING_APPROVAL -> "payments.payment.pending_approval";
            case APPROVED -> "payments.payment.approved";
            case SUBMITTED -> "payments.payment.submitted";
            case ACCEPTED -> "payments.payment.accepted";
            case SETTLED -> "INBOUND".equals(direction) ? "payments.payment.received" : "payments.payment.settled";
            case REJECTED -> "payments.payment.rejected";
            case FAILED -> "payments.payment.failed";
            case CANCELLED -> "payments.payment.cancelled";
            case RETURNED -> "payments.payment.returned";
            case SCREENING, IN_REPAIR -> null;
        });
    }

    /** ISO 20022 ExternalStatusReason1Code values used by the hub. */
    public static final class Reason {
        public static final String INSUFFICIENT_FUNDS = "AM04";
        public static final String NOT_ALLOWED_AMOUNT = "AM02";
        public static final String INVALID_ACCOUNT = "AC01";
        public static final String CLOSED_ACCOUNT = "AC04";
        public static final String BLOCKED_ACCOUNT = "AC06";
        public static final String REGULATORY = "RR04";
        public static final String NOT_AUTHORISED = "AG01";
        public static final String REQUESTED_BY_CUSTOMER = "CUST";
        public static final String DUPLICATE = "DUPL";
        public static final String NARRATIVE = "NARR";

        private Reason() {}
    }
}
