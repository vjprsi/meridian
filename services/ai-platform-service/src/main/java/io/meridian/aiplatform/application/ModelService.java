package io.meridian.aiplatform.application;

import io.meridian.aiplatform.domain.Lifecycles;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Model registry under model risk management: versions are edited while TRAINING / VALIDATING / REJECTED, submitted
 * with offline metrics and passing bias tests (HIGH-risk models also need explainability and an independent
 * validation report), approved or rejected by a principal other than the submitter, then deployed.
 */
@Service
public class ModelService {
    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public ModelService(Store store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    /** Version payload checks (create and update, merged with the existing row). */
    public void prepareVersion(ObjectNode values, JsonNode existing) {
        if (existing != null && !Lifecycles.VERSION_EDITABLE.contains(existing.get("status").asString())) {
            throw ApiException.conflict("version_locked", "A version in status " + existing.get("status").asString()
                    + " cannot be edited; register a new version");
        }
        JsonNode merged = existing == null ? values : ((ObjectNode) existing.deepCopy()).setAll(values);
        UUID modelId = Fields.requireUuid(merged, "ml_model_id");
        JsonNode model = store.find("ml_model", modelId).orElseThrow(() ->
                ApiException.unprocessable("model_not_found", "Model " + modelId + " does not exist"));
        if ("RETIRED".equals(model.get("status").asString())) {
            throw ApiException.unprocessable("model_retired", "Model " + model.get("code").asString() + " is retired");
        }
        String sha = Fields.text(merged, "artifact_sha256");
        if (sha != null && !sha.matches("[0-9a-f]{64}")) {
            throw Fields.invalid("artifact_sha256", "invalid_checksum", "artifact_sha256 must be 64 lower-case hex characters");
        }
        if (merged.hasNonNull("training_from") && merged.hasNonNull("training_to")
                && Fields.date(merged, "training_to").isBefore(Fields.date(merged, "training_from"))) {
            throw Fields.invalid("training_to", "invalid_range", "training_to must not be before training_from");
        }
        JsonNode metrics = merged.get("metrics");
        if (metrics != null && !metrics.isNull()) {
            metrics.propertyNames().forEach(k -> {
                if (!Fields.numeric(metrics.get(k))) {
                    throw Fields.invalid("metrics." + k, "invalid_metric", "metric " + k + " must be a number");
                }
            });
        }
        JsonNode bias = merged.get("bias_test_results");
        if (bias != null && bias.isArray()) {
            for (JsonNode b : bias) {
                if (!b.hasNonNull("attribute") || !b.hasNonNull("metric") || !b.path("passed").isBoolean()) {
                    throw Fields.invalid("bias_test_results", "invalid_bias_test", "each bias test needs attribute, metric and passed");
                }
            }
        }
    }

    @Transactional
    public String submit(UUID id, String comment) {
        JsonNode v = store.requireLocked("ml_model_version", id);
        Lifecycles.version(v.get("status").asString(), "PENDING_APPROVAL");
        JsonNode model = store.require("ml_model", uuid(v, "ml_model_id"));
        if (!v.path("metrics").isObject() || v.get("metrics").isEmpty()) {
            throw ApiException.unprocessable("metrics_required", "Offline metrics are required before approval");
        }
        JsonNode bias = v.path("bias_test_results");
        for (JsonNode b : bias) {
            if (!b.path("passed").asBoolean(false)) {
                throw ApiException.unprocessable("bias_test_failed", "Bias test " + b.path("metric").asString() + " on "
                        + b.path("attribute").asString() + " failed; the version cannot be submitted");
            }
        }
        if ("HIGH".equals(model.get("risk_tier").asString())) {
            if (!bias.isArray() || bias.isEmpty()) {
                throw ApiException.unprocessable("bias_tests_required", "HIGH-risk models need bias test results");
            }
            if (!v.hasNonNull("explainability_notes") && !v.hasNonNull("explainability_document_id")) {
                throw ApiException.unprocessable("explainability_required", "HIGH-risk models need explainability notes or a report");
            }
            if (!v.hasNonNull("validation_document_id")) {
                throw ApiException.unprocessable("validation_report_required", "HIGH-risk models need an independent validation report");
            }
        }
        JsonNode row = store.updateReturning("""
                update ml_model_version t set status = 'PENDING_APPROVAL', submitted_by = ?, submitted_at = now(),
                       decision_comment = ?, rejection_reason = null, updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", TenantContext.principal(), comment, TenantContext.principal(), id)
                .orElseThrow();
        outbox.publish("reporting.ml_model_version.submitted", "ml_model_version", id, row);
        return row.toString();
    }

    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode v = store.requireLocked("ml_model_version", id);
        Lifecycles.version(v.get("status").asString(), "APPROVED");
        requireIndependent(v);
        JsonNode row = store.updateReturning("""
                update ml_model_version t set status = 'APPROVED', approved_by = ?, approved_at = now(), decision_comment = ?,
                       updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                TenantContext.principal(), comment, TenantContext.principal(), id).orElseThrow();
        outbox.publish("reporting.ml_model_version.approved", "ml_model_version", id, row);
        return row.toString();
    }

    @Transactional
    public String reject(UUID id, String reason) {
        JsonNode v = store.requireLocked("ml_model_version", id);
        Lifecycles.version(v.get("status").asString(), "REJECTED");
        requireIndependent(v);
        JsonNode row = store.updateReturning("""
                update ml_model_version t set status = 'REJECTED', rejection_reason = ?, decision_comment = ?, updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", reason, reason, TenantContext.principal(), id).orElseThrow();
        outbox.publish("reporting.ml_model_version.rejected", "ml_model_version", id, row);
        return row.toString();
    }

    private static void requireIndependent(JsonNode v) {
        String me = TenantContext.principal();
        if (me.equals(Fields.text(v, "submitted_by")) || me.equals(Fields.text(v, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The developer or submitter of a model version cannot decide on it");
        }
    }

    /** Deploys an approved version. */
    @Transactional
    public String deploy(UUID versionId, JsonNode req) {
        JsonNode v = store.requireLocked("ml_model_version", versionId);
        String status = v.get("status").asString();
        if (!"APPROVED".equals(status) && !"DEPLOYED".equals(status)) {
            throw ApiException.conflict("version_not_approved", "Only approved versions can be deployed (version is " + status + ")");
        }
        UUID modelId = uuid(v, "ml_model_id");
        JsonNode model = store.requireLocked("ml_model", modelId);
        String env = Fields.requireText(req, "environment").toUpperCase(Locale.ROOT);
        if (!Lifecycles.ENVIRONMENTS.contains(env)) {
            throw Fields.invalid("environment", "invalid_environment", "environment must be one of " + Lifecycles.ENVIRONMENTS);
        }
        if ("PRODUCTION".equals(env) && !"ACTIVE".equals(model.get("status").asString())) {
            throw ApiException.unprocessable("model_not_active", "Model " + model.get("code").asString() + " is "
                    + model.get("status").asString() + "; production deployments need an ACTIVE model");
        }
        String mode = Fields.requireText(req, "mode");
        Integer requested = Fields.integer(req, "traffic_percent");
        int traffic = switch (mode) {
            case "SHADOW" -> {
                if (requested != null && requested != 0) {
                    throw Fields.invalid("traffic_percent", "invalid_traffic", "SHADOW deployments serve no traffic (0)");
                }
                yield 0;
            }
            case "CANARY", "CHALLENGER" -> {
                int t = requested == null ? 10 : requested;
                if (t < 1 || t > 50) {
                    throw Fields.invalid("traffic_percent", "invalid_traffic", mode + " traffic must be 1-50%");
                }
                yield t;
            }
            case "CHAMPION" -> {
                if (requested != null && requested != 100) {
                    throw Fields.invalid("traffic_percent", "invalid_traffic", "A CHAMPION serves 100% of traffic");
                }
                yield 100;
            }
            default -> throw Fields.invalid("mode", "invalid_enum", "mode must be SHADOW, CANARY, CHAMPION or CHALLENGER");
        };
        if (store.count("select count(*) from model_deployment where ml_model_version_id = ? and environment = ? and status = 'ACTIVE'",
                versionId, env) > 0) {
            throw ApiException.conflict("already_deployed", "The version already has an active deployment in " + env);
        }
        if ("CHAMPION".equals(mode) && store.count("select count(*) from model_deployment where ml_model_id = ? and environment = ? "
                + "and mode = 'CHAMPION' and status = 'ACTIVE'", modelId, env) > 0) {
            throw ApiException.conflict("champion_exists", "The model already has a champion in " + env
                    + "; deploy as SHADOW / CANARY / CHALLENGER and promote");
        }
        JsonNode thresholds = req.get("thresholds");
        if (thresholds != null && !thresholds.isNull()) {
            DeploymentService.checkThresholds(thresholds);
        }
        UUID id = Ids.newId();
        ObjectNode d = json.createObjectNode();
        d.put("id", id.toString());
        d.put("ml_model_version_id", versionId.toString());
        d.put("ml_model_id", modelId.toString());
        d.put("environment", env);
        d.put("mode", mode);
        d.put("traffic_percent", traffic);
        d.put("status", "ACTIVE");
        d.put("endpoint", "https://models." + env.toLowerCase(Locale.ROOT) + ".meridian.internal/v1/models/"
                + model.get("code").asString().toLowerCase(Locale.ROOT) + "-" + v.get("model_version").asString() + ":predict");
        if (thresholds != null && !thresholds.isNull()) {
            d.set("thresholds", thresholds);
        }
        d.put("auto_rollback", Fields.bool(req, "auto_rollback", false));
        d.put("deployed_by", TenantContext.principal());
        crud.insert("model_deployment", d);
        JsonNode row = store.updateReturning("update model_deployment t set deployed_at = now() where t.id = ? returning row_to_json(t)::text", id)
                .orElseThrow();
        store.update("update ml_model_version set status = 'DEPLOYED', updated_by = ? where id = ?", TenantContext.principal(), versionId);
        if ("CHAMPION".equals(mode) && "PRODUCTION".equals(env)) {
            store.update("update ml_model set champion_version_id = ?, updated_by = ? where id = ?", versionId, TenantContext.principal(), modelId);
        }
        outbox.publish("reporting.model_deployment.activated", "model_deployment", id, row);
        return row.toString();
    }

    static UUID uuid(JsonNode n, String f) {
        return UUID.fromString(n.get(f).asString());
    }
}
