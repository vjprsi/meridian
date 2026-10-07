package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.dec;
import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loan.domain.Apr;
import io.meridian.loan.domain.Currencies;
import io.meridian.loan.domain.Frequency;
import io.meridian.loan.domain.ScheduleGenerator;
import io.meridian.loan.infrastructure.ExternalClients;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Loan booking (from an offer or directly), PATCH rules, cancellation, closing, auto-debit settings, simulation. */
@Service
public class LoanService {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Fields a PATCH may change; everything else is owned by business actions. */
    static final Set<String> PATCHABLE = Set.of("branch_id", "risk_grade", "metadata", "disbursement_account_id",
            "disbursement_wallet_id", "custom_field_1", "custom_field_2", "custom_field_3", "custom_field_4",
            "custom_field_5");

    private final LoanSupport loans;
    private final Rows rows;
    private final ExternalClients external;
    private final Events events;

    public LoanService(LoanSupport loans, ExternalClients external) {
        this.loans = loans;
        this.rows = loans.rows();
        this.external = external;
        this.events = loans.events();
    }

    // ------------------------------------------------------------------ booking

    /** Books a loan: validates against the product terms, pins them, generates the indicative schedule. */
    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode values = rows.columns(LoanSupport.LOAN, req);
        String currency = str(values, "currency");
        BigDecimal principal = dec(values, "principal_amount");
        int term = values.path("term_months").asInt(0);
        BigDecimal rate = dec(values, "interest_rate");
        if (principal == null || principal.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "principal_amount must be positive");
        }
        if (!Currencies.fitsMinorUnit(principal, currency)) {
            throw ApiException.unprocessable("invalid_amount", "principal_amount has more decimals than " + currency + " allows");
        }
        if (term < 1 || term > 600) {
            throw ApiException.unprocessable("invalid_term", "term_months must be 1-600");
        }
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid_rate", "interest_rate must be between 0 and 1 (fraction)");
        }
        if ("ZERO".equals(str(values, "interest_rate_type")) && rate.signum() != 0) {
            throw ApiException.unprocessable("invalid_rate", "ZERO rate loans must have interest_rate 0");
        }
        UUID productId = uuid(values, "product_id");
        UUID versionId = uuid(values, "product_version_id");
        ObjectNode terms = external.productTerms(productId, versionId, currency);
        ProductTerms pt = new ProductTerms(terms);
        pt.decimalParam("lending.min_amount").filter(min -> principal.compareTo(min) < 0).ifPresent(min -> {
            throw ApiException.unprocessable("amount_out_of_range", "principal_amount is below the product minimum " + min.toPlainString());
        });
        pt.decimalParam("lending.max_amount").filter(max -> principal.compareTo(max) > 0).ifPresent(max -> {
            throw ApiException.unprocessable("amount_out_of_range", "principal_amount is above the product maximum " + max.toPlainString());
        });
        pt.intParam("lending.min_term_months").filter(min -> term < min).ifPresent(min -> {
            throw ApiException.unprocessable("term_out_of_range", "term_months is below the product minimum " + min);
        });
        pt.intParam("lending.max_term_months").filter(max -> term > max).ifPresent(max -> {
            throw ApiException.unprocessable("term_out_of_range", "term_months is above the product maximum " + max);
        });
        int scale = Currencies.digits(currency);
        BigDecimal originationFee = Rows.dec(req, "origination_fee");
        if (originationFee == null) {
            originationFee = pt.feeAmount("LOAN_ORIGINATION", principal, scale).orElse(BigDecimal.ZERO);
        }
        if (originationFee.signum() < 0 || originationFee.compareTo(principal) >= 0) {
            throw ApiException.unprocessable("invalid_fee", "origination_fee must be between 0 and the principal");
        }
        terms.put("origination_fee", originationFee.setScale(scale, java.math.RoundingMode.HALF_EVEN).toPlainString());
        if (!values.hasNonNull("penalty_rate")) {
            pt.rate("PENALTY_INTEREST").ifPresent(p -> values.put("penalty_rate", p.toPlainString()));
        }
        if (!values.hasNonNull("day_count")) {
            values.put("day_count", pt.dayCount("DEBIT_INTEREST").orElse("ACT_365"));
        }
        if (!values.hasNonNull("grace_period_days")) {
            values.put("grace_period_days", pt.intParam("lending.grace_period_days").orElse(0));
        }
        if (!values.hasNonNull("business_day_convention")) {
            values.put("business_day_convention", "MODIFIED_FOLLOWING");
        }
        if (!values.hasNonNull("interest_rate_type")) {
            values.put("interest_rate_type", rate.signum() == 0 ? "ZERO" : "FIXED");
        }
        for (String f : List.of("disbursement_account_id", "repayment_account_id")) {
            UUID acc = uuid(values, f);
            if (acc != null) {
                external.depositLedgerAccount(acc, currency, f);
            }
        }
        if (Rows.bool(values, "auto_debit_enabled") || !values.has("auto_debit_enabled")) {
            values.put("auto_debit_enabled", values.hasNonNull("repayment_account_id"));
        }
        LocalDate start = Rows.date(values, "start_date") != null ? Rows.date(values, "start_date") : loans.today(values);
        values.put("start_date", start.toString());
        Frequency freq = Frequency.parse(str(values, "repayment_frequency"));
        if (!values.hasNonNull("first_repayment_date")) {
            values.put("first_repayment_date", freq.next(start, term).toString());
        }
        values.set("product_terms", terms);
        values.put("loan_number", number("LN"));
        values.put("status", "PENDING_DISBURSEMENT");
        values.put("disbursed_amount", "0");
        values.put("outstanding_principal", "0");
        ObjectNode loan = rows.insert(LoanSupport.LOAN, values);
        ScheduleGenerator.Schedule s = loans.generate(loans.terms(loan, principal, rate,
                freq.periods(term, start), start, Rows.date(loan, "first_repayment_date"), null));
        loans.persistSchedule(loan, s, "ORIGINATION", start, rate, true);
        loan = loans.get(uuid(loan, "id"));
        events.publish("lending.loan.created", LoanSupport.LOAN, loan);
        return loan;
    }

    /** A unique human-readable number within the tenant: prefix + 10 digits. */
    String number(String prefix) {
        for (int i = 0; i < 10; i++) {
            String n = prefix + String.format("%010d", Math.floorMod(RANDOM.nextLong(), 10_000_000_000L));
            boolean taken = rows.scalar("select exists(select 1 from loan where loan_number = ?)", Boolean.class, n)
                    .orElse(false);
            if (!taken) {
                return n;
            }
        }
        throw new IllegalStateException("could not allocate a loan number");
    }

    public void guardPatch(JsonNode req, JsonNode loan) {
        req.propertyNames().forEach(f -> {
            if (!req.get(f).isNull() && !PATCHABLE.contains(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed with PATCH; use the "
                        + "loan actions (change-rate, restructure, set-auto-debit, ...)");
            }
        });
        if (req.hasNonNull("disbursement_account_id") && !"PENDING_DISBURSEMENT".equals(str(loan, "status"))) {
            throw ApiException.unprocessable("field_not_updatable", "disbursement_account_id can only change before disbursement");
        }
        if (req.hasNonNull("disbursement_account_id")) {
            external.depositLedgerAccount(UUID.fromString(req.get("disbursement_account_id").asString()),
                    str(loan, "currency"), "disbursement_account_id");
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Cancels a loan before disbursement, or within the cooling-off period when nothing was repaid yet: completed
     * disbursements are reversed in the ledger (the customer returns the principal, the origination fee is refunded).
     */
    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode loan = loans.lock(id);
        String status = str(loan, "status");
        if ("ACTIVE".equals(status)) {
            LocalDate until = Rows.date(loan, "cooling_off_until");
            if (until == null || loans.today(loan).isAfter(until)) {
                throw ApiException.conflict("cooling_off_expired", "The cooling-off period ended on " + until);
            }
            boolean repaid = rows.scalar("select exists(select 1 from loan_repayment where loan_id = ?)", Boolean.class, id)
                    .orElse(false);
            if (repaid) {
                throw ApiException.conflict("loan_has_repayments", "A loan with repayments cannot be cancelled; use prepay");
            }
            for (ObjectNode d : rows.query("""
                    select row_to_json(d)::text from loan_disbursement d where loan_id = ? and status = 'COMPLETED'
                    order by tranche_no""", id)) {
                UUID je = uuid(d, "journal_entry_id");
                if (je != null) {
                    loans.ledger().reverse(je, "Loan cancelled: " + reason, LedgerPort.key("loan-cancel", uuid(d, "id"), null));
                }
                ObjectNode u = rows.object();
                u.put("status", "REVERSED");
                rows.update("loan_disbursement", uuid(d, "id"), u);
            }
            rows.execute("update loan_fee_charge set status = 'REVERSED' where loan_id = ? and fee_type = 'ORIGINATION'", id);
        } else if (!"PENDING_DISBURSEMENT".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only pending loans or loans in cooling-off can be cancelled (status "
                    + status + ")");
        }
        rows.execute("update loan_disbursement set status = 'CANCELLED', failure_reason = ? where loan_id = ? and status in ('SCHEDULED','PENDING','FAILED')",
                "loan cancelled", id);
        rows.execute("update schedule_installment set status = 'RESCHEDULED' where loan_id = ? and status in " + LoanSupport.OPEN_SQL, id);
        rows.execute("update repayment_schedule set status = 'CLOSED' where loan_id = ? and status = 'ACTIVE'", id);
        ObjectNode upd = rows.object();
        for (String f : List.of("outstanding_principal", "outstanding_interest", "outstanding_fees", "outstanding_penalties",
                "total_outstanding", "overdue_amount")) {
            upd.put(f, "0");
        }
        upd.put("closed_at", loans.now().toString());
        loan.setAll(rows.update(LoanSupport.LOAN, id, upd));
        loans.transition(loan, "CANCELLED", reason, null);
        events.publish("lending.loan.cancelled", LoanSupport.LOAN, loan);
        return loan;
    }

    /** Closes a paid-off loan: refunds any credit balance to the repayment account and closes the ledger account. */
    @Transactional
    public ObjectNode close(UUID id) {
        ObjectNode loan = loans.lock(id);
        if (!"PAID_OFF".equals(str(loan, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only paid-off loans can be closed (status " + str(loan, "status") + ")");
        }
        if (decOr0(loan, "total_outstanding").signum() != 0) {
            throw ApiException.conflict("balance_outstanding", "The loan still has an outstanding balance");
        }
        BigDecimal credit = decOr0(loan, "credit_balance");
        if (credit.signum() > 0) {
            UUID target = uuid(loan, "repayment_account_id");
            if (target == null) {
                throw ApiException.conflict("refund_account_missing", "A credit balance of " + credit.toPlainString()
                        + " must be refunded but the loan has no repayment account");
            }
            UUID customerLedger = external.depositLedgerAccount(target, str(loan, "currency"), "repayment_account_id");
            loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-credit-refund", id, null), "PAYMENT",
                    "Refund of loan credit balance " + str(loan, "loan_number"), str(loan, "currency"), "LOAN", id,
                    loans.today(loan), new LedgerPort.Legs().debit(loans.gl().overpaymentOrClearing(), credit, "Loan overpayment")
                    .credit(customerLedger, credit, "Loan credit refund").list()));
            ObjectNode u = rows.object();
            u.put("credit_balance", "0");
            loan.setAll(rows.update(LoanSupport.LOAN, id, u));
        }
        UUID acc = uuid(loan, "ledger_account_id");
        if (acc != null) {
            loans.ledger().close(acc, "Loan closed", LedgerPort.key("loan-close", id, null));
        }
        rows.execute("update repayment_schedule set status = 'CLOSED' where loan_id = ? and status = 'ACTIVE'", id);
        ObjectNode upd = rows.object();
        upd.put("closed_at", loans.now().toString());
        loan.setAll(rows.update(LoanSupport.LOAN, id, upd));
        loans.transition(loan, "CLOSED", "closed", null);
        events.publish("lending.loan.closed", LoanSupport.LOAN, loan);
        return loan;
    }

    @Transactional
    public ObjectNode setAutoDebit(UUID id, boolean enabled, UUID repaymentAccount) {
        ObjectNode loan = loans.lock(id);
        if (Set.of("CLOSED", "CANCELLED", "WRITTEN_OFF", "PAID_OFF").contains(str(loan, "status"))) {
            throw ApiException.conflict("invalid_transition", "Auto-debit cannot be changed on a " + str(loan, "status") + " loan");
        }
        UUID account = repaymentAccount != null ? repaymentAccount : uuid(loan, "repayment_account_id");
        if (enabled && account == null) {
            throw ApiException.unprocessable("repayment_account_required", "repayment_account_id is required to enable auto-debit");
        }
        if (repaymentAccount != null) {
            external.depositLedgerAccount(repaymentAccount, str(loan, "currency"), "repayment_account_id");
        }
        ObjectNode upd = rows.object();
        upd.put("auto_debit_enabled", enabled);
        if (repaymentAccount != null) {
            upd.put("repayment_account_id", repaymentAccount.toString());
        }
        loan.setAll(rows.update(LoanSupport.LOAN, id, upd));
        return loan;
    }

    // ------------------------------------------------------------------ simulation

    /** Schedule, totals and APR for hypothetical terms (nothing stored). */
    public ObjectNode simulate(JsonNode req) {
        UUID productId = Rows.uuid(req, "product_id");
        UUID versionId = Rows.uuid(req, "product_version_id");
        String currency = req.hasNonNull("currency") ? req.get("currency").asString() : "EUR";
        ProductTerms pt = versionId == null ? new ProductTerms(null)
                : new ProductTerms(external.productTerms(productId, versionId, currency));
        BigDecimal principal = Rows.dec(req, "principal_amount");
        int term = req.path("term_months").asInt(0);
        if (principal == null || principal.signum() <= 0 || term < 1 || term > 600) {
            throw ApiException.unprocessable("invalid_terms", "principal_amount must be positive and term_months 1-600");
        }
        BigDecimal rate = Rows.dec(req, "interest_rate");
        if (rate == null) {
            rate = pt.rate("DEBIT_INTEREST").orElseThrow(() -> ApiException.unprocessable("rate_required",
                    "interest_rate is required when the product version has no fixed lending rate"));
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid_rate", "interest_rate must be between 0 and 1");
        }
        int scale = Currencies.digits(currency);
        ObjectNode pseudo = rows.object();
        pseudo.put("currency", currency);
        pseudo.put("schedule_type", req.hasNonNull("schedule_type") ? req.get("schedule_type").asString() : "ANNUITY");
        pseudo.put("repayment_frequency", req.hasNonNull("repayment_frequency") ? req.get("repayment_frequency").asString() : "MONTHLY");
        pseudo.put("day_count", req.hasNonNull("day_count") ? req.get("day_count").asString()
                : pt.dayCount("DEBIT_INTEREST").orElse("ACT_365"));
        pseudo.put("business_day_convention", "MODIFIED_FOLLOWING");
        if (req.hasNonNull("balloon_amount")) {
            pseudo.put("balloon_amount", req.get("balloon_amount").asString());
        }
        if (req.hasNonNull("interest_only_periods")) {
            pseudo.put("interest_only_periods", req.get("interest_only_periods").asInt());
        }
        LocalDate start = Rows.date(req, "start_date") != null ? Rows.date(req, "start_date") : loans.today();
        Frequency freq = Frequency.parse(str(pseudo, "repayment_frequency"));
        LocalDate first = Rows.date(req, "first_repayment_date");
        ScheduleGenerator.Schedule s = loans.generate(loans.terms(pseudo, principal, rate, freq.periods(term, start),
                start, first, null));
        BigDecimal fees = Rows.dec(req, "upfront_fees");
        if (fees == null) {
            fees = pt.feeAmount("LOAN_ORIGINATION", principal, scale).orElse(BigDecimal.ZERO);
        }
        BigDecimal apr = Apr.ofSchedule(principal.subtract(fees), start, s.lines(), null);
        ObjectNode out = rows.object();
        out.put("principal_amount", principal.setScale(scale, java.math.RoundingMode.HALF_EVEN).toPlainString());
        out.put("currency", currency);
        out.put("interest_rate", rate.toPlainString());
        out.put("schedule_type", str(pseudo, "schedule_type"));
        out.put("repayment_frequency", freq.name());
        out.put("day_count", str(pseudo, "day_count"));
        out.put("installment_count", s.lines().size());
        out.put("instalment_amount", s.regularInstalment().toPlainString());
        out.put("total_interest", s.totalInterest().toPlainString());
        out.put("upfront_fees", fees.toPlainString());
        out.put("total_repayable", s.totalRepayable().add(fees).toPlainString());
        out.put("apr", apr.toPlainString());
        out.put("start_date", start.toString());
        out.put("maturity_date", s.maturity().toString());
        ArrayNode lines = loans.preview(s);
        out.set("installments", lines);
        return out;
    }
}
