package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.bool;
import static io.meridian.payment.infrastructure.Rows.date;
import static io.meridian.payment.infrastructure.Rows.dec;
import static io.meridian.payment.infrastructure.Rows.integer;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.domain.Bic;
import io.meridian.payment.domain.BulkFileParser;
import io.meridian.payment.domain.Iban;
import io.meridian.payment.infrastructure.CoreBankingClient;
import io.meridian.payment.infrastructure.LedgerClient;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bulk payments (payroll, supplier runs): created from JSON items or uploaded CSV / pain.001 files, validated per
 * item, submitted for maker-checker approval, executed with one batch hold (batch booking) or per-item holds, and
 * completed as items settle. Report: per-item CSV.
 */
@Service
public class BulkService {
    static final String TABLE = "bulk_payment";
    static final String ITEMS = "bulk_payment_item";

    private final Rows rows;
    private final Events events;
    private final PaymentProcessor processor;
    private final PaymentHooks hooks;
    private final CoreBankingClient core;
    private final LedgerClient ledger;
    private final PaymentProperties props;
    private final TransactionTemplate tx;
    private final PaymentApprovalRequests central;

    public BulkService(Rows rows, Events events, PaymentProcessor processor, PaymentHooks hooks,
                       CoreBankingClient core, LedgerClient ledger, PaymentProperties props, TransactionTemplate tx,
                       PaymentApprovalRequests central) {
        this.central = central;
        this.rows = rows;
        this.events = events;
        this.processor = processor;
        this.hooks = hooks;
        this.core = core;
        this.ledger = ledger;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ create / upload

    /** JSON create with nested items. */
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        if (!req.path("items").isArray() || req.path("items").isEmpty()) {
            throw ApiException.unprocessable("items_required", "A bulk payment needs at least one item");
        }
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode item : req.get("items")) {
            items.add(rows.columns(ITEMS, item));
        }
        return persist(v, items);
    }

    /** File upload (CSV or pain.001) parsed into items. */
    public ObjectNode upload(JsonNode req) {
        String format = str(req, "file_format");
        BulkFileParser.Parsed parsed;
        try {
            parsed = BulkFileParser.parse(format, str(req, "content"));
        } catch (BulkFileParser.FileFormatException e) {
            throw ApiException.unprocessable(e.code(), e.getMessage());
        }
        ObjectNode v = rows.object();
        for (String f : List.of("customer_id", "debtor_account_id", "file_format", "document_id", "currency",
                "requested_execution_date", "batch_booking")) {
            if (req.hasNonNull(f)) {
                v.set(f, req.get(f));
            }
        }
        v.put("name", req.hasNonNull("name") ? str(req, "name")
                : (parsed.messageId() != null ? "pain.001 " + parsed.messageId() : format + " upload"));
        if (!v.hasNonNull("requested_execution_date") && parsed.requestedExecutionDate() != null) {
            v.put("requested_execution_date", parsed.requestedExecutionDate());
        }
        if (parsed.categoryPurpose() != null) {
            v.put("category_purpose", parsed.categoryPurpose());
        }
        if (!v.hasNonNull("batch_booking") && parsed.batchBooking() != null) {
            v.put("batch_booking", parsed.batchBooking());
        }
        if (parsed.controlSum() != null) {
            v.put("control_sum", parsed.controlSum().toPlainString());
        }
        List<ObjectNode> items = new ArrayList<>();
        for (BulkFileParser.Line l : parsed.lines()) {
            ObjectNode i = rows.object();
            i.put("creditor_name", l.creditorName() == null ? "" : PaymentProcessor.truncate(l.creditorName(), 140));
            String idType = l.accountIdType();
            if (idType == null && l.accountNumber() != null) {
                idType = l.accountNumber().replace(" ", "").matches("[A-Za-z]{2}[0-9]{2}.*") ? "IBAN" : "ACCOUNT_NUMBER";
            }
            i.put("creditor_account_id_type", idType == null ? "ACCOUNT_NUMBER" : idType.toUpperCase());
            i.put("creditor_account_number", l.accountNumber() == null ? "" : PaymentProcessor.truncate(l.accountNumber(), 64));
            putIf(i, "creditor_agent_bic", l.bic());
            putIf(i, "creditor_agent_clearing_id", l.clearingId());
            putIf(i, "end_to_end_id", l.endToEndId());
            putIf(i, "remittance_unstructured", l.remittance() == null ? null : PaymentProcessor.truncate(l.remittance(), 140));
            i.put("amount", l.amount() == null ? "" : l.amount().trim());
            putIf(i, "currency", l.currency());
            items.add(i);
        }
        return persist(v, items);
    }

    private ObjectNode persist(ObjectNode v, List<ObjectNode> items) {
        UUID debtorAccountId = uuid(v, "debtor_account_id");
        JsonNode account = core.depositAccount(debtorAccountId, "debtor");
        if (!uuid(account, "customer_id").equals(uuid(v, "customer_id"))) {
            throw ApiException.unprocessable("customer_account_mismatch", "The debtor account belongs to another customer");
        }
        if (Set.of("CLOSED", "BLOCKED", "BLOCKED_DEBIT", "FROZEN").contains(str(account, "status"))) {
            throw ApiException.unprocessable("debtor_account_not_active", "The debtor account is " + str(account, "status"));
        }
        String currency = v.hasNonNull("currency") ? str(v, "currency") : str(account, "currency");
        if (!currency.equals(str(account, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The batch currency must be the debtor account currency");
        }
        v.put("currency", currency);
        LocalDate execution = date(v, "requested_execution_date");
        if (execution != null && execution.isBefore(rows.today())) {
            throw ApiException.unprocessable("execution_date_in_past", "requested_execution_date is in the past");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (ObjectNode i : items) {
            BigDecimal a = parseAmount(str(i, "amount"));
            if (a != null) {
                total = total.add(a);
            }
        }
        if (v.hasNonNull("control_sum") && dec(v, "control_sum").compareTo(total) != 0) {
            throw ApiException.unprocessable("control_sum_mismatch", "control_sum " + str(v, "control_sum")
                    + " does not equal the sum of the items " + total.toPlainString());
        }
        BigDecimal sum = total;
        return tx.execute(s -> {
            ObjectNode b = rows.object();
            b.setAll(v);
            b.put("status", "UPLOADED");
            b.put("item_count", items.size());
            b.put("total_amount", sum.toPlainString());
            ObjectNode bulk = rows.insert(TABLE, b);
            UUID bulkId = UUID.fromString(str(bulk, "id"));
            int line = 0;
            for (ObjectNode i : items) {
                ObjectNode row = i.deepCopy();
                row.put("bulk_payment_id", bulkId.toString());
                row.put("line_no", ++line);
                BigDecimal a = parseAmount(str(i, "amount"));
                row.put("amount", a == null ? "0" : a.toPlainString());
                if (!row.hasNonNull("currency")) {
                    row.put("currency", currency);
                }
                if (!row.hasNonNull("creditor_name")) {
                    row.put("creditor_name", "");
                }
                if (!row.hasNonNull("creditor_account_id_type")) {
                    row.put("creditor_account_id_type", "ACCOUNT_NUMBER");
                }
                if (!row.hasNonNull("creditor_account_number")) {
                    row.put("creditor_account_number", "");
                }
                row.put("status", "PENDING");
                if (a == null) {
                    row.set("validation_errors", errors(error("amount", "invalid_amount", "Amount is missing or not a number")));
                }
                rows.insert(ITEMS, row);
            }
            return validateLocked(bulkId);
        });
    }

    // ------------------------------------------------------------------ validation

    public ObjectNode validate(UUID bulkId) {
        return tx.execute(s -> {
            ObjectNode b = rows.lock(TABLE, bulkId);
            if (!Set.of("UPLOADED", "VALIDATED").contains(str(b, "status"))) {
                throw ApiException.conflict("invalid_transition", "Bulk payment in status " + str(b, "status")
                        + " cannot be validated");
            }
            return validateLocked(bulkId);
        });
    }

    private ObjectNode validateLocked(UUID bulkId) {
        ObjectNode bulk = rows.get(TABLE, bulkId);
        String currency = str(bulk, "currency");
        List<ObjectNode> items = rows.query("select row_to_json(t)::text from bulk_payment_item t where bulk_payment_id = ? "
                + "and status in ('PENDING', 'VALID', 'INVALID') order by line_no", bulkId);
        Set<String> e2e = new HashSet<>();
        int valid = 0;
        int failed = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (ObjectNode i : items) {
            ArrayNode errs = rows.array();
            String name = str(i, "creditor_name");
            if (name == null || name.isBlank()) {
                errs.add(error("creditor_name", "required", "Creditor name is required"));
            }
            String type = str(i, "creditor_account_id_type");
            String number = str(i, "creditor_account_number");
            if (number == null || number.isBlank()) {
                errs.add(error("creditor_account_number", "required", "Creditor account is required"));
            } else if ("IBAN".equals(type)) {
                Iban.validate(number).ifPresent(code -> errs.add(error("creditor_account_number", code, "Invalid IBAN")));
            } else if (!number.matches("[A-Za-z0-9\\-]{4,34}")) {
                errs.add(error("creditor_account_number", "invalid_account_number", "Account number must be 4-34 letters or digits"));
            }
            String bic = str(i, "creditor_agent_bic");
            if (bic != null && !Bic.isValid(bic)) {
                errs.add(error("creditor_agent_bic", "invalid_bic", "BIC " + bic + " is malformed"));
            }
            BigDecimal amount = dec(i, "amount");
            if (amount == null || amount.signum() <= 0) {
                if (!i.path("validation_errors").isArray() || i.get("validation_errors").isEmpty()) {
                    errs.add(error("amount", "invalid_amount", "Amount must be positive"));
                } else {
                    errs.addAll((ArrayNode) i.get("validation_errors"));
                }
            } else if (amount.stripTrailingZeros().scale() > java.util.Currency.getInstance(currency).getDefaultFractionDigits()) {
                errs.add(error("amount", "invalid_amount_precision", "Too many decimals for " + currency));
            }
            if (!currency.equals(str(i, "currency"))) {
                errs.add(error("currency", "currency_mismatch", "Item currency must be the batch currency " + currency));
            }
            String ref = str(i, "end_to_end_id");
            if (ref != null && !e2e.add(ref)) {
                errs.add(error("end_to_end_id", "duplicate_end_to_end_id", "End-to-end id " + ref + " repeats in the batch"));
            }
            ObjectNode c = rows.object();
            c.put("status", errs.isEmpty() ? "VALID" : "INVALID");
            c.set("validation_errors", errs);
            rows.update(ITEMS, UUID.fromString(str(i, "id")), c);
            if (errs.isEmpty()) {
                valid++;
                total = total.add(amount);
            } else {
                failed++;
            }
        }
        ObjectNode c = rows.object();
        c.put("status", "VALIDATED");
        c.put("valid_count", valid);
        c.put("failed_count", failed);
        ObjectNode updated = rows.update(TABLE, bulkId, c);
        events.publish("payments.bulk_payment.validated", TABLE, updated);
        return updated;
    }

    /** Item correction before submission; the batch must be validated again. */
    public ObjectNode updateItem(UUID itemId, JsonNode req) {
        return tx.execute(s -> {
            ObjectNode item = rows.lock(ITEMS, itemId);
            ObjectNode bulk = rows.lock(TABLE, uuid(item, "bulk_payment_id"));
            if (!Set.of("UPLOADED", "VALIDATED").contains(str(bulk, "status"))) {
                throw ApiException.conflict("bulk_not_editable", "Items can only be corrected before submission");
            }
            ObjectNode c = rows.columns(ITEMS, req);
            for (String ro : List.of("bulk_payment_id", "line_no", "status", "validation_errors", "payment_id")) {
                c.remove(ro);
            }
            c.put("status", "PENDING");
            ObjectNode updated = rows.update(ITEMS, itemId, c);
            BigDecimal total = rows.scalar("select coalesce(sum(amount), 0) from bulk_payment_item where bulk_payment_id = ?",
                    BigDecimal.class, uuid(item, "bulk_payment_id")).orElse(BigDecimal.ZERO);
            ObjectNode b = rows.object();
            b.put("status", "UPLOADED");
            b.put("total_amount", total.toPlainString());
            rows.update(TABLE, uuid(item, "bulk_payment_id"), b);
            return updated;
        });
    }

    // ------------------------------------------------------------------ submit / approve / execute

    public ObjectNode submit(UUID bulkId) {
        return tx.execute(s -> {
            ObjectNode b = rows.lock(TABLE, bulkId);
            if (!"VALIDATED".equals(str(b, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only a VALIDATED bulk payment can be submitted (status "
                        + str(b, "status") + ")");
            }
            if (integer(b, "valid_count") == 0) {
                throw ApiException.unprocessable("no_valid_items", "The bulk payment has no valid items");
            }
            BigDecimal validTotal = validTotal(bulkId);
            var steps = processor.approvalSteps(validTotal, props.approval().bulkMinApprovals());
            processor.createApprovalSteps(null, bulkId, steps);
            ObjectNode c = rows.object();
            c.put("status", "PENDING_APPROVAL");
            c.put("submitted_by", TenantContext.principal());
            c.put("submitted_at", rows.now().toString());
            ObjectNode updated = rows.update(TABLE, bulkId, c);
            events.publish("payments.bulk_payment.submitted", TABLE, updated);
            // Central maker-checker: checkers decide in the workflow inbox; BulkPaymentApprovalHandler applies it.
            central.requestBulk(updated);
            return updated;
        });
    }

    private BigDecimal validTotal(UUID bulkId) {
        return rows.scalar("select coalesce(sum(amount), 0) from bulk_payment_item where bulk_payment_id = ? and status = 'VALID'",
                BigDecimal.class, bulkId).orElse(BigDecimal.ZERO);
    }

    /** All approval levels approved: reserve funds and create one payment per valid item. */
    public ObjectNode approvalsComplete(UUID bulkId) {
        ObjectNode b = tx.execute(s -> {
            ObjectNode bulk = rows.lock(TABLE, bulkId);
            if (!"PENDING_APPROVAL".equals(str(bulk, "status"))) {
                throw ApiException.conflict("invalid_transition", "Bulk payment is not pending approval");
            }
            ObjectNode approved = rows.update(TABLE, bulkId, rows.object().put("status", "APPROVED"));
            events.publish("payments.bulk_payment.approved", TABLE, approved);
            return approved;
        });
        return execute(UUID.fromString(str(b, "id")));
    }

    private ObjectNode execute(UUID bulkId) {
        ObjectNode bulk = rows.get(TABLE, bulkId);
        BigDecimal total = validTotal(bulkId);
        JsonNode account = core.depositAccount(uuid(bulk, "debtor_account_id"), "debtor");
        UUID holdId = null;
        if (bool(bulk, "batch_booking")) {
            try {
                JsonNode hold = ledger.placeHold("blk-hold:" + bulkId, uuid(account, "ledger_account_id"), total,
                        str(bulk, "currency"), "Bulk payment " + str(bulk, "name"), bulkId);
                holdId = UUID.fromString(hold.path("id").asString());
            } catch (ApiException e) {
                if (e.status() == 422) {
                    return tx.execute(s -> {
                        ObjectNode c = rows.object();
                        c.put("status", "REJECTED");
                        c.put("rejection_reason", "Funds reservation failed: " + PaymentProcessor.truncate(e.getMessage(), 200));
                        ObjectNode r = rows.update(TABLE, bulkId, c);
                        events.publish("payments.bulk_payment.cancelled", TABLE, r);
                        return r;
                    });
                }
                throw e;
            }
        }
        UUID hold = holdId;
        tx.execute(s -> {
            ObjectNode c = rows.object();
            c.put("status", "PROCESSING");
            if (hold != null) {
                c.put("hold_id", hold.toString());
            }
            return rows.update(TABLE, bulkId, c);
        });
        List<ObjectNode> items = rows.query("select row_to_json(t)::text from bulk_payment_item t where bulk_payment_id = ? "
                + "and status = 'VALID' order by line_no", bulkId);
        for (ObjectNode item : items) {
            UUID itemId = UUID.fromString(str(item, "id"));
            ObjectNode req = rows.object();
            req.put("payment_type", "SALA".equals(str(bulk, "category_purpose")) ? "PAYROLL" : "BULK_ITEM");
            req.put("direction", "OUTBOUND");
            req.put("channel", "BULK_FILE");
            req.put("customer_id", str(bulk, "customer_id"));
            req.put("debtor_account_id", str(bulk, "debtor_account_id"));
            req.put("bulk_payment_id", bulkId.toString());
            for (String f : List.of("creditor_name", "creditor_account_id_type", "creditor_account_number",
                    "creditor_agent_bic", "creditor_agent_clearing_id", "amount", "currency", "remittance_unstructured")) {
                if (item.hasNonNull(f)) {
                    req.set(f, item.get(f));
                }
            }
            if (bulk.hasNonNull("category_purpose")) {
                req.put("category_purpose", str(bulk, "category_purpose"));
            }
            if (bulk.hasNonNull("requested_execution_date")) {
                req.put("requested_execution_date", str(bulk, "requested_execution_date"));
            }
            req.put("charge_bearer", "SLEV");
            ObjectNode payment;
            try {
                payment = processor.initiate(req, "blk:" + bulkId + ":" + itemId,
                        new PaymentProcessor.Options(true, hold, str(item, "end_to_end_id"), false));
            } catch (ApiException e) {
                if (e.status() >= 500) {
                    throw e;
                }
                tx.execute(s -> {
                    ObjectNode c = rows.object();
                    c.put("status", "FAILED");
                    c.set("validation_errors", errors(error("payment", e.code(), e.getMessage())));
                    return rows.update(ITEMS, itemId, c);
                });
                continue;
            }
            String ps = str(payment, "status");
            tx.execute(s -> {
                ObjectNode c = rows.object();
                c.put("payment_id", str(payment, "id"));
                c.put("end_to_end_id", str(payment, "end_to_end_id"));
                c.put("status", switch (ps) {
                    case "SETTLED" -> "SETTLED";
                    case "REJECTED", "FAILED" -> "FAILED";
                    case "CANCELLED" -> "CANCELLED";
                    default -> "SUBMITTED";
                });
                return rows.update(ITEMS, itemId, c);
            });
        }
        return tx.execute(s -> {
            rows.update(TABLE, bulkId, rows.object().put("submitted_at", rows.now().toString()));
            return hooks.completeIfDone(bulkId).orElseGet(() -> rows.get(TABLE, bulkId));
        });
    }

    public ObjectNode rejectLocked(UUID bulkId, String reason) {
        ObjectNode c = rows.object();
        c.put("status", "REJECTED");
        c.put("rejection_reason", PaymentProcessor.truncate(reason, 255));
        ObjectNode r = rows.update(TABLE, bulkId, c);
        events.publish("payments.bulk_payment.cancelled", TABLE, r);
        return r;
    }

    public ObjectNode reject(UUID bulkId, String reason) {
        return tx.execute(s -> {
            ObjectNode b = rows.lock(TABLE, bulkId);
            if (!"PENDING_APPROVAL".equals(str(b, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only a bulk payment pending approval can be rejected");
            }
            rows.execute("update payment_approval set status = 'REJECTED', decided_at = now(), comment = ? "
                    + "where bulk_payment_id = ? and status = 'PENDING'", PaymentProcessor.truncate(reason, 500), bulkId);
            central.closed(PaymentApprovalRequests.BULK, bulkId, "rejected directly");
            return rejectLocked(bulkId, reason);
        });
    }

    public ObjectNode cancel(UUID bulkId, String reason) {
        return tx.execute(s -> {
            ObjectNode b = rows.lock(TABLE, bulkId);
            if (!Set.of("UPLOADED", "VALIDATING", "VALIDATED", "PENDING_APPROVAL").contains(str(b, "status"))) {
                throw ApiException.conflict("invalid_transition", "Bulk payment in status " + str(b, "status") + " cannot be cancelled");
            }
            rows.execute("update payment_approval set status = 'REJECTED', decided_at = now(), comment = 'Bulk payment cancelled' "
                    + "where bulk_payment_id = ? and status = 'PENDING'", bulkId);
            rows.execute("update bulk_payment_item set status = 'CANCELLED' where bulk_payment_id = ? and status in ('PENDING', 'VALID')",
                    bulkId);
            ObjectNode c = rows.object();
            c.put("status", "CANCELLED");
            c.put("rejection_reason", PaymentProcessor.truncate(reason == null ? "Cancelled" : reason, 255));
            ObjectNode r = rows.update(TABLE, bulkId, c);
            events.publish("payments.bulk_payment.cancelled", TABLE, r);
            if ("PENDING_APPROVAL".equals(str(b, "status"))) {
                central.closed(PaymentApprovalRequests.BULK, bulkId, "bulk payment cancelled");
            }
            return r;
        });
    }

    // ------------------------------------------------------------------ report

    /** Per-item execution report (CSV, pain.002-like status per line). */
    public String report(UUID bulkId) {
        ObjectNode bulk = rows.get(TABLE, bulkId);
        StringBuilder csv = new StringBuilder(
                "line_no,end_to_end_id,creditor_name,creditor_account,amount,currency,item_status,payment_id,payment_status,reason\n");
        List<ObjectNode> items = rows.query("""
                select json_build_object('i', row_to_json(i), 'p', row_to_json(p))::text
                from bulk_payment_item i left join payment p on p.id = i.payment_id
                where i.bulk_payment_id = ? order by i.line_no""", bulkId);
        for (ObjectNode row : items) {
            JsonNode i = row.get("i");
            JsonNode p = row.get("p");
            String reason = "";
            if (i.path("validation_errors").isArray() && !i.get("validation_errors").isEmpty()) {
                List<String> codes = new ArrayList<>();
                i.get("validation_errors").forEach(e -> codes.add(e.path("code").asString()));
                reason = String.join(";", codes);
            } else if (p != null && !p.isNull() && p.hasNonNull("rejection_code")) {
                reason = p.get("rejection_code").asString();
            }
            csv.append(i.path("line_no").asInt()).append(',')
                    .append(cell(str(i, "end_to_end_id"))).append(',')
                    .append(cell(str(i, "creditor_name"))).append(',')
                    .append(cell(mask(str(i, "creditor_account_number")))).append(',')
                    .append(dec(i, "amount").toPlainString()).append(',')
                    .append(str(i, "currency")).append(',')
                    .append(str(i, "status")).append(',')
                    .append(p == null || p.isNull() ? "" : str(p, "id")).append(',')
                    .append(p == null || p.isNull() ? "" : str(p, "status")).append(',')
                    .append(cell(reason)).append('\n');
        }
        csv.append("# bulk_payment=").append(bulkId).append(", status=").append(str(bulk, "status"))
                .append(", items=").append(integer(bulk, "item_count")).append(", valid=").append(integer(bulk, "valid_count"))
                .append(", settled=").append(integer(bulk, "settled_count")).append(", total=")
                .append(dec(bulk, "total_amount").toPlainString()).append(' ').append(str(bulk, "currency")).append('\n');
        return csv.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String mask(String account) {
        if (account == null || account.length() < 6) {
            return account;
        }
        return account.substring(0, 4) + "*".repeat(account.length() - 8 > 0 ? account.length() - 8 : 1)
                + account.substring(account.length() - 4);
    }

    private static String cell(String s) {
        if (s == null) {
            return "";
        }
        String v = s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@") ? "'" + s : s;
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ObjectNode error(String field, String code, String message) {
        ObjectNode e = rows.object();
        e.put("field", field);
        e.put("code", code);
        e.put("message", message);
        return e;
    }

    private ArrayNode errors(ObjectNode... e) {
        ArrayNode a = rows.array();
        for (ObjectNode x : e) {
            a.add(x);
        }
        return a;
    }

    private static void putIf(ObjectNode n, String f, String v) {
        if (v != null && !v.isBlank()) {
            n.put(f, v.trim());
        }
    }

}
