package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loan.domain.Currencies;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Fee charges on loans (manual, late payment) and waivers, posted to the fee receivable and fee income. */
@Service
public class FeeService {
    static final String TABLE = "loan_fee_charge";

    private final LoanSupport loans;
    private final Rows rows;

    public FeeService(LoanSupport loans) {
        this.loans = loans;
        this.rows = loans.rows();
    }

    @Transactional
    public ObjectNode create(JsonNode req, String idempotencyKey) {
        UUID loanId = uuid(req, "loan_id");
        ObjectNode loan = rows.find(LoanSupport.LOAN, loanId).map(l -> loans.lock(loanId))
                .orElseThrow(() -> ApiException.unprocessable("loan_not_found", "loan_id " + loanId + " does not exist"));
        RepaymentService.requireServiceable(loan);
        String type = str(req, "fee_type");
        if ("ORIGINATION".equals(type)) {
            throw ApiException.unprocessable("invalid_fee_type", "Origination fees are charged with the first disbursement");
        }
        BigDecimal amount = Rows.dec(req, "amount");
        BigDecimal tax = Rows.decOr0(req, "tax_amount");
        String currency = str(req, "currency");
        if (!str(loan, "currency").equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch", "The fee currency must be the loan currency " + str(loan, "currency"));
        }
        if (amount == null || amount.signum() <= 0 || tax.signum() < 0 || !Currencies.fitsMinorUnit(amount, currency)
                || !Currencies.fitsMinorUnit(tax, currency)) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive and amounts must fit the currency minor unit");
        }
        UUID inst = uuid(req, "schedule_installment_id");
        if (inst != null) {
            ObjectNode i = rows.find(LoanSupport.INSTALLMENT, inst).orElseThrow(() -> ApiException.unprocessable(
                    "installment_not_found", "schedule_installment_id does not exist"));
            if (!loanId.equals(uuid(i, "loan_id")) || !LoanSupport.OPEN_INSTALLMENT.contains(str(i, "status"))) {
                throw ApiException.unprocessable("invalid_installment", "The instalment must be an open instalment of the loan");
            }
        }
        LocalDate date = Rows.date(req, "charge_date") != null ? Rows.date(req, "charge_date") : loans.today();
        return charge(loan, type, amount, tax, date, inst, uuid(req, "fee_definition_id"), "manual:" + idempotencyKey);
    }

    /** Charges a fee to a locked loan (manual or late fee): ledger, fee row, instalment fees, loan balances, event. */
    public ObjectNode charge(ObjectNode loan, String type, BigDecimal amount, BigDecimal tax, LocalDate date, UUID installmentId,
                             UUID feeDefinitionId, String discriminator) {
        UUID id = io.meridian.common.id.Ids.newId();
        UUID je = loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-fee", uuid(loan, "id"), discriminator), "FEE",
                type + " fee " + str(loan, "loan_number"), str(loan, "currency"), "LOAN_FEE", id, date,
                new LedgerPort.Legs().debit(loans.gl().feeReceivable(), amount.add(tax), "Fee receivable")
                        .credit(loans.gl().feeIncome(), amount, type + " fee")
                        .credit(loans.gl().taxPayable(), tax, "Tax on fee").list()));
        ObjectNode f = rows.object();
        f.put("id", id.toString());
        f.put("loan_id", str(loan, "id"));
        if (feeDefinitionId != null) {
            f.put("fee_definition_id", feeDefinitionId.toString());
        }
        f.put("fee_type", type);
        f.put("amount", amount.toPlainString());
        f.put("tax_amount", tax.toPlainString());
        f.put("currency", str(loan, "currency"));
        f.put("charge_date", date.toString());
        if (installmentId != null) {
            f.put("schedule_installment_id", installmentId.toString());
            ObjectNode i = rows.lock(LoanSupport.INSTALLMENT, installmentId);
            ObjectNode upd = rows.object();
            upd.put("fees_due", decOr0(i, "fees_due").add(amount).add(tax).toPlainString());
            upd.put("total_due", decOr0(i, "total_due").add(amount).add(tax).toPlainString());
            rows.update(LoanSupport.INSTALLMENT, installmentId, upd);
        }
        f.put("status", "CHARGED");
        f.put("journal_entry_id", je.toString());
        ObjectNode fee = rows.insert(TABLE, f);
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("outstanding_fees", amount.add(tax));
        loans.adjust(loan, deltas);
        loans.events().publish("lending.loan.fee_charged", TABLE, fee);
        return fee;
    }

    @Transactional
    public ObjectNode waive(UUID feeId, String reason) {
        ObjectNode fee = rows.get(TABLE, feeId);
        ObjectNode loan = loans.lock(uuid(fee, "loan_id"));
        fee = rows.lock(TABLE, feeId);
        if (!Set.of("CHARGED", "PARTIALLY_PAID").contains(str(fee, "status"))) {
            throw ApiException.conflict("invalid_transition", "A " + str(fee, "status") + " fee cannot be waived");
        }
        BigDecimal amount = decOr0(fee, "amount");
        BigDecimal tax = decOr0(fee, "tax_amount");
        BigDecimal unpaid = RepaymentService.feeOwed(fee);
        // waive proportionally between the fee and its tax
        BigDecimal taxPart = amount.add(tax).signum() == 0 ? BigDecimal.ZERO
                : unpaid.multiply(tax).divide(amount.add(tax), LoanSupport.scale(loan), java.math.RoundingMode.HALF_EVEN);
        loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-fee-waiver", feeId, null), "ADJUSTMENT",
                "Fee waiver " + str(loan, "loan_number") + ": " + reason, str(loan, "currency"), "LOAN_FEE", feeId,
                loans.today(), new LedgerPort.Legs().debit(loans.gl().feeIncome(), unpaid.subtract(taxPart), "Fee waived")
                .debit(loans.gl().taxPayable(), taxPart, "Tax on waived fee")
                .credit(loans.gl().feeReceivable(), unpaid, "Fee receivable").list()));
        UUID inst = uuid(fee, "schedule_installment_id");
        if (inst != null) {
            ObjectNode i = rows.lock(LoanSupport.INSTALLMENT, inst);
            ObjectNode upd = rows.object();
            upd.put("fees_due", decOr0(i, "fees_due").subtract(unpaid).toPlainString());
            upd.put("total_due", decOr0(i, "total_due").subtract(unpaid).toPlainString());
            upd.put("waived_amount", decOr0(i, "waived_amount").add(unpaid).toPlainString());
            i.setAll(upd);
            if (LoanSupport.OPEN_INSTALLMENT.contains(str(i, "status"))) {
                upd.put("status", LoanSupport.installmentStatus(i, loans.today()));
            }
            rows.update(LoanSupport.INSTALLMENT, inst, upd);
        }
        ObjectNode upd = rows.object();
        upd.put("status", "WAIVED");
        upd.put("waived_by", TenantContext.principal());
        upd.put("waiver_reason", reason);
        ObjectNode waived = rows.update(TABLE, feeId, upd);
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("outstanding_fees", unpaid.negate());
        loans.adjust(loan, deltas);
        loans.delinquency(loan, loans.today(), "fee waived");
        loans.events().publish("lending.loan.fee_waived", TABLE, waived);
        return waived;
    }
}
