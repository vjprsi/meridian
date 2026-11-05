package io.meridian.casemanagement.application;

import io.meridian.casemanagement.config.CaseManagementProperties;
import io.meridian.casemanagement.domain.DisputeRules;
import io.meridian.casemanagement.infrastructure.Db;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-OPS-1 (D-ED-13, G-09): the provider-initiated dispute tracker for non-card wallet transactions. A dispute is a
 * tracker only (no new approval type): RAISED -&gt; UNDER_INVESTIGATION -&gt; RESOLVED | REJECTED | WITHDRAWN. It
 * carries a GSMA reason code, an evidence file, published SLA timers (raised_at, due_at from the per-tenant SLA,
 * sla_breached) and a repeat-claimant flag. Resolving it may raise a maker-checker {@link CorrectionService}
 * correction (reuse of capability 1) or close with no financial action. Per-tenant gated (D-ED-14).
 *
 * <p>The lifecycle legality, SLA maths and repeat-claimant threshold live in {@link DisputeRules} (pure, unit-tested).
 */
@Service
public class DisputeService {
    static final String TABLE = "wallet_dispute";
    static final String EVIDENCE = "wallet_dispute_evidence";

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final CorrectionService corrections;
    private final CaseManagementProperties props;

    public DisputeService(Db db, CrudEngine crud, OutboxWriter outbox, JsonMapper json, CorrectionService corrections,
                          CaseManagementProperties props) {
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.corrections = corrections;
        this.props = props;
    }

    private void gate() {
        if (!props.dispute().enabled()) {
            throw ApiException.unprocessable("wallet_disputes_disabled",
                    "The wallet dispute / correction workflow is not enabled for this tenant");
        }
    }

    // ------------------------------------------------------------------ raise

    @Transactional
    public String raise(JsonNode body) {
        gate();
        ObjectNode v = crud.toColumns(TABLE, body);
        if (!DisputeRules.validReason(Db.str(v, "reason"))) {
            throw ApiException.unprocessable("invalid_reason", "reason must be one of " + DisputeRules.REASONS);
        }
        if (Db.str(v, "wallet_transaction_id") == null) {
            throw ApiException.unprocessable("wallet_transaction_id_required", "wallet_transaction_id is required");
        }
        for (String f : new String[]{"status", "sla_breached", "repeat_claimant", "resolution_type",
                "resolution_notes", "wallet_txn_correction_id", "resolved_by", "resolved_at"}) {
            v.remove(f);
        }
        OffsetDateTime now = Clocks.now();
        v.put("status", "RAISED");
        v.put("raised_at", now.toString());
        v.put("due_at", DisputeRules.dueAt(now, null, props.dispute().slaHours()).toString());
        boolean repeat = DisputeRules.repeatClaimant(priorClaims(v, now), props.dispute().repeatClaimantThreshold());
        v.put("repeat_claimant", repeat);
        v.put("sla_breached", false);
        String row = crud.insert(TABLE, v);
        JsonNode n = json.readTree(row);
        outbox.publish("risk.wallet_dispute.raised", TABLE, id(n), n);
        if (repeat) {
            outbox.publish("risk.wallet_dispute.repeat_claimant_flagged", TABLE, id(n), n);
        }
        return row;
    }

    /** Count of the claimant's earlier disputes inside the repeat-claimant window (by customer id, else by MSISDN). */
    private int priorClaims(ObjectNode v, OffsetDateTime now) {
        UUID customerId = Db.uuid(v, "claimant_customer_id");
        String msisdn = Db.str(v, "claimant_msisdn");
        OffsetDateTime from = now.minusDays(props.dispute().repeatClaimantWindowDays());
        if (customerId != null) {
            return db.jdbc().sql("select count(*) from " + TABLE + " where claimant_customer_id = ? and raised_at >= ?")
                    .params(customerId, from.toString()).query(Integer.class).single();
        }
        if (msisdn != null) {
            return db.jdbc().sql("select count(*) from " + TABLE + " where claimant_msisdn = ? and raised_at >= ?")
                    .params(msisdn, from.toString()).query(Integer.class).single();
        }
        return 0;
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public String investigate(UUID id, String note) {
        require(id, "investigate");
        db.update("update " + TABLE + " set status = 'UNDER_INVESTIGATION', assigned_to = coalesce(assigned_to, ?), updated_by = ? where id = ?",
                TenantContext.principal(), TenantContext.principal(), id);
        return publish(id, "risk.wallet_dispute.investigating");
    }

    /** Resolve the dispute: CORRECTION raises a maker-checker correction, NO_ACTION closes with no money movement. */
    @Transactional
    public String resolve(UUID id, String resolutionType, String notes, String correctionReason,
                          BigDecimal correctionAmount, String correctionRationale) {
        JsonNode d = require(id, "resolve");
        if (!"CORRECTION".equals(resolutionType) && !"NO_ACTION".equals(resolutionType)) {
            throw ApiException.unprocessable("invalid_resolution_type", "resolution_type must be CORRECTION or NO_ACTION");
        }
        UUID correctionId = null;
        if ("CORRECTION".equals(resolutionType)) {
            String reason = correctionReason != null ? correctionReason : Db.str(d, "reason");
            BigDecimal amount = correctionAmount != null ? correctionAmount : Db.dec(d, "amount");
            String rationale = correctionRationale != null ? correctionRationale
                    : "Dispute resolved in favour of the claimant (" + reason + ")";
            JsonNode c = corrections.requestForDispute(id, UUID.fromString(d.get("wallet_transaction_id").asString()),
                    reason, amount, Db.str(d, "currency"), rationale);
            correctionId = UUID.fromString(c.get("id").asString());
        }
        db.update("update " + TABLE + " set status = 'RESOLVED', resolution_type = ?, resolution_notes = ?, "
                        + "wallet_txn_correction_id = ?, resolved_by = ?, resolved_at = now(), updated_by = ? where id = ?",
                resolutionType, notes, correctionId, TenantContext.principal(), TenantContext.principal(), id);
        return publish(id, "risk.wallet_dispute.resolved");
    }

    @Transactional
    public String reject(UUID id, String reason) {
        require(id, "reject");
        db.update("update " + TABLE + " set status = 'REJECTED', resolution_type = 'NO_ACTION', resolution_notes = ?, "
                + "resolved_by = ?, resolved_at = now(), updated_by = ? where id = ?",
                reason, TenantContext.principal(), TenantContext.principal(), id);
        return publish(id, "risk.wallet_dispute.rejected");
    }

    @Transactional
    public String withdraw(UUID id, String reason) {
        require(id, "withdraw");
        db.update("update " + TABLE + " set status = 'WITHDRAWN', resolution_notes = ?, resolved_by = ?, "
                + "resolved_at = now(), updated_by = ? where id = ?",
                reason, TenantContext.principal(), TenantContext.principal(), id);
        return publish(id, "risk.wallet_dispute.withdrawn");
    }

    // ------------------------------------------------------------------ evidence

    @Transactional
    public String addEvidence(UUID disputeId, JsonNode body) {
        require(disputeId, "add_evidence");
        ObjectNode v = crud.toColumns(EVIDENCE, body);
        if (!DisputeRules.validEvidenceType(Db.str(v, "evidence_type"))) {
            throw ApiException.unprocessable("invalid_evidence_type", "evidence_type must be one of " + DisputeRules.EVIDENCE_TYPES);
        }
        v.put("wallet_dispute_id", disputeId.toString());
        return crud.insert(EVIDENCE, v);
    }

    public String evidence(UUID disputeId) {
        db.require(TABLE, disputeId, false);
        return "{\"data\":[" + String.join(",", db.jdbc()
                .sql("select row_to_json(e)::text from " + EVIDENCE + " e where wallet_dispute_id = ? order by created_at")
                .param(disputeId).query(String.class).list()) + "]}";
    }

    // ------------------------------------------------------------------ SLA sweep (idempotent)

    /** Marks an open, past-deadline dispute as SLA-breached (forward-only, logged). Idempotent. */
    @Transactional
    public String markSlaBreached(UUID id) {
        JsonNode d = db.require(TABLE, id, true);
        OffsetDateTime due = Db.str(d, "due_at") == null ? null : OffsetDateTime.parse(d.get("due_at").asString());
        if (Db.bool(d, "sla_breached") || !DisputeRules.slaBreached(Db.str(d, "status"), due, Clocks.now())) {
            return db.text(d);
        }
        db.update("update " + TABLE + " set sla_breached = true, updated_by = ? where id = ?", TenantContext.principal(), id);
        return publish(id, "risk.wallet_dispute.sla_breached");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode require(UUID id, String action) {
        gate();
        JsonNode d = db.require(TABLE, id, true);
        if (!DisputeRules.disputeAllows(action, Db.str(d, "status"))) {
            throw ApiException.conflict("invalid_transition",
                    "Cannot " + action.replace('_', ' ') + " a dispute that is " + Db.str(d, "status"));
        }
        return d;
    }

    private String publish(UUID id, String event) {
        JsonNode row = db.require(TABLE, id, false);
        outbox.publish(event, TABLE, id, row);
        return db.text(row);
    }

    private static UUID id(JsonNode n) {
        return UUID.fromString(n.get("id").asString());
    }
}
