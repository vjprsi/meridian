package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loan.domain.DayCount;
import io.meridian.loan.domain.Delinquency;
import io.meridian.loan.infrastructure.ExternalClients;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-loan end-of-day steps, each in its own transaction (called by {@link EndOfDayJobs}): daily accrual, instalment
 * due/overdue processing with late fees, auto-debit collection and the delinquency update.
 */
@Service
public class ServicingJobs {
    static final MathContext MC = MathContext.DECIMAL128;

    private final LoanSupport loans;
    private final Rows rows;
    private final RepaymentService repayments;
    private final FeeService fees;
    private final ExternalClients external;

    public ServicingJobs(LoanSupport loans, RepaymentService repayments, FeeService fees, ExternalClients external) {
        this.loans = loans;
        this.rows = loans.rows();
        this.repayments = repayments;
        this.fees = fees;
        this.external = external;
    }

    public List<UUID> serviceableLoans() {
        return rows.query("select row_to_json(l)::text from loan l where status in ('ACTIVE','IN_ARREARS','RESTRUCTURED') order by id")
                .stream().map(l -> uuid(l, "id")).toList();
    }

    /**
     * Accrues interest (on the outstanding principal) and penalty interest (on the overdue amount) for every day from
     * the last accrued day through {@code date}: one append-only row per day with the precise amount; the ledger
     * receives the rounded difference of the cumulative accrual, so rounding never drifts. Non-performing loans
     * (more than 90 DPD) accrue into interest suspense. Returns the number of days accrued.
     */
    @Transactional
    public int accrue(UUID loanId, LocalDate date) {
        ObjectNode loan = loans.lock(loanId);
        if (!LoanSupport.SERVICEABLE.contains(str(loan, "status")) || decOr0(loan, "outstanding_principal").signum() <= 0) {
            return 0;
        }
        LocalDate last = Rows.date(loan, "last_accrual_date");
        LocalDate from = last != null ? last.plusDays(1) : Rows.date(loan, "start_date");
        if (from == null || from.isAfter(date)) {
            return 0;
        }
        DayCount dc = DayCount.parse(str(loan, "day_count"));
        BigDecimal principal = decOr0(loan, "outstanding_principal");
        BigDecimal rate = decOr0(loan, "interest_rate");
        BigDecimal penaltyRate = loans.penaltyRate(loan);
        List<ObjectNode> open = loans.openInstallments(loanId);
        boolean suspended = Delinquency.nonPerforming(loan.path("days_past_due").asInt(0));
        BigDecimal cumI = rows.scalar("select coalesce(sum(interest_accrued), 0) from loan_accrual where loan_id = ?", BigDecimal.class, loanId)
                .orElse(BigDecimal.ZERO);
        BigDecimal cumP = rows.scalar("select coalesce(sum(penalty_accrued), 0) from loan_accrual where loan_id = ?", BigDecimal.class, loanId)
                .orElse(BigDecimal.ZERO);
        int scale = LoanSupport.scale(loan);
        List<ObjectNode> days = new java.util.ArrayList<>();
        BigDecimal addI = BigDecimal.ZERO;
        BigDecimal addP = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(date); d = d.plusDays(1)) {
            overdue = overdueOn(open, d);
            BigDecimal basis = BigDecimal.valueOf(dc.basis(d));
            BigDecimal i = principal.multiply(rate, MC).divide(basis, MC).setScale(8, RoundingMode.HALF_EVEN);
            BigDecimal p = overdue.signum() > 0 && penaltyRate.signum() > 0
                    ? overdue.multiply(penaltyRate, MC).divide(basis, MC).setScale(8, RoundingMode.HALF_EVEN) : BigDecimal.ZERO;
            addI = addI.add(i);
            addP = addP.add(p);
            ObjectNode row = rows.object();
            row.put("loan_id", loanId.toString());
            row.put("accrual_date", d.toString());
            row.put("principal_base", principal.toPlainString());
            row.put("interest_rate", rate.toPlainString());
            row.put("interest_accrued", i.toPlainString());
            row.put("penalty_base", overdue.toPlainString());
            row.put("penalty_accrued", p.toPlainString());
            row.put("is_suspended", suspended);
            row.put("currency", str(loan, "currency"));
            days.add(row);
        }
        BigDecimal postI = cumI.add(addI).setScale(scale, RoundingMode.HALF_EVEN).subtract(cumI.setScale(scale, RoundingMode.HALF_EVEN));
        BigDecimal postP = cumP.add(addP).setScale(scale, RoundingMode.HALF_EVEN).subtract(cumP.setScale(scale, RoundingMode.HALF_EVEN));
        UUID je = null;
        if (postI.signum() > 0 || postP.signum() > 0) {
            je = loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-accrual", loanId, date.toString()), "ACCRUAL",
                    "Interest accrual " + str(loan, "loan_number") + " " + from + ".." + date, str(loan, "currency"), "LOAN", loanId,
                    date, new LedgerPort.Legs()
                    .debit(loans.gl().interestReceivable(), postI, "Interest receivable")
                    .credit(suspended ? loans.gl().suspenseOrIncome() : loans.gl().interestIncome(), postI,
                            suspended ? "Interest in suspense" : "Interest income")
                    .debit(loans.gl().penaltyReceivableOrInterest(), postP, "Penalty receivable")
                    .credit(loans.gl().penaltyIncomeOrInterest(), postP, "Penalty income").list()));
        }
        for (ObjectNode row : days) {
            if (je != null) {
                row.put("journal_entry_id", je.toString());
            }
            rows.execute("""
                    insert into loan_accrual (id, tenant_id, loan_id, accrual_date, principal_base, interest_rate, interest_accrued,
                      penalty_base, penalty_accrued, is_suspended, currency, journal_entry_id, created_by)
                    values (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (loan_id, accrual_date) do nothing""",
                    io.meridian.common.id.Ids.newId(), loanId, LocalDate.parse(str(row, "accrual_date")),
                    new BigDecimal(str(row, "principal_base")), new BigDecimal(str(row, "interest_rate")),
                    new BigDecimal(str(row, "interest_accrued")), new BigDecimal(str(row, "penalty_base")),
                    new BigDecimal(str(row, "penalty_accrued")), suspended, str(loan, "currency"), je,
                    io.meridian.common.tenancy.TenantContext.principal());
        }
        if (postP.signum() > 0) {
            rows.queryOne("""
                    select row_to_json(i)::text from schedule_installment i where loan_id = ?
                      and status in ('SCHEDULED','DUE','PARTIALLY_PAID','OVERDUE') and due_date < ?
                      and principal_due + interest_due > principal_paid + interest_paid
                    order by due_date limit 1""", loanId, date).ifPresent(i -> {
                ObjectNode u = rows.object();
                u.put("penalties_due", decOr0(i, "penalties_due").add(postP).toPlainString());
                rows.update(LoanSupport.INSTALLMENT, uuid(i, "id"), u);
            });
        }
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("outstanding_interest", postI);
        deltas.put("outstanding_penalties", postP);
        loans.adjust(loan, deltas);
        ObjectNode upd = rows.object();
        upd.put("last_accrual_date", date.toString());
        rows.update(LoanSupport.LOAN, loanId, upd);
        return days.size();
    }

    /** Unpaid principal and interest of instalments due before {@code day} (the penalty base of that day). */
    private static BigDecimal overdueOn(List<ObjectNode> open, LocalDate day) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ObjectNode i : open) {
            if (Rows.date(i, "due_date").isBefore(day)) {
                sum = sum.add(LoanSupport.remaining(i, "PRINCIPAL")).add(LoanSupport.remaining(i, "INTEREST"));
            }
        }
        return sum.max(BigDecimal.ZERO);
    }

    /** Result of the due processing of one loan. */
    public record DueResult(int due, int overdue, int lateFees, BigDecimal creditApplied) {}

    /**
     * Instalments falling due on or before {@code date} become DUE ({@code installment_due}); those unpaid beyond the
     * grace period become OVERDUE ({@code installment_overdue}) and are charged the product's late-payment fee once.
     * A credit balance held on the loan is applied to what is due.
     */
    @Transactional
    public DueResult processDue(UUID loanId, LocalDate date) {
        ObjectNode loan = loans.lock(loanId);
        if (!LoanSupport.SERVICEABLE.contains(str(loan, "status"))) {
            return new DueResult(0, 0, 0, BigDecimal.ZERO);
        }
        int grace = loan.path("grace_period_days").asInt(0);
        int due = 0;
        int overdue = 0;
        int lateFees = 0;
        ProductTerms terms = ProductTerms.of(loan);
        for (ObjectNode i : loans.openInstallments(loanId)) {
            LocalDate d = Rows.date(i, "due_date");
            String status = str(i, "status");
            if (d.isAfter(date) || LoanSupport.remainingTotal(i).signum() <= 0) {
                continue;
            }
            if ("SCHEDULED".equals(status)) {
                ObjectNode u = rows.object();
                u.put("status", "DUE");
                loans.events().publish("lending.loan.installment_due", LoanSupport.INSTALLMENT, rows.update(LoanSupport.INSTALLMENT, uuid(i, "id"), u));
                status = "DUE";
                due++;
            }
            if (!"OVERDUE".equals(status) && ChronoUnit.DAYS.between(d, date) > grace) {
                ObjectNode u = rows.object();
                u.put("status", "OVERDUE");
                u.put("days_late", (int) ChronoUnit.DAYS.between(d, date));
                ObjectNode od = rows.update(LoanSupport.INSTALLMENT, uuid(i, "id"), u);
                overdue++;
                boolean charged = rows.scalar("select exists(select 1 from loan_fee_charge where schedule_installment_id = ? and fee_type = 'LATE_PAYMENT')",
                        Boolean.class, uuid(i, "id")).orElse(false);
                BigDecimal fee = terms.feeAmount("LATE_PAYMENT", LoanSupport.remainingTotal(i), LoanSupport.scale(loan)).orElse(BigDecimal.ZERO);
                if (!charged && fee.signum() > 0) {
                    fees.charge(loan, "LATE_PAYMENT", fee, BigDecimal.ZERO, date, uuid(i, "id"), null, "late:" + uuid(i, "id"));
                    lateFees++;
                }
                loans.events().publish("lending.loan.installment_overdue", LoanSupport.INSTALLMENT, rows.get(LoanSupport.INSTALLMENT, uuid(od, "id")));
            }
        }
        BigDecimal applied = BigDecimal.ZERO;
        BigDecimal credit = decOr0(loans.get(loanId), "credit_balance");
        if (credit.signum() > 0) {
            loan = loans.get(loanId);
            BigDecimal owed = dueAmount(loanId, date);
            BigDecimal use = credit.min(owed);
            if (use.signum() > 0) {
                repayments.apply(loan, new RepaymentService.Collect(use, "MANUAL", null, date, "CREDIT_BALANCE", null,
                        "credit-apply:" + loanId + ":" + date, loans.gl().overpaymentOrClearing(), true));
                applied = use;
            }
        }
        loans.refresh(loans.get(loanId));
        return new DueResult(due, overdue, lateFees, applied);
    }

    /** Everything payable on {@code date}: unpaid amounts of instalments due by then plus unlinked fees. */
    public BigDecimal dueAmount(UUID loanId, LocalDate date) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ObjectNode i : loans.openInstallments(loanId)) {
            if (!Rows.date(i, "due_date").isAfter(date)) {
                sum = sum.add(LoanSupport.remainingTotal(i).max(BigDecimal.ZERO));
            }
        }
        for (ObjectNode f : rows.query("select row_to_json(f)::text from loan_fee_charge f where loan_id = ? and schedule_installment_id is null and status in ('CHARGED','PARTIALLY_PAID') and charge_date <= ?",
                loanId, date)) {
            sum = sum.add(RepaymentService.feeOwed(f));
        }
        return sum;
    }

    /** Outcome of an auto-debit attempt. */
    public enum DebitOutcome { NOTHING_DUE, COLLECTED, PARTIAL, INSUFFICIENT_FUNDS, NOT_ENABLED }

    /**
     * Collects what is due from the linked repayment account (up to its available balance); idempotent per loan and
     * business date through the repayment idempotency key.
     */
    @Transactional
    public DebitOutcome autoDebit(UUID loanId, LocalDate date) {
        ObjectNode loan = loans.lock(loanId);
        if (!LoanSupport.SERVICEABLE.contains(str(loan, "status")) || !Rows.bool(loan, "auto_debit_enabled")
                || uuid(loan, "repayment_account_id") == null) {
            return DebitOutcome.NOT_ENABLED;
        }
        String key = "auto-debit:" + loanId + ":" + date;
        if (rows.scalar("select exists(select 1 from loan_repayment where idempotency_key = ?)", Boolean.class, key).orElse(false)) {
            return DebitOutcome.COLLECTED;
        }
        BigDecimal owed = dueAmount(loanId, date);
        if (owed.signum() <= 0) {
            return DebitOutcome.NOTHING_DUE;
        }
        UUID account = uuid(loan, "repayment_account_id");
        UUID ledgerAccount = external.depositLedgerAccount(account, str(loan, "currency"), "repayment_account_id");
        BigDecimal available = LedgerPort.available(loans.ledger().balance(ledgerAccount));
        BigDecimal collect = owed.min(available).setScale(LoanSupport.scale(loan), RoundingMode.DOWN);
        if (collect.signum() <= 0) {
            return DebitOutcome.INSUFFICIENT_FUNDS;
        }
        try {
            repayments.apply(loan, new RepaymentService.Collect(collect, "AUTO_DEBIT", account, date, "AUTO_DEBIT " + date, null,
                    key, null, false));
        } catch (ApiException e) {
            if ("insufficient_funds".equals(e.code()) || "account_frozen".equals(e.code())) {
                return DebitOutcome.INSUFFICIENT_FUNDS;
            }
            throw e;
        }
        return collect.compareTo(owed) < 0 ? DebitOutcome.PARTIAL : DebitOutcome.COLLECTED;
    }

    /** DPD, bucket, arrears status and ECL stage of one loan as of {@code date}; BNPL plans default beyond 90 DPD. */
    @Transactional
    public boolean delinquency(UUID loanId, LocalDate date) {
        ObjectNode loan = loans.lock(loanId);
        boolean changed = loans.delinquency(loan, date, "end of day " + date);
        if (loan.path("days_past_due").asInt(0) > 90) {
            rows.queryOne("select row_to_json(b)::text from bnpl_plan b where loan_id = ? and status = 'ACTIVE'", loanId).ifPresent(p -> {
                ObjectNode u = rows.object();
                u.put("status", "DEFAULTED");
                rows.update("bnpl_plan", uuid(p, "id"), u);
            });
        }
        return changed;
    }
}
