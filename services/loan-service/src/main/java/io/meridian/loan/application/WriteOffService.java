package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loan.domain.Currencies;
import io.meridian.loan.domain.Lifecycles;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Write-offs with maker-checker (request, approve and post, reject) and post-write-off recoveries. */
@Service
public class WriteOffService {
    static final String TABLE = "loan_write_off";

    private final LoanSupport loans;
    private final Rows rows;

    public WriteOffService(LoanSupport loans) {
        this.loans = loans;
        this.rows = loans.rows();
    }

    @Transactional
    public ObjectNode request(UUID loanId, JsonNode req) {
        ObjectNode loan = loans.lock(loanId);
        RepaymentService.requireServiceable(loan);
        boolean pending = rows.scalar("select exists(select 1 from loan_write_off where loan_id = ? and status = 'PENDING_APPROVAL')",
                Boolean.class, loanId).orElse(false);
        if (pending) {
            throw ApiException.conflict("write_off_pending", "A write-off of this loan is already awaiting approval");
        }
        String type = str(req, "write_off_type");
        ObjectNode w = rows.object();
        w.put("loan_id", loanId.toString());
        w.put("write_off_type", type);
        w.put("reason", str(req, "reason"));
        w.put("currency", str(loan, "currency"));
        if ("FULL".equals(type)) {
            fullAmounts(loan, w);
        } else {
            BigDecimal p = Rows.dec(req, "principal_amount");
            if (p == null || p.signum() <= 0 || p.compareTo(decOr0(loan, "outstanding_principal")) >= 0
                    || !Currencies.fitsMinorUnit(p, str(loan, "currency"))) {
                throw ApiException.unprocessable("invalid_amount",
                        "principal_amount must be positive and below the outstanding principal for a PARTIAL write-off");
            }
            w.put("principal_amount", p.toPlainString());
            w.put("interest_amount", "0");
            w.put("fees_amount", "0");
            w.put("total_amount", p.toPlainString());
        }
        w.put("status", "PENDING_APPROVAL");
        ObjectNode row = rows.insert(TABLE, w);
        loans.events().publish("lending.loan.write_off_requested", TABLE, row);
        return row;
    }

    private static void fullAmounts(JsonNode loan, ObjectNode w) {
        BigDecimal p = decOr0(loan, "outstanding_principal");
        BigDecimal i = decOr0(loan, "outstanding_interest").max(BigDecimal.ZERO);
        BigDecimal f = decOr0(loan, "outstanding_fees").add(decOr0(loan, "outstanding_penalties"));
        w.put("principal_amount", p.toPlainString());
        w.put("interest_amount", i.toPlainString());
        w.put("fees_amount", f.toPlainString());
        w.put("total_amount", p.add(i).add(f).toPlainString());
    }

    @Transactional
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode w = rows.get(TABLE, id);
        ObjectNode loan = loans.lock(uuid(w, "loan_id"));
        w = rows.lock(TABLE, id);
        move(w, "POSTED");
        String me = TenantContext.principal();
        if (me.equals(str(w, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The write-off must be approved by a user other than the requester");
        }
        RepaymentService.requireServiceable(loan);
        boolean full = "FULL".equals(str(w, "write_off_type"));
        ObjectNode amounts = rows.object();
        if (full) {
            fullAmounts(loan, amounts);
        } else {
            if (decOr0(w, "principal_amount").compareTo(decOr0(loan, "outstanding_principal")) >= 0) {
                throw ApiException.conflict("write_off_exceeds_balance", "The outstanding principal fell below the requested write-off");
            }
            amounts.put("principal_amount", str(w, "principal_amount"));
            amounts.put("interest_amount", "0");
            amounts.put("fees_amount", "0");
            amounts.put("total_amount", str(w, "principal_amount"));
        }
        BigDecimal p = decOr0(amounts, "principal_amount");
        BigDecimal i = decOr0(amounts, "interest_amount");
        BigDecimal fees = full ? decOr0(loan, "outstanding_fees") : BigDecimal.ZERO;
        BigDecimal pen = full ? decOr0(loan, "outstanding_penalties") : BigDecimal.ZERO;
        BigDecimal total = p.add(i).add(fees).add(pen);
        UUID je = loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-write-off", id, null), "ADJUSTMENT",
                "Write-off " + str(loan, "loan_number") + ": " + str(w, "reason"), str(loan, "currency"), "LOAN_WRITE_OFF", id,
                loans.today(), new LedgerPort.Legs().debit(loans.gl().writeOffExpense(), total, "Credit loss")
                .credit(loans.receivable(loan), p, "Principal written off")
                .credit(loans.gl().interestReceivable(), i, "Interest written off")
                .credit(loans.gl().feeReceivable(), fees, "Fees written off")
                .credit(loans.gl().penaltyReceivableOrInterest(), pen, "Penalties written off").list()));
        UUID loanId = uuid(loan, "id");
        if (full) {
            rows.execute("update schedule_installment set waived_amount = waived_amount + greatest(principal_due - principal_paid, 0) "
                    + "+ greatest(interest_due - interest_paid, 0) + greatest(fees_due - fees_paid, 0) + greatest(penalties_due - penalties_paid, 0), "
                    + "status = 'WAIVED' where loan_id = ? and status in " + LoanSupport.OPEN_SQL, loanId);
            rows.execute("update loan_fee_charge set status = 'WAIVED', waived_by = ?, waiver_reason = 'written off' where loan_id = ? and status in ('CHARGED','PARTIALLY_PAID')",
                    me, loanId);
            rows.execute("update repayment_schedule set status = 'CLOSED' where loan_id = ? and status = 'ACTIVE'", loanId);
            ObjectNode upd = rows.object();
            for (String f : java.util.List.of("outstanding_principal", "outstanding_interest", "outstanding_fees", "outstanding_penalties",
                    "total_outstanding", "overdue_amount")) {
                upd.put(f, "0");
            }
            upd.put("ecl_stage", "STAGE_3");
            loan.setAll(rows.update(LoanSupport.LOAN, loanId, upd));
            loans.transition(loan, "WRITTEN_OFF", "written off: " + str(w, "reason"), loans.today());
        } else {
            Map<String, BigDecimal> deltas = new LinkedHashMap<>();
            deltas.put("outstanding_principal", p.negate());
            loans.adjust(loan, deltas);
            loans.regenerateFuture(loan, "PARTIAL_WRITE_OFF", loans.today(), decOr0(loan, "interest_rate"), null, null);
        }
        ObjectNode upd = rows.object();
        upd.setAll(amounts);
        upd.put("fees_amount", fees.add(pen).toPlainString());
        upd.put("total_amount", total.toPlainString());
        upd.put("status", "POSTED");
        upd.put("approved_by", me);
        upd.put("journal_entry_id", je.toString());
        upd.put("posted_at", loans.now().toString());
        ObjectNode posted = rows.update(TABLE, id, upd);
        loans.events().publish("lending.loan.written_off", TABLE, posted);
        return posted;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode w = rows.lock(TABLE, id);
        move(w, "REJECTED");
        ObjectNode upd = rows.object();
        upd.put("status", "REJECTED");
        upd.put("rejection_reason", reason);
        return rows.update(TABLE, id, upd);
    }

    private static void move(JsonNode w, String to) {
        try {
            Lifecycles.require(Lifecycles.WRITE_OFF, "Write-off", str(w, "status"), to);
        } catch (Lifecycles.InvalidTransition e) {
            throw ApiException.conflict(e.code(), e.getMessage());
        }
    }

    /**
     * A recovery after write-off: cash received (net of the agency commission) against recovery income; the
     * write-off's recovered amount grows.
     */
    @Transactional
    public ObjectNode recovery(JsonNode req) {
        UUID loanId = uuid(req, "loan_id");
        ObjectNode loan = rows.find(LoanSupport.LOAN, loanId).map(l -> loans.lock(loanId))
                .orElseThrow(() -> ApiException.unprocessable("loan_not_found", "loan_id " + loanId + " does not exist"));
        UUID writeOffId = uuid(req, "loan_write_off_id");
        ObjectNode w = writeOffId != null
                ? rows.find(TABLE, writeOffId).orElseThrow(() -> ApiException.unprocessable("write_off_not_found", "loan_write_off_id does not exist"))
                : rows.queryOne("select row_to_json(w)::text from loan_write_off w where loan_id = ? and status = 'POSTED' order by posted_at desc limit 1", loanId)
                .orElseThrow(() -> ApiException.conflict("loan_not_written_off", "Loan " + loanId + " has no posted write-off"));
        if (!loanId.equals(uuid(w, "loan_id")) || !"POSTED".equals(str(w, "status"))) {
            throw ApiException.unprocessable("invalid_write_off", "The write-off must be a posted write-off of the loan");
        }
        BigDecimal amount = Rows.dec(req, "amount");
        BigDecimal commission = Rows.decOr0(req, "agency_commission");
        if (!str(loan, "currency").equals(str(req, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The recovery currency must be the loan currency");
        }
        if (amount == null || amount.signum() <= 0 || commission.signum() < 0 || commission.compareTo(amount) > 0
                || !Currencies.fitsMinorUnit(amount, str(loan, "currency"))) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive and the commission between 0 and the amount");
        }
        UUID id = io.meridian.common.id.Ids.newId();
        UUID je = loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-recovery", id, null), "PAYMENT",
                "Recovery " + str(loan, "loan_number") + " (" + str(req, "source") + ")", str(loan, "currency"), "LOAN_RECOVERY", id,
                loans.today(), new LedgerPort.Legs().debit(loans.gl().clearing(), amount.subtract(commission), "Recovery received")
                .debit(loans.gl().agencyCommission() != null ? loans.gl().agencyCommission() : loans.gl().writeOffExpense(), commission, "Agency commission")
                .credit(loans.gl().recoveryIncome(), amount, "Recovery of written-off debt").list()));
        ObjectNode r = rows.object();
        r.put("id", id.toString());
        r.put("loan_id", loanId.toString());
        r.put("loan_write_off_id", str(w, "id"));
        r.put("amount", amount.toPlainString());
        r.put("currency", str(loan, "currency"));
        r.put("source", str(req, "source"));
        r.put("agency_commission", commission.toPlainString());
        OffsetDateTime at = Rows.ts(req, "received_at");
        r.put("received_at", (at == null ? loans.now() : at).toString());
        if (req.hasNonNull("external_reference")) {
            r.put("external_reference", str(req, "external_reference"));
        }
        r.put("journal_entry_id", je.toString());
        ObjectNode recovery = rows.insert("loan_recovery", r);
        ObjectNode wu = rows.object();
        wu.put("recovered_amount", decOr0(w, "recovered_amount").add(amount).toPlainString());
        rows.update(TABLE, uuid(w, "id"), wu);
        loans.events().publish("lending.loan.recovery_received", "loan_recovery", recovery);
        return recovery;
    }
}
