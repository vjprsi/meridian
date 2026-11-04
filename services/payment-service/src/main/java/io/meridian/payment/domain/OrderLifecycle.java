package io.meridian.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WE-ETM-1 (G-08): the Order &amp; Pay (ETM) state machines, kept pure (no DB / ledger) so they unit-test without a
 * database, mirroring {@link PaymentLifecycle}. Two independent lifecycles are tracked against one order so the money
 * movement (the {@code payment} table and {@link PaymentLifecycle}) can be reasoned about separately from the goods:
 *
 * <ul>
 *   <li>the <b>order</b> — what the customer is buying / the bill — {@code CREATED → AWAITING_PAYMENT → PAID →
 *       FULFILLING → COMPLETED}, with {@code CANCELLED} and {@code FAILED} as outcomes;</li>
 *   <li>the <b>fulfilment</b> — delivery / confirmation of the goods or service — {@code PENDING → IN_PROGRESS →
 *       FULFILLED}, with {@code FAILED} and {@code CANCELLED} as outcomes.</li>
 * </ul>
 *
 * Because the two are segregated, paid-but-not-fulfilled, fulfilled-but-not-paid and both-done are all representable.
 */
public final class OrderLifecycle {

    public enum OrderStatus { CREATED, AWAITING_PAYMENT, PAID, FULFILLING, COMPLETED, CANCELLED, FAILED }

    public enum FulfilmentStatus { PENDING, IN_PROGRESS, FULFILLED, FAILED, CANCELLED }

    private static final Map<OrderStatus, Set<OrderStatus>> ORDER_NEXT = Map.ofEntries(
            Map.entry(OrderStatus.CREATED, EnumSet.of(OrderStatus.AWAITING_PAYMENT, OrderStatus.PAID,
                    OrderStatus.CANCELLED, OrderStatus.FAILED)),
            Map.entry(OrderStatus.AWAITING_PAYMENT, EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED,
                    OrderStatus.FAILED)),
            Map.entry(OrderStatus.PAID, EnumSet.of(OrderStatus.FULFILLING, OrderStatus.COMPLETED,
                    OrderStatus.CANCELLED, OrderStatus.FAILED)),
            Map.entry(OrderStatus.FULFILLING, EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED,
                    OrderStatus.FAILED)),
            Map.entry(OrderStatus.COMPLETED, EnumSet.noneOf(OrderStatus.class)),
            Map.entry(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)),
            Map.entry(OrderStatus.FAILED, EnumSet.noneOf(OrderStatus.class)));

    private static final Map<FulfilmentStatus, Set<FulfilmentStatus>> FULFILMENT_NEXT = Map.of(
            FulfilmentStatus.PENDING, EnumSet.of(FulfilmentStatus.IN_PROGRESS, FulfilmentStatus.FULFILLED,
                    FulfilmentStatus.FAILED, FulfilmentStatus.CANCELLED),
            FulfilmentStatus.IN_PROGRESS, EnumSet.of(FulfilmentStatus.FULFILLED, FulfilmentStatus.FAILED,
                    FulfilmentStatus.CANCELLED),
            FulfilmentStatus.FULFILLED, EnumSet.noneOf(FulfilmentStatus.class),
            FulfilmentStatus.FAILED, EnumSet.noneOf(FulfilmentStatus.class),
            FulfilmentStatus.CANCELLED, EnumSet.noneOf(FulfilmentStatus.class));

    public static final Set<OrderStatus> ORDER_FINAL =
            EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.FAILED);

    public static final Set<FulfilmentStatus> FULFILMENT_FINAL =
            EnumSet.of(FulfilmentStatus.FULFILLED, FulfilmentStatus.FAILED, FulfilmentStatus.CANCELLED);

    /** Order statuses in which the order may still be cancelled (before or just after payment, pre-fulfilment). */
    public static final Set<OrderStatus> ORDER_CANCELLABLE =
            EnumSet.of(OrderStatus.CREATED, OrderStatus.AWAITING_PAYMENT, OrderStatus.PAID, OrderStatus.FULFILLING);

    private OrderLifecycle() {}

    public static boolean orderAllowed(String from, String to) {
        return ORDER_NEXT.getOrDefault(OrderStatus.valueOf(from), Set.of()).contains(OrderStatus.valueOf(to));
    }

    public static boolean fulfilmentAllowed(String from, String to) {
        return FULFILMENT_NEXT.getOrDefault(FulfilmentStatus.valueOf(from), Set.of()).contains(FulfilmentStatus.valueOf(to));
    }

    public static boolean orderFinal(String status) {
        return ORDER_FINAL.contains(OrderStatus.valueOf(status));
    }

    public static boolean fulfilmentFinal(String status) {
        return FULFILMENT_FINAL.contains(FulfilmentStatus.valueOf(status));
    }

    /** True once fulfilment has started or completed (goods committed): favours roll-forward over rollback. */
    public static boolean fulfilmentStarted(String status) {
        FulfilmentStatus s = FulfilmentStatus.valueOf(status);
        return s == FulfilmentStatus.IN_PROGRESS || s == FulfilmentStatus.FULFILLED;
    }

    /** Outbox event published when an order enters {@code status}; empty for intermediate statuses. */
    public static Optional<String> orderEvent(String status) {
        return Optional.ofNullable(switch (OrderStatus.valueOf(status)) {
            case CREATED -> "payments.payment_order.created";
            case PAID -> "payments.payment_order.paid";
            case COMPLETED -> "payments.payment_order.completed";
            case CANCELLED -> "payments.payment_order.cancelled";
            case FAILED -> "payments.payment_order.failed";
            case AWAITING_PAYMENT, FULFILLING -> null;
        });
    }
}
