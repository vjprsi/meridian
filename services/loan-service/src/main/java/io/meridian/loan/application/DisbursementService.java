package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.dec;
import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loan.domain.Frequency;
import io.meridian.loan.domain.ScheduleGenerator;
import io.meridian.loan.infrastructure.ExternalClients;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Disbursement of loan funds, single or in tranches: debit the loan receivable, credit the destination (customer
 * deposit account, merchant settlement account, or the clearing account for wallets, cards and external payouts),
 * net of the origination fee on the first tranche. Failed postings are recorded (FAILED) and can be retried.
 */
@Service
public class DisbursementService {
    static final String TABLE = "loan_disbursement";

    private final LoanSupport loans;
    private final Rows rows;
    private final ExternalClients external;

    public DisbursementService(LoanSupport loans, ExternalClients external) {
        this.loans = loans;
        this.rows = loans.rows();
        this.external = external;
    }

    @Transactional
    public ObjectNode disburse(UUID loanId, JsonNode req, String idempotencyKey) {
        ObjectNode loan = loans.lock(loanId);
        String status = str(loan, "status");
        BigDecimal principal = decOr0(loan, "principal_amount");
        BigDecimal disbursed = decOr0(loan, "disbursed_amount");
        BigDecimal remaining = principal.subtract(disbursed);
        if (!Set.of("PENDING_DISBURSEMENT", "ACTIVE").contains(status) || remaining.signum() <= 0) {
            throw ApiException.conflict("invalid_transition", "Loan " + loanId + " (" + status + ") has nothing left to disburse");
        }
        if ("ACTIVE".equals(status)) {
            boolean dueStarted = rows.scalar("""
                    select exists(select 1 from schedule_installment where loan_id = ? and status in ('DUE','PARTIALLY_PAID','OVERDUE','PAID'))""",
                    Boolean.class, loanId).orElse(false);
            if (dueStarted) {
                throw ApiException.conflict("disbursement_window_closed", "Tranches can only be drawn before the first instalment falls due");
            }
        }
        boolean pending = rows.scalar("select exists(select 1 from loan_disbursement where loan_id = ? and status in ('SCHEDULED','PENDING'))",
                Boolean.class, loanId).orElse(false);
        if (pending) {
            throw ApiException.conflict("disbursement_pending", "A disbursement of this loan is already scheduled or pending");
        }
        BigDecimal amount = dec(req, "amount") == null ? remaining : dec(req, "amount");
        if (amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive and at most the undisbursed "
                    + remaining.toPlainString());
        }
        if (amount.scale() > LoanSupport.scale(loan) && amount.stripTrailingZeros().scale() > LoanSupport.scale(loan)) {
            throw ApiException.unprocessable("invalid_amount", "amount has more decimals than the currency allows");
        }
        String method = str(req, "method");
        UUID destAccount = uuid(req, "destination_account_id");
        if ("DEPOSIT_ACCOUNT".equals(method) && destAccount == null) {
            destAccount = uuid(loan, "disbursement_account_id");
        }
        if (Set.of("DEPOSIT_ACCOUNT", "MERCHANT_SETTLEMENT").contains(method) && destAccount == null) {
            throw ApiException.unprocessable("destination_required", "destination_account_id is required for " + method);
        }
        if ("WALLET".equals(method) && uuid(req, "destination_wallet_id") == null && uuid(loan, "disbursement_wallet_id") == null) {
            throw ApiException.unprocessable("destination_required", "destination_wallet_id is required for WALLET");
        }
        if (destAccount != null) {
            external.depositLedgerAccount(destAccount, str(loan, "currency"), "destination_account_id");
        }
        int tranche = rows.scalar("select count(*) + 1 from loan_disbursement where loan_id = ? and status in ('COMPLETED','REVERSED')",
                Integer.class, loanId).orElse(1);
        BigDecimal fee = BigDecimal.ZERO;
        boolean feeCharged = rows.scalar("select exists(select 1 from loan_fee_charge where loan_id = ? and fee_type = 'ORIGINATION')",
                Boolean.class, loanId).orElse(false);
        if (!feeCharged && tranche == 1) {
            fee = new BigDecimal(loan.path("product_terms").path("origination_fee").asString("0")).min(amount);
        }
        LocalDate scheduled = Rows.date(req, "scheduled_date");
        ObjectNode row = rows.object();
        row.put("loan_id", loanId.toString());
        row.put("tranche_no", tranche);
        row.put("amount", amount.toPlainString());
        row.put("fees_deducted", fee.toPlainString());
        row.put("net_amount", amount.subtract(fee).toPlainString());
        row.put("currency", str(loan, "currency"));
        row.put("method", method);
        if (destAccount != null) {
            row.put("destination_account_id", destAccount.toString());
        }
        UUID wallet = uuid(req, "destination_wallet_id") != null ? uuid(req, "destination_wallet_id") : uuid(loan, "disbursement_wallet_id");
        if ("WALLET".equals(method) && wallet != null) {
            row.put("destination_wallet_id", wallet.toString());
        }
        if (loan.hasNonNull("merchant_id") && "MERCHANT_SETTLEMENT".equals(method)) {
            row.put("merchant_id", str(loan, "merchant_id"));
        }
        boolean future = scheduled != null && scheduled.isAfter(loans.today());
        if (scheduled != null) {
            row.put("scheduled_date", scheduled.toString());
        }
        row.put("status", future ? "SCHEDULED" : "PENDING");
        row.put("idempotency_key", idempotencyKey);
        ObjectNode d = rows.insert(TABLE, row);
        if (future) {
            return d;
        }
        return execute(loan, d);
    }

    /** Retries a FAILED disbursement (new ledger key per attempt). */
    @Transactional
    public ObjectNode retry(UUID id) {
        ObjectNode d = rows.lock(TABLE, id);
        if (!Set.of("FAILED", "SCHEDULED").contains(str(d, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only failed or scheduled disbursements can be retried (status "
                    + str(d, "status") + ")");
        }
        ObjectNode loan = loans.lock(uuid(d, "loan_id"));
        if (!Set.of("PENDING_DISBURSEMENT", "ACTIVE").contains(str(loan, "status"))) {
            throw ApiException.conflict("invalid_transition", "The loan is " + str(loan, "status"));
        }
        ObjectNode upd = rows.object();
        upd.put("attempts", d.path("attempts").asInt(1) + ("FAILED".equals(str(d, "status")) ? 1 : 0));
        upd.put("status", "PENDING");
        d = rows.update(TABLE, id, upd);
        return execute(loan, d);
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode d = rows.lock(TABLE, id);
        if (!Set.of("SCHEDULED", "PENDING", "FAILED").contains(str(d, "status"))) {
            throw ApiException.conflict("invalid_transition", "A " + str(d, "status") + " disbursement cannot be cancelled");
        }
        ObjectNode upd = rows.object();
        upd.put("status", "CANCELLED");
        upd.put("failure_reason", reason);
        return rows.update(TABLE, id, upd);
    }

    /** Executes SCHEDULED disbursements due on or before {@code date} (end of day). Returns the number executed. */
    public List<UUID> scheduledDue(LocalDate date) {
        return rows.query("select row_to_json(d)::text from loan_disbursement d where status = 'SCHEDULED' and scheduled_date <= ? order by id",
                date).stream().map(r -> uuid(r, "id")).toList();
    }

    /** Posts a PENDING disbursement; records COMPLETED (and applies it to the loan) or FAILED. */
    ObjectNode execute(ObjectNode loan, ObjectNode d) {
        UUID id = uuid(d, "id");
        UUID loanId = uuid(loan, "id");
        BigDecimal amount = decOr0(d, "amount");
        BigDecimal fee = decOr0(d, "fees_deducted");
        String method = str(d, "method");
        UUID je;
        try {
            UUID receivable = loans.receivable(loan);
            UUID destination = uuid(d, "destination_account_id") != null
                    ? external.depositLedgerAccount(uuid(d, "destination_account_id"), str(loan, "currency"), "destination_account_id")
                    : loans.gl().clearing();
            je = loans.ledger().post(new LedgerPort.Posting(
                    LedgerPort.key("loan-disbursement", id, String.valueOf(d.path("attempts").asInt(1))), "LOAN",
                    "Disbursement " + str(loan, "loan_number") + " tranche " + d.path("tranche_no").asInt(1),
                    str(loan, "currency"), "LOAN_DISBURSEMENT", id, loans.today(),
                    new LedgerPort.Legs().debit(receivable, amount, "Loan principal")
                            .credit(destination, amount.subtract(fee), "Disbursement " + method)
                            .credit(loans.gl().feeIncome(), fee, "Origination fee").list()));
        } catch (ApiException e) {
            if (e.status() >= 500 && !"ledger_account_not_configured".equals(e.code())) {
                throw e;
            }
            ObjectNode upd = rows.object();
            upd.put("status", "FAILED");
            upd.put("failure_reason", (e.code() + ": " + e.getMessage()).substring(0, Math.min(255, (e.code() + ": " + e.getMessage()).length())));
            ObjectNode failed = rows.update(TABLE, id, upd);
            loans.events().publish("lending.loan.disbursement_failed", TABLE, failed);
            return failed;
        }
        ObjectNode upd = rows.object();
        upd.put("status", "COMPLETED");
        upd.put("journal_entry_id", je.toString());
        upd.put("disbursed_at", loans.now().toString());
        upd.putNull("failure_reason");
        ObjectNode done = rows.update(TABLE, id, upd);
        apply(loan, done);
        loans.events().publish("lending.loan.disbursed", TABLE, done);
        return done;
    }

    private void apply(ObjectNode loan, ObjectNode d) {
        UUID loanId = uuid(loan, "id");
        LocalDate today = loans.today();
        BigDecimal amount = decOr0(d, "amount");
        BigDecimal fee = decOr0(d, "fees_deducted");
        if (fee.signum() > 0) {
            ObjectNode f = rows.object();
            f.put("loan_id", loanId.toString());
            f.put("fee_type", "ORIGINATION");
            f.put("amount", fee.toPlainString());
            f.put("paid_amount", fee.toPlainString());
            f.put("currency", str(loan, "currency"));
            f.put("charge_date", today.toString());
            f.put("status", "PAID");
            f.put("journal_entry_id", str(d, "journal_entry_id"));
            loans.events().publish("lending.loan.fee_charged", "loan_fee_charge", rows.insert("loan_fee_charge", f));
        }
        boolean first = "PENDING_DISBURSEMENT".equals(str(loan, "status"));
        ObjectNode upd = rows.object();
        upd.put("disbursed_amount", decOr0(loan, "disbursed_amount").add(amount).toPlainString());
        upd.put("outstanding_principal", decOr0(loan, "outstanding_principal").add(amount).toPlainString());
        if (first) {
            upd.put("start_date", today.toString());
            int days = new ProductTerms(loan.get("product_terms")).intParam("lending.cooling_off_days")
                    .orElse(loans.props().coolingOffDays());
            upd.put("cooling_off_until", today.plusDays(days).toString());
            upd.put("last_accrual_date", today.minusDays(1).toString());
        }
        loan.setAll(rows.update(LoanSupport.LOAN, loanId, upd));
        if (first) {
            loans.transition(loan, "ACTIVE", "disbursed", today);
        }
        // the schedule covers the disbursed principal from the first disbursement date
        LocalDate start = Rows.date(loan, "start_date");
        Frequency freq = Frequency.parse(str(loan, "repayment_frequency"));
        LocalDate firstDue = Rows.date(loan, "first_repayment_date");
        if (firstDue == null || !firstDue.isAfter(start)) {
            firstDue = freq.next(start, loan.path("term_months").asInt(1));
            ObjectNode u = rows.object();
            u.put("first_repayment_date", firstDue.toString());
            loan.setAll(rows.update(LoanSupport.LOAN, loanId, u));
        }
        BigDecimal rate = decOr0(loan, "interest_rate");
        ScheduleGenerator.Schedule s = loans.generate(loans.terms(loan, decOr0(loan, "outstanding_principal"), rate,
                freq.periods(loan.path("term_months").asInt(1), start), start, firstDue, null));
        loans.persistSchedule(loan, s, first ? "ORIGINATION" : "DISBURSEMENT", start, rate, true);
        loans.adjust(loan, Map.of());
    }
}
