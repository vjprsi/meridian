package io.meridian.transactionmonitoring.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
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
    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final ScenarioCatalog catalog;
    private final JsonMapper json;

    public ScenarioService(Db db, CrudEngine crud, OutboxWriter outbox, ScenarioCatalog catalog, JsonMapper json) {
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
        if (TenantContext.principal().equals(Db.str(v, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "Version " + version + " was authored by " + Db.str(v, "created_by") + "; another user must activate it");
        }
        db.update("update tm_scenario_version set status = 'RETIRED', updated_by = ? where tm_scenario_id = ? and status = 'ACTIVE' and scenario_version <> ?",
                TenantContext.principal(), scenarioId, version);
        db.update("update tm_scenario_version set status = 'ACTIVE', approved_by = ?, effective_from = now(), updated_by = ? where id = ?",
                TenantContext.principal(), TenantContext.principal(), UUID.fromString(v.get("id").asString()));
        db.update("update tm_scenario set status = 'ACTIVE', active_version = ?, updated_by = ? where id = ?",
                version, TenantContext.principal(), scenarioId);
        JsonNode activated = db.require("tm_scenario_version", UUID.fromString(v.get("id").asString()), false);
        outbox.publish("risk.tm_scenario.version_activated", "tm_scenario_version", UUID.fromString(v.get("id").asString()), activated);
        catalog.invalidate();
        return db.text(db.require("tm_scenario", scenarioId, false));
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
