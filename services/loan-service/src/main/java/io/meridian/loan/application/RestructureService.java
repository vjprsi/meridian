package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loan.domain.Frequency;
import io.meridian.loan.domain.Lifecycles;
import io.meridian.loan.domain.ScheduleGenerator;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Interest-rate changes (immediate schedule regeneration) and restructures with maker-checker: propose (schedule
 * preview) -> submit (maker) -> approve (checker, a different user) -> apply (new schedule version, capitalisation
 * of arrears, forbearance flag and RESTRUCTURED status).
 */
@Service
public class RestructureService {
    static final String TABLE = "loan_restructure";
    static final Set<String> OPEN = Set.of("PROPOSED", "PENDING_APPROVAL", "APPROVED");
    /** Approval resource type for restructures in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "loan_restructure";

    private final LoanSupport loans;
    private final Rows rows;
    private final Approvals approvals;

    public RestructureService(LoanSupport loans, Approvals approvals) {
        this.loans = loans;
        this.rows = loans.rows();
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ rate change

    @Transactional
    public ObjectNode changeRate(UUID loanId, JsonNode req) {
        ObjectNode loan = loans.lock(loanId);
        RepaymentService.requireServiceable(loan);
        BigDecimal newRate = Rows.dec(req, "new_rate");
        LocalDate effective = Rows.date(req, "effective_date");
        if (newRate == null || newRate.signum() < 0 || newRate.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid_rate", "new_rate must be between 0 and 1 (fraction)");
        }
        BigDecimal old = decOr0(loan, "interest_rate");
        if (old.compareTo(newRate) == 0) {
            throw ApiException.unprocessable("rate_unchanged", "new_rate equals the current rate");
        }
        LocalDate last = Rows.date(loan, "last_accrual_date");
        if (effective.isAfter(loans.today(loan))) {
            throw ApiException.unprocessable("future_rate_change_unsupported", "effective_date cannot be in the future");
        }
        if (last != null && !effective.isAfter(last)) {
            throw ApiException.unprocessable("effective_date_before_last_accrual",
                    "effective_date must be after the last accrued day " + last + " (accrued interest is not recalculated)");
        }
        ObjectNode upd = rows.object();
        upd.put("interest_rate", newRate.toPlainString());
        if (newRate.signum() > 0 && "ZERO".equals(str(loan, "interest_rate_type"))) {
            upd.put("interest_rate_type", "FIXED");
        }
        loan.setAll(rows.update(LoanSupport.LOAN, loanId, upd));
        ObjectNode schedule = loans.regenerateFuture(loan, "RATE_CHANGE", effective, newRate, null, null);
        ObjectNode change = rateChange(loan, old, newRate, Rows.dec(req, "index_value"), effective, str(req, "reason"),
                schedule == null ? null : uuid(schedule, "id"));
        loans.events().publish("lending.loan.rate_changed", "loan_rate_change", change);
        return change;
    }

    private ObjectNode rateChange(JsonNode loan, BigDecimal old, BigDecimal newRate, BigDecimal index, LocalDate effective,
                                  String reason, UUID scheduleId) {
        ObjectNode c = rows.object();
        c.put("loan_id", str(loan, "id"));
        c.put("old_rate", old.toPlainString());
        c.put("new_rate", newRate.toPlainString());
        if (index != null) {
            c.put("index_value", index.toPlainString());
        }
        c.put("effective_date", effective.toString());
        c.put("reason", reason);
        if (scheduleId != null) {
            c.put("new_schedule_id", scheduleId.toString());
        }
        return rows.insert("loan_rate_change", c);
    }

    // ------------------------------------------------------------------ restructure

    @Transactional
    public ObjectNode propose(UUID loanId, JsonNode req) {
        ObjectNode loan = loans.lock(loanId);
        RepaymentService.requireServiceable(loan);
        boolean inProgress = rows.scalar("select exists(select 1 from loan_restructure where loan_id = ? and status in ('PROPOSED','PENDING_APPROVAL','APPROVED'))",
                Boolean.class, loanId).orElse(false);
        if (inProgress) {
            throw ApiException.conflict("restructure_in_progress", "The loan already has an open restructure");
        }
        ObjectNode values = rows.columns(TABLE, req);
        values.put("loan_id", loanId.toString());
        String type = str(values, "restructure_type");
        switch (type) {
            case "RESCHEDULE", "TERM_EXTENSION" -> require(values, "new_term_months", type);
            case "REFINANCE", "RATE_REDUCTION" -> require(values, "new_interest_rate", type);
            case "PAYMENT_HOLIDAY" -> {
                require(values, "holiday_months", type);
                int m = values.get("holiday_months").asInt();
                if (m < 1 || m > 12) {
                    throw ApiException.unprocessable("invalid_holiday", "holiday_months must be 1-12");
                }
            }
            case "CAPITALISATION" -> values.put("capitalise_arrears", true);
            case "SETTLEMENT" -> throw ApiException.unprocessable("settlement_not_supported",
                    "Settlements are booked as a repayment followed by a partial write-off of the remainder");
            default -> throw ApiException.unprocessable("invalid_restructure_type", "Unsupported restructure type " + type);
        }
        if (values.hasNonNull("new_term_months") && (values.get("new_term_months").asInt() < 1 || values.get("new_term_months").asInt() > 600)) {
            throw ApiException.unprocessable("invalid_term", "new_term_months must be 1-600");
        }
        BigDecimal rate = Rows.dec(values, "new_interest_rate");
        if (rate != null && (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0)) {
            throw ApiException.unprocessable("invalid_rate", "new_interest_rate must be between 0 and 1");
        }
        LocalDate effective = Rows.date(values, "effective_date") != null ? Rows.date(values, "effective_date") : loans.today(loan);
        if (effective.isBefore(loans.today(loan))) {
            throw ApiException.unprocessable("invalid_effective_date", "effective_date cannot be in the past");
        }
        values.put("effective_date", effective.toString());
        Plan plan = plan(loan, values);
        values.set("proposed_schedule", loans.preview(plan.schedule()));
        values.put("status", "PROPOSED");
        ObjectNode r = rows.insert(TABLE, values);
        loans.events().publish("lending.loan.restructure_proposed", TABLE, r);
        return r;
    }

    private static void require(JsonNode values, String field, String type) {
        if (!values.hasNonNull(field)) {
            throw ApiException.unprocessable("field_required", field + " is required for " + type);
        }
    }

    /** What applying a restructure would do. */
    record Plan(ScheduleGenerator.Schedule schedule, BigDecimal principal, BigDecimal capInterest, BigDecimal capFees,
                BigDecimal capPenalties, boolean replaceOpen, BigDecimal rate, LocalDate effective) {
        BigDecimal capitalised() {
            return capInterest.add(capFees).add(capPenalties);
        }
    }

    private Plan plan(JsonNode loan, JsonNode r) {
        UUID loanId = uuid(loan, "id");
        LocalDate effective = Rows.date(r, "effective_date");
        BigDecimal rate = Rows.dec(r, "new_interest_rate") != null ? Rows.dec(r, "new_interest_rate") : decOr0(loan, "interest_rate");
        boolean capitalise = Rows.bool(r, "capitalise_arrears") || "CAPITALISATION".equals(str(r, "restructure_type"));
        BigDecimal duePrincipal = BigDecimal.ZERO;
        BigDecimal capI = BigDecimal.ZERO;
        BigDecimal capF = BigDecimal.ZERO;
        BigDecimal capP = BigDecimal.ZERO;
        int future = 0;
        LocalDate firstFuture = null;
        for (ObjectNode i : loans.openInstallments(loanId)) {
            if ("SCHEDULED".equals(str(i, "status"))) {
                future++;
                firstFuture = firstFuture == null ? Rows.date(i, "due_date") : firstFuture;
                continue;
            }
            duePrincipal = duePrincipal.add(LoanSupport.remaining(i, "PRINCIPAL"));
            capI = capI.add(LoanSupport.remaining(i, "INTEREST"));
            capF = capF.add(LoanSupport.remaining(i, "FEE"));
            capP = capP.add(LoanSupport.remaining(i, "PENALTY"));
        }
        BigDecimal principal = decOr0(loan, "outstanding_principal");
        if (capitalise) {
            principal = principal.add(capI).add(capF).add(capP);
        } else {
            principal = principal.subtract(duePrincipal);
            capI = BigDecimal.ZERO;
            capF = BigDecimal.ZERO;
            capP = BigDecimal.ZERO;
        }
        if (principal.signum() <= 0) {
            throw ApiException.conflict("nothing_to_restructure", "No principal remains to be rescheduled");
        }
        Frequency f = Frequency.parse(str(loan, "repayment_frequency"));
        Integer newTerm = Rows.integer(r, "new_term_months");
        int periods = newTerm != null ? f.periods(newTerm, effective) : Math.max(future, 1);
        LocalDate firstDue = firstFuture != null ? loans.cadenceOnOrAfter(loan, firstFuture) : null;
        if (firstDue == null || !firstDue.isAfter(effective)) {
            firstDue = f.next(effective, newTerm == null ? 1 : newTerm);
        }
        Integer holiday = Rows.integer(r, "holiday_months");
        if (holiday != null && holiday > 0) {
            firstDue = firstDue.plusMonths(holiday);
        }
        String type = "BULLET".equals(str(loan, "schedule_type")) || "CUSTOM".equals(str(loan, "schedule_type"))
                ? "ANNUITY" : str(loan, "schedule_type");
        if ("BALLOON".equals(type) && decOr0(loan, "balloon_amount").compareTo(principal) >= 0) {
            type = "ANNUITY";
        }
        ScheduleGenerator.Schedule s = loans.generate(loans.terms(loan, principal, rate, periods, effective, firstDue, type));
        return new Plan(s, principal, capI, capF, capP, capitalise, rate, effective);
    }

    @Transactional
    public ObjectNode submit(UUID id) {
        ObjectNode r = rows.lock(TABLE, id);
        move(r, "PENDING_APPROVAL");
        ObjectNode upd = rows.object();
        upd.put("status", "PENDING_APPROVAL");
        upd.put("submitted_by", TenantContext.principal());
        ObjectNode submitted = rows.update(TABLE, id, upd);
        // Central maker-checker: the checker decides in the workflow inbox; RestructureApprovalHandler applies it.
        ObjectNode loan = rows.get(LoanSupport.LOAN, uuid(submitted, "loan_id"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loan_id", str(submitted, "loan_id"));
        payload.put("loan_number", str(loan, "loan_number"));
        payload.put("restructure_type", str(submitted, "restructure_type"));
        payload.put("reason", str(submitted, "reason"));
        payload.put("new_term_months", Rows.integer(submitted, "new_term_months"));
        payload.put("new_interest_rate", str(submitted, "new_interest_rate"));
        payload.put("holiday_months", Rows.integer(submitted, "holiday_months"));
        payload.put("capitalise_arrears", Rows.bool(submitted, "capitalise_arrears"));
        payload.put("settlement_amount", str(submitted, "settlement_amount"));
        payload.put("effective_date", str(submitted, "effective_date"));
        payload.put("outstanding_principal", str(loan, "outstanding_principal"));
        payload.put("currency", str(loan, "currency"));
        payload.put("submitted_by", str(submitted, "submitted_by"));
        payload.put("row_version", submitted.path("row_version").asInt());
        JsonNode request = approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                        "Loan " + str(loan, "loan_number") + " restructure (" + str(submitted, "restructure_type") + ")", payload)
                .withSummary(str(submitted, "reason"))
                .withAmount(decOr0(loan, "outstanding_principal"), str(loan, "currency")), "submit:" + id);
        UUID requestId = Approvals.idOf(request);
        if (requestId != null) {
            ObjectNode link = rows.object();
            link.put("approval_request_id", requestId.toString());
            submitted = rows.update(TABLE, id, link);
        }
        return submitted;
    }

    /**
     * True while the restructure is PENDING_APPROVAL for the given workflow request (the one linked at submit, if
     * any) and was submitted by the same maker.
     */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, UUID approvalRequestId, String submittedBy) {
        return rows.find(TABLE, id)
                .filter(r -> "PENDING_APPROVAL".equals(str(r, "status")))
                .filter(r -> str(r, "approval_request_id") == null || approvalRequestId == null
                        || approvalRequestId.toString().equals(str(r, "approval_request_id")))
                .filter(r -> submittedBy == null || submittedBy.equals(str(r, "submitted_by")))
                .isPresent();
    }

    @Transactional
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode r = rows.lock(TABLE, id);
        move(r, "APPROVED");
        String me = TenantContext.principal();
        if (me.equals(str(r, "submitted_by")) || me.equals(str(r, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The restructure must be approved by a user other than its maker");
        }
        ObjectNode upd = rows.object();
        upd.put("status", "APPROVED");
        upd.put("approved_by", me);
        ObjectNode approved = rows.update(TABLE, id, upd);
        loans.events().publish("lending.loan.restructure_approved", TABLE, approved);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return approved;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode r = rows.lock(TABLE, id);
        move(r, "REJECTED");
        ObjectNode upd = rows.object();
        upd.put("status", "REJECTED");
        upd.put("rejection_reason", reason);
        ObjectNode rejected = rows.update(TABLE, id, upd);
        loans.events().publish("lending.loan.restructure_rejected", TABLE, rejected);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return rejected;
    }

    @Transactional
    public ObjectNode apply(UUID id) {
        ObjectNode r = rows.get(TABLE, id);
        ObjectNode loan = loans.lock(uuid(r, "loan_id"));
        r = rows.lock(TABLE, id);
        move(r, "APPLIED");
        RepaymentService.requireServiceable(loan);
        LocalDate effective = Rows.date(r, "effective_date");
        if (effective == null || effective.isBefore(loans.today(loan))) {
            effective = loans.today(loan);
            r.put("effective_date", effective.toString());
        }
        Plan plan = plan(loan, r);
        UUID loanId = uuid(loan, "id");
        if (plan.capitalised().signum() > 0) {
            loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("loan-capitalisation", id, null), "ADJUSTMENT",
                    "Capitalisation of arrears " + str(loan, "loan_number"), str(loan, "currency"), "LOAN_RESTRUCTURE", id,
                    effective, new LedgerPort.Legs().debit(loans.receivable(loan), plan.capitalised(), "Capitalised arrears")
                    .credit(loans.gl().interestReceivable(), plan.capInterest(), "Interest capitalised")
                    .credit(loans.gl().feeReceivable(), plan.capFees(), "Fees capitalised")
                    .credit(loans.gl().penaltyReceivableOrInterest(), plan.capPenalties(), "Penalties capitalised").list()));
            rows.execute("update loan_fee_charge set status = 'PAID', paid_amount = amount + tax_amount where loan_id = ? "
                    + "and schedule_installment_id is not null and status in ('CHARGED','PARTIALLY_PAID')", loanId);
            Map<String, BigDecimal> deltas = new LinkedHashMap<>();
            deltas.put("outstanding_principal", plan.capitalised());
            deltas.put("outstanding_interest", plan.capInterest().negate());
            deltas.put("outstanding_fees", plan.capFees().negate());
            deltas.put("outstanding_penalties", plan.capPenalties().negate());
            loans.adjust(loan, deltas);
        }
        BigDecimal oldRate = decOr0(loan, "interest_rate");
        ObjectNode upd = rows.object();
        upd.put("interest_rate", plan.rate().toPlainString());
        upd.put("is_forborne", Rows.bool(r, "is_forborne") || Rows.bool(loan, "is_forborne"));
        if (Rows.integer(r, "new_term_months") != null) {
            LocalDate start = Rows.date(loan, "start_date");
            upd.put("term_months", (int) Math.max(1, ChronoUnit.MONTHS.between(start, plan.schedule().maturity())));
        }
        loan.setAll(rows.update(LoanSupport.LOAN, loanId, upd));
        ObjectNode schedule = loans.persistSchedule(loan, plan.schedule(), "RESTRUCTURE", effective, plan.rate(), plan.replaceOpen());
        if (oldRate.compareTo(plan.rate()) != 0) {
            loans.events().publish("lending.loan.rate_changed", "loan_rate_change",
                    rateChange(loan, oldRate, plan.rate(), null, effective, "RESTRUCTURE", uuid(schedule, "id")));
        }
        loans.transition(loan, "RESTRUCTURED", str(r, "restructure_type") + ": " + str(r, "reason"), effective);
        loans.delinquency(loan, loans.today(loan), "restructured");
        ObjectNode ru = rows.object();
        ru.put("status", "APPLIED");
        ru.put("applied_at", loans.now().toString());
        ru.put("new_schedule_id", str(schedule, "id"));
        ru.put("capitalised_amount", plan.capitalised().toPlainString());
        ru.put("effective_date", effective.toString());
        ru.set("proposed_schedule", loans.preview(plan.schedule()));
        ObjectNode applied = rows.update(TABLE, id, ru);
        loans.events().publish("lending.loan.restructured", TABLE, applied);
        return applied;
    }

    private static void move(JsonNode r, String to) {
        try {
            Lifecycles.require(Lifecycles.RESTRUCTURE, "Restructure", str(r, "status"), to);
        } catch (Lifecycles.InvalidTransition e) {
            throw ApiException.conflict(e.code(), e.getMessage());
        }
    }
}
