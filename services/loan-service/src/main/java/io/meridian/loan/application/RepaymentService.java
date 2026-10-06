package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.loan.domain.Currencies;
import io.meridian.loan.domain.Waterfall;
import io.meridian.loan.infrastructure.ExternalClients;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Repayments through the allocation waterfall (configurable per product and loan status), excess handling (reduce
 * term / instalment, hold as credit, refund), reversal of repayments, instalment waivers and the pay-off settlement
 * (closing open instalments and truing up the accrued interest).
 */
@Service
public class RepaymentService {
    static final String TABLE = "loan_repayment";
    private static final Set<String> FUNDED_FROM_CLEARING = Set.of("WALLET", "CARD", "EXTERNAL_TRANSFER", "CASH",
            "PAYROLL", "COLLATERAL_LIQUIDATION", "RECOVERY", "MANUAL");

    /** How a repayment is funded and identified. */
    public record Collect(BigDecimal amount, String source, UUID sourceAccount, LocalDate valueDate, String externalReference,
                          UUID promiseToPay, String idempotencyKey, UUID fundingLedgerOverride, boolean fromCredit) {}

    private final LoanSupport loans;
    private final Rows rows;
    private final ExternalClients external;

    public RepaymentService(LoanSupport loans, ExternalClients external) {
        this.loans = loans;
        this.rows = loans.rows();
        this.external = external;
    }

    @Transactional
    public ObjectNode repay(UUID loanId, JsonNode req, String idempotencyKey) {
        ObjectNode loan = loans.lock(loanId);
        requireServiceable(loan);
        BigDecimal amount = Rows.dec(req, "amount");
        if (amount == null || amount.signum() <= 0 || !Currencies.fitsMinorUnit(amount, str(loan, "currency"))) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most "
                    + LoanSupport.scale(loan) + " decimals");
        }
        LocalDate valueDate = Rows.date(req, "value_date") != null ? Rows.date(req, "value_date") : loans.today();
        if (valueDate.isAfter(loans.today())) {
            throw ApiException.unprocessable("invalid_value_date", "value_date cannot be in the future");
        }
        return apply(loan, new Collect(amount, str(req, "source"), uuid(req, "source_account_id"), valueDate,
                str(req, "external_reference"), uuid(req, "promise_to_pay_id"), idempotencyKey, null, false));
    }

    static void requireServiceable(JsonNode loan) {
        String s = str(loan, "status");
        if ("WRITTEN_OFF".equals(s)) {
            throw ApiException.conflict("loan_written_off", "The loan is written off; record recoveries with POST /v1/loan-recoveries");
        }
        if (!LoanSupport.SERVICEABLE.contains(s)) {
            throw ApiException.conflict("invalid_transition", "Repayments are not accepted on a " + s + " loan");
        }
    }

    /** Applies a repayment to a locked, serviceable loan. */
    public ObjectNode apply(ObjectNode loan, Collect c) {
        UUID loanId = uuid(loan, "id");
        Optional<ObjectNode> replay = rows.queryOne("select row_to_json(r)::text from loan_repayment r where idempotency_key = ?",
                c.idempotencyKey());
        if (replay.isPresent()) {
            return replay.get();
        }
        Optional<ObjectNode> rule = rule(loan, c.valueDate());
        List<Waterfall.Component> order = new ArrayList<>();
        rule.ifPresent(r -> r.path("order_of_components").forEach(x -> order.add(Waterfall.Component.parse(x.asString()))));
        boolean oldestFirst = rule.map(r -> Rows.bool(r, "allocate_oldest_first")).orElse(true);
        String excessHandling = rule.map(r -> str(r, "excess_handling")).orElse("REDUCE_TERM");

        Buckets b = buckets(loanId, c.valueDate());
        Waterfall.Result res = Waterfall.allocate(b.list, order, oldestFirst, c.amount());
        BigDecimal excess = res.excess();
        BigDecimal prepaid = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        if (excess.signum() > 0) {
            if (c.fromCredit()) {
                refund = excess;
            } else {
                switch (excessHandling) {
                    case "REDUCE_TERM", "REDUCE_INSTALMENT" -> {
                        BigDecimal future = futurePrincipal(loanId, c.valueDate());
                        prepaid = excess.min(future);
                        credit = excess.subtract(prepaid);
                    }
                    case "HOLD_AS_CREDIT" -> credit = excess;
                    default -> refund = excess;
                }
            }
        }
        BigDecimal collected = c.amount().subtract(refund);
        BigDecimal p = res.total(Waterfall.Component.PRINCIPAL);
        BigDecimal i = res.total(Waterfall.Component.INTEREST);
        BigDecimal f = res.total(Waterfall.Component.FEE);
        BigDecimal pen = res.total(Waterfall.Component.PENALTY);
        if (collected.signum() <= 0) {
            throw ApiException.unprocessable("nothing_due", "Nothing is due on the loan and excess payments are refunded");
        }
        UUID id = Ids.newId();
        UUID funding = funding(loan, c);
        UUID je = loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-repayment", id, null), "PAYMENT",
                "Repayment " + str(loan, "loan_number") + " (" + c.source() + ")", str(loan, "currency"), "LOAN_REPAYMENT", id,
                c.valueDate(), new LedgerPort.Legs()
                .debit(funding, collected, c.fromCredit() ? "Loan credit balance applied" : "Repayment " + c.source())
                .credit(loans.receivable(loan), p.add(prepaid), "Principal")
                .credit(loans.gl().interestReceivable(), i, "Interest")
                .credit(loans.gl().feeReceivable(), f, "Fees")
                .credit(loans.gl().penaltyReceivableOrInterest(), pen, "Penalties")
                .credit(loans.gl().overpaymentOrClearing(), credit, "Overpayment held as credit").list()));

        ArrayNode detail = rows.json().createArrayNode();
        applyAllocations(res, b, detail, c.valueDate());
        if (prepaid.signum() > 0) {
            detail.addObject().put("type", "PREPAYMENT").put("component", "PRINCIPAL").put("amount", prepaid.toPlainString())
                    .put("option", excessHandling);
        }
        if (credit.signum() > 0) {
            detail.addObject().put("type", "CREDIT").put("component", "CREDIT").put("amount", credit.toPlainString());
        }
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("outstanding_principal", p.add(prepaid).negate());
        deltas.put("outstanding_interest", i.negate());
        deltas.put("outstanding_fees", f.negate());
        deltas.put("outstanding_penalties", pen.negate());
        deltas.put("credit_balance", c.fromCredit() ? collected.negate() : credit);
        loans.adjust(loan, deltas);

        ObjectNode row = rows.object();
        row.put("id", id.toString());
        row.put("loan_id", loanId.toString());
        row.put("amount", c.amount().toPlainString());
        row.put("currency", str(loan, "currency"));
        row.put("source", c.source());
        row.put("value_date", c.valueDate().toString());
        row.put("allocated_principal", p.add(prepaid).toPlainString());
        row.put("allocated_interest", i.toPlainString());
        row.put("allocated_fees", f.toPlainString());
        row.put("allocated_penalties", pen.toPlainString());
        row.put("excess_amount", refund.add(credit).toPlainString());
        row.set("allocation_detail", detail);
        rule.ifPresent(r -> row.put("allocation_rule_id", str(r, "id")));
        row.put("status", "POSTED");
        if (c.sourceAccount() != null) {
            row.put("source_account_id", c.sourceAccount().toString());
        }
        if (c.promiseToPay() != null) {
            row.put("promise_to_pay_id", c.promiseToPay().toString());
        }
        if (c.externalReference() != null) {
            row.put("external_reference", c.externalReference());
        }
        row.put("journal_entry_id", je.toString());
        row.put("idempotency_key", c.idempotencyKey());
        ObjectNode repayment = rows.insert(TABLE, row);

        if (prepaid.signum() > 0) {
            BigDecimal instalment = "REDUCE_TERM".equals(excessHandling) ? regularInstalment(loanId) : null;
            loans.regenerateFuture(loan, "PREPAYMENT", c.valueDate(), decOr0(loan, "interest_rate"), null, instalment);
        }
        creditLineInstalmentRepaid(loan, p.add(prepaid));
        settleIfPaidOff(loan, "repaid");
        loans.delinquency(loan, loans.today(), "repayment");
        loans.events().publish("lending.loan.repayment_received", TABLE, repayment);
        return repayment;
    }

    private UUID funding(ObjectNode loan, Collect c) {
        if (c.fundingLedgerOverride() != null) {
            return c.fundingLedgerOverride();
        }
        UUID account = c.sourceAccount();
        if (account == null && "AUTO_DEBIT".equals(c.source())) {
            account = uuid(loan, "repayment_account_id");
            if (account == null) {
                throw ApiException.unprocessable("repayment_account_required", "AUTO_DEBIT needs a repayment account");
            }
        }
        if (account != null) {
            return external.depositLedgerAccount(account, str(loan, "currency"), "source_account_id");
        }
        if (!FUNDED_FROM_CLEARING.contains(c.source())) {
            throw ApiException.unprocessable("source_account_required", "source_account_id is required for " + c.source());
        }
        return loans.gl().clearing();
    }

    // ------------------------------------------------------------------ buckets and allocation

    record Buckets(List<Waterfall.Bucket> list, Map<String, ObjectNode> installments, Map<String, ObjectNode> fees) {}

    Buckets buckets(UUID loanId, LocalDate valueDate) {
        List<Waterfall.Bucket> list = new ArrayList<>();
        Map<String, ObjectNode> inst = new HashMap<>();
        Map<String, ObjectNode> fees = new HashMap<>();
        for (ObjectNode i : loans.openInstallments(loanId)) {
            LocalDate due = Rows.date(i, "due_date");
            if (due.isAfter(valueDate)) {
                continue;
            }
            String ref = "inst:" + str(i, "id");
            inst.put(ref, i);
            Map<Waterfall.Component, BigDecimal> m = new HashMap<>();
            m.put(Waterfall.Component.PRINCIPAL, LoanSupport.remaining(i, "PRINCIPAL"));
            m.put(Waterfall.Component.INTEREST, LoanSupport.remaining(i, "INTEREST"));
            m.put(Waterfall.Component.FEE, LoanSupport.remaining(i, "FEE"));
            m.put(Waterfall.Component.PENALTY, LoanSupport.remaining(i, "PENALTY"));
            list.add(new Waterfall.Bucket(ref, due, m));
        }
        for (ObjectNode fc : rows.query("""
                select row_to_json(f)::text from loan_fee_charge f where loan_id = ? and schedule_installment_id is null
                  and status in ('CHARGED','PARTIALLY_PAID') and charge_date <= ? order by charge_date, id""", loanId, valueDate)) {
            String ref = "fee:" + str(fc, "id");
            fees.put(ref, fc);
            list.add(new Waterfall.Bucket(ref, Rows.date(fc, "charge_date"), Map.of(Waterfall.Component.FEE, feeOwed(fc))));
        }
        return new Buckets(list, inst, fees);
    }

    static BigDecimal feeOwed(JsonNode fc) {
        return decOr0(fc, "amount").add(decOr0(fc, "tax_amount")).subtract(decOr0(fc, "paid_amount"));
    }

    private void applyAllocations(Waterfall.Result res, Buckets b, ArrayNode detail, LocalDate valueDate) {
        Map<String, Map<Waterfall.Component, BigDecimal>> perRef = new LinkedHashMap<>();
        for (Waterfall.Allocation a : res.allocations()) {
            perRef.computeIfAbsent(a.ref(), k -> new LinkedHashMap<>()).merge(a.component(), a.amount(), BigDecimal::add);
        }
        LocalDate today = loans.today();
        for (Map.Entry<String, Map<Waterfall.Component, BigDecimal>> e : perRef.entrySet()) {
            String ref = e.getKey();
            if (ref.startsWith("inst:")) {
                ObjectNode i = b.installments().get(ref);
                ObjectNode upd = rows.object();
                for (Map.Entry<Waterfall.Component, BigDecimal> ce : e.getValue().entrySet()) {
                    String col = paidColumn(ce.getKey());
                    upd.put(col, decOr0(i, col).add(ce.getValue()).toPlainString());
                    detail.addObject().put("type", "INSTALLMENT").put("id", str(i, "id"))
                            .put("installment_no", i.path("installment_no").asInt()).put("due_date", str(i, "due_date"))
                            .put("component", ce.getKey().name()).put("amount", ce.getValue().toPlainString());
                    if (ce.getKey() == Waterfall.Component.FEE) {
                        payLinkedFees(uuid(i, "id"), ce.getValue(), detail);
                    }
                }
                i.setAll(upd);
                upd.put("status", LoanSupport.installmentStatus(i, today));
                if ("PAID".equals(str(upd, "status"))) {
                    upd.put("paid_at", loans.now().toString());
                }
                rows.update(LoanSupport.INSTALLMENT, uuid(i, "id"), upd);
            } else {
                ObjectNode fc = b.fees().get(ref);
                BigDecimal amt = e.getValue().getOrDefault(Waterfall.Component.FEE, BigDecimal.ZERO);
                payFee(fc, amt);
                detail.addObject().put("type", "FEE_CHARGE").put("id", str(fc, "id")).put("component", "FEE")
                        .put("amount", amt.toPlainString());
            }
        }
    }

    private void payLinkedFees(UUID installmentId, BigDecimal amount, ArrayNode detail) {
        BigDecimal left = amount;
        for (ObjectNode fc : rows.query("""
                select row_to_json(f)::text from loan_fee_charge f where schedule_installment_id = ?
                  and status in ('CHARGED','PARTIALLY_PAID') order by charge_date, id""", installmentId)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal x = feeOwed(fc).min(left);
            if (x.signum() > 0) {
                payFee(fc, x);
                detail.addObject().put("type", "FEE_CHARGE_LINKED").put("id", str(fc, "id")).put("component", "FEE")
                        .put("amount", x.toPlainString());
                left = left.subtract(x);
            }
        }
    }

    private void payFee(ObjectNode fc, BigDecimal amount) {
        BigDecimal paid = decOr0(fc, "paid_amount").add(amount);
        ObjectNode upd = rows.object();
        upd.put("paid_amount", paid.toPlainString());
        upd.put("status", paid.compareTo(decOr0(fc, "amount").add(decOr0(fc, "tax_amount"))) >= 0 ? "PAID"
                : paid.signum() > 0 ? "PARTIALLY_PAID" : "CHARGED");
        rows.update("loan_fee_charge", uuid(fc, "id"), upd);
    }

    static String paidColumn(Waterfall.Component c) {
        return switch (c) {
            case PRINCIPAL -> "principal_paid";
            case INTEREST -> "interest_paid";
            case FEE -> "fees_paid";
            case PENALTY -> "penalties_paid";
            default -> throw new IllegalArgumentException("component " + c + " has no instalment column");
        };
    }

    private BigDecimal futurePrincipal(UUID loanId, LocalDate valueDate) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ObjectNode i : loans.openInstallments(loanId)) {
            if (Rows.date(i, "due_date").isAfter(valueDate)) {
                sum = sum.add(LoanSupport.remaining(i, "PRINCIPAL"));
            }
        }
        return sum;
    }

    private BigDecimal regularInstalment(UUID loanId) {
        return rows.scalar("""
                select i.total_due from schedule_installment i join repayment_schedule s on s.id = i.repayment_schedule_id
                where i.loan_id = ? and s.status = 'ACTIVE' and i.status = 'SCHEDULED' order by i.due_date limit 1""",
                BigDecimal.class, loanId).orElse(null);
    }

    Optional<ObjectNode> rule(JsonNode loan, LocalDate date) {
        return rows.queryOne("""
                select row_to_json(r)::text from allocation_rule r
                where is_active and (product_id = ? or product_id is null)
                  and (applies_to_status is null or applies_to_status = ?)
                  and valid_from <= ? and (valid_to is null or valid_to >= ?)
                order by (product_id is null), (applies_to_status is null), valid_from desc limit 1""",
                uuid(loan, "product_id"), str(loan, "status"), date, date);
    }

    // ------------------------------------------------------------------ pay-off

    /**
     * When no principal and nothing due remains: future instalments are closed, the accrued interest and penalty
     * residuals are trued up in the ledger, the loan becomes PAID_OFF (and its BNPL plan COMPLETED).
     */
    public boolean settleIfPaidOff(ObjectNode loan, String reason) {
        if (decOr0(loan, "outstanding_principal").signum() > 0 || !LoanSupport.SERVICEABLE.contains(str(loan, "status"))) {
            return false;
        }
        UUID loanId = uuid(loan, "id");
        LocalDate today = loans.today();
        for (ObjectNode i : loans.openInstallments(loanId)) {
            boolean due = !Rows.date(i, "due_date").isAfter(today);
            BigDecimal left = LoanSupport.remaining(i, "FEE").add(LoanSupport.remaining(i, "PENALTY"))
                    .add(due ? LoanSupport.remaining(i, "INTEREST") : BigDecimal.ZERO);
            if (left.signum() > 0) {
                return false;
            }
        }
        boolean feesOpen = rows.scalar("select exists(select 1 from loan_fee_charge where loan_id = ? and status in ('CHARGED','PARTIALLY_PAID'))",
                Boolean.class, loanId).orElse(false);
        if (feesOpen) {
            return false;
        }
        rows.execute("update schedule_installment set status = case when principal_paid + interest_paid + fees_paid + penalties_paid > 0 "
                + "then 'PAID' else 'RESCHEDULED' end, paid_at = case when principal_paid + interest_paid > 0 then now() else paid_at end "
                + "where loan_id = ? and status in " + LoanSupport.OPEN_SQL, loanId);
        trueUp(loan, "payoff");
        rows.execute("update repayment_schedule set status = 'CLOSED' where loan_id = ? and status = 'ACTIVE'", loanId);
        ObjectNode upd = rows.object();
        upd.put("paid_off_at", loans.now().toString());
        upd.put("days_past_due", 0);
        upd.put("delinquency_bucket", "CURRENT");
        loan.setAll(rows.update(LoanSupport.LOAN, loanId, upd));
        loans.refresh(loan);
        loans.transition(loan, "PAID_OFF", reason, today);
        loans.events().publish("lending.loan.paid_off", LoanSupport.LOAN, loan);
        rows.queryOne("select row_to_json(b)::text from bnpl_plan b where loan_id = ? and status in ('ACTIVE','DEFAULTED')", loanId)
                .ifPresent(plan -> {
                    ObjectNode u = rows.object();
                    u.put("status", "COMPLETED");
                    loans.events().publish("lending.bnpl_plan.completed", "bnpl_plan", rows.update("bnpl_plan", uuid(plan, "id"), u));
                });
        return true;
    }

    /** Brings outstanding interest and penalties to zero against income (over-accrual reversed, under-accrual recognised). */
    void trueUp(ObjectNode loan, String why) {
        UUID loanId = uuid(loan, "id");
        BigDecimal interest = decOr0(loan, "outstanding_interest");
        BigDecimal penalties = decOr0(loan, "outstanding_penalties");
        if (interest.signum() == 0 && penalties.signum() == 0) {
            return;
        }
        LedgerPort.Legs legs = new LedgerPort.Legs();
        if (interest.signum() > 0) {
            legs.debit(loans.gl().interestIncome(), interest, "Interest accrual true-up").credit(loans.gl().interestReceivable(), interest, "Interest receivable");
        } else if (interest.signum() < 0) {
            legs.debit(loans.gl().interestReceivable(), interest.negate(), "Interest receivable").credit(loans.gl().interestIncome(), interest.negate(), "Interest accrual true-up");
        }
        if (penalties.signum() > 0) {
            legs.debit(loans.gl().penaltyIncomeOrInterest(), penalties, "Penalty true-up").credit(loans.gl().penaltyReceivableOrInterest(), penalties, "Penalty receivable");
        } else if (penalties.signum() < 0) {
            legs.debit(loans.gl().penaltyReceivableOrInterest(), penalties.negate(), "Penalty receivable").credit(loans.gl().penaltyIncomeOrInterest(), penalties.negate(), "Penalty true-up");
        }
        loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-trueup", loanId, why + ":" + interest.toPlainString()), "ADJUSTMENT",
                "Accrual true-up " + str(loan, "loan_number"), str(loan, "currency"), "LOAN", loanId, loans.today(), legs.list()));
        ObjectNode upd = rows.object();
        upd.put("outstanding_interest", "0");
        upd.put("outstanding_penalties", "0");
        loan.setAll(rows.update(LoanSupport.LOAN, loanId, upd));
    }

    private void creditLineInstalmentRepaid(ObjectNode loan, BigDecimal principal) {
        UUID line = uuid(loan, "credit_line_id");
        if (line == null || principal.signum() <= 0) {
            return;
        }
        rows.execute("""
                update credit_line set instalment_balance = greatest(instalment_balance - ?, 0),
                       available_amount = greatest(coalesce(case when temporary_limit is not null and temporary_limit_expires_at > now()
                         then temporary_limit end, credit_limit) - utilised_amount - greatest(instalment_balance - ?, 0)
                         - outstanding_interest - outstanding_fees, 0)
                where id = ?""", principal, principal, line);
    }

    // ------------------------------------------------------------------ reversal

    @Transactional
    public ObjectNode reverse(UUID repaymentId, String reason) {
        ObjectNode original = rows.get(TABLE, repaymentId);
        if (!"POSTED".equals(str(original, "status")) || original.hasNonNull("reversal_of_id")) {
            throw ApiException.conflict("invalid_transition", "Only posted repayments can be reversed");
        }
        if (original.hasNonNull("prepayment_quote_id")) {
            throw ApiException.conflict("prepayment_not_reversible", "Prepayments against a quote cannot be reversed");
        }
        if (original.hasNonNull("credit_line_id")) {
            throw ApiException.conflict("invalid_transition", "Credit-line payments are reversed by the credit-line service");
        }
        ObjectNode loan = loans.lock(uuid(original, "loan_id"));
        boolean reversed = rows.scalar("select exists(select 1 from loan_repayment where reversal_of_id = ?)", Boolean.class, repaymentId)
                .orElse(false);
        if (reversed) {
            throw ApiException.conflict("already_reversed", "Repayment " + repaymentId + " was already reversed");
        }
        if (Set.of("CLOSED", "CANCELLED", "WRITTEN_OFF").contains(str(loan, "status"))) {
            throw ApiException.conflict("invalid_transition", "Repayments of a " + str(loan, "status") + " loan cannot be reversed");
        }
        UUID loanId = uuid(loan, "id");
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal prepaid = BigDecimal.ZERO;
        LocalDate today = loans.today();
        for (JsonNode a : original.path("allocation_detail")) {
            BigDecimal amt = new BigDecimal(a.path("amount").asString("0"));
            switch (a.path("type").asString("")) {
                case "INSTALLMENT" -> {
                    ObjectNode i = rows.get(LoanSupport.INSTALLMENT, UUID.fromString(a.get("id").asString()));
                    String col = paidColumn(Waterfall.Component.parse(a.get("component").asString()));
                    ObjectNode upd = rows.object();
                    upd.put(col, decOr0(i, col).subtract(amt).toPlainString());
                    i.setAll(upd);
                    if (!"RESCHEDULED".equals(str(i, "status"))) {
                        upd.put("status", LoanSupport.installmentStatus(i, today));
                        upd.putNull("paid_at");
                    }
                    rows.update(LoanSupport.INSTALLMENT, uuid(i, "id"), upd);
                }
                case "FEE_CHARGE", "FEE_CHARGE_LINKED" -> {
                    ObjectNode fc = rows.get("loan_fee_charge", UUID.fromString(a.get("id").asString()));
                    payFee(fc, amt.negate());
                }
                case "PREPAYMENT" -> prepaid = prepaid.add(amt);
                case "CREDIT" -> credit = credit.add(amt);
                default -> { }
            }
        }
        if (credit.compareTo(decOr0(loan, "credit_balance")) > 0) {
            throw ApiException.conflict("credit_already_used", "The credit balance created by this repayment was already applied");
        }
        UUID je = loans.ledger().reverse(uuid(original, "journal_entry_id"), reason,
                LedgerPort.key("loan-repayment-reversal", repaymentId, null));
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("outstanding_principal", decOr0(original, "allocated_principal"));
        deltas.put("outstanding_interest", decOr0(original, "allocated_interest"));
        deltas.put("outstanding_fees", decOr0(original, "allocated_fees"));
        deltas.put("outstanding_penalties", decOr0(original, "allocated_penalties"));
        deltas.put("credit_balance", credit.negate());
        loans.adjust(loan, deltas);
        if ("PAID_OFF".equals(str(loan, "status"))) {
            ObjectNode u = rows.object();
            u.putNull("paid_off_at");
            loan.setAll(rows.update(LoanSupport.LOAN, loanId, u));
            rows.execute("update repayment_schedule set status = 'ACTIVE' where id = (select id from repayment_schedule where loan_id = ? order by version desc limit 1)", loanId);
            loans.transition(loan, Rows.bool(loan, "is_forborne") ? "RESTRUCTURED" : "ACTIVE", "repayment reversed", today);
        }
        if (prepaid.signum() > 0) {
            loans.regenerateFuture(loan, "PREPAYMENT_REVERSAL", today, decOr0(loan, "interest_rate"), null, null);
        }
        ObjectNode row = rows.object();
        row.put("loan_id", loanId.toString());
        row.put("amount", str(original, "amount"));
        row.put("currency", str(original, "currency"));
        row.put("source", str(original, "source"));
        row.put("value_date", today.toString());
        for (String f : List.of("allocated_principal", "allocated_interest", "allocated_fees", "allocated_penalties", "excess_amount")) {
            row.put(f, str(original, f));
        }
        row.set("allocation_detail", original.get("allocation_detail"));
        row.put("status", "REVERSED");
        row.put("reversal_of_id", repaymentId.toString());
        row.put("reason", reason);
        row.put("journal_entry_id", je.toString());
        row.put("idempotency_key", "reversal:" + repaymentId);
        ObjectNode reversal = rows.insert(TABLE, row);
        loans.delinquency(loan, today, "repayment reversed");
        loans.events().publish("lending.loan.repayment_reversed", TABLE, reversal);
        return reversal;
    }

    // ------------------------------------------------------------------ waivers

    /** Waives the unpaid remainder of an instalment (all or the given components) against income / write-off expense. */
    @Transactional
    public ObjectNode waiveInstallment(UUID installmentId, String reason, List<String> components) {
        ObjectNode inst = rows.get(LoanSupport.INSTALLMENT, installmentId);
        ObjectNode loan = loans.lock(uuid(inst, "loan_id"));
        inst = rows.lock(LoanSupport.INSTALLMENT, installmentId);
        if (!LoanSupport.OPEN_INSTALLMENT.contains(str(inst, "status"))) {
            throw ApiException.conflict("invalid_transition", "A " + str(inst, "status") + " instalment cannot be waived");
        }
        requireServiceable(loan);
        List<String> comps = components == null || components.isEmpty() ? List.of("PRINCIPAL", "INTEREST", "FEE", "PENALTY") : components;
        BigDecimal p = comps.contains("PRINCIPAL") ? LoanSupport.remaining(inst, "PRINCIPAL").max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal i = comps.contains("INTEREST") ? LoanSupport.remaining(inst, "INTEREST").max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal f = comps.contains("FEE") ? LoanSupport.remaining(inst, "FEE").max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal pen = comps.contains("PENALTY") ? LoanSupport.remaining(inst, "PENALTY").max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal total = p.add(i).add(f).add(pen);
        if (total.signum() == 0) {
            throw ApiException.conflict("nothing_to_waive", "Nothing is left to waive on this instalment");
        }
        // interest of a future instalment is not accrued yet: only the accrued part is reversed in the ledger
        BigDecimal interestReceivable = i.min(decOr0(loan, "outstanding_interest").max(BigDecimal.ZERO));
        loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("installment-waiver", installmentId, String.join(",", comps)),
                "ADJUSTMENT", "Waiver instalment " + inst.path("installment_no").asInt() + " " + str(loan, "loan_number") + ": " + reason,
                str(loan, "currency"), "LOAN_WAIVER", installmentId, loans.today(), new LedgerPort.Legs()
                .debit(loans.gl().waiverOrWriteOff(), p, "Principal waived")
                .debit(loans.gl().interestIncome(), interestReceivable, "Interest waived")
                .debit(loans.gl().feeIncome(), f, "Fees waived")
                .debit(loans.gl().penaltyIncomeOrInterest(), pen, "Penalties waived")
                .credit(loans.receivable(loan), p, "Principal")
                .credit(loans.gl().interestReceivable(), interestReceivable, "Interest")
                .credit(loans.gl().feeReceivable(), f, "Fees")
                .credit(loans.gl().penaltyReceivableOrInterest(), pen, "Penalties").list()));
        ObjectNode upd = rows.object();
        upd.put("principal_due", decOr0(inst, "principal_due").subtract(p).toPlainString());
        upd.put("interest_due", decOr0(inst, "interest_due").subtract(i).toPlainString());
        upd.put("fees_due", decOr0(inst, "fees_due").subtract(f).toPlainString());
        upd.put("penalties_due", decOr0(inst, "penalties_due").subtract(pen).toPlainString());
        upd.put("total_due", decOr0(inst, "total_due").subtract(p).subtract(i).subtract(f).toPlainString());
        upd.put("waived_amount", decOr0(inst, "waived_amount").add(total).toPlainString());
        inst.setAll(upd);
        upd.put("status", LoanSupport.remainingTotal(inst).signum() <= 0 ? "WAIVED" : LoanSupport.installmentStatus(inst, loans.today()));
        ObjectNode waived = rows.update(LoanSupport.INSTALLMENT, installmentId, upd);
        if (f.signum() > 0) {
            rows.execute("update loan_fee_charge set status = 'WAIVED', waived_by = ?, waiver_reason = ? where schedule_installment_id = ? and status in ('CHARGED','PARTIALLY_PAID')",
                    io.meridian.common.tenancy.TenantContext.principal(), reason, installmentId);
        }
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("outstanding_principal", p.negate());
        deltas.put("outstanding_interest", interestReceivable.negate());
        deltas.put("outstanding_fees", f.negate());
        deltas.put("outstanding_penalties", pen.negate());
        loans.adjust(loan, deltas);
        if (p.signum() > 0 && decOr0(loan, "outstanding_principal").signum() > 0) {
            // keep the balance-after column of later instalments consistent with the reduced principal
            rows.execute("update schedule_installment set outstanding_balance_after = greatest(outstanding_balance_after - ?, 0) "
                    + "where loan_id = ? and due_date > ? and status in " + LoanSupport.OPEN_SQL, p, uuid(loan, "id"), Rows.date(inst, "due_date"));
        }
        settleIfPaidOff(loan, "waived");
        loans.delinquency(loan, loans.today(), "instalment waived");
        loans.events().publish("lending.loan.installment_waived", LoanSupport.INSTALLMENT, waived);
        return waived;
    }
}
