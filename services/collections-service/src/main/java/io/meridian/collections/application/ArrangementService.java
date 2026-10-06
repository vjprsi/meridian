package io.meridian.collections.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.collections.config.CollectionsProperties;
import io.meridian.collections.domain.ArrangementTracker;
import io.meridian.collections.domain.Currencies;
import io.meridian.collections.domain.Lifecycles;
import io.meridian.collections.domain.PaymentPlanCalculator;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
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

/** Promises to pay and payment plans: recording, activation (four-eyes outside policy), cancellation, tracking. */
@Service
public class ArrangementService {
    /** Approval resource type for payment plans outside policy (activation) in workflow-service (G11). */
    public static final String PLAN_APPROVAL_RESOURCE = "payment_plan";

    private final Rows rows;
    private final Events events;
    private final CaseService cases;
    private final CollectionsProperties props;
    private final Approvals approvals;

    public ArrangementService(Rows rows, Events events, CaseService cases, CollectionsProperties props,
                              Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.cases = cases;
        this.props = props;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ promises

    @Transactional
    public ObjectNode promise(UUID caseId, BigDecimal amount, LocalDate date, String channel) {
        ObjectNode c = cases.lockOpen(caseId);
        String status = Rows.str(c, "status");
        if ("PLAN_ACTIVE".equals(status)) {
            throw ApiException.conflict("payment_plan_active", "The case has an active payment plan");
        }
        if ("LEGAL".equals(status)) {
            throw ApiException.conflict("case_in_legal", "Promises on cases in legal proceedings go through the law firm");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "promised_amount must be positive");
        }
        String currency = Rows.str(c, "currency");
        if (!Currencies.fitsMinorUnit(amount, currency)) {
            throw ApiException.unprocessable("invalid_amount", "promised_amount has more decimals than " + currency + " allows");
        }
        if (amount.compareTo(Rows.dec(c, "total_outstanding")) > 0) {
            throw ApiException.unprocessable("amount_exceeds_outstanding", "promised_amount exceeds the total outstanding");
        }
        LocalDate today = rows.dbToday();
        if (date.isBefore(today) || date.isAfter(today.plusDays(props.maxPromiseDays()))) {
            throw ApiException.unprocessable("invalid_promised_date",
                    "promised_date must be between today and " + props.maxPromiseDays() + " days ahead");
        }
        int active = rows.scalar("select count(*) from promise_to_pay where collection_case_id = ? and status = 'ACTIVE'",
                Integer.class, caseId).orElse(0);
        if (active > 0) {
            throw ApiException.conflict("promise_already_active", "The case already has an active promise to pay");
        }
        ObjectNode p = rows.object();
        p.put("collection_case_id", caseId.toString());
        p.put("promised_amount", amount.toPlainString());
        p.put("currency", currency);
        p.put("promised_date", date.toString());
        p.put("status", "ACTIVE");
        if (channel != null) {
            p.put("channel", channel);
        }
        p.put("taken_by", TenantContext.principal());
        ObjectNode promise = rows.insert("promise_to_pay", p);
        cases.setStatus(caseId, status, "PROMISE_ACTIVE");
        cases.updatePriority(rows.get(CaseService.TABLE, caseId));
        events.publish("lending.promise_to_pay.made", "promise_to_pay", promise);
        return promise;
    }

    @Transactional
    public ObjectNode cancelPromise(UUID id, String reason) {
        ObjectNode p = rows.lock("promise_to_pay", id);
        Lifecycles.require(Lifecycles.PROMISE, "Promise to pay", Rows.str(p, "status"), "CANCELLED");
        ObjectNode v = rows.object();
        v.put("status", "CANCELLED");
        v.put("cancel_reason", reason == null ? "cancelled" : reason);
        v.put("resolved_at", rows.dbNow());
        ObjectNode out = rows.update("promise_to_pay", id, v);
        reopenAfterArrangement(Rows.uuid(p, "collection_case_id"), "PROMISE_ACTIVE");
        events.publish("lending.promise_to_pay.cancelled", "promise_to_pay", out);
        return out;
    }

    /** Evaluates one active promise against the payments received (EOD). Returns the new status if it changed. */
    @Transactional
    public String trackPromise(UUID promiseId, List<ArrangementTracker.Payment> payments, LocalDate businessDate) {
        ObjectNode p = rows.lock("promise_to_pay", promiseId);
        if (!"ACTIVE".equals(Rows.str(p, "status"))) {
            return null;
        }
        LocalDate from = Rows.ts(p, "created_at").toLocalDate();
        LocalDate promised = Rows.date(p, "promised_date");
        BigDecimal paid = ArrangementTracker.paid(payments, from, promised.plusDays(ArrangementTracker.GRACE_DAYS));
        BigDecimal amount = Rows.dec(p, "promised_amount");
        String outcome = ArrangementTracker.promiseOutcome(amount, paid, promised, businessDate).orElse(null);
        ObjectNode v = rows.object();
        v.put("paid_amount", paid.toPlainString());
        if (outcome == null) {
            rows.update("promise_to_pay", promiseId, v);
            return null;
        }
        v.put("status", outcome);
        v.put("resolved_at", rows.dbNow());
        ObjectNode out = rows.update("promise_to_pay", promiseId, v);
        UUID caseId = Rows.uuid(p, "collection_case_id");
        if ("BROKEN".equals(outcome)) {
            rows.execute("update collection_case set broken_promises = broken_promises + 1 where id = ?", caseId);
        }
        reopenAfterArrangement(caseId, "PROMISE_ACTIVE");
        events.publish("KEPT".equals(outcome) ? "lending.promise_to_pay.kept" : "lending.promise_to_pay.broken",
                "promise_to_pay", out);
        return outcome;
    }

    // ------------------------------------------------------------------ payment plans

    @Transactional
    public ObjectNode proposePlan(UUID caseId, int count, String frequency, LocalDate start, Boolean interestFrozen,
                                  BigDecimal total) {
        ObjectNode c = cases.lockOpen(caseId);
        if ("LEGAL".equals(Rows.str(c, "status"))) {
            throw ApiException.conflict("case_in_legal", "Cases in legal proceedings cannot get a payment plan");
        }
        int open = rows.scalar("""
                select count(*) from payment_plan where collection_case_id = ? and status in ('PROPOSED', 'ACTIVE')""",
                Integer.class, caseId).orElse(0);
        if (open > 0) {
            throw ApiException.conflict("payment_plan_exists", "The case already has a proposed or active payment plan");
        }
        String currency = Rows.str(c, "currency");
        BigDecimal overdue = Rows.dec(c, "overdue_amount");
        BigDecimal amount = total != null ? total : overdue;
        if (amount.compareTo(Rows.dec(c, "total_outstanding")) > 0) {
            throw ApiException.unprocessable("amount_exceeds_outstanding", "total_amount exceeds the total outstanding");
        }
        if (start.isBefore(rows.dbToday())) {
            throw ApiException.unprocessable("invalid_start_date", "start_date must be today or later");
        }
        List<PaymentPlanCalculator.Instalment> plan = PaymentPlanCalculator.instalments(amount, currency, count, frequency, start);
        ObjectNode row = rows.object();
        row.put("collection_case_id", caseId.toString());
        row.put("total_amount", Currencies.round(amount, currency).toPlainString());
        row.put("currency", currency);
        row.put("installment_count", count);
        row.put("frequency", frequency);
        row.put("start_date", start.toString());
        ArrayNode arr = row.putArray("installments");
        for (PaymentPlanCalculator.Instalment i : plan) {
            arr.addObject().put("no", i.no()).put("due_date", i.dueDate().toString())
                    .put("amount", i.amount().toPlainString()).put("paid", "0");
        }
        row.put("status", "PROPOSED");
        row.put("interest_frozen", Boolean.TRUE.equals(interestFrozen));
        row.put("requires_approval", PaymentPlanCalculator.outsidePolicy(count, amount, overdue));
        ObjectNode out = rows.insert("payment_plan", row);
        events.publish("lending.payment_plan.proposed", "payment_plan", out);
        if (Rows.bool(out, "requires_approval")) {
            // Outside policy: a second user activates it, from the workflow inbox (PaymentPlanApprovalHandler).
            UUID id = Rows.uuid(out, "id");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("collection_case_id", caseId.toString());
            payload.put("customer_id", Rows.str(c, "customer_id"));
            payload.put("exposure_type", Rows.str(c, "exposure_type"));
            payload.put("exposure_id", Rows.str(c, "exposure_id"));
            BigDecimal planTotal = Currencies.round(Rows.dec(out, "total_amount"), currency);
            payload.put("overdue_amount", Currencies.round(overdue, currency).toPlainString());
            payload.put("total_outstanding", Currencies.round(Rows.dec(c, "total_outstanding"), currency).toPlainString());
            payload.put("total_amount", planTotal.toPlainString());
            payload.put("currency", currency);
            payload.put("installment_count", count);
            payload.put("frequency", frequency);
            payload.put("start_date", start.toString());
            payload.put("interest_frozen", Rows.bool(out, "interest_frozen"));
            approvals.request(ApprovalSpec.of(PLAN_APPROVAL_RESOURCE, id, "activate",
                            "Payment plan outside policy on case " + Rows.str(c, "case_number"), payload)
                            .withAmount(planTotal, currency),
                    "propose-plan:" + id);
        }
        return out;
    }

    @Transactional
    public ObjectNode activatePlan(UUID id) {
        ObjectNode p = rows.lock("payment_plan", id);
        Lifecycles.require(Lifecycles.PLAN, "Payment plan", Rows.str(p, "status"), "ACTIVE");
        String principal = TenantContext.principal();
        if (Rows.bool(p, "requires_approval") && principal.equals(Rows.str(p, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The plan is outside policy: it must be activated by a different user than the one who proposed it");
        }
        UUID caseId = Rows.uuid(p, "collection_case_id");
        ObjectNode c = cases.lockOpen(caseId);
        // an active promise is superseded by the plan
        for (ObjectNode pr : rows.query("""
                select row_to_json(p)::text from promise_to_pay p where collection_case_id = ? and status = 'ACTIVE'
                for update""", caseId)) {
            ObjectNode v = rows.object();
            v.put("status", "CANCELLED");
            v.put("cancel_reason", "Superseded by payment plan");
            v.put("resolved_at", rows.dbNow());
            events.publish("lending.promise_to_pay.cancelled", "promise_to_pay",
                    rows.update("promise_to_pay", Rows.uuid(pr, "id"), v));
        }
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("activated_by", principal);
        v.put("activated_at", rows.dbNow());
        ObjectNode out = rows.update("payment_plan", id, v);
        String caseStatus = Rows.str(rows.get(CaseService.TABLE, caseId), "status");
        cases.setStatus(caseId, caseStatus, "PLAN_ACTIVE");
        cases.updatePriority(rows.get(CaseService.TABLE, caseId));
        events.publish("lending.payment_plan.activated", "payment_plan", out);
        approvals.cancelOpen(PLAN_APPROVAL_RESOURCE, id, null, "activated directly");
        return out;
    }

    /**
     * True while the plan is PROPOSED, outside policy, with the submitted terms (amount, instalment count, start
     * date) unchanged.
     */
    @Transactional(readOnly = true)
    public boolean planAwaitsApproval(UUID id, JsonNode submitted) {
        return rows.find("payment_plan", id)
                .filter(p -> "PROPOSED".equals(Rows.str(p, "status")) && Rows.bool(p, "requires_approval"))
                .filter(p -> submitted == null || !submitted.hasNonNull("total_amount")
                        || new BigDecimal(submitted.get("total_amount").asString()).compareTo(Rows.dec(p, "total_amount")) == 0
                        && submitted.path("installment_count").asInt() == Rows.integer(p, "installment_count")
                        && submitted.path("start_date").asString("").equals(String.valueOf(Rows.date(p, "start_date"))))
                .isPresent();
    }

    @Transactional
    public ObjectNode cancelPlan(UUID id, String reason) {
        ObjectNode p = rows.lock("payment_plan", id);
        Lifecycles.require(Lifecycles.PLAN, "Payment plan", Rows.str(p, "status"), "CANCELLED");
        ObjectNode v = rows.object();
        v.put("status", "CANCELLED");
        v.put("cancel_reason", reason);
        ObjectNode out = rows.update("payment_plan", id, v);
        if ("ACTIVE".equals(Rows.str(p, "status"))) {
            reopenAfterArrangement(Rows.uuid(p, "collection_case_id"), "PLAN_ACTIVE");
        }
        events.publish("lending.payment_plan.cancelled", "payment_plan", out);
        approvals.cancelOpen(PLAN_APPROVAL_RESOURCE, id, null, "cancelled directly");
        return out;
    }

    /** Allocates the payments since activation to the plan instalments (EOD); completes or breaks the plan. */
    @Transactional
    public String trackPlan(UUID planId, List<ArrangementTracker.Payment> payments, LocalDate businessDate) {
        ObjectNode p = rows.lock("payment_plan", planId);
        if (!"ACTIVE".equals(Rows.str(p, "status"))) {
            return null;
        }
        LocalDate from = Rows.ts(p, "activated_at").toLocalDate();
        BigDecimal paid = ArrangementTracker.paid(payments, from, null);
        List<ArrangementTracker.PlanInstalment> inst = new ArrayList<>();
        for (JsonNode i : p.get("installments")) {
            inst.add(new ArrangementTracker.PlanInstalment(i.get("no").asInt(), LocalDate.parse(i.get("due_date").asString()),
                    new BigDecimal(i.get("amount").asString()), BigDecimal.ZERO));
        }
        ArrangementTracker.PlanState state = ArrangementTracker.plan(inst, paid, businessDate);
        ObjectNode v = rows.object();
        ArrayNode arr = v.putArray("installments");
        for (ArrangementTracker.PlanInstalment i : state.instalments()) {
            arr.addObject().put("no", i.no()).put("due_date", i.dueDate().toString())
                    .put("amount", i.amount().toPlainString()).put("paid", i.paid().toPlainString());
        }
        v.put("paid_amount", paid.toPlainString());
        if ("ACTIVE".equals(state.status())) {
            rows.update("payment_plan", planId, v);
            return null;
        }
        v.put("status", state.status());
        if ("BROKEN".equals(state.status())) {
            v.put("broken_at", rows.dbNow());
        }
        ObjectNode out = rows.update("payment_plan", planId, v);
        UUID caseId = Rows.uuid(p, "collection_case_id");
        reopenAfterArrangement(caseId, "PLAN_ACTIVE");
        events.publish("COMPLETED".equals(state.status()) ? "lending.payment_plan.completed" : "lending.payment_plan.broken",
                "payment_plan", out);
        return state.status();
    }

    /** When the arrangement in force ends, the case goes back to being worked. */
    private void reopenAfterArrangement(UUID caseId, String arrangementStatus) {
        ObjectNode c = rows.lock(CaseService.TABLE, caseId);
        if (arrangementStatus.equals(Rows.str(c, "status"))) {
            cases.setStatus(caseId, arrangementStatus, "IN_PROGRESS");
        }
        cases.updatePriority(rows.get(CaseService.TABLE, caseId));
    }
}
