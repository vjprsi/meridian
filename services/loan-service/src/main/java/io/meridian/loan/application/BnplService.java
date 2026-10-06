package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.dec;
import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loan.config.LoanProperties;
import io.meridian.loan.domain.Currencies;
import io.meridian.loan.domain.Frequency;
import io.meridian.loan.domain.Lifecycles;
import io.meridian.loan.infrastructure.ExternalClients;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Buy-now-pay-later: real-time eligibility at checkout, plan creation (a BNPL loan with an equal split schedule, the
 * merchant settled net of the merchant discount), merchant refunds (reduce the last instalments, refund any excess)
 * and cancellation before the first payment.
 */
@Service
public class BnplService {
    static final String TABLE = "bnpl_plan";

    private final LoanSupport loans;
    private final Rows rows;
    private final LoanService loanService;
    private final DisbursementService disbursements;
    private final RepaymentService repayments;
    private final ExternalClients external;

    public BnplService(LoanSupport loans, LoanService loanService, DisbursementService disbursements,
                       RepaymentService repayments, ExternalClients external) {
        this.loans = loans;
        this.rows = loans.rows();
        this.loanService = loanService;
        this.disbursements = disbursements;
        this.repayments = repayments;
        this.external = external;
    }

    /** Equal instalments rounded to the minor unit; the last one absorbs the rounding. */
    public static List<BigDecimal> split(BigDecimal amount, int count, int scale) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
        BigDecimal each = amount.divide(BigDecimal.valueOf(count), scale, RoundingMode.DOWN);
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < count - 1; i++) {
            out.add(each);
            sum = sum.add(each);
        }
        out.add(amount.subtract(sum).setScale(scale, RoundingMode.HALF_EVEN));
        return out;
    }

    public ObjectNode eligibility(JsonNode req) {
        LoanProperties.Bnpl policy = loans.props().bnpl();
        UUID customer = uuid(req, "customer_id");
        BigDecimal amount = dec(req, "amount");
        String currency = str(req, "currency");
        int count = req.hasNonNull("installment_count") ? req.get("installment_count").asInt() : policy.defaultInstallments();
        int scale = Currencies.digits(currency);
        ArrayNode reasons = rows.json().createArrayNode();
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        if (count < 2 || count > policy.maxInstallments()) {
            reasons.addObject().put("code", "INSTALLMENT_COUNT_OUT_OF_RANGE").put("detail", "2-" + policy.maxInstallments() + " instalments");
        }
        if (amount.compareTo(policy.minAmount()) < 0) {
            reasons.addObject().put("code", "AMOUNT_BELOW_MINIMUM").put("detail", "minimum " + policy.minAmount().toPlainString());
        }
        if (amount.compareTo(policy.maxAmount()) > 0) {
            reasons.addObject().put("code", "AMOUNT_ABOVE_MAXIMUM").put("detail", "maximum " + policy.maxAmount().toPlainString());
        }
        ObjectNode exposure = rows.queryOne("""
                select json_build_object('bnpl', coalesce(sum(case when loan_type = 'BNPL' then total_outstanding end), 0),
                  'arrears', count(*) filter (where status = 'IN_ARREARS' or days_past_due > 0),
                  'written_off', count(*) filter (where status in ('WRITTEN_OFF','CHARGED_OFF')))::text
                from loan where customer_id = ? and status in ('ACTIVE','IN_ARREARS','RESTRUCTURED','WRITTEN_OFF','CHARGED_OFF')""", customer)
                .orElseThrow();
        BigDecimal open = decOr0(exposure, "bnpl");
        if (exposure.path("arrears").asInt(0) > 0) {
            reasons.addObject().put("code", "CUSTOMER_IN_ARREARS").put("detail", "the customer has loans in arrears");
        }
        if (exposure.path("written_off").asInt(0) > 0) {
            reasons.addObject().put("code", "PRIOR_WRITE_OFF").put("detail", "the customer has written-off loans");
        }
        BigDecimal remaining = policy.maxExposure().subtract(open).max(BigDecimal.ZERO);
        if (amount.compareTo(remaining) > 0) {
            reasons.addObject().put("code", "EXPOSURE_LIMIT").put("detail", "remaining BNPL limit " + remaining.toPlainString());
        }
        ObjectNode out = rows.object();
        boolean eligible = reasons.isEmpty();
        out.put("eligible", eligible);
        out.put("decision", eligible ? "APPROVED" : "DECLINED");
        out.put("customer_id", customer.toString());
        out.put("merchant_id", str(req, "merchant_id"));
        out.put("amount", amount.toPlainString());
        out.put("currency", currency);
        out.put("installment_count", count);
        out.put("open_bnpl_exposure", open.toPlainString());
        out.put("remaining_limit", remaining.toPlainString());
        out.set("reason_codes", reasons);
        if (count >= 2 && count <= policy.maxInstallments()) {
            ArrayNode plan = out.putArray("split");
            LocalDate d = loans.today();
            List<BigDecimal> parts = split(amount.setScale(scale, RoundingMode.HALF_EVEN), count, scale);
            for (int i = 0; i < parts.size(); i++) {
                d = Frequency.FORTNIGHTLY.nth(loans.today(), i + 1);
                plan.addObject().put("installment_no", i + 1).put("due_date", d.toString()).put("amount", parts.get(i).toPlainString());
            }
        }
        return out;
    }

    @Transactional
    public ObjectNode create(JsonNode req, String idempotencyKey) {
        ObjectNode values = rows.columns(TABLE, req);
        String currency = str(values, "currency");
        int scale = Currencies.digits(currency);
        BigDecimal purchase = dec(values, "purchase_amount");
        BigDecimal down = decOr0(values, "down_payment");
        if (purchase == null || purchase.signum() <= 0 || down.signum() < 0 || down.compareTo(purchase) >= 0
                || !Currencies.fitsMinorUnit(purchase, currency) || !Currencies.fitsMinorUnit(down, currency)) {
            throw ApiException.unprocessable("invalid_amount", "purchase_amount must be positive and the down payment below it");
        }
        BigDecimal financed = purchase.subtract(down);
        int count = values.path("installment_count").asInt(0);
        boolean duplicate = rows.scalar("select exists(select 1 from bnpl_plan where merchant_id = ? and merchant_order_ref = ? and status not in ('CANCELLED'))",
                Boolean.class, uuid(values, "merchant_id"), str(values, "merchant_order_ref")).orElse(false);
        if (duplicate) {
            throw ApiException.conflict("duplicate_order", "A BNPL plan already exists for this merchant order");
        }
        ObjectNode check = rows.object();
        check.put("customer_id", str(values, "customer_id"));
        check.put("merchant_id", str(values, "merchant_id"));
        check.put("amount", financed.toPlainString());
        check.put("currency", currency);
        check.put("installment_count", count);
        ObjectNode elig = eligibility(check);
        if (!elig.path("eligible").asBoolean()) {
            throw new ApiException(422, "bnpl_not_eligible", "BNPL declined: " + elig.get("reason_codes").toString());
        }
        BigDecimal mdr = decOr0(values, "merchant_discount_rate");
        if (mdr.signum() < 0 || mdr.compareTo(new BigDecimal("0.5")) > 0) {
            throw ApiException.unprocessable("invalid_rate", "merchant_discount_rate must be between 0 and 0.5");
        }
        BigDecimal rate = decOr0(values, "interest_rate");
        BigDecimal mdrFee = purchase.multiply(mdr).setScale(scale, RoundingMode.HALF_EVEN).min(financed);
        Frequency freq = Frequency.parse(str(values, "frequency") == null ? "FORTNIGHTLY" : str(values, "frequency"));
        LocalDate today = loans.today();
        List<BigDecimal> parts = split(financed, count, scale);
        ObjectNode loanReq = rows.object();
        loanReq.put("customer_id", str(values, "customer_id"));
        loanReq.put("product_id", str(values, "product_id"));
        loanReq.put("product_version_id", str(values, "product_version_id"));
        loanReq.put("legal_entity_id", str(values, "legal_entity_id"));
        loanReq.put("loan_type", "BNPL");
        loanReq.put("currency", currency);
        loanReq.put("principal_amount", financed.toPlainString());
        loanReq.put("interest_rate_type", rate.signum() == 0 ? "ZERO" : "FIXED");
        loanReq.put("interest_rate", rate.toPlainString());
        loanReq.put("schedule_type", "CUSTOM");
        loanReq.put("repayment_frequency", freq.name());
        loanReq.put("business_day_convention", "FOLLOWING");
        loanReq.put("term_months", freq.termMonths(count));
        loanReq.put("merchant_id", str(values, "merchant_id"));
        loanReq.put("origination_fee", mdrFee.toPlainString());
        loanReq.put("first_repayment_date", freq.nth(today, 1).toString());
        if (values.hasNonNull("repayment_account_id")) {
            loanReq.put("repayment_account_id", str(values, "repayment_account_id"));
        }
        ArrayNode custom = loanReq.putArray("custom_schedule");
        for (int i = 0; i < parts.size(); i++) {
            custom.addObject().put("due_date", freq.nth(today, i + 1).toString()).put("principal", parts.get(i).toPlainString());
        }
        ObjectNode loan = loanService.create(loanReq);
        ObjectNode disb = rows.object();
        disb.put("method", "MERCHANT_SETTLEMENT");
        disb.put("destination_account_id", str(values, "merchant_settlement_account_id"));
        ObjectNode d = disbursements.disburse(uuid(loan, "id"), disb, "bnpl:" + idempotencyKey);
        if (!"COMPLETED".equals(str(d, "status"))) {
            throw ApiException.unprocessable("merchant_settlement_failed", "Merchant settlement failed: " + str(d, "failure_reason"));
        }
        values.put("loan_id", str(loan, "id"));
        values.put("merchant_settlement_amount", financed.subtract(mdrFee).toPlainString());
        values.put("frequency", freq.name());
        values.put("status", "ACTIVE");
        ObjectNode plan = rows.insert(TABLE, values);
        loans.events().publish("lending.bnpl_plan.created", TABLE, plan);
        return plan;
    }

    @Transactional
    public ObjectNode refund(UUID planId, BigDecimal amount, String reason) {
        ObjectNode plan = rows.get(TABLE, planId);
        ObjectNode loan = loans.lock(uuid(plan, "loan_id"));
        plan = rows.lock(TABLE, planId);
        if (!List.of("ACTIVE", "COMPLETED", "DEFAULTED").contains(str(plan, "status"))) {
            throw ApiException.conflict("invalid_transition", "A " + str(plan, "status") + " plan cannot be refunded");
        }
        BigDecimal refundable = decOr0(plan, "purchase_amount").subtract(decOr0(plan, "refunded_amount"));
        if (amount == null || amount.signum() <= 0 || amount.compareTo(refundable) > 0
                || !Currencies.fitsMinorUnit(amount, str(plan, "currency"))) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive and at most " + refundable.toPlainString());
        }
        UUID loanId = uuid(loan, "id");
        // the refund reduces the customer's debt from the last instalment backwards; any excess goes back to the customer
        BigDecimal left = amount.min(decOr0(loan, "outstanding_principal"));
        BigDecimal reduce = left;
        List<ObjectNode> open = new ArrayList<>(loans.openInstallments(loanId));
        java.util.Collections.reverse(open);
        LocalDate today = loans.today();
        for (ObjectNode i : open) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal x = LoanSupport.remaining(i, "PRINCIPAL").min(left);
            if (x.signum() <= 0) {
                continue;
            }
            ObjectNode u = rows.object();
            u.put("principal_due", decOr0(i, "principal_due").subtract(x).toPlainString());
            u.put("total_due", decOr0(i, "total_due").subtract(x).toPlainString());
            i.setAll(u);
            String st = LoanSupport.installmentStatus(i, today);
            boolean nothingPaid = decOr0(i, "principal_paid").add(decOr0(i, "interest_paid")).signum() == 0;
            u.put("status", "PAID".equals(st) && nothingPaid ? "RESCHEDULED" : st);
            rows.update(LoanSupport.INSTALLMENT, uuid(i, "id"), u);
            left = left.subtract(x);
        }
        BigDecimal excess = amount.subtract(reduce);
        UUID merchant = external.depositLedgerAccount(uuid(plan, "merchant_settlement_account_id"), str(plan, "currency"),
                "merchant_settlement_account_id");
        UUID customerAccount = uuid(loan, "repayment_account_id") == null ? loans.gl().clearing()
                : external.depositLedgerAccount(uuid(loan, "repayment_account_id"), str(plan, "currency"), "repayment_account_id");
        loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("bnpl-refund", planId, decOr0(plan, "refunded_amount").toPlainString()),
                "PAYMENT", "BNPL merchant refund " + str(plan, "merchant_order_ref"), str(plan, "currency"), "BNPL_REFUND", planId, today,
                new LedgerPort.Legs().debit(merchant, amount, "Merchant refund")
                        .credit(loans.receivable(loan), reduce, "BNPL principal reduced")
                        .credit(customerAccount, excess, "Refund to customer").list()));
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("outstanding_principal", reduce.negate());
        loans.adjust(loan, deltas);
        BigDecimal refunded = decOr0(plan, "refunded_amount").add(amount);
        boolean full = refunded.compareTo(decOr0(plan, "purchase_amount")) >= 0;
        if (LoanSupport.SERVICEABLE.contains(str(loan, "status"))) {
            repayments.settleIfPaidOff(loan, "merchant refund");
            loans.delinquency(loan, today, "merchant refund");
        }
        ObjectNode upd = rows.object();
        upd.put("refunded_amount", refunded.toPlainString());
        upd.put("status_reason", reason);
        String status = full ? "REFUNDED" : "PAID_OFF".equals(str(loans.get(loanId), "status")) ? "COMPLETED" : str(plan, "status");
        if (!status.equals(str(rows.get(TABLE, planId), "status"))) {
            Lifecycles.require(Lifecycles.BNPL, "BNPL plan", str(rows.get(TABLE, planId), "status"), status);
        }
        upd.put("status", status);
        ObjectNode updated = rows.update(TABLE, planId, upd);
        loans.events().publish("lending.bnpl_plan.refunded", TABLE, updated);
        return updated;
    }

    @Transactional
    public ObjectNode cancel(UUID planId, String reason) {
        ObjectNode plan = rows.get(TABLE, planId);
        ObjectNode loan = loans.lock(uuid(plan, "loan_id"));
        plan = rows.lock(TABLE, planId);
        if (!"ACTIVE".equals(str(plan, "status")) && !"PENDING".equals(str(plan, "status"))) {
            throw ApiException.conflict("invalid_transition", "A " + str(plan, "status") + " plan cannot be cancelled");
        }
        UUID loanId = uuid(loan, "id");
        boolean paid = rows.scalar("select exists(select 1 from loan_repayment where loan_id = ? and status = 'POSTED')", Boolean.class, loanId)
                .orElse(false);
        if (paid) {
            throw ApiException.conflict("instalment_already_paid", "The plan has payments; use a merchant refund instead");
        }
        for (ObjectNode d : rows.query("select row_to_json(d)::text from loan_disbursement d where loan_id = ? and status = 'COMPLETED'", loanId)) {
            loans.ledger().reverse(uuid(d, "journal_entry_id"), "BNPL cancelled: " + reason, LedgerPort.key("bnpl-cancel", uuid(d, "id"), null));
            rows.update("loan_disbursement", uuid(d, "id"), rows.object().put("status", "REVERSED"));
        }
        rows.execute("update loan_fee_charge set status = 'REVERSED' where loan_id = ? and fee_type = 'ORIGINATION'", loanId);
        rows.execute("update schedule_installment set status = 'RESCHEDULED' where loan_id = ? and status in " + LoanSupport.OPEN_SQL, loanId);
        rows.execute("update repayment_schedule set status = 'CLOSED' where loan_id = ? and status = 'ACTIVE'", loanId);
        ObjectNode lu = rows.object();
        for (String f : List.of("outstanding_principal", "outstanding_interest", "outstanding_fees", "outstanding_penalties",
                "total_outstanding", "overdue_amount")) {
            lu.put(f, "0");
        }
        lu.put("closed_at", loans.now().toString());
        loan.setAll(rows.update(LoanSupport.LOAN, loanId, lu));
        loans.transition(loan, "CANCELLED", "BNPL cancelled: " + reason, loans.today());
        loans.events().publish("lending.loan.cancelled", LoanSupport.LOAN, loan);
        ObjectNode upd = rows.object();
        upd.put("status", "CANCELLED");
        upd.put("status_reason", reason);
        ObjectNode cancelled = rows.update(TABLE, planId, upd);
        loans.events().publish("lending.bnpl_plan.cancelled", TABLE, cancelled);
        return cancelled;
    }
}
