package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.loan.domain.Currencies;
import io.meridian.loan.domain.DayCount;
import io.meridian.loan.infrastructure.ExternalClients;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prepayment quotes (full pay-off or partial with reduced term or instalment) valid until a database-clock time, and
 * prepayment against a valid quote, including the early repayment charge from the product's LOAN_PREPAYMENT fee.
 */
@Service
public class PrepaymentService {
    static final String TABLE = "loan_prepayment_quote";

    private final LoanSupport loans;
    private final Rows rows;
    private final RepaymentService repayments;
    private final ExternalClients external;

    public PrepaymentService(LoanSupport loans, RepaymentService repayments, ExternalClients external) {
        this.loans = loans;
        this.rows = loans.rows();
        this.repayments = repayments;
        this.external = external;
    }

    @Transactional
    public ObjectNode quote(UUID loanId, JsonNode req) {
        ObjectNode loan = loans.lock(loanId);
        RepaymentService.requireServiceable(loan);
        String type = str(req, "quote_type");
        int scale = LoanSupport.scale(loan);
        LocalDate today = loans.today(loan);
        ProductTerms terms = ProductTerms.of(loan);
        BigDecimal principal;
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        String option = null;
        BigDecimal requested = Rows.dec(req, "requested_amount");
        if ("FULL".equals(type)) {
            principal = decOr0(loan, "outstanding_principal");
            interest = decOr0(loan, "outstanding_interest").add(unaccrued(loan, today));
            fees = decOr0(loan, "outstanding_fees").add(decOr0(loan, "outstanding_penalties"));
        } else {
            if (requested == null || requested.signum() <= 0 || !Currencies.fitsMinorUnit(requested, str(loan, "currency"))) {
                throw ApiException.unprocessable("invalid_amount", "requested_amount is required for PARTIAL quotes");
            }
            if (requested.compareTo(decOr0(loan, "outstanding_principal")) >= 0) {
                throw ApiException.unprocessable("use_full_prepayment", "requested_amount covers the whole principal; request a FULL quote");
            }
            if (decOr0(loan, "overdue_amount").signum() > 0) {
                throw ApiException.conflict("loan_in_arrears", "Arrears must be paid before a partial prepayment");
            }
            principal = requested;
            option = str(req, "partial_option") == null ? "REDUCE_TERM" : str(req, "partial_option");
        }
        BigDecimal erc = terms.feeAmount("LOAN_PREPAYMENT", principal, scale).orElse(BigDecimal.ZERO);
        interest = interest.setScale(scale, RoundingMode.HALF_EVEN);
        BigDecimal total = principal.add(interest).add(fees).add(erc).max(BigDecimal.ZERO);
        OffsetDateTime validUntil = rows.scalar("select now() + make_interval(hours => ?)", OffsetDateTime.class,
                loans.props().quoteValidityHours()).orElseThrow();
        ObjectNode q = rows.object();
        q.put("loan_id", loanId.toString());
        q.put("quote_type", type);
        if (option != null) {
            q.put("partial_option", option);
        }
        if (requested != null) {
            q.put("requested_amount", requested.toPlainString());
        }
        q.put("quote_date", today.toString());
        q.put("principal", principal.toPlainString());
        q.put("interest", interest.toPlainString());
        q.put("fees", fees.toPlainString());
        q.put("prepayment_penalty", erc.toPlainString());
        q.put("total_amount", total.toPlainString());
        q.put("currency", str(loan, "currency"));
        q.put("valid_until", validUntil.toString());
        q.put("status", "ISSUED");
        ObjectNode quote = rows.insert(TABLE, q);
        loans.events().publish("lending.loan.prepayment_quoted", TABLE, quote);
        return quote;
    }

    /** Interest for the days after the last accrual up to (excluding) {@code payoff}. */
    private BigDecimal unaccrued(JsonNode loan, LocalDate payoff) {
        LocalDate last = Rows.date(loan, "last_accrual_date");
        LocalDate from = last == null ? Rows.date(loan, "start_date") : last.plusDays(1);
        if (from == null || !from.isBefore(payoff)) {
            return BigDecimal.ZERO;
        }
        return decOr0(loan, "outstanding_principal").multiply(decOr0(loan, "interest_rate"), MathContext.DECIMAL128)
                .multiply(DayCount.parse(str(loan, "day_count")).yearFraction(from, payoff), MathContext.DECIMAL128);
    }

    @Transactional
    public ObjectNode prepay(UUID loanId, JsonNode req, String idempotencyKey) {
        ObjectNode loan = loans.lock(loanId);
        RepaymentService.requireServiceable(loan);
        UUID quoteId = uuid(req, "loan_prepayment_quote_id");
        ObjectNode quote = rows.find(TABLE, quoteId).orElseThrow(() -> ApiException.unprocessable("quote_not_found",
                "loan_prepayment_quote_id " + quoteId + " does not exist"));
        quote = rows.lock(TABLE, quoteId);
        if (!loanId.equals(uuid(quote, "loan_id"))) {
            throw ApiException.unprocessable("quote_not_for_loan", "The quote belongs to another loan");
        }
        if (!"ISSUED".equals(str(quote, "status"))) {
            throw ApiException.conflict("quote_not_usable", "The quote is " + str(quote, "status"));
        }
        boolean valid = rows.scalar("select valid_until > now() from loan_prepayment_quote where id = ?", Boolean.class, quoteId)
                .orElse(false);
        if (!valid) {
            throw ApiException.unprocessable("quote_expired", "The quote expired at " + str(quote, "valid_until") + "; request a new one");
        }
        boolean full = "FULL".equals(str(quote, "quote_type"));
        String source = str(req, "source") == null ? "MANUAL" : str(req, "source");
        UUID sourceAccount = uuid(req, "source_account_id");
        if (sourceAccount == null && "AUTO_DEBIT".equals(source)) {
            sourceAccount = uuid(loan, "repayment_account_id");
        }
        UUID funding = sourceAccount != null ? external.depositLedgerAccount(sourceAccount, str(loan, "currency"), "source_account_id")
                : loans.gl().clearing();
        BigDecimal principal = decOr0(quote, "principal");
        BigDecimal interest = decOr0(quote, "interest");
        BigDecimal erc = decOr0(quote, "prepayment_penalty");
        BigDecimal fees = full ? decOr0(loan, "outstanding_fees") : BigDecimal.ZERO;
        BigDecimal penalties = full ? decOr0(loan, "outstanding_penalties") : BigDecimal.ZERO;
        if (full && principal.compareTo(decOr0(loan, "outstanding_principal")) != 0) {
            throw ApiException.conflict("quote_stale", "The loan changed since the quote was issued; request a new quote");
        }
        BigDecimal total = principal.add(interest.max(BigDecimal.ZERO)).add(fees).add(penalties).add(erc);
        UUID id = Ids.newId();
        LedgerPort.Legs legs = new LedgerPort.Legs().debit(funding, total, "Prepayment " + source)
                .credit(loans.receivable(loan), principal, "Principal")
                .credit(loans.gl().interestReceivable(), interest.max(BigDecimal.ZERO), "Interest")
                .credit(loans.gl().feeReceivable(), fees, "Fees")
                .credit(loans.gl().penaltyReceivableOrInterest(), penalties, "Penalties")
                .credit(loans.gl().feeIncome(), erc, "Early repayment charge");
        UUID je = loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-prepayment", quoteId, null), "PAYMENT",
                (full ? "Pay-off " : "Partial prepayment ") + str(loan, "loan_number"), str(loan, "currency"), "LOAN_REPAYMENT",
                id, loans.today(loan), legs.list()));
        ArrayNode detail = rows.json().createArrayNode();
        LocalDate today = loans.today(loan);
        if (full) {
            for (ObjectNode i : loans.openInstallments(loanId)) {
                boolean due = !Rows.date(i, "due_date").isAfter(today);
                ObjectNode u = rows.object();
                if (due) {
                    u.put("principal_paid", str(i, "principal_due"));
                    u.put("interest_paid", str(i, "interest_due"));
                    u.put("fees_paid", str(i, "fees_due"));
                    u.put("penalties_paid", str(i, "penalties_due"));
                    u.put("status", "PAID");
                    u.put("paid_at", loans.now().toString());
                } else {
                    u.put("status", "RESCHEDULED");
                }
                rows.update(LoanSupport.INSTALLMENT, uuid(i, "id"), u);
            }
            rows.execute("update loan_fee_charge set paid_amount = amount + tax_amount, status = 'PAID' where loan_id = ? and status in ('CHARGED','PARTIALLY_PAID')", loanId);
            detail.addObject().put("type", "PREPAYMENT_FULL").put("component", "PRINCIPAL").put("amount", principal.toPlainString());
            detail.addObject().put("type", "PREPAYMENT_FULL").put("component", "INTEREST").put("amount", interest.max(BigDecimal.ZERO).toPlainString());
            detail.addObject().put("type", "PREPAYMENT_FULL").put("component", "FEE").put("amount", fees.toPlainString());
            detail.addObject().put("type", "PREPAYMENT_FULL").put("component", "PENALTY").put("amount", penalties.toPlainString());
        } else {
            detail.addObject().put("type", "PREPAYMENT").put("component", "PRINCIPAL").put("amount", principal.toPlainString())
                    .put("option", str(quote, "partial_option"));
        }
        if (erc.signum() > 0) {
            ObjectNode f = rows.object();
            f.put("loan_id", loanId.toString());
            f.put("fee_type", "PREPAYMENT");
            f.put("amount", erc.toPlainString());
            f.put("paid_amount", erc.toPlainString());
            f.put("currency", str(loan, "currency"));
            f.put("charge_date", today.toString());
            f.put("status", "PAID");
            f.put("journal_entry_id", je.toString());
            ObjectNode fee = rows.insert("loan_fee_charge", f);
            detail.addObject().put("type", "EARLY_REPAYMENT_CHARGE").put("id", str(fee, "id")).put("component", "FEE")
                    .put("amount", erc.toPlainString());
        }
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("outstanding_principal", principal.negate());
        deltas.put("outstanding_interest", interest.max(BigDecimal.ZERO).negate());
        deltas.put("outstanding_fees", fees.negate());
        deltas.put("outstanding_penalties", penalties.negate());
        loans.adjust(loan, deltas);
        ObjectNode row = rows.object();
        row.put("id", id.toString());
        row.put("loan_id", loanId.toString());
        row.put("amount", total.toPlainString());
        row.put("currency", str(loan, "currency"));
        row.put("source", source);
        row.put("value_date", today.toString());
        row.put("allocated_principal", principal.toPlainString());
        row.put("allocated_interest", interest.max(BigDecimal.ZERO).toPlainString());
        row.put("allocated_fees", fees.add(erc).toPlainString());
        row.put("allocated_penalties", penalties.toPlainString());
        row.put("excess_amount", "0");
        row.set("allocation_detail", detail);
        row.put("status", "POSTED");
        if (sourceAccount != null) {
            row.put("source_account_id", sourceAccount.toString());
        }
        row.put("prepayment_quote_id", quoteId.toString());
        row.put("journal_entry_id", je.toString());
        row.put("idempotency_key", idempotencyKey);
        ObjectNode repayment = rows.insert(RepaymentService.TABLE, row);
        ObjectNode qu = rows.object();
        qu.put("status", "USED");
        qu.put("loan_repayment_id", id.toString());
        rows.update(TABLE, quoteId, qu);
        if (full) {
            repayments.settleIfPaidOff(loan, "prepaid in full");
        } else {
            BigDecimal instalment = "REDUCE_TERM".equals(str(quote, "partial_option")) ? rows.scalar("""
                    select i.total_due from schedule_installment i join repayment_schedule s on s.id = i.repayment_schedule_id
                    where i.loan_id = ? and s.status = 'ACTIVE' and i.status = 'SCHEDULED' order by i.due_date limit 1""",
                    BigDecimal.class, loanId).orElse(null) : null;
            loans.regenerateFuture(loan, "PREPAYMENT", today, decOr0(loan, "interest_rate"), null, instalment);
            loans.delinquency(loan, today, "partial prepayment");
        }
        loans.events().publish("lending.loan.repayment_received", RepaymentService.TABLE, repayment);
        return repayment;
    }

    /** Marks quotes past their validity EXPIRED (database clock). */
    @Transactional
    public int expireQuotes() {
        return rows.execute("update loan_prepayment_quote set status = 'EXPIRED' where status = 'ISSUED' and valid_until <= now()");
    }
}
