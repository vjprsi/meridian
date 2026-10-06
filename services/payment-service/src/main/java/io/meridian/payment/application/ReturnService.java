package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.dec;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.infrastructure.CoreBankingClient;
import io.meridian.payment.infrastructure.LedgerClient;
import io.meridian.payment.infrastructure.RoutingClient;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Returns (pacs.004), recalls (camt.056 / camt.029) and refunds. Funds move back with reverse postings between the
 * customer's ledger account and the rail settlement account; the original payment becomes RETURNED.
 */
@Service
public class ReturnService {
    private final Rows rows;
    private final Events events;
    private final PaymentRecords records;
    private final PaymentProcessor processor;
    private final LedgerClient ledger;
    private final CoreBankingClient core;
    private final RoutingClient routing;
    private final PaymentProperties props;
    private final TransactionTemplate tx;

    public ReturnService(Rows rows, Events events, PaymentRecords records, PaymentProcessor processor,
                         LedgerClient ledger, CoreBankingClient core, RoutingClient routing, PaymentProperties props,
                         TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.records = records;
        this.processor = processor;
        this.ledger = ledger;
        this.core = core;
        this.routing = routing;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ outbound return of an inbound payment

    /** We return a settled inbound payment: debit the customer, credit the rail settlement account. */
    public ObjectNode returnInbound(UUID paymentId, String reasonCode, String reason, BigDecimal amount, UUID recallId) {
        ObjectNode p = records.get(paymentId);
        if (!"INBOUND".equals(str(p, "direction"))) {
            throw ApiException.unprocessable("not_inbound", "Only inbound payments can be returned; use recall for outbound payments");
        }
        if (!"SETTLED".equals(str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only SETTLED payments can be returned (status " + str(p, "status") + ")");
        }
        BigDecimal original = dec(p, "amount");
        BigDecimal returned = amount == null ? original : amount;
        if (returned.signum() <= 0 || returned.compareTo(original) > 0) {
            throw ApiException.unprocessable("invalid_return_amount", "Return amount must be positive and at most " + original.toPlainString());
        }
        JsonNode account = core.depositAccount(uuid(p, "creditor_account_id"), "creditor");
        UUID settlement = settlementAccount(p);
        ObjectNode ret = tx.execute(s -> {
            ObjectNode r = rows.object();
            r.put("payment_id", paymentId.toString());
            r.put("direction", "OUTBOUND");
            r.put("status", "INITIATED");
            r.put("reason_code", PaymentProcessor.truncate(reasonCode, 8));
            if (reason != null) {
                r.put("reason", PaymentProcessor.truncate(reason, 255));
            }
            r.put("amount", returned.toPlainString());
            r.put("currency", str(p, "currency"));
            r.put("fee_amount", "0");
            r.put("return_id", PaymentProcessor.reference("RTR"));
            return rows.insert("payment_return", r);
        });
        UUID returnId = UUID.fromString(str(ret, "id"));
        UUID je;
        try {
            je = ledger.post("prt-post:" + returnId, "TRANSFER", "Return of " + str(p, "end_to_end_id") + " (" + reasonCode + ")",
                    str(p, "currency"), "PAYMENT_RETURN", returnId, List.of(
                            LedgerClient.Leg.debit(uuid(account, "ledger_account_id"), returned, "Return " + reasonCode),
                            LedgerClient.Leg.credit(settlement, returned, "Return to " + str(p, "debtor_name"))));
        } catch (ApiException e) {
            if (e.status() == 422) {
                tx.execute(s -> rows.update("payment_return", returnId, rows.object().put("status", "FAILED")));
                throw ApiException.unprocessable("return_posting_failed", "Return could not be posted: " + e.getMessage());
            }
            throw e;
        }
        return tx.execute(s -> {
            ObjectNode c = rows.object();
            c.put("status", "COMPLETED");
            c.put("journal_entry_id", je.toString());
            c.put("completed_at", rows.now().toString());
            ObjectNode done = rows.update("payment_return", returnId, c);
            records.transition(records.lock(paymentId), "RETURNED", reasonCode, reason == null ? "Returned to the sender" : reason,
                    null, null);
            events.publish("payments.payment_return.completed", "payment_return", done);
            if (recallId != null) {
                ObjectNode rc = rows.object();
                rc.put("return_id", returnId.toString());
                rows.update("payment_recall", recallId, rc);
            }
            return done;
        });
    }

    // ------------------------------------------------------------------ inbound return of an outbound payment

    /** pacs.004 received for one of our settled outbound payments: credit the debtor back. */
    public ObjectNode inboundReturn(JsonNode req) {
        String e2e = str(req, "original_end_to_end_id");
        ObjectNode p = rows.queryOne("""
                select row_to_json(t)::text from payment t where end_to_end_id = ? and direction in ('OUTBOUND', 'INTERNAL')
                order by created_at desc limit 1""", e2e)
                .orElseThrow(() -> ApiException.notFound("payment with end_to_end_id", e2e));
        UUID paymentId = UUID.fromString(str(p, "id"));
        Optional<ObjectNode> existing = rows.queryOne("""
                select row_to_json(t)::text from payment_return t where payment_id = ? and direction = 'INBOUND'
                and status = 'COMPLETED' limit 1""", paymentId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!"SETTLED".equals(str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only SETTLED payments can be returned (status " + str(p, "status") + ")");
        }
        BigDecimal amount = dec(req, "amount");
        if (amount == null || amount.signum() <= 0 || amount.compareTo(dec(p, "amount")) > 0) {
            throw ApiException.unprocessable("invalid_return_amount", "Return amount must be positive and at most the payment amount");
        }
        return creditBack(p, amount, str(req, "reason_code"), str(req, "reason"), str(req, "return_id"), null);
    }

    private ObjectNode creditBack(ObjectNode p, BigDecimal amount, String reasonCode, String reason, String railReturnId,
                                  UUID recallId) {
        UUID paymentId = UUID.fromString(str(p, "id"));
        UUID settlement = settlementAccount(p);
        ObjectNode ret = tx.execute(s -> {
            ObjectNode r = rows.object();
            r.put("payment_id", paymentId.toString());
            r.put("direction", "INBOUND");
            r.put("status", "SUBMITTED");
            r.put("reason_code", PaymentProcessor.truncate(reasonCode, 8));
            if (reason != null) {
                r.put("reason", PaymentProcessor.truncate(reason, 255));
            }
            r.put("amount", amount.toPlainString());
            r.put("currency", str(p, "currency"));
            r.put("fee_amount", "0");
            r.put("return_id", railReturnId != null ? PaymentProcessor.truncate(railReturnId, 35) : PaymentProcessor.reference("RTR"));
            return rows.insert("payment_return", r);
        });
        UUID returnId = UUID.fromString(str(ret, "id"));
        UUID je = ledger.post("prt-credit:" + returnId, "TRANSFER", "Returned payment " + str(p, "end_to_end_id"),
                str(p, "currency"), "PAYMENT_RETURN", returnId, List.of(
                        LedgerClient.Leg.debit(settlement, amount, "Return from " + str(p, "creditor_name")),
                        LedgerClient.Leg.credit(uuid(p, "debtor_ledger_account_id"), amount, "Returned " + reasonCode)));
        return tx.execute(s -> {
            ObjectNode c = rows.object();
            c.put("status", "COMPLETED");
            c.put("journal_entry_id", je.toString());
            c.put("completed_at", rows.now().toString());
            ObjectNode done = rows.update("payment_return", returnId, c);
            records.transition(records.lock(paymentId), "RETURNED", reasonCode,
                    reason == null ? "Returned by the creditor agent" : reason, null, null);
            events.publish("payments.payment_return.completed", "payment_return", done);
            if (recallId != null) {
                rows.update("payment_recall", recallId, rows.object().put("return_id", returnId.toString()));
            }
            return done;
        });
    }

    // ------------------------------------------------------------------ recalls

    /** camt.056 for one of our settled outbound payments. */
    public ObjectNode recall(UUID paymentId, String reasonCode, String reason) {
        return tx.execute(s -> {
            ObjectNode p = records.lock(paymentId);
            if (!Set.of("OUTBOUND", "INTERNAL").contains(str(p, "direction"))) {
                throw ApiException.unprocessable("not_outbound", "Only outbound payments can be recalled");
            }
            if (!"SETTLED".equals(str(p, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only SETTLED payments can be recalled; cancel it instead");
            }
            if (!Set.of("DUPL", "FRAD", "TECH", "CUST", "AM09", "AC03").contains(reasonCode)) {
                throw ApiException.unprocessable("invalid_recall_reason", "reason_code must be DUPL, FRAD, TECH, CUST, AM09 or AC03");
            }
            if (rows.scalar("select 1 from payment_recall where payment_id = ? and status in ('REQUESTED', 'PENDING') limit 1",
                    Integer.class, paymentId).isPresent()) {
                throw ApiException.conflict("recall_in_progress", "A recall of this payment is already in progress");
            }
            ObjectNode r = rows.object();
            r.put("payment_id", paymentId.toString());
            r.put("direction", "OUTBOUND");
            r.put("status", "PENDING");
            r.put("reason_code", reasonCode);
            if (reason != null) {
                r.put("reason", PaymentProcessor.truncate(reason, 255));
            }
            r.put("requested_by", PaymentProcessor.truncate(TenantContext.principal(), 64));
            ObjectNode recall = rows.insert("payment_recall", r);
            records.history(paymentId, str(p, "status"), str(p, "status"), reasonCode, "Recall requested (camt.056)", null);
            events.publish("payments.payment_recall.requested", "payment_recall", recall);
            return recall;
        });
    }

    /** Counterparty answer (camt.029) to our recall; accepted recalls return the funds. */
    public ObjectNode resolve(UUID recallId, boolean accepted, String responseCode, String responseReason) {
        ObjectNode recall = rows.get("payment_recall", recallId);
        if (!"OUTBOUND".equals(str(recall, "direction"))) {
            throw ApiException.unprocessable("not_outbound_recall", "Inbound recalls are accepted or rejected, not resolved");
        }
        if (!Set.of("REQUESTED", "PENDING").contains(str(recall, "status"))) {
            throw ApiException.conflict("invalid_transition", "Recall is already " + str(recall, "status"));
        }
        if (accepted) {
            ObjectNode p = records.get(uuid(recall, "payment_id"));
            creditBack(p, dec(p, "amount"), str(recall, "reason_code"), "Recall accepted", null, recallId);
        }
        return tx.execute(s -> {
            rows.lock("payment_recall", recallId);
            ObjectNode c = rows.object();
            c.put("status", accepted ? "ACCEPTED" : "REJECTED");
            if (responseCode != null) {
                c.put("response_code", PaymentProcessor.truncate(responseCode, 8));
            }
            if (responseReason != null) {
                c.put("response_reason", PaymentProcessor.truncate(responseReason, 255));
            }
            c.put("resolved_at", rows.now().toString());
            ObjectNode r = rows.update("payment_recall", recallId, c);
            events.publish("payments.payment_recall.resolved", "payment_recall", r);
            return r;
        });
    }

    /** camt.056 received for one of our settled inbound payments. */
    public ObjectNode inboundRecall(JsonNode req) {
        String e2e = str(req, "original_end_to_end_id");
        ObjectNode p = rows.queryOne("""
                select row_to_json(t)::text from payment t where end_to_end_id = ? and direction = 'INBOUND'
                order by created_at desc limit 1""", e2e)
                .orElseThrow(() -> ApiException.notFound("inbound payment with end_to_end_id", e2e));
        UUID paymentId = UUID.fromString(str(p, "id"));
        Optional<ObjectNode> open = rows.queryOne("""
                select row_to_json(t)::text from payment_recall t where payment_id = ? and direction = 'INBOUND'
                and status in ('REQUESTED', 'PENDING') limit 1""", paymentId);
        if (open.isPresent()) {
            return open.get();
        }
        return tx.execute(s -> {
            ObjectNode r = rows.object();
            r.put("payment_id", paymentId.toString());
            r.put("direction", "INBOUND");
            r.put("status", "REQUESTED");
            r.put("reason_code", PaymentProcessor.truncate(str(req, "reason_code"), 8));
            if (req.hasNonNull("reason")) {
                r.put("reason", PaymentProcessor.truncate(str(req, "reason"), 255));
            }
            r.put("requested_by", PaymentProcessor.truncate(req.hasNonNull("requested_by") ? str(req, "requested_by")
                    : str(p, "debtor_agent_bic"), 64));
            ObjectNode recall = rows.insert("payment_recall", r);
            events.publish("payments.payment_recall.received", "payment_recall", recall);
            return recall;
        });
    }

    /** Positive answer to an inbound recall: return the funds (pacs.004). */
    public ObjectNode acceptInbound(UUID recallId, String comment) {
        ObjectNode recall = requireInboundOpen(recallId);
        returnInbound(uuid(recall, "payment_id"), str(recall, "reason_code"),
                comment == null ? "Recall accepted" : comment, null, recallId);
        return tx.execute(s -> {
            ObjectNode c = rows.object();
            c.put("status", "ACCEPTED");
            c.put("response_code", "ACCR");
            if (comment != null) {
                c.put("response_reason", PaymentProcessor.truncate(comment, 255));
            }
            c.put("resolved_at", rows.now().toString());
            ObjectNode r = rows.update("payment_recall", recallId, c);
            events.publish("payments.payment_recall.resolved", "payment_recall", r);
            return r;
        });
    }

    /** Negative answer (camt.029 RJCR) to an inbound recall. */
    public ObjectNode rejectInbound(UUID recallId, String responseCode, String responseReason) {
        requireInboundOpen(recallId);
        if (!Set.of("NOOR", "ARDT", "AC04", "AM04", "NOAS", "CUST", "LEGL", "NARR").contains(responseCode)) {
            throw ApiException.unprocessable("invalid_response_code",
                    "response_code must be a camt.029 reason: NOOR, ARDT, AC04, AM04, NOAS, CUST, LEGL or NARR");
        }
        return tx.execute(s -> {
            ObjectNode c = rows.object();
            c.put("status", "REJECTED");
            c.put("response_code", responseCode);
            if (responseReason != null) {
                c.put("response_reason", PaymentProcessor.truncate(responseReason, 255));
            }
            c.put("resolved_at", rows.now().toString());
            ObjectNode r = rows.update("payment_recall", recallId, c);
            events.publish("payments.payment_recall.resolved", "payment_recall", r);
            return r;
        });
    }

    private ObjectNode requireInboundOpen(UUID recallId) {
        ObjectNode recall = rows.get("payment_recall", recallId);
        if (!"INBOUND".equals(str(recall, "direction"))) {
            throw ApiException.unprocessable("not_inbound_recall", "Only recalls received from a rail can be accepted or rejected");
        }
        if (!Set.of("REQUESTED", "PENDING").contains(str(recall, "status"))) {
            throw ApiException.conflict("invalid_transition", "Recall is already " + str(recall, "status"));
        }
        return recall;
    }

    // ------------------------------------------------------------------ refunds

    /** Refund of a settled inbound payment: a new outbound REFUND payment back to the original payer. */
    public ObjectNode refund(UUID paymentId, BigDecimal amount, String reason, String idempotencyKey) {
        ObjectNode p = records.get(paymentId);
        if (!"INBOUND".equals(str(p, "direction")) || !"SETTLED".equals(str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only SETTLED inbound payments can be refunded");
        }
        BigDecimal already = rows.scalar("""
                select coalesce(sum(amount), 0) from payment where original_payment_id = ? and payment_type = 'REFUND'
                and status not in ('REJECTED', 'FAILED', 'CANCELLED')""", BigDecimal.class, paymentId).orElse(BigDecimal.ZERO);
        BigDecimal refundable = dec(p, "amount").subtract(already);
        BigDecimal value = amount == null ? refundable : amount;
        if (value.signum() <= 0 || value.compareTo(refundable) > 0) {
            throw ApiException.unprocessable("invalid_refund_amount", "Refund must be positive and at most " + refundable.toPlainString());
        }
        if (p.get("debtor_account_number") == null || p.get("debtor_account_number").isNull()) {
            throw ApiException.unprocessable("payer_unknown", "The original payer account is unknown; use a return instead");
        }
        ObjectNode req = rows.object();
        req.put("payment_type", "REFUND");
        req.put("direction", "OUTBOUND");
        req.put("channel", "API");
        req.put("debtor_account_id", str(p, "creditor_account_id"));
        req.put("creditor_name", str(p, "debtor_name"));
        req.put("creditor_account_id_type", str(p, "debtor_account_id_type") == null ? "ACCOUNT_NUMBER" : str(p, "debtor_account_id_type"));
        req.put("creditor_account_number", str(p, "debtor_account_number"));
        if (p.hasNonNull("debtor_agent_bic")) {
            req.put("creditor_agent_bic", str(p, "debtor_agent_bic"));
        }
        if (p.hasNonNull("debtor_country")) {
            req.put("creditor_country", str(p, "debtor_country"));
        }
        req.put("amount", value.toPlainString());
        req.put("currency", str(p, "currency"));
        req.put("original_payment_id", paymentId.toString());
        req.put("charge_bearer", "SLEV");
        req.put("remittance_unstructured", PaymentProcessor.truncate("Refund " + str(p, "end_to_end_id")
                + (reason == null ? "" : " " + reason), 140));
        return processor.initiate(req, "refund:" + paymentId + ":" + idempotencyKey, PaymentProcessor.Options.DEFAULT);
    }

    private UUID settlementAccount(ObjectNode p) {
        UUID fromRail = routing.rail(uuid(p, "rail_id")).map(r -> uuid(r, "settlement_account_id")).orElse(null);
        UUID account = fromRail != null ? fromRail : props.gl().clearing();
        if (account == null) {
            throw new ApiException(500, "ledger_account_not_configured", "No rail settlement or clearing account configured");
        }
        return account;
    }
}
