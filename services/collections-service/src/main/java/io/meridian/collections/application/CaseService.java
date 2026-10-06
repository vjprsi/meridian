package io.meridian.collections.application;

import io.meridian.collections.domain.Delinquency;
import io.meridian.collections.domain.Lifecycles;
import io.meridian.collections.domain.Priority;
import io.meridian.collections.domain.StrategyMatcher;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Collections cases: opening from delinquency signals, refresh and cure, assignment, contacts, disputes, closure. */
@Service
public class CaseService {
    public static final String TABLE = "collection_case";
    /** Actions that contact the customer (count towards the weekly contact cap). */
    public static final Set<String> CONTACT_TYPES = Set.of("SMS", "PUSH", "EMAIL", "WHATSAPP", "CALL", "FIELD_VISIT",
            "LETTER", "LEGAL_NOTICE");
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Rows rows;
    private final Events events;

    public CaseService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    /** A delinquency signal for one exposure (event, EOD sync or manual). */
    public record Signal(UUID customerId, String exposureType, UUID exposureId, int daysPastDue, BigDecimal overdueAmount,
                         BigDecimal totalOutstanding, String currency, UUID productId, String riskGrade) {}

    // ------------------------------------------------------------------ open / refresh / cure

    /**
     * Opens a case for a delinquent exposure or refreshes the open one (DPD, bucket, amounts, strategy, priority).
     * A signal with DPD 0 cures the open case; with no open case it is rejected (nothing to collect).
     */
    @Transactional
    public ObjectNode openOrRefresh(Signal s) {
        validate(s);
        Optional<ObjectNode> existing = findOpenForUpdate(s.exposureType(), s.exposureId());
        if (s.daysPastDue() <= 0) {
            if (existing.isPresent()) {
                return resolve(existing.get(), "CURED", "Exposure is current again");
            }
            throw ApiException.unprocessable("not_delinquent", "The exposure is not past due; no case is opened");
        }
        if (existing.isPresent()) {
            return refresh(existing.get(), s);
        }
        ObjectNode row = rows.object();
        row.put("case_number", newCaseNumber());
        row.put("customer_id", s.customerId().toString());
        row.put("exposure_type", s.exposureType());
        row.put("exposure_id", s.exposureId().toString());
        if (s.productId() != null) {
            row.put("product_id", s.productId().toString());
        }
        if (s.riskGrade() != null) {
            row.put("risk_grade", s.riskGrade());
        }
        row.put("status", "OPEN");
        row.put("days_past_due", s.daysPastDue());
        row.put("max_days_past_due", s.daysPastDue());
        row.put("delinquency_bucket", Delinquency.bucket(s.daysPastDue()));
        row.put("overdue_amount", s.overdueAmount().toPlainString());
        row.put("total_outstanding", s.totalOutstanding().toPlainString());
        row.put("currency", s.currency());
        row.put("opened_at", rows.dbNow());
        selectStrategy(s, false).ifPresent(st -> row.put("collection_strategy_id", st.id().toString()));
        row.put("priority_score", Priority.score(s.daysPastDue(), s.overdueAmount(), 0, false, false, false));
        ObjectNode c = rows.insert(TABLE, row);
        events.publish("lending.collection_case.opened", TABLE, c);
        return c;
    }

    private ObjectNode refresh(ObjectNode c, Signal s) {
        if (!Objects.equals(Rows.str(c, "currency"), s.currency())) {
            throw ApiException.unprocessable("currency_mismatch", "The exposure currency differs from the case currency");
        }
        boolean changed = Rows.integer(c, "days_past_due") != s.daysPastDue()
                || Rows.dec(c, "overdue_amount").compareTo(s.overdueAmount()) != 0
                || Rows.dec(c, "total_outstanding").compareTo(s.totalOutstanding()) != 0;
        ObjectNode v = rows.object();
        v.put("days_past_due", s.daysPastDue());
        v.put("max_days_past_due", Math.max(s.daysPastDue(), Rows.integer(c, "max_days_past_due")));
        v.put("delinquency_bucket", Delinquency.bucket(s.daysPastDue()));
        v.put("overdue_amount", s.overdueAmount().toPlainString());
        v.put("total_outstanding", s.totalOutstanding().toPlainString());
        if (s.productId() != null) {
            v.put("product_id", s.productId().toString());
        }
        if (s.riskGrade() != null) {
            v.put("risk_grade", s.riskGrade());
        }
        Optional<StrategyMatcher.Strategy> st = selectStrategy(s, Rows.bool(c, "is_vulnerable"));
        UUID current = Rows.uuid(c, "collection_strategy_id");
        UUID next = st.map(StrategyMatcher.Strategy::id).orElse(null);
        if (!Objects.equals(current, next)) {
            if (next == null) {
                v.putNull("collection_strategy_id");
            } else {
                v.put("collection_strategy_id", next.toString());
            }
            v.put("last_step_no", 0); // a new strategy starts from its first due step
            changed = true;
        }
        ObjectNode updated = rows.update(TABLE, Rows.uuid(c, "id"), v);
        updated = updatePriority(updated);
        if (changed) {
            events.publish("lending.collection_case.updated", TABLE, updated);
        }
        return updated;
    }

    /** Resolves an open case (cure, pay-off, settlement): arrangements end, pending suggestions expire. */
    @Transactional
    public ObjectNode resolve(ObjectNode c, String resolution, String reason) {
        String to = Lifecycles.closingStatus(resolution);
        Lifecycles.require(Lifecycles.CASE, "Collection case", Rows.str(c, "status"), to);
        UUID id = Rows.uuid(c, "id");
        endArrangements(id, reason == null ? "Case closed (" + resolution + ")" : reason);
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("resolution", resolution);
        v.put("resolved_at", rows.dbNow());
        if ("CURED".equals(resolution) || "PAID_OFF".equals(resolution)) {
            v.put("days_past_due", 0);
            v.put("delinquency_bucket", "CURRENT");
            v.put("overdue_amount", "0");
            if ("PAID_OFF".equals(resolution)) {
                v.put("total_outstanding", "0");
            }
        }
        ObjectNode closed = rows.update(TABLE, id, v);
        events.publish("lending.collection_case.closed", TABLE, closed);
        return closed;
    }

    /** Locks and resolves a case if it is still open (EOD cure / pay-off). */
    @Transactional
    public ObjectNode resolve(UUID caseId, String resolution, String reason) {
        ObjectNode c = rows.lock(TABLE, caseId);
        return Lifecycles.caseOpen(Rows.str(c, "status")) ? resolve(c, resolution, reason) : c;
    }

    @Transactional
    public ObjectNode markWrittenOff(UUID caseId) {
        return markWrittenOff(rows.lock(TABLE, caseId));
    }

    /** The exposure was written off: the case stays open for recovery in status WRITTEN_OFF. */
    @Transactional
    public ObjectNode markWrittenOff(ObjectNode c) {
        if ("WRITTEN_OFF".equals(Rows.str(c, "status"))) {
            return c;
        }
        Lifecycles.require(Lifecycles.CASE, "Collection case", Rows.str(c, "status"), "WRITTEN_OFF");
        ObjectNode v = rows.object();
        v.put("status", "WRITTEN_OFF");
        ObjectNode updated = rows.update(TABLE, Rows.uuid(c, "id"), v);
        events.publish("lending.collection_case.updated", TABLE, updated);
        return updated;
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public ObjectNode assign(UUID id, String assignedTo, UUID queueId) {
        if ((assignedTo == null || assignedTo.isBlank()) && queueId == null) {
            throw ApiException.unprocessable("assignment_required", "assigned_to or collection_queue_id is required");
        }
        ObjectNode c = lockOpen(id);
        ObjectNode v = rows.object();
        UUID targetQueue = queueId != null ? queueId : Rows.uuid(c, "collection_queue_id");
        if (queueId != null) {
            ObjectNode q = rows.find("collection_queue", queueId).orElseThrow(() -> ApiException.unprocessable(
                    "queue_not_found", "collection_queue_id " + queueId + " does not reference an existing queue"));
            if (!Rows.bool(q, "is_active")) {
                throw ApiException.unprocessable("queue_inactive", "Queue " + Rows.str(q, "code") + " is inactive");
            }
            v.put("collection_queue_id", queueId.toString());
            if (assignedTo == null && !queueId.equals(Rows.uuid(c, "collection_queue_id"))) {
                v.putNull("assigned_to"); // moving queues returns the case to the queue's pool
            }
        }
        if (assignedTo != null && !assignedTo.isBlank()) {
            if (targetQueue != null) {
                List<String> agents = agents(rows.get("collection_queue", targetQueue));
                if (!agents.isEmpty() && !agents.contains(assignedTo)) {
                    throw ApiException.unprocessable("agent_not_in_queue",
                            assignedTo + " is not a collector of the case's queue");
                }
            }
            v.put("assigned_to", assignedTo);
        }
        ObjectNode updated = rows.update(TABLE, id, v);
        events.publish("lending.collection_case.assigned", TABLE, updated);
        return updated;
    }

    @Transactional
    public ObjectNode logAction(UUID id, String actionType, String outcome, String notes, String address) {
        ObjectNode c = lockOpen(id);
        ObjectNode a = rows.object();
        a.put("collection_case_id", id.toString());
        a.put("action_type", actionType);
        a.put("performed_by", TenantContext.principal());
        a.put("outcome", outcome);
        if (notes != null) {
            a.put("notes", notes);
        }
        if (address != null) {
            a.put("contact_channel_address", address);
        }
        String now = rows.dbNow();
        a.put("performed_at", now);
        ObjectNode action = rows.insert("collection_action", a);
        ObjectNode v = rows.object();
        if (CONTACT_TYPES.contains(actionType) && !"SUPPRESSED".equals(outcome)) {
            v.put("last_contact_at", now);
        }
        if ("OPEN".equals(Rows.str(c, "status"))) {
            v.put("status", "IN_PROGRESS");
        }
        if (!v.isEmpty()) {
            rows.update(TABLE, id, v);
        }
        // an accepted suggestion of this channel has now been carried out
        rows.execute("""
                update next_best_action set status = 'EXECUTED', updated_by = ? where collection_case_id = ?
                  and status = 'ACCEPTED' and suggested_action = ?""", TenantContext.principal(), id, actionType);
        events.publish("lending.collection_action.performed", "collection_action", action);
        return action;
    }

    /** An automated action of the strategy run (performed by SYSTEM, dated with the business date). */
    @Transactional
    public ObjectNode systemAction(UUID caseId, String actionType, UUID stepId, String outcome, String notes,
                                   java.time.LocalDate businessDate) {
        ObjectNode a = rows.object();
        a.put("collection_case_id", caseId.toString());
        a.put("action_type", actionType);
        if (stepId != null) {
            a.put("collection_strategy_step_id", stepId.toString());
        }
        a.put("performed_by", "SYSTEM");
        a.put("outcome", outcome);
        if (notes != null) {
            a.put("notes", notes);
        }
        a.put("business_date", businessDate.toString());
        String now = rows.dbNow();
        a.put("performed_at", now);
        ObjectNode action = rows.insert("collection_action", a);
        if (CONTACT_TYPES.contains(actionType) && "SENT".equals(outcome)) {
            ObjectNode v = rows.object();
            v.put("last_contact_at", now);
            rows.update(TABLE, caseId, v);
        }
        events.publish("lending.collection_action.performed", "collection_action", action);
        return action;
    }

    @Transactional
    public ObjectNode markDispute(UUID id, boolean disputed, String notes) {
        ObjectNode c = lockOpen(id);
        if (Rows.bool(c, "is_disputed") == disputed) {
            return c;
        }
        ObjectNode v = rows.object();
        v.put("is_disputed", disputed);
        ObjectNode updated = updatePriority(rows.update(TABLE, id, v));
        events.publish("lending.collection_case.disputed", TABLE, updated);
        return updated;
    }

    @Transactional
    public ObjectNode sendToAgency(UUID id, String agency) {
        ObjectNode c = lockOpen(id);
        if (Rows.str(c, "agency_name") != null) {
            throw ApiException.conflict("already_with_agency", "The case is already with " + Rows.str(c, "agency_name"));
        }
        if ("LEGAL".equals(Rows.str(c, "status"))) {
            throw ApiException.conflict("case_in_legal", "Cases in legal proceedings cannot be outsourced");
        }
        ObjectNode v = rows.object();
        v.put("agency_name", agency);
        v.put("agency_assigned_at", rows.dbNow());
        v.putNull("assigned_to");
        if ("OPEN".equals(Rows.str(c, "status"))) {
            v.put("status", "IN_PROGRESS");
        }
        ObjectNode updated = rows.update(TABLE, id, v);
        events.publish("lending.collection_case.sent_to_agency", TABLE, updated);
        return updated;
    }

    @Transactional
    public ObjectNode close(UUID id, String resolution, String notes) {
        ObjectNode c = lockOpen(id);
        return resolve(c, resolution, notes);
    }

    // ------------------------------------------------------------------ helpers

    public ObjectNode lock(UUID id) {
        return rows.lock(TABLE, id);
    }

    /** Locks a case that can still be worked; closed cases are 409 case_closed. */
    public ObjectNode lockOpen(UUID id) {
        ObjectNode c = rows.lock(TABLE, id);
        if (!Lifecycles.caseOpen(Rows.str(c, "status"))) {
            throw ApiException.conflict("case_closed", "Case " + Rows.str(c, "case_number") + " is " + Rows.str(c, "status"));
        }
        return c;
    }

    public void setStatus(UUID id, String from, String to) {
        if (from.equals(to)) {
            return;
        }
        Lifecycles.require(Lifecycles.CASE, "Collection case", from, to);
        ObjectNode v = rows.object();
        v.put("status", to);
        rows.update(TABLE, id, v);
    }

    public Optional<ObjectNode> findOpenForUpdate(String exposureType, UUID exposureId) {
        return rows.queryOne("""
                select row_to_json(c)::text from collection_case c where exposure_id = ?
                  and (exposure_type = ? or (exposure_type in ('LOAN', 'BNPL') and ? in ('LOAN', 'BNPL')))
                  and status not in ('RESOLVED', 'CLOSED') order by opened_at desc limit 1 for update""",
                exposureId, exposureType, exposureType);
    }

    /** Recomputes the priority score from the case and its arrangements. */
    public ObjectNode updatePriority(ObjectNode c) {
        String status = Rows.str(c, "status");
        boolean arrangement = "PROMISE_ACTIVE".equals(status) || "PLAN_ACTIVE".equals(status);
        int score = Priority.score(Rows.integer(c, "days_past_due"), Rows.dec(c, "overdue_amount"),
                Rows.integer(c, "broken_promises"), arrangement, Rows.bool(c, "is_disputed"), Rows.bool(c, "is_vulnerable"));
        if (Objects.equals(Rows.integer(c, "priority_score"), score)) {
            return c;
        }
        ObjectNode v = rows.object();
        v.put("priority_score", score);
        return rows.update(TABLE, Rows.uuid(c, "id"), v);
    }

    public Optional<StrategyMatcher.Strategy> selectStrategy(Signal s, boolean vulnerable) {
        List<StrategyMatcher.Strategy> all = new ArrayList<>();
        for (ObjectNode r : rows.query("select row_to_json(s)::text from collection_strategy s where is_active")) {
            all.add(StrategyAdmin.toStrategy(r));
        }
        return StrategyMatcher.select(all, new StrategyMatcher.CaseFacts(s.exposureType(), s.daysPastDue(),
                s.totalOutstanding(), s.riskGrade(), s.productId(), vulnerable));
    }

    public static List<String> agents(JsonNode queue) {
        List<String> out = new ArrayList<>();
        JsonNode a = queue.get("agent_user_ids");
        if (a != null && a.isArray()) {
            a.forEach(x -> out.add(x.asString()));
        }
        return out;
    }

    private void endArrangements(UUID caseId, String reason) {
        for (ObjectNode p : rows.query("""
                select row_to_json(p)::text from promise_to_pay p where collection_case_id = ? and status = 'ACTIVE'
                for update""", caseId)) {
            ObjectNode v = rows.object();
            v.put("status", "CANCELLED");
            v.put("cancel_reason", trim(reason));
            v.put("resolved_at", rows.dbNow());
            events.publish("lending.promise_to_pay.cancelled", "promise_to_pay",
                    rows.update("promise_to_pay", Rows.uuid(p, "id"), v));
        }
        for (ObjectNode p : rows.query("""
                select row_to_json(p)::text from payment_plan p where collection_case_id = ?
                  and status in ('PROPOSED', 'ACTIVE') for update""", caseId)) {
            ObjectNode v = rows.object();
            v.put("status", "CANCELLED");
            v.put("cancel_reason", trim(reason));
            events.publish("lending.payment_plan.cancelled", "payment_plan",
                    rows.update("payment_plan", Rows.uuid(p, "id"), v));
        }
        rows.execute("""
                update next_best_action set status = 'EXPIRED' where collection_case_id = ?
                  and status in ('SUGGESTED', 'ACCEPTED')""", caseId);
    }

    private static String trim(String s) {
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    private void validate(Signal s) {
        if (s.customerId() == null || s.exposureId() == null || s.exposureType() == null || s.currency() == null) {
            throw ApiException.unprocessable("field_required",
                    "customer_id, exposure_type, exposure_id and currency are required");
        }
        if (s.daysPastDue() < 0) {
            throw ApiException.unprocessable("invalid_days_past_due", "days_past_due must not be negative");
        }
        if (s.overdueAmount() == null || s.overdueAmount().signum() < 0 || s.totalOutstanding() == null
                || s.totalOutstanding().signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "overdue_amount and total_outstanding must not be negative");
        }
        if (s.overdueAmount().compareTo(s.totalOutstanding()) > 0) {
            throw ApiException.unprocessable("invalid_amount", "overdue_amount cannot exceed total_outstanding");
        }
    }

    private String newCaseNumber() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("COL-").append(Year.now().getValue()).append('-');
            for (int i = 0; i < 8; i++) {
                sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            String n = sb.toString();
            if (rows.scalar("select count(*) from collection_case where case_number = ?", Integer.class, n).orElse(0) == 0) {
                return n;
            }
        }
        throw new IllegalStateException("could not allocate a case number");
    }
}
