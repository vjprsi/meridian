package io.meridian.transactionmonitoring.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import io.meridian.events.OutboxWriter;
import io.meridian.transactionmonitoring.domain.Scenarios;
import io.meridian.transactionmonitoring.infrastructure.Db;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scenario threshold versions (validated per typology, numbered), maker-checker activation of a version (the
 * activator must not be its author; the previous version is retired) and retirement.
 */
@Service
public class ScenarioService {
    /** Approval resource type for scenario version activation submissions in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "tm_scenario_version";
    private static final String CLEAR_SUBMISSION = "submitted_by = null, submitted_at = null";

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final ScenarioCatalog catalog;
    private final JsonMapper json;
    private final Approvals approvals;

    public ScenarioService(Db db, CrudEngine crud, OutboxWriter outbox, ScenarioCatalog catalog, JsonMapper json,
                           Approvals approvals) {
        this.approvals = approvals;
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.catalog = catalog;
        this.json = json;
    }

    @Transactional
    public String addVersion(UUID scenarioId, JsonNode body) {
        JsonNode s = db.require("tm_scenario", scenarioId, true);
        if ("RETIRED".equals(s.get("status").asString())) {
            throw ApiException.conflict("scenario_retired", "A retired scenario cannot get new versions");
        }
        ObjectNode v = crud.toColumns("tm_scenario_version", body);
        v.put("tm_scenario_id", scenarioId.toString());
        if (!v.hasNonNull("risk_weight")) {
            v.put("risk_weight", "1");
        }
        BigDecimal weight = new BigDecimal(v.get("risk_weight").asString());
        if (weight.signum() <= 0 || weight.compareTo(new BigDecimal("3")) > 0) {
            throw ApiException.unprocessable("invalid_risk_weight", "risk_weight must be in (0, 3]");
        }
        String error = Scenarios.validate(s.get("scenario_type").asString(), catalog.params(v));
        if (error != null) {
            throw ApiException.unprocessable("invalid_parameters", error);
        }
        int next = db.jdbc().sql("select coalesce(max(scenario_version), 0) + 1 from tm_scenario_version where tm_scenario_id = ?")
                .param(scenarioId).query(Integer.class).single();
        v.put("scenario_version", next);
        v.put("status", "DRAFT");
        return crud.insert("tm_scenario_version", v);
    }

    @Transactional
    public String activate(UUID scenarioId, int version) {
        JsonNode s = db.require("tm_scenario", scenarioId, true);
        if ("RETIRED".equals(s.get("status").asString())) {
            throw ApiException.conflict("scenario_retired", "A retired scenario cannot be activated");
        }
        if (!Scenarios.SUPPORTED.contains(s.get("scenario_type").asString())) {
            throw ApiException.unprocessable("scenario_type_not_supported", s.get("scenario_type").asString() + " has no evaluator");
        }
        JsonNode v = db.one("select row_to_json(v)::text from tm_scenario_version v where tm_scenario_id = ? and scenario_version = ? for update",
                scenarioId, version).orElseThrow(() -> ApiException.notFound("scenario version", version));
        if ("ACTIVE".equals(v.get("status").asString()) && "ACTIVE".equals(s.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Version " + version + " is already active");
        }
        if (TenantContext.principal().equals(Db.str(v, "created_by"))
                || TenantContext.principal().equals(Db.str(v, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "Version " + version + " was authored or submitted by "
                    + TenantContext.principal() + "; another user must activate it");
        }
        db.update("update tm_scenario_version set status = 'RETIRED', updated_by = ? where tm_scenario_id = ? and status = 'ACTIVE' and scenario_version <> ?",
                TenantContext.principal(), scenarioId, version);
        db.update("update tm_scenario_version set status = 'ACTIVE', approved_by = ?, effective_from = now(), updated_by = ?, "
                + CLEAR_SUBMISSION + " where id = ?",
                TenantContext.principal(), TenantContext.principal(), UUID.fromString(v.get("id").asString()));
        approvals.cancelOpen(APPROVAL_RESOURCE, UUID.fromString(v.get("id").asString()), null, "activated directly");
        db.update("update tm_scenario set status = 'ACTIVE', active_version = ?, updated_by = ? where id = ?",
                version, TenantContext.principal(), scenarioId);
        JsonNode activated = db.require("tm_scenario_version", UUID.fromString(v.get("id").asString()), false);
        outbox.publish("risk.tm_scenario.version_activated", "tm_scenario_version", UUID.fromString(v.get("id").asString()), activated);
        catalog.invalidate();
        return db.text(db.require("tm_scenario", scenarioId, false));
    }

    /**
     * Maker step: asks for activation of a DRAFT or TUNING version. The version keeps its status until a checker
     * activates it on the scenario (directly or in the workflow inbox, {@link ScenarioVersionApprovalHandler}) or
     * rejects the submission. A new submission replaces a pending one.
     */
    @Transactional
    public String submitVersion(UUID versionId, String comment) {
        JsonNode v = db.require("tm_scenario_version", versionId, true);
        UUID scenarioId = UUID.fromString(v.get("tm_scenario_id").asString());
        JsonNode s = db.require("tm_scenario", scenarioId, false);
        if ("RETIRED".equals(s.get("status").asString())) {
            throw ApiException.conflict("scenario_retired", "A retired scenario cannot be activated");
        }
        String status = v.get("status").asString();
        if (!"DRAFT".equals(status) && !"TUNING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " version cannot be submitted for activation");
        }
        OffsetDateTime at = Clocks.now();
        db.update("update tm_scenario_version set submitted_by = ?, submitted_at = ?, rejected_by = null,"
                + " rejection_reason = null, updated_by = ? where id = ?", TenantContext.principal(), at, TenantContext.principal(), versionId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tm_scenario_id", scenarioId.toString());
        payload.put("scenario_code", Db.str(s, "code"));
        payload.put("scenario_type", Db.str(s, "scenario_type"));
        payload.put("scenario_version", v.get("scenario_version").asInt());
        payload.put("active_version", s.hasNonNull("active_version") ? s.get("active_version").asInt() : null);
        payload.put("parameters", v.get("parameters"));
        payload.put("lookback_days", Db.integer(v, "lookback_days"));
        payload.put("min_amount", Db.str(v, "min_amount"));
        payload.put("min_count", Db.integer(v, "min_count"));
        payload.put("risk_weight", Db.str(v, "risk_weight"));
        payload.put("tuning_rationale", Db.str(v, "tuning_rationale"));
        payload.put("submitted_at", at.toString());
        if (comment != null) {
            payload.put("comment", comment);
        }
        UUID requestId = Approvals.idOf(approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, versionId, "activate",
                "TM scenario " + Db.str(s, "code") + " v" + v.get("scenario_version").asInt(), payload),
                "tm-version:" + versionId + ":" + at));
        if (requestId != null) {
            db.update("update tm_scenario_version set approval_request_id = ? where id = ?", requestId, versionId);
            approvals.cancelOpenExcept(APPROVAL_RESOURCE, versionId, null, requestId, "superseded");
        }
        JsonNode row = db.require("tm_scenario_version", versionId, false);
        outbox.publish("risk.tm_scenario.version_submitted", "tm_scenario_version", versionId, row);
        return db.text(row);
    }

    /** True while the version waits on the activation submission opened as {@code approvalRequestId}. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID versionId, UUID approvalRequestId) {
        return db.one("select row_to_json(t)::text from tm_scenario_version t where id = ?", versionId)
                .filter(v -> v.hasNonNull("submitted_at"))
                .filter(v -> approvalRequestId != null && approvalRequestId.toString().equals(Db.str(v, "approval_request_id")))
                .isPresent();
    }

    /** Applies an approved submission: activates the version on its scenario. */
    @Transactional
    public String activateVersion(UUID versionId) {
        JsonNode v = db.require("tm_scenario_version", versionId, false);
        return activate(UUID.fromString(v.get("tm_scenario_id").asString()), v.get("scenario_version").asInt());
    }

    /** Checker step: rejects the pending activation submission; the version keeps its status. */
    @Transactional
    public String rejectVersion(UUID versionId, String reason) {
        JsonNode v = db.require("tm_scenario_version", versionId, true);
        if (!v.hasNonNull("submitted_at")) {
            throw ApiException.conflict("no_pending_submission", "Version has no pending activation submission");
        }
        if (TenantContext.principal().equals(Db.str(v, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The submitter cannot reject their own submission");
        }
        db.update("update tm_scenario_version set rejected_by = ?, rejection_reason = ?, updated_by = ?, " + CLEAR_SUBMISSION
                + " where id = ?", TenantContext.principal(), reason, TenantContext.principal(), versionId);
        JsonNode row = db.require("tm_scenario_version", versionId, false);
        outbox.publish("risk.tm_scenario.version_rejected", "tm_scenario_version", versionId, row);
        approvals.cancelOpen(APPROVAL_RESOURCE, versionId, null, "rejected directly");
        return db.text(row);
    }

    @Transactional
    public String retire(UUID scenarioId, String reason) {
        JsonNode s = db.require("tm_scenario", scenarioId, true);
        if ("RETIRED".equals(s.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Scenario is already retired");
        }
        db.update("update tm_scenario set status = 'RETIRED', retired_reason = ?, updated_by = ? where id = ?",
                reason, TenantContext.principal(), scenarioId);
        db.update("update tm_scenario_version set status = 'RETIRED', updated_by = ? where tm_scenario_id = ? and status = 'ACTIVE'",
                TenantContext.principal(), scenarioId);
        // pending activation submissions lapse with the scenario (their workflow decisions are then ignored)
        db.update("update tm_scenario_version set " + CLEAR_SUBMISSION + " where tm_scenario_id = ? and submitted_at is not null",
                scenarioId);
        JsonNode row = db.require("tm_scenario", scenarioId, false);
        outbox.publish("risk.tm_scenario.retired", "tm_scenario", scenarioId, row);
        catalog.invalidate();
        return db.text(row);
    }

    /** Scenario version as an evaluatable scenario (any status: backtests use drafts too). */
    public ScenarioCatalog.Active asActive(UUID scenarioId, int version) {
        JsonNode s = db.require("tm_scenario", scenarioId, false);
        JsonNode v = db.one("select row_to_json(v)::text from tm_scenario_version v where tm_scenario_id = ? and scenario_version = ?",
                scenarioId, version).orElseThrow(() -> ApiException.notFound("scenario version", version));
        return catalog.active(s, v);
    }

    public ScenarioCatalog.Active asActive(UUID versionId) {
        JsonNode v = db.require("tm_scenario_version", versionId, false);
        JsonNode s = db.require("tm_scenario", UUID.fromString(v.get("tm_scenario_id").asString()), false);
        return catalog.active(s, v);
    }
}
