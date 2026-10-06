package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.bool;
import static io.meridian.payment.infrastructure.Rows.dec;
import static io.meridian.payment.infrastructure.Rows.integer;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.payment.infrastructure.LedgerClient;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reactions to a payment's final outcome inside the same transaction: request-to-pay paid, payment link and QR
 * usage, bulk item status and bulk completion (releasing the unused part of the batch hold).
 */
@Component
public class PaymentHooks {
    private final Rows rows;
    private final Events events;
    private final LedgerClient ledger;

    public PaymentHooks(Rows rows, Events events, LedgerClient ledger) {
        this.rows = rows;
        this.events = events;
        this.ledger = ledger;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onSettled(ObjectNode p) {
        UUID paymentId = UUID.fromString(str(p, "id"));
        UUID rtpId = uuid(p, "request_to_pay_id");
        if (rtpId != null) {
            rows.find("request_to_pay", rtpId).ifPresent(r -> {
                if (!"PAID".equals(str(r, "status"))) {
                    ObjectNode c = rows.object();
                    c.put("status", "PAID");
                    c.put("paid_payment_id", paymentId.toString());
                    c.put("responded_at", rows.now().toString());
                    events.publish("payments.request_to_pay.paid", "request_to_pay", rows.update("request_to_pay", rtpId, c));
                }
            });
        }
        JsonNode meta = p.path("metadata");
        UUID linkId = meta.hasNonNull("payment_link_id") ? UUID.fromString(meta.get("payment_link_id").asString()) : null;
        if (linkId != null) {
            ObjectNode link = rows.lock("payment_link", linkId);
            int count = integer(link, "payment_count") + 1;
            ObjectNode c = rows.object();
            c.put("payment_count", count);
            c.put("last_payment_id", paymentId.toString());
            boolean exhausted = !bool(link, "reusable")
                    || (link.hasNonNull("max_payments") && count >= integer(link, "max_payments"));
            if (exhausted) {
                c.put("status", "PAID");
            }
            events.publish("payments.payment_link.paid", "payment_link", rows.update("payment_link", linkId, c));
        }
        UUID qrId = meta.hasNonNull("payment_qr_code_id") ? UUID.fromString(meta.get("payment_qr_code_id").asString()) : null;
        if (qrId != null) {
            ObjectNode qr = rows.lock("payment_qr_code", qrId);
            ObjectNode c = rows.object();
            if ("DYNAMIC".equals(str(qr, "qr_type"))) {
                c.put("status", "USED");
                c.put("used_payment_id", paymentId.toString());
            }
            ObjectNode updated = c.isEmpty() ? qr : rows.update("payment_qr_code", qrId, c);
            events.publish("payments.payment_qr_code.paid", "payment_qr_code", updated);
        }
        bulkItem(p, "SETTLED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onFailed(ObjectNode p) {
        bulkItem(p, "CANCELLED".equals(str(p, "status")) ? "CANCELLED" : "FAILED");
    }

    private void bulkItem(ObjectNode p, String itemStatus) {
        UUID bulkId = uuid(p, "bulk_payment_id");
        if (bulkId == null) {
            return;
        }
        rows.execute("update bulk_payment_item set status = ? where payment_id = ?::uuid and status = 'SUBMITTED'",
                itemStatus, str(p, "id"));
        completeIfDone(bulkId);
    }

    /** Completes a PROCESSING bulk payment once every submitted item reached a final status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ObjectNode> completeIfDone(UUID bulkId) {
        ObjectNode bulk = rows.lock("bulk_payment", bulkId);
        if (!"PROCESSING".equals(str(bulk, "status"))) {
            return Optional.empty();
        }
        ObjectNode counts = rows.queryOne("""
                select json_build_object(
                  'open', count(*) filter (where status in ('PENDING', 'VALID', 'SUBMITTED')),
                  'settled', count(*) filter (where status = 'SETTLED'),
                  'settled_amount', coalesce(sum(amount) filter (where status = 'SETTLED'), 0),
                  'executed_amount', coalesce(sum(amount) filter (where payment_id is not null), 0),
                  'failed', count(*) filter (where status in ('FAILED', 'CANCELLED')))::text
                from bulk_payment_item where bulk_payment_id = ?""", bulkId).orElseThrow();
        ObjectNode c = rows.object();
        c.put("settled_count", integer(counts, "settled"));
        if (integer(counts, "open") > 0) {
            rows.update("bulk_payment", bulkId, c);
            return Optional.empty();
        }
        int settled = integer(counts, "settled");
        c.put("status", integer(counts, "failed") == 0 && settled > 0 ? "COMPLETED" : "PARTIALLY_COMPLETED");
        c.put("completed_at", rows.now().toString());
        UUID holdId = uuid(bulk, "hold_id");
        BigDecimal settledAmount = dec(counts, "settled_amount");
        if (holdId != null && bool(bulk, "batch_booking") && settledAmount.compareTo(dec(counts, "executed_amount")) < 0) {
            ledger.releaseHold(holdId, "Bulk payment completed; releasing unused reservation", "blk-release:" + bulkId);
        }
        ObjectNode done = rows.update("bulk_payment", bulkId, c);
        events.publish("payments.bulk_payment.completed", "bulk_payment", done);
        return Optional.of(done);
    }
}
