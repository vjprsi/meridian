package io.meridian.fraud.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.fraud.infrastructure.Db;
import io.meridian.fraud.infrastructure.Downstream;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Analyst workflow on fraud alerts (assign, contact customer, resolve, escalate to a case) and outcome labels.
 * Lifecycle: OPEN -> IN_REVIEW -> CONFIRMED_FRAUD | FALSE_POSITIVE | CLOSED, or OPEN / IN_REVIEW -> ESCALATED ->
 * resolved. Resolving with a fraud score records the label for model training.
 */
@Service
public class FraudAlertService {
    static final Set<String> WORKABLE = Set.of("OPEN", "IN_REVIEW");
    static final Set<String> RESOLVABLE = Set.of("OPEN", "IN_REVIEW", "ESCALATED");
    static final Set<String> RESOLUTIONS = Set.of("CONFIRMED_FRAUD", "FALSE_POSITIVE", "CLOSED");
    static final Set<String> CHANNELS = Set.of("PUSH", "SMS", "EMAIL", "WHATSAPP", "IN_APP", "USSD", "VOICE");

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final Downstream downstream;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public FraudAlertService(Db db, CrudEngine crud, OutboxWriter outbox, Downstream downstream, TransactionTemplate tx,
                             JsonMapper json) {
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.downstream = downstream;
        this.tx = tx;
        this.json = json;
    }

    public String assign(UUID id, String analyst) {
        return tx.execute(s -> {
            JsonNode a = db.require("fraud_alert", id, true);
            requireIn(a, WORKABLE, "assign");
            db.update("update fraud_alert set assigned_to = ?, status = 'IN_REVIEW', updated_by = ? where id = ?",
                    analyst, TenantContext.principal(), id);
            return publish(id, "risk.fraud_alert.assigned");
        });
    }

    public String resolve(UUID id, String status, String typology, String resolution) {
        if (!RESOLUTIONS.contains(status)) {
            throw ApiException.unprocessable("invalid_resolution", "status must be one of " + RESOLUTIONS);
        }
        if ("CONFIRMED_FRAUD".equals(status) && typology == null) {
            throw ApiException.unprocessable("typology_required", "typology is required when fraud is confirmed");
        }
        return tx.execute(s -> {
            JsonNode a = db.require("fraud_alert", id, true);
            requireIn(a, RESOLVABLE, "resolve");
            db.update("""
                    update fraud_alert set status = ?, typology = coalesce(?, typology), resolution = ?, resolved_by = ?,
                        resolved_at = now(), updated_by = ? where id = ?""",
                    status, typology, resolution, TenantContext.principal(), TenantContext.principal(), id);
            String label = switch (status) {
                case "CONFIRMED_FRAUD" -> "FRAUD";
                case "FALSE_POSITIVE" -> "NOT_FRAUD";
                default -> null;
            };
            UUID scoreId = Db.uuid(a, "fraud_score_id");
            if (label != null && scoreId != null) {
                ObjectNode fb = json.createObjectNode();
                fb.put("label", label);
                if (typology != null || Db.str(a, "typology") != null) {
                    fb.put("typology", typology != null ? typology : Db.str(a, "typology"));
                }
                fb.put("source", "ANALYST");
                if ("FRAUD".equals(label) && a.hasNonNull("amount")) {
                    fb.put("loss_amount", a.get("amount").asString());
                    fb.put("currency", Db.str(a, "currency"));
                }
                recordFeedback(scoreId, fb);
            }
            return publish(id, "risk.fraud_alert.resolved");
        });
    }

    /** Opens a case in case-management-service (idempotent per alert), then marks the alert ESCALATED. */
    public JsonNode escalate(UUID id, String caseType, String reason) {
        JsonNode a = db.require("fraud_alert", id, false);
        if (a.hasNonNull("aml_case_id")) {
            throw ApiException.conflict("already_escalated", "Alert is already linked to case " + a.get("aml_case_id").asString());
        }
        requireIn(a, WORKABLE, "escalate");
        JsonNode amlCase = downstream.openCase(a, caseType, reason);
        UUID caseId = UUID.fromString(amlCase.get("id").asString());
        tx.executeWithoutResult(s -> {
            JsonNode locked = db.require("fraud_alert", id, true);
            requireIn(locked, WORKABLE, "escalate");
            db.update("update fraud_alert set status = 'ESCALATED', aml_case_id = ?, updated_by = ? where id = ?",
                    caseId, TenantContext.principal(), id);
            publish(id, "risk.fraud_alert.escalated");
        });
        return amlCase;
    }

    public String contactCustomer(UUID id, String channel) {
        if (!CHANNELS.contains(channel)) {
            throw ApiException.unprocessable("invalid_channel", "channel must be one of " + CHANNELS);
        }
        JsonNode a = db.require("fraud_alert", id, false);
        requireIn(a, WORKABLE, "contact the customer for");
        if (!a.hasNonNull("customer_id")) {
            throw ApiException.unprocessable("customer_unknown", "The alert has no customer to contact");
        }
        UUID notificationId = downstream.contactCustomer(a, channel);
        return tx.execute(s -> {
            db.require("fraud_alert", id, true);
            db.update("""
                    update fraud_alert set customer_contacted = true, notification_id = ?,
                        status = case when status = 'OPEN' then 'IN_REVIEW' else status end, updated_by = ? where id = ?""",
                    notificationId, TenantContext.principal(), id);
            return publish(id, "risk.fraud_alert.customer_contacted");
        });
    }

    /** Records an outcome label for a scored event (append-only) and publishes risk.fraud_feedback.recorded. */
    public String recordFeedback(UUID scoreId, ObjectNode body) {
        return tx.execute(s -> {
            db.require("fraud_score", scoreId, false);
            ObjectNode v = crud.toColumns("fraud_feedback", body);
            v.put("fraud_score_id", scoreId.toString());
            if (!v.hasNonNull("reported_at")) {
                v.put("reported_at", java.time.OffsetDateTime.now().toString());
            }
            BigDecimal loss = Db.dec(v, "loss_amount");
            BigDecimal recovered = Db.dec(v, "recovered_amount");
            if ((loss != null || recovered != null) && !v.hasNonNull("currency")) {
                throw ApiException.unprocessable("currency_required", "currency is required with loss or recovered amounts");
            }
            if ((loss != null && loss.signum() < 0) || (recovered != null && recovered.signum() < 0)) {
                throw ApiException.unprocessable("invalid_amount", "amounts must not be negative");
            }
            if (loss != null && recovered != null && recovered.compareTo(loss) > 0) {
                throw ApiException.unprocessable("invalid_amount", "recovered_amount cannot exceed loss_amount");
            }
            String row = crud.insert("fraud_feedback", v);
            JsonNode n = json.readTree(row);
            outbox.publish("risk.fraud_feedback.recorded", "fraud_feedback", UUID.fromString(n.get("id").asString()), n);
            return row;
        });
    }

    private String publish(UUID id, String event) {
        JsonNode row = db.require("fraud_alert", id, false);
        outbox.publish(event, "fraud_alert", id, row);
        return db.text(row);
    }

    private static void requireIn(JsonNode alert, Set<String> allowed, String what) {
        String status = alert.get("status").asString();
        if (!allowed.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + what + " an alert that is " + status);
        }
    }
}
