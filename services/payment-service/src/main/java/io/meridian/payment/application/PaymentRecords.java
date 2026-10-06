package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.str;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.domain.PaymentLifecycle;
import io.meridian.payment.infrastructure.Rows;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payment row state changes: every status change is validated against {@link PaymentLifecycle}, written together
 * with an append-only status history row and the status event, inside the caller's transaction.
 */
@Component
public class PaymentRecords {
    public static final String TABLE = "payment";

    private final Rows rows;
    private final Events events;

    public PaymentRecords(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    public ObjectNode get(UUID id) {
        return rows.get(TABLE, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ObjectNode lock(UUID id) {
        return rows.lock(TABLE, id);
    }

    /** Locks the payment and checks it is in one of {@code allowed}; 409 {@code invalid_transition} otherwise. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ObjectNode lockIn(UUID id, Set<String> allowed, String action) {
        ObjectNode p = lock(id);
        String status = str(p, "status");
        if (!allowed.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action + " a payment in status " + status);
        }
        return p;
    }

    /**
     * Moves a locked payment to {@code to}, applying {@code changes} in the same update, recording history and
     * publishing the status event.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ObjectNode transition(ObjectNode payment, String to, String reasonCode, String reason, ObjectNode changes,
                                 UUID railMessageId) {
        String from = str(payment, "status");
        if (!PaymentLifecycle.allowed(from, to)) {
            throw ApiException.conflict("invalid_transition", "Payment cannot move from " + from + " to " + to);
        }
        UUID id = UUID.fromString(str(payment, "id"));
        ObjectNode values = changes == null ? rows.object() : changes;
        values.put("status", to);
        ObjectNode updated = rows.update(TABLE, id, values);
        history(id, from, to, reasonCode, reason, railMessageId);
        PaymentLifecycle.event(to, str(updated, "direction"))
                .ifPresent(e -> events.publish(e, TABLE, updated));
        return updated;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void history(UUID paymentId, String from, String to, String reasonCode, String reason, UUID railMessageId) {
        ObjectNode h = rows.object();
        h.put("payment_id", paymentId.toString());
        if (from != null) {
            h.put("from_status", from);
        }
        h.put("to_status", to);
        if (reasonCode != null) {
            h.put("reason_code", reasonCode.length() > 8 ? reasonCode.substring(0, 8) : reasonCode);
        }
        if (reason != null) {
            h.put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        }
        h.put("actor", TenantContext.principal());
        if (railMessageId != null) {
            h.put("rail_message_id", railMessageId.toString());
        }
        rows.insert("payment_status_history", h);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ObjectNode update(UUID id, ObjectNode values) {
        return rows.update(TABLE, id, values);
    }
}
