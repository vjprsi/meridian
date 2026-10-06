package io.meridian.transactionmonitoring.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.transactionmonitoring.infrastructure.Db;
import io.meridian.transactionmonitoring.infrastructure.Downstream;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * TM alert triage: OPEN -> TRIAGED (assigned) -> CLOSED_NO_ACTION (closed with rationale) or CLOSED_CASE_OPENED
 * (escalated into a new or existing AML case in case-management-service).
 */
@Service
public class AlertService {
    static final Set<String> WORKABLE = Set.of("OPEN", "TRIAGED");

    private final Db db;
    private final OutboxWriter outbox;
    private final Downstream downstream;
    private final TransactionTemplate tx;

    public AlertService(Db db, OutboxWriter outbox, Downstream downstream, TransactionTemplate tx) {
        this.db = db;
        this.outbox = outbox;
        this.downstream = downstream;
        this.tx = tx;
    }

    public String assign(UUID id, String analyst) {
        return tx.execute(s -> {
            require(db.require("tm_alert", id, true), "assign");
            db.update("update tm_alert set assigned_to = ?, status = 'TRIAGED', updated_by = ? where id = ?",
                    analyst, TenantContext.principal(), id);
            return publish(id, "risk.tm_alert.assigned");
        });
    }

    public String close(UUID id, String reason, String note) {
        return tx.execute(s -> {
            require(db.require("tm_alert", id, true), "close");
            db.update("""
                    update tm_alert set status = 'CLOSED_NO_ACTION', closed_reason = ?, triage_note = ?, closed_at = now(),
                        updated_by = ? where id = ?""", reason, note, TenantContext.principal(), id);
            return publish(id, "risk.tm_alert.closed");
        });
    }

    public JsonNode escalate(UUID id, UUID caseId, String caseType, String reason) {
        JsonNode a = db.require("tm_alert", id, false);
        require(a, "escalate");
        String code = db.require("tm_scenario", UUID.fromString(a.get("tm_scenario_id").asString()), false).get("code").asString();
        JsonNode amlCase = downstream.escalate(a, caseId, caseType, reason, code);
        UUID newCase = UUID.fromString(amlCase.get("id").asString());
        tx.executeWithoutResult(s -> {
            require(db.require("tm_alert", id, true), "escalate");
            db.update("""
                    update tm_alert set status = 'CLOSED_CASE_OPENED', aml_case_id = ?, triage_note = coalesce(triage_note, ?),
                        closed_at = now(), updated_by = ? where id = ?""", newCase, reason, TenantContext.principal(), id);
            publish(id, "risk.tm_alert.escalated");
        });
        return amlCase;
    }

    private String publish(UUID id, String event) {
        JsonNode row = db.require("tm_alert", id, false);
        outbox.publish(event, "tm_alert", id, row);
        return db.text(row);
    }

    private static void require(JsonNode a, String what) {
        String st = a.get("status").asString();
        if (!WORKABLE.contains(st)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + what + " an alert that is " + st);
        }
    }
}
