package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.date;
import static io.meridian.payment.infrastructure.Rows.dec;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.ts;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.domain.PaymentLifecycle.Reason;
import io.meridian.payment.infrastructure.CoreBankingClient;
import io.meridian.payment.infrastructure.LedgerClient;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Direct-debit mandates (SEPA Core/B2B, Bacs, ACH, PIX Automático, UPI Autopay...) and their collections. As debtor
 * bank (INBOUND mandate) a collection reserves and captures the customer's funds to the direct-debit clearing
 * account; as creditor (OUTBOUND mandate) it credits the customer from clearing. Debtors may claim a refund within
 * the scheme window (SEPA Core: 8 weeks; not for B2B).
 */
@Service
public class MandateService {
    static final String MANDATE = "payment_mandate";
    static final String COLLECTION = "direct_debit_collection";

    private final Rows rows;
    private final Events events;
    private final PaymentRecords records;
    private final PaymentProcessor processor;
    private final LedgerClient ledger;
    private final CoreBankingClient core;
    private final PaymentProperties props;
    private final TransactionTemplate tx;

    public MandateService(Rows rows, Events events, PaymentRecords records, PaymentProcessor processor,
                          LedgerClient ledger, CoreBankingClient core, PaymentProperties props, TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.records = records;
        this.processor = processor;
        this.ledger = ledger;
        this.core = core;
        this.props = props;
        this.tx = tx;
    }

    // ================================================================== mandates

    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(MANDATE, req);
        validateMandate(v);
        v.put("status", "PENDING");
        return tx.execute(s -> rows.insert(MANDATE, v));
    }

    public void validateMandate(ObjectNode v) {
        String ref = str(v, "mandate_reference");
        if (ref != null && !ref.matches("[A-Za-z0-9+?/\\-:().,' ]{1,35}")) {
            throw ApiException.unprocessable("invalid_mandate_reference", "mandate_reference must be 1-35 SEPA characters");
        }
        LocalDate first = date(v, "first_collection_date");
        LocalDate fin = date(v, "final_collection_date");
        if (first != null && fin != null && fin.isBefore(first)) {
            throw ApiException.unprocessable("invalid_collection_dates", "final_collection_date is before first_collection_date");
        }
        BigDecimal max = dec(v, "max_amount");
        if (max != null && max.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "max_amount must be positive");
        }
        String direction = str(v, "direction");
        if ("INBOUND".equals(direction)) {
            UUID debtor = uuid(v, "debtor_account_id");
            if (debtor == null) {
                throw ApiException.unprocessable("debtor_account_required",
                        "INBOUND mandates (we are the debtor bank) need debtor_account_id");
            }
            JsonNode account = core.depositAccount(debtor, "debtor");
            if (str(v, "currency") != null && !str(v, "currency").equals(str(account, "currency"))) {
                throw ApiException.unprocessable("currency_mismatch", "Mandate currency must be the debtor account currency");
            }
            v.put("customer_id", str(account, "customer_id"));
        } else if ("OUTBOUND".equals(direction)) {
            UUID creditor = uuid(v, "creditor_account_id");
            if (creditor == null) {
                throw ApiException.unprocessable("creditor_account_required",
                        "OUTBOUND mandates (we collect as creditor) need creditor_account_id");
            }
            JsonNode account = core.depositAccount(creditor, "creditor");
            v.put("customer_id", str(account, "customer_id"));
        } else if (direction != null) {
            throw ApiException.unprocessable("invalid_direction", "Mandate direction must be INBOUND or OUTBOUND");
        }
    }

    public ObjectNode activate(UUID id) {
        return tx.execute(s -> {
            ObjectNode m = rows.lock(MANDATE, id);
            require(m, Set.of("PENDING"), "activate");
            ObjectNode c = rows.object();
            c.put("status", "ACTIVE");
            if (!m.hasNonNull("signed_at")) {
                c.put("signed_at", rows.now().toString());
            }
            ObjectNode updated = rows.update(MANDATE, id, c);
            events.publish("payments.payment_mandate.activated", MANDATE, updated);
            return updated;
        });
    }

    public ObjectNode suspend(UUID id, String reason) {
        return change(id, Set.of("ACTIVE"), "SUSPENDED", null, "payments.payment_mandate.suspended", "suspend");
    }

    public ObjectNode resume(UUID id) {
        return change(id, Set.of("SUSPENDED"), "ACTIVE", null, "payments.payment_mandate.resumed", "resume");
    }

    public ObjectNode cancel(UUID id, String reason) {
        return tx.execute(s -> {
            ObjectNode m = rows.lock(MANDATE, id);
            require(m, Set.of("PENDING", "ACTIVE", "SUSPENDED"), "cancel");
            rows.execute("update direct_debit_collection set status = 'CANCELLED' where mandate_id = ? and status = 'SCHEDULED'", id);
            ObjectNode c = rows.object();
            c.put("status", "CANCELLED");
            c.put("cancellation_reason", PaymentProcessor.truncate(reason, 255));
            ObjectNode updated = rows.update(MANDATE, id, c);
            events.publish("payments.payment_mandate.cancelled", MANDATE, updated);
            return updated;
        });
    }

    private ObjectNode change(UUID id, Set<String> from, String to, String reason, String event, String action) {
        return tx.execute(s -> {
            ObjectNode m = rows.lock(MANDATE, id);
            require(m, from, action);
            ObjectNode updated = rows.update(MANDATE, id, rows.object().put("status", to));
            events.publish(event, MANDATE, updated);
            return updated;
        });
    }

    private static void require(ObjectNode row, Set<String> allowed, String action) {
        if (!allowed.contains(str(row, "status"))) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action + " in status " + str(row, "status"));
        }
    }

    // ================================================================== collections

    public ObjectNode schedule(JsonNode req) {
        ObjectNode v = rows.columns(COLLECTION, req);
        UUID mandateId = uuid(v, "mandate_id");
        return tx.execute(s -> {
            ObjectNode m = rows.lock(MANDATE, mandateId);
            if (!"ACTIVE".equals(str(m, "status"))) {
                throw ApiException.unprocessable("mandate_not_active", "Collections need an ACTIVE mandate (status " + str(m, "status") + ")");
            }
            BigDecimal amount = dec(v, "amount");
            if (amount == null || amount.signum() <= 0) {
                throw ApiException.unprocessable("invalid_amount", "amount must be positive");
            }
            if (m.hasNonNull("max_amount") && amount.compareTo(dec(m, "max_amount")) > 0) {
                throw ApiException.unprocessable("amount_exceeds_mandate", "amount exceeds the mandate maximum " + str(m, "max_amount"));
            }
            if (!str(m, "currency").equals(str(v, "currency"))) {
                throw ApiException.unprocessable("currency_mismatch", "Collection currency must be the mandate currency");
            }
            LocalDate due = date(v, "due_date");
            if (due.isBefore(rows.today())) {
                throw ApiException.unprocessable("due_date_in_past", "due_date is in the past");
            }
            LocalDate first = date(m, "first_collection_date");
            LocalDate fin = date(m, "final_collection_date");
            if (first != null && due.isBefore(first) || fin != null && due.isAfter(fin)) {
                throw ApiException.unprocessable("due_date_outside_mandate", "due_date is outside the mandate collection period");
            }
            long previous = rows.scalar("select count(*) from direct_debit_collection where mandate_id = ? and status <> 'CANCELLED'",
                    Long.class, mandateId).orElse(0L);
            String seq;
            if ("ONE_OFF".equals(str(m, "frequency"))) {
                if (previous > 0) {
                    throw ApiException.unprocessable("one_off_mandate_used", "A one-off mandate allows a single collection");
                }
                seq = "OOFF";
            } else if (previous == 0) {
                seq = "FRST";
            } else if (fin != null && due.equals(fin)) {
                seq = "FNAL";
            } else {
                seq = "RCUR";
            }
            v.put("sequence_type", seq);
            v.put("status", "SCHEDULED");
            v.put("pre_notification_sent_at", rows.now().toString());
            return rows.insert(COLLECTION, v);
        });
    }

    /** Presents and settles a due collection. */
    public ObjectNode collect(UUID collectionId) {
        ObjectNode c = rows.get(COLLECTION, collectionId);
        if (!"SCHEDULED".equals(str(c, "status"))) {
            throw ApiException.conflict("invalid_transition", "Collection is " + str(c, "status"));
        }
        if (date(c, "due_date").isAfter(rows.today())) {
            throw ApiException.conflict("collection_not_due", "The collection is due on " + str(c, "due_date"));
        }
        ObjectNode m = rows.get(MANDATE, uuid(c, "mandate_id"));
        if (!"ACTIVE".equals(str(m, "status"))) {
            return fail(collectionId, "MD01", "Mandate is " + str(m, "status"), null);
        }
        BigDecimal amount = dec(c, "amount");
        boolean weAreDebtorBank = "INBOUND".equals(str(m, "direction"));
        UUID clearing = props.gl().directDebitClearing() != null ? props.gl().directDebitClearing() : props.gl().clearing();
        JsonNode account = core.depositAccount(weAreDebtorBank ? uuid(m, "debtor_account_id") : uuid(m, "creditor_account_id"),
                weAreDebtorBank ? "debtor" : "creditor");
        ObjectNode payment = insertPayment(c, m, account, weAreDebtorBank);
        UUID paymentId = UUID.fromString(str(payment, "id"));
        UUID je;
        UUID holdId = null;
        try {
            if (weAreDebtorBank) {
                JsonNode hold = ledger.placeHold("pay-hold:" + paymentId, uuid(account, "ledger_account_id"), amount,
                        str(c, "currency"), "Direct debit " + str(m, "mandate_reference"), paymentId);
                holdId = UUID.fromString(hold.path("id").asString());
                ledger.capture(holdId, amount, clearing, "Direct debit " + str(m, "mandate_reference") + " to "
                        + str(m, "creditor_name"), true, "pay-capture:" + paymentId);
                je = ledger.captureJournalEntry(holdId);
            } else {
                je = ledger.post("pay-credit:" + paymentId, "TRANSFER", "Direct debit collected " + str(m, "mandate_reference"),
                        str(c, "currency"), "PAYMENT", paymentId, List.of(
                                LedgerClient.Leg.debit(clearing, amount, "Collection from " + str(m, "debtor_name")),
                                LedgerClient.Leg.credit(uuid(account, "ledger_account_id"), amount, "Direct debit " + str(m, "mandate_reference"))));
            }
        } catch (ApiException e) {
            if (e.status() == 422) {
                String code = "insufficient_funds".equals(e.code()) ? Reason.INSUFFICIENT_FUNDS : Reason.BLOCKED_ACCOUNT;
                tx.execute(s -> processor.rejectLocked(records.lock(paymentId), code, "Collection failed: " + e.getMessage(), null));
                return fail(collectionId, code, e.getMessage(), paymentId);
            }
            throw e;
        }
        UUID hold = holdId;
        return tx.execute(s -> {
            ObjectNode locked = records.lock(paymentId);
            String now = rows.now().toString();
            ObjectNode pc = rows.object();
            pc.put("submitted_at", now);
            pc.put("accepted_at", now);
            pc.put("settled_at", now);
            pc.put("value_date", rows.today().toString());
            if (hold != null) {
                pc.put("hold_id", hold.toString());
            }
            if (je != null) {
                pc.put("journal_entry_id", je.toString());
            }
            records.transition(locked, "SETTLED", "ACSC", "Direct debit collected", pc, null);
            ObjectNode cc = rows.object();
            cc.put("status", "COLLECTED");
            cc.put("collected_at", now);
            cc.put("payment_id", paymentId.toString());
            ObjectNode collected = rows.update(COLLECTION, collectionId, cc);
            ObjectNode mc = rows.object();
            mc.put("last_collected_at", now);
            if (Set.of("OOFF", "FNAL").contains(str(c, "sequence_type"))) {
                mc.put("status", "EXPIRED");
            }
            rows.update(MANDATE, uuid(m, "id"), mc);
            events.publish("payments.direct_debit_collection.collected", COLLECTION, collected);
            return collected;
        });
    }

    private ObjectNode insertPayment(ObjectNode c, ObjectNode m, JsonNode account, boolean weAreDebtorBank) {
        return tx.execute(s -> {
            ObjectNode v = rows.object();
            v.put("payment_type", "DIRECT_DEBIT");
            v.put("direction", weAreDebtorBank ? "OUTBOUND" : "INBOUND");
            v.put("channel", "SCHEDULER");
            v.put("status", "INITIATED");
            v.put("idempotency_key", "ddc:" + str(c, "id"));
            v.put("customer_id", str(account, "customer_id"));
            v.put("mandate_id", str(m, "id"));
            v.put("amount", dec(c, "amount").toPlainString());
            v.put("currency", str(c, "currency"));
            v.put("end_to_end_id", PaymentProcessor.truncate(str(m, "mandate_reference") + "-" + str(c, "sequence_type")
                    + "-" + str(c, "due_date").replace("-", ""), 35));
            v.put("uetr", UUID.randomUUID().toString());
            v.put("instruction_id", PaymentProcessor.reference("DD"));
            v.put("charge_bearer", "SLEV");
            v.put("debtor_name", str(m, "debtor_name"));
            v.put("debtor_account_number", str(m, "debtor_account_number"));
            if (m.hasNonNull("debtor_agent_bic")) {
                v.put("debtor_agent_bic", str(m, "debtor_agent_bic"));
            }
            v.put("creditor_name", str(m, "creditor_name"));
            if (weAreDebtorBank) {
                v.put("debtor_account_id", str(m, "debtor_account_id"));
                v.put("debtor_ledger_account_id", str(account, "ledger_account_id"));
                v.put("creditor_account_id_type", "PROXY");
                v.put("creditor_account_number", str(m, "creditor_id"));
            } else {
                v.put("creditor_account_id", str(m, "creditor_account_id"));
                String iban = str(account, "iban");
                v.put("creditor_account_id_type", iban != null ? "IBAN" : "ACCOUNT_NUMBER");
                v.put("creditor_account_number", iban != null ? iban : str(account, "account_number"));
            }
            v.put("remittance_unstructured", PaymentProcessor.truncate("Mandate " + str(m, "mandate_reference")
                    + " " + str(m, "creditor_id"), 140));
            v.put("initiated_by", PaymentProcessor.truncate(TenantContext.principal(), 64));
            ObjectNode p = rows.insert("payment", v);
            UUID id = UUID.fromString(str(p, "id"));
            records.history(id, null, "INITIATED", null, "Direct debit collection " + str(c, "sequence_type"), null);
            events.publish("payments.payment.initiated", "payment", p);
            return records.transition(records.lock(id), "APPROVED", null, "Authorised by mandate " + str(m, "mandate_reference"),
                    null, null);
        });
    }

    private ObjectNode fail(UUID collectionId, String code, String reason, UUID paymentId) {
        return tx.execute(s -> {
            ObjectNode cc = rows.object();
            cc.put("status", "FAILED");
            if (code != null) {
                cc.put("return_reason_code", code);
            }
            cc.put("failure_reason", PaymentProcessor.truncate(reason, 255));
            if (paymentId != null) {
                cc.put("payment_id", paymentId.toString());
            }
            ObjectNode failed = rows.update(COLLECTION, collectionId, cc);
            events.publish("payments.direct_debit_collection.failed", COLLECTION, failed);
            return failed;
        });
    }

    public ObjectNode cancelCollection(UUID collectionId, String reason) {
        return tx.execute(s -> {
            ObjectNode c = rows.lock(COLLECTION, collectionId);
            if (!"SCHEDULED".equals(str(c, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only SCHEDULED collections can be cancelled");
            }
            ObjectNode cc = rows.object();
            cc.put("status", "CANCELLED");
            if (reason != null) {
                cc.put("failure_reason", PaymentProcessor.truncate(reason, 255));
            }
            ObjectNode r = rows.update(COLLECTION, collectionId, cc);
            events.publish("payments.direct_debit_collection.cancelled", COLLECTION, r);
            return r;
        });
    }

    /** Debtor's right to refund: reverses the collection posting within the scheme window. */
    public ObjectNode refund(UUID collectionId, String reasonCode) {
        ObjectNode c = rows.get(COLLECTION, collectionId);
        if (!"COLLECTED".equals(str(c, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only COLLECTED direct debits can be refunded");
        }
        ObjectNode m = rows.get(MANDATE, uuid(c, "mandate_id"));
        if (Set.of("SEPA_B2B").contains(str(m, "scheme"))) {
            throw ApiException.unprocessable("refund_not_allowed", "The " + str(m, "scheme") + " scheme has no refund right");
        }
        if (ts(c, "collected_at").plusDays(props.refundDays()).isBefore(rows.now())) {
            throw ApiException.unprocessable("refund_window_closed", "Refunds must be claimed within " + props.refundDays() + " days");
        }
        UUID paymentId = uuid(c, "payment_id");
        ObjectNode p = records.get(paymentId);
        UUID je = ledger.reverse(uuid(p, "journal_entry_id"), "Direct debit refund " + reasonCode, "ddc-refund:" + collectionId);
        return tx.execute(s -> {
            ObjectNode r = rows.object();
            r.put("payment_id", paymentId.toString());
            r.put("direction", "INBOUND".equals(str(m, "direction")) ? "OUTBOUND" : "INBOUND");
            r.put("status", "COMPLETED");
            r.put("reason_code", PaymentProcessor.truncate(reasonCode, 8));
            r.put("reason", "Direct debit refund");
            r.put("amount", dec(c, "amount").toPlainString());
            r.put("currency", str(c, "currency"));
            r.put("fee_amount", "0");
            r.put("return_id", PaymentProcessor.reference("DDR"));
            r.put("journal_entry_id", je.toString());
            r.put("completed_at", rows.now().toString());
            ObjectNode ret = rows.insert("payment_return", r);
            records.transition(records.lock(paymentId), "RETURNED", reasonCode, "Direct debit refunded", null, null);
            ObjectNode cc = rows.object();
            cc.put("status", "RETURNED");
            cc.put("return_reason_code", PaymentProcessor.truncate(reasonCode, 8));
            ObjectNode returned = rows.update(COLLECTION, collectionId, cc);
            events.publish("payments.payment_return.completed", "payment_return", ret);
            events.publish("payments.direct_debit_collection.failed", COLLECTION, returned);
            return ret;
        });
    }
}
