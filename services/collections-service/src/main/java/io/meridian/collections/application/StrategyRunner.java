package io.meridian.collections.application;

import io.meridian.collections.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes the due steps of a case's strategy for one business date: automated contacts are sent (logged as SYSTEM
 * actions) or suppressed under conduct rules, manual steps route the case to the step's queue.
 */
@Service
public class StrategyRunner {
    /** Steps the system performs itself. */
    static final Set<String> AUTOMATED = Set.of("SMS", "PUSH", "EMAIL", "WHATSAPP", "LETTER", "AUTO_DEBIT_RETRY");

    public record StepResult(int sent, int suppressed, int routed) {
        static final StepResult NONE = new StepResult(0, 0, 0);
    }

    private final Rows rows;
    private final CaseService cases;

    public StrategyRunner(Rows rows, CaseService cases) {
        this.rows = rows;
        this.cases = cases;
    }

    @Transactional
    public StepResult run(UUID caseId, LocalDate businessDate) {
        ObjectNode c = cases.lock(caseId);
        String status = Rows.str(c, "status");
        UUID strategyId = Rows.uuid(c, "collection_strategy_id");
        // legal, written-off, plan and outsourced cases follow their own track
        if (strategyId == null || Set.of("RESOLVED", "CLOSED", "LEGAL", "WRITTEN_OFF", "PLAN_ACTIVE").contains(status)
                || Rows.str(c, "agency_name") != null) {
            cases.updatePriority(c);
            return StepResult.NONE;
        }
        ObjectNode strategy = rows.get("collection_strategy", strategyId);
        int dpd = Rows.integer(c, "days_past_due");
        int lastStep = Rows.integer(c, "last_step_no");
        List<ObjectNode> steps = rows.query("""
                select row_to_json(s)::text from collection_strategy_step s where collection_strategy_id = ?
                  and step_no > ? and trigger_dpd <= ? order by step_no""", strategyId, lastStep, dpd);
        int contacts = rows.scalar("""
                select count(*) from collection_action where collection_case_id = ?
                  and action_type in ('SMS','PUSH','EMAIL','WHATSAPP','CALL','FIELD_VISIT','LETTER','LEGAL_NOTICE')
                  and outcome <> 'SUPPRESSED'
                  and coalesce(business_date, (performed_at at time zone 'UTC')::date) > ?::date - 7""",
                Integer.class, caseId, businessDate).orElse(0);
        int cap = Rows.integer(strategy, "max_contacts_per_week");
        boolean disputed = Rows.bool(c, "is_disputed");
        boolean vulnerable = Rows.bool(c, "is_vulnerable");
        int sent = 0;
        int suppressed = 0;
        int routed = 0;
        for (ObjectNode step : steps) {
            int no = Rows.integer(step, "step_no");
            JsonNode cond = step.get("condition");
            if (cond != null && cond.path("no_active_promise").asBoolean(false) && "PROMISE_ACTIVE".equals(status)) {
                break; // wait until the promise is resolved
            }
            if (cond != null && cond.hasNonNull("min_overdue")
                    && Rows.dec(c, "overdue_amount").compareTo(new BigDecimal(cond.get("min_overdue").asString())) < 0) {
                lastStep = no;
                continue;
            }
            String type = Rows.str(step, "action_type");
            String template = Rows.str(step, "template_code");
            UUID stepId = Rows.uuid(step, "id");
            boolean contact = CaseService.CONTACT_TYPES.contains(type);
            if (AUTOMATED.contains(type)) {
                String reason = disputed && contact ? "debt disputed"
                        : contact && cap > 0 && contacts >= cap ? "weekly contact cap of " + cap + " reached" : null;
                if (reason == null) {
                    cases.systemAction(caseId, type, stepId, "SENT", template == null ? null : "template " + template,
                            businessDate);
                    sent++;
                    if (contact) {
                        contacts++;
                    }
                } else {
                    cases.systemAction(caseId, type, stepId, "SUPPRESSED", "Suppressed: " + reason, businessDate);
                    suppressed++;
                }
            } else if ("LEGAL_NOTICE".equals(type) && (vulnerable || disputed)) {
                cases.systemAction(caseId, type, stepId, "SUPPRESSED",
                        "Suppressed: " + (vulnerable ? "vulnerable customer" : "debt disputed"), businessDate);
                suppressed++;
            } else {
                UUID queue = Rows.uuid(step, "queue_id");
                ObjectNode v = rows.object();
                if (queue != null && !queue.equals(Rows.uuid(c, "collection_queue_id"))) {
                    v.put("collection_queue_id", queue.toString());
                    v.putNull("assigned_to");
                }
                v.put("next_action_at", businessDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
                c = rows.update(CaseService.TABLE, caseId, v);
                routed++;
            }
            lastStep = no;
        }
        if (lastStep != Rows.integer(c, "last_step_no")) {
            ObjectNode v = rows.object();
            v.put("last_step_no", lastStep);
            c = rows.update(CaseService.TABLE, caseId, v);
        }
        cases.updatePriority(rows.get(CaseService.TABLE, caseId));
        return new StepResult(sent, suppressed, routed);
    }
}
