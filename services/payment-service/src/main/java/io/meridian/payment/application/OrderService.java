package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.str;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.domain.OrderLifecycle;
import io.meridian.payment.domain.OrderSettlementPolicy;
import io.meridian.payment.domain.OrderSettlementPolicy.Action;
import io.meridian.payment.domain.OrderSettlementPolicy.OrderAlertLevel;
import io.meridian.payment.domain.OrderSettlementPolicy.PgHeartbeatStatus;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-ETM-1 (G-08): Order &amp; Pay (ETM). Keeps the order (what is bought), the payment (the money movement in the
 * {@code payment} table, driven by {@link PaymentProcessor}) and the fulfilment (delivery of the goods or service) as
 * three segregated lifecycle states on one {@code payment_order}, resolves ambiguous payment-gateway outcomes
 * deterministically (retry / rollback / roll-forward from a recorded PG heartbeat, via {@link OrderSettlementPolicy}),
 * and derives a green / yellow / red health alert.
 *
 * <p>Entirely gated: with {@code meridian.payment.etm.enabled} false (the default) every entry point is a no-op or a
 * {@code 422 etm_disabled}, so existing payment behaviour is untouched. The payment leg is created with
 * {@link PaymentProcessor#initiate} and reversed through the existing {@link PaymentProcessor#cancel} /
 * {@link ReturnService#recall} paths — no parallel money-movement engine. Every money move and state change is keyed
 * and re-checked under the row lock, so retries and overlapping resolver passes never double-post or double-resolve.
 */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final String TABLE = "payment_order";
    static final String EVENTS = "payment_order_event";

    private final Rows rows;
    private final Events events;
    private final PaymentProcessor processor;
    private final ReturnService returns;
    private final PaymentProperties props;
    private final TransactionTemplate tx;

    public OrderService(Rows rows, Events events, PaymentProcessor processor, ReturnService returns,
                        PaymentProperties props, TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.processor = processor;
        this.returns = returns;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ create

    /** Creates an order in CREATED / PENDING; the payment and fulfilment legs are started separately. */
    public ObjectNode create(JsonNode req, String key) {
        gate();
        if (key != null) {
            Optional<ObjectNode> existing = rows.queryOne(
                    "select row_to_json(t)::text from payment_order t where idempotency_key = ?", key);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        BigDecimal amount = Rows.dec(req, "amount");
        String currency = str(req, "currency");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        if (currency == null) {
            throw ApiException.unprocessable("currency_required", "currency is required");
        }
        OffsetDateTime now = rows.now();
        ObjectNode o = rows.object();
        o.put("order_reference", PaymentProcessor.reference("ORD"));
        o.put("order_status", "CREATED");
        o.put("fulfilment_status", "PENDING");
        o.put("amount", amount.toPlainString());
        o.put("currency", currency);
        o.put("alert_level", "GREEN");
        o.put("ambiguous", false);
        o.put("settlement_attempts", 0);
        o.put("pg_heartbeat_status", "UNKNOWN");
        o.put("payment_deadline_at", now.plus(props.etm().paymentTimeout()).toString());
        o.put("initiated_by", PaymentProcessor.truncate(TenantContext.principal(), 64));
        copy(req, o, "customer_id");
        copy(req, o, "merchant_id");
        copy(req, o, "channel");
        copy(req, o, "external_order_id");
        copy(req, o, "metadata");
        if (req.hasNonNull("description")) {
            o.put("description", PaymentProcessor.truncate(str(req, "description"), 255));
        }
        if (key != null) {
            o.put("idempotency_key", key);
        }
        return tx.execute(s -> {
            ObjectNode inserted = rows.insert(TABLE, o);
            event(inserted, "ORDER_STATUS", null, "CREATED", null, "GREEN", null, "Order created");
            events.publish("payments.payment_order.created", TABLE, inserted);
            return inserted;
        });
    }

    // ------------------------------------------------------------------ payment leg

    /**
     * Starts the money-movement leg through the payments engine and links it to the order, leaving order and
     * fulfilment untouched beyond moving the order out of CREATED. Reuses {@link PaymentProcessor#initiate}.
     */
    public ObjectNode pay(UUID id, JsonNode req) {
        gate();
        ObjectNode order = tx.execute(s -> {
            ObjectNode o = rows.lock(TABLE, id);
            if (o.hasNonNull("payment_id")) {
                throw ApiException.conflict("already_paying", "The order already has a payment leg");
            }
            if (!Set.of("CREATED", "AWAITING_PAYMENT").contains(str(o, "order_status"))) {
                throw ApiException.conflict("invalid_transition", "Cannot start payment for an order in status "
                        + str(o, "order_status"));
            }
            return o;
        });
        ObjectNode preq = req.hasNonNull("payment") && req.get("payment").isObject()
                ? ((ObjectNode) req.get("payment")).deepCopy() : rows.object();
        preq.put("amount", Rows.dec(order, "amount").toPlainString());
        preq.put("currency", str(order, "currency"));
        if (!preq.has("payment_type")) {
            preq.put("payment_type", "BILL_PAYMENT");
        }
        if (!preq.has("direction")) {
            preq.put("direction", "OUTBOUND");
        }
        if (!preq.has("channel")) {
            preq.put("channel", order.hasNonNull("channel") ? str(order, "channel") : "API");
        }
        if (order.hasNonNull("customer_id")) {
            preq.put("customer_id", str(order, "customer_id"));
        }
        if (order.hasNonNull("merchant_id")) {
            preq.put("creditor_merchant_id", str(order, "merchant_id"));
        }
        if (req.hasNonNull("debtor_account_id")) {
            preq.put("debtor_account_id", str(req, "debtor_account_id"));
        }
        ObjectNode payment = processor.initiate(preq, "order-pay:" + id, PaymentProcessor.Options.DEFAULT);
        String pstatus = str(payment, "status");
        return tx.execute(s -> {
            ObjectNode o = rows.lock(TABLE, id);
            OffsetDateTime now = rows.now();
            ObjectNode c = rows.object();
            c.put("payment_id", str(payment, "id"));
            c.put("payment_status", pstatus);
            if ("SETTLED".equals(pstatus)) {
                return markPaid(o, c, now, "Payment leg settled on initiation");
            }
            applyOrderStatus(o, c, "AWAITING_PAYMENT", "Payment leg started");
            return apply(o, c, null);
        });
    }

    /** Applies the payment-gateway / rail outcome for the order's payment leg: SETTLED, FAILED or AMBIGUOUS. */
    public ObjectNode paymentResult(UUID id, String outcome, String heartbeat, String reason) {
        gate();
        String o2 = outcome == null ? "" : outcome.toUpperCase();
        return tx.execute(s -> {
            ObjectNode o = rows.lock(TABLE, id);
            OffsetDateTime now = rows.now();
            ObjectNode c = rows.object();
            if (heartbeat != null) {
                c.put("pg_heartbeat_status", heartbeatOf(heartbeat).name());
                c.put("pg_heartbeat_at", now.toString());
            }
            switch (o2) {
                case "SETTLED" -> {
                    c.put("ambiguous", false);
                    c.putNull("next_settlement_at");
                    return markPaid(o, c, now, reason != null ? reason : "Payment settled");
                }
                case "FAILED" -> {
                    c.put("payment_status", "FAILED");
                    c.put("ambiguous", false);
                    c.putNull("next_settlement_at");
                    c.put("completed_at", now.toString());
                    if (reason != null) {
                        c.put("resolution_reason", PaymentProcessor.truncate(reason, 255));
                    }
                    applyOrderStatus(o, c, "FAILED", reason != null ? reason : "Payment failed");
                    return apply(o, c, "payments.payment_order.failed");
                }
                case "AMBIGUOUS" -> {
                    c.put("ambiguous", true);
                    int attempts = Rows.integer(o, "settlement_attempts");
                    c.put("next_settlement_at", now.plus(OrderSettlementPolicy.backoff(attempts,
                            props.etm().settlementBackoff(), java.time.Duration.ofHours(1))).toString());
                    if (reason != null) {
                        c.put("resolution_reason", PaymentProcessor.truncate(reason, 255));
                    }
                    ObjectNode updated = apply(o, c, "payments.payment_order.ambiguous");
                    event(updated, "SETTLEMENT", null, "AMBIGUOUS", null, str(updated, "alert_level"),
                            str(updated, "pg_heartbeat_status"), reason != null ? reason : "Payment outcome uncertain");
                    return updated;
                }
                default -> throw ApiException.unprocessable("invalid_outcome",
                        "outcome must be SETTLED, FAILED or AMBIGUOUS");
            }
        });
    }

    // ------------------------------------------------------------------ fulfilment leg

    /** Records fulfilment progress (segregated from the payment); completing fulfilment of a paid order completes it. */
    public ObjectNode fulfil(UUID id, String status, String reason) {
        gate();
        String to = status == null ? "" : status.toUpperCase();
        if (!Set.of("IN_PROGRESS", "FULFILLED", "FAILED", "CANCELLED").contains(to)) {
            throw ApiException.unprocessable("invalid_fulfilment_status",
                    "status must be IN_PROGRESS, FULFILLED, FAILED or CANCELLED");
        }
        return tx.execute(s -> {
            ObjectNode o = rows.lock(TABLE, id);
            String from = str(o, "fulfilment_status");
            if (!OrderLifecycle.fulfilmentAllowed(from, to)) {
                throw ApiException.conflict("invalid_transition", "Fulfilment cannot move from " + from + " to " + to);
            }
            OffsetDateTime now = rows.now();
            ObjectNode c = rows.object();
            c.put("fulfilment_status", to);
            String orderEvent = null;
            if ("FULFILLED".equals(to)) {
                c.put("fulfilled_at", now.toString());
                orderEvent = "payments.payment_order.fulfilled";
                // paid + fulfilled => order complete (segregated states agree)
                if (Set.of("PAID", "FULFILLING").contains(str(o, "order_status"))) {
                    c.put("completed_at", now.toString());
                    applyOrderStatus(o, c, "COMPLETED", "Paid and fulfilled");
                }
            } else if ("IN_PROGRESS".equals(to) && "PAID".equals(str(o, "order_status"))) {
                applyOrderStatus(o, c, "FULFILLING", "Fulfilment in progress");
            }
            event(o, "FULFILMENT_STATUS", from, to, null, null, null, reason);
            ObjectNode updated = apply(o, c, orderEvent);
            if (orderEvent != null && "COMPLETED".equals(str(updated, "order_status"))) {
                events.publish("payments.payment_order.completed", TABLE, updated);
            }
            return updated;
        });
    }

    /** Records a payment-gateway liveness / status probe for the order's rail. */
    public ObjectNode heartbeat(UUID id, String status) {
        gate();
        PgHeartbeatStatus hb = heartbeatOf(status);
        return tx.execute(s -> {
            ObjectNode o = rows.lock(TABLE, id);
            ObjectNode c = rows.object();
            c.put("pg_heartbeat_status", hb.name());
            c.put("pg_heartbeat_at", rows.now().toString());
            ObjectNode updated = rows.update(TABLE, id, c);
            event(updated, "SETTLEMENT", null, "HEARTBEAT", null, str(updated, "alert_level"), hb.name(),
                    "Payment-gateway heartbeat " + hb.name());
            return updated;
        });
    }

    // ------------------------------------------------------------------ ambiguous-case settlement

    /** Resolves one ambiguous order now (idempotent: a non-ambiguous order is returned unchanged). */
    public ObjectNode resolve(UUID id) {
        gate();
        return resolveOne(id);
    }

    /**
     * Resolves every due ambiguous order for the bound tenant: the gated, idempotent resolver entry point used by both
     * the internal scheduler and {@code POST /v1/payment-orders/resolve-ambiguous}. Returns empty when ETM is off.
     */
    public ObjectNode resolveDueAmbiguous() {
        ObjectNode out = rows.object();
        if (!props.etm().enabled()) {
            return out.put("resolved", 0);
        }
        List<ObjectNode> due = rows.query("""
                select row_to_json(t)::text from payment_order t
                where ambiguous = true and (next_settlement_at is null or next_settlement_at <= ?)
                order by next_settlement_at nulls first limit 200""", rows.now());
        int retried = 0;
        int rolledForward = 0;
        int rolledBack = 0;
        int waited = 0;
        var failures = out.putArray("failures");
        for (ObjectNode o : due) {
            UUID id = Rows.uuid(o, "id");
            try {
                ObjectNode r = resolveOne(id);
                switch (str(r, "settlement_action") == null ? "" : str(r, "settlement_action")) {
                    case "RETRY" -> retried++;
                    case "ROLL_FORWARD" -> rolledForward++;
                    case "ROLLBACK" -> rolledBack++;
                    case "WAIT" -> waited++;
                    default -> { }
                }
            } catch (ApiException e) {
                failures.addObject().put("payment_order_id", id.toString()).put("code", e.code())
                        .put("message", e.getMessage());
            }
        }
        out.put("candidates", due.size());
        out.put("retried", retried);
        out.put("rolled_forward", rolledForward);
        out.put("rolled_back", rolledBack);
        out.put("waited", waited);
        out.put("resolved", rolledForward + rolledBack);
        return out;
    }

    private ObjectNode resolveOne(UUID id) {
        PaymentProperties.Etm cfg = props.etm();
        boolean[] mustRollback = new boolean[1];
        UUID[] rollbackPayment = new UUID[1];
        boolean[] paymentSettled = new boolean[1];
        ObjectNode order = tx.execute(s -> {
            ObjectNode o = rows.lock(TABLE, id);
            if (!Rows.bool(o, "ambiguous")) {
                return o; // idempotent: already resolved or never ambiguous
            }
            OffsetDateTime now = rows.now();
            PgHeartbeatStatus hb = OrderSettlementPolicy.effectiveHeartbeat(heartbeatOf(str(o, "pg_heartbeat_status")),
                    Rows.ts(o, "pg_heartbeat_at"), now, cfg.heartbeatStaleAfter());
            boolean timedOut = fulfilmentTimedOut(o, now);
            int attempts = Rows.integer(o, "settlement_attempts");
            boolean exhausted = attempts >= cfg.maxSettlementAttempts();
            boolean started = OrderLifecycle.fulfilmentStarted(str(o, "fulfilment_status"));
            Action action = OrderSettlementPolicy.decide(hb, timedOut, exhausted, started);
            if (action == Action.ROLLBACK) {
                // finalise after the external reversal, so the return / recall call is outside the row lock
                mustRollback[0] = true;
                rollbackPayment[0] = Rows.uuid(o, "payment_id");
                paymentSettled[0] = "SETTLED".equals(str(o, "payment_status"));
                return o;
            }
            return applyResolution(o, action, attempts, now, hb, cfg);
        });
        if (mustRollback[0]) {
            return rollback(id, rollbackPayment[0], paymentSettled[0]);
        }
        return order;
    }

    /** Applies RETRY / WAIT / ROLL_FORWARD on the locked order (no external money move needed). */
    private ObjectNode applyResolution(ObjectNode o, Action action, int attempts, OffsetDateTime now,
                                       PgHeartbeatStatus hb, PaymentProperties.Etm cfg) {
        ObjectNode c = rows.object();
        c.put("settlement_action", action.name());
        switch (action) {
            case RETRY -> {
                int next = attempts + 1;
                c.put("settlement_attempts", next);
                c.put("next_settlement_at", now.plus(OrderSettlementPolicy.backoff(next, cfg.settlementBackoff(),
                        java.time.Duration.ofHours(1))).toString());
            }
            case WAIT -> c.put("next_settlement_at", now.plus(OrderSettlementPolicy.backoff(attempts,
                    cfg.settlementBackoff(), java.time.Duration.ofHours(1))).toString());
            case ROLL_FORWARD -> {
                // treat the payment as succeeded and complete fulfilment
                c.put("ambiguous", false);
                c.putNull("next_settlement_at");
                c.put("payment_status", "SETTLED");
                if (!"PAID".equals(str(o, "order_status")) && !"FULFILLING".equals(str(o, "order_status"))
                        && !OrderLifecycle.orderFinal(str(o, "order_status"))) {
                    c.put("paid_at", now.toString());
                    applyOrderStatus(o, c, "PAID", "Rolled forward: treated as settled");
                }
                if (!OrderLifecycle.fulfilmentFinal(str(o, "fulfilment_status"))) {
                    c.put("fulfilment_status", "FULFILLED");
                    c.put("fulfilled_at", now.toString());
                }
                c.put("completed_at", now.toString());
                applyOrderStatus(o, c, "COMPLETED", "Rolled forward: fulfilment completed");
            }
            default -> throw new IllegalStateException("applyResolution does not handle " + action);
        }
        ObjectNode updated = apply(o, c, action == Action.ROLL_FORWARD ? "payments.payment_order.completed" : null);
        event(updated, "SETTLEMENT", null, action.name(), action.name(), str(updated, "alert_level"), hb.name(),
                "Ambiguous-case resolution " + action.name());
        events.publish("payments.payment_order.resolved", TABLE, updated);
        return updated;
    }

    /** ROLLBACK: reverse the payment leg through the existing return / recall path, then cancel the order. */
    private ObjectNode rollback(UUID id, UUID paymentId, boolean settled) {
        UUID recallId = reversePayment(paymentId, settled, "Order rolled back after an ambiguous payment outcome");
        return tx.execute(s -> {
            ObjectNode o = rows.lock(TABLE, id);
            if (!Rows.bool(o, "ambiguous")) {
                return o; // another pass already resolved it
            }
            OffsetDateTime now = rows.now();
            ObjectNode c = rows.object();
            c.put("settlement_action", "ROLLBACK");
            c.put("ambiguous", false);
            c.putNull("next_settlement_at");
            c.put("completed_at", now.toString());
            c.put("resolution_reason", "Rolled back after an ambiguous payment outcome");
            if (recallId != null) {
                c.put("rollback_recall_id", recallId.toString());
            }
            if (!OrderLifecycle.fulfilmentFinal(str(o, "fulfilment_status"))) {
                c.put("fulfilment_status", "CANCELLED");
            }
            applyOrderStatus(o, c, "CANCELLED", "Rolled back");
            ObjectNode updated = apply(o, c, "payments.payment_order.cancelled");
            event(updated, "SETTLEMENT", null, "ROLLBACK", "ROLLBACK", str(updated, "alert_level"),
                    str(updated, "pg_heartbeat_status"), "Ambiguous-case resolution ROLLBACK");
            events.publish("payments.payment_order.resolved", TABLE, updated);
            return updated;
        });
    }

    // ------------------------------------------------------------------ cancel

    /** Cancels an order; a settled payment leg is reversed through the existing return / recall path. */
    public ObjectNode cancel(UUID id, String reason) {
        gate();
        ObjectNode snap = rows.get(TABLE, id);
        if (OrderLifecycle.orderFinal(str(snap, "order_status"))) {
            throw ApiException.conflict("invalid_transition", "The order is already " + str(snap, "order_status"));
        }
        UUID recallId = snap.hasNonNull("payment_id")
                ? reversePayment(Rows.uuid(snap, "payment_id"), "SETTLED".equals(str(snap, "payment_status")),
                        reason != null ? reason : "Order cancelled")
                : null;
        return tx.execute(s -> {
            ObjectNode o = rows.lock(TABLE, id);
            if (OrderLifecycle.orderFinal(str(o, "order_status"))) {
                return o;
            }
            OffsetDateTime now = rows.now();
            ObjectNode c = rows.object();
            c.put("ambiguous", false);
            c.putNull("next_settlement_at");
            c.put("completed_at", now.toString());
            if (reason != null) {
                c.put("resolution_reason", PaymentProcessor.truncate(reason, 255));
            }
            if (recallId != null) {
                c.put("rollback_recall_id", recallId.toString());
            }
            if (!OrderLifecycle.fulfilmentFinal(str(o, "fulfilment_status"))) {
                c.put("fulfilment_status", "CANCELLED");
            }
            applyOrderStatus(o, c, "CANCELLED", reason != null ? reason : "Order cancelled");
            return apply(o, c, "payments.payment_order.cancelled");
        });
    }

    /**
     * Reverses a payment leg through an existing path: a not-yet-settled payment is cancelled
     * ({@link PaymentProcessor#cancel}); a settled one is recalled ({@link ReturnService#recall}, camt.056), returning
     * the recall id. Best-effort and isolated — a reversal that cannot be performed is logged, never invents a posting.
     */
    private UUID reversePayment(UUID paymentId, boolean settled, String reason) {
        if (paymentId == null) {
            return null;
        }
        try {
            if (settled) {
                // reuse the existing reversal path (camt.056 recall); TECH = technical / fraudulent-origin recall
                ObjectNode recall = returns.recall(paymentId, "TECH", reason);
                return Rows.uuid(recall, "id");
            }
            processor.cancel(paymentId, reason);
            return null;
        } catch (ApiException e) {
            log.warn("could not reverse payment {} for an order rollback/cancel: {}", paymentId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ queries / helpers

    public ObjectNode get(UUID id) {
        return rows.get(TABLE, id);
    }

    /** Order / fulfilment / alert / settlement transitions of the order, oldest first. */
    public List<ObjectNode> listEvents(UUID id) {
        return rows.query("""
                select row_to_json(t)::text from payment_order_event t
                where payment_order_id = ? order by created_at""", id);
    }

    private void gate() {
        if (!props.etm().enabled()) {
            throw ApiException.unprocessable("etm_disabled", "Order & Pay (ETM) is not enabled for this tenant");
        }
    }

    /** Marks an order paid: PAID, payment settled, fulfilment deadline opened from now. */
    private ObjectNode markPaid(ObjectNode o, ObjectNode c, OffsetDateTime now, String reason) {
        c.put("payment_status", "SETTLED");
        c.put("paid_at", now.toString());
        if (!o.hasNonNull("fulfilment_deadline_at")) {
            c.put("fulfilment_deadline_at", now.plus(props.etm().fulfilmentTimeout()).toString());
        }
        if ("FULFILLED".equals(str(o, "fulfilment_status"))) {
            c.put("completed_at", now.toString());
            applyOrderStatus(o, c, "COMPLETED", reason);
            ObjectNode updated = apply(o, c, "payments.payment_order.paid");
            events.publish("payments.payment_order.completed", TABLE, updated);
            return updated;
        }
        applyOrderStatus(o, c, "PAID", reason);
        return apply(o, c, "payments.payment_order.paid");
    }

    /** Validates and records an order-status transition into {@code changes}, writing the history row. */
    private void applyOrderStatus(ObjectNode o, ObjectNode changes, String to, String reason) {
        String from = str(o, "order_status");
        if (from.equals(to)) {
            return;
        }
        if (!OrderLifecycle.orderAllowed(from, to)) {
            throw ApiException.conflict("invalid_transition", "Order cannot move from " + from + " to " + to);
        }
        changes.put("order_status", to);
        event(o, "ORDER_STATUS", from, to, null, null, null, reason);
    }

    /**
     * Writes {@code changes} to the order with the recomputed alert level, publishing {@code orderEvent} (if any) and
     * an alert-changed event when the health signal moves. The resulting order state is read from {@code changes}
     * falling back to the locked row, so the alert always reflects what is being persisted.
     */
    private ObjectNode apply(ObjectNode o, ObjectNode changes, String orderEvent) {
        String orderStatus = changes.hasNonNull("order_status") ? str(changes, "order_status") : str(o, "order_status");
        String fulfilment = changes.hasNonNull("fulfilment_status") ? str(changes, "fulfilment_status")
                : str(o, "fulfilment_status");
        boolean ambiguous = changes.has("ambiguous") ? Rows.bool(changes, "ambiguous") : Rows.bool(o, "ambiguous");
        int attempts = changes.hasNonNull("settlement_attempts") ? Rows.integer(changes, "settlement_attempts")
                : Rows.integer(o, "settlement_attempts");
        boolean started = OrderLifecycle.fulfilmentStarted(fulfilment);
        boolean exhausted = OrderSettlementPolicy.needsManualIntervention(attempts, props.etm().maxSettlementAttempts(),
                started);
        OrderAlertLevel level = OrderSettlementPolicy.alert(orderStatus, fulfilment, ambiguous, exhausted);
        String previous = str(o, "alert_level");
        changes.put("alert_level", level.name());
        UUID id = Rows.uuid(o, "id");
        ObjectNode updated = rows.update(TABLE, id, changes);
        if (orderEvent != null) {
            events.publish(orderEvent, TABLE, updated);
        }
        if (!level.name().equals(previous)) {
            event(updated, "ALERT", previous, level.name(), null, level.name(), str(updated, "pg_heartbeat_status"),
                    "Health alert " + previous + " -> " + level.name());
            events.publish("payments.payment_order.alert_changed", TABLE, updated);
        }
        return updated;
    }

    private void event(ObjectNode order, String type, String from, String to, String action, String level,
                       String heartbeat, String reason) {
        ObjectNode e = rows.object();
        e.put("payment_order_id", str(order, "id"));
        e.put("event_type", type);
        if (from != null) {
            e.put("from_status", from);
        }
        e.put("to_status", to);
        if (action != null) {
            e.put("settlement_action", action);
        }
        if (level != null) {
            e.put("alert_level", level);
        }
        if (heartbeat != null) {
            e.put("pg_heartbeat_status", heartbeat);
        }
        if (reason != null) {
            e.put("reason", PaymentProcessor.truncate(reason, 255));
        }
        e.put("actor", PaymentProcessor.truncate(TenantContext.principal(), 64));
        rows.insert(EVENTS, e);
    }

    private boolean fulfilmentTimedOut(ObjectNode o, OffsetDateTime now) {
        OffsetDateTime deadline = Rows.ts(o, "fulfilment_deadline_at");
        return deadline != null && !now.isBefore(deadline);
    }

    private static PgHeartbeatStatus heartbeatOf(String status) {
        if (status == null) {
            return PgHeartbeatStatus.UNKNOWN;
        }
        try {
            return PgHeartbeatStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_heartbeat_status",
                    "status must be UP, DEGRADED, DOWN or UNKNOWN");
        }
    }

    private static void copy(JsonNode from, ObjectNode to, String field) {
        if (from.hasNonNull(field)) {
            to.set(field, from.get(field));
        }
    }
}
