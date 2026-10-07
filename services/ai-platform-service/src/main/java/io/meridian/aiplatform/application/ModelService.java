package io.meridian.aiplatform.application;

import io.meridian.aiplatform.domain.Lifecycles;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
    /** Approval resource type for model versions in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "ml_model_version";

    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Approvals approvals;

    public ModelService(Store store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, Approvals approvals) {
        this.approvals = approvals;
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
        if (existing != null && values.has("artifact_sha256") && !java.util.Objects.equals(sha, Fields.text(existing, "artifact_sha256"))
                && store.count("select count(*) from ml_model_artifact where ml_model_version_id = ?",
                        UUID.fromString(existing.get("id").asString())) > 0) {
            throw ApiException.conflict("artifact_locked", "The version's inline artefact is uploaded; its checksum cannot change");
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
        // Central maker-checker: the checker decides in the workflow inbox; ModelVersionApprovalHandler applies it.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ml_model_id", model.get("id").asString());
        payload.put("model_code", model.get("code").asString());
        payload.put("risk_tier", model.get("risk_tier").asString());
        payload.put("model_version", row.path("model_version").asString());
        payload.put("artifact_sha256", Fields.text(row, "artifact_sha256"));
        payload.put("metrics", row.get("metrics"));
        payload.put("bias_test_results", row.get("bias_test_results"));
        payload.put("validation_document_id", Fields.text(row, "validation_document_id"));
        payload.put("submitted_at", row.get("submitted_at").asString());
        payload.put("row_version", row.path("row_version").asLong());
        payload.put("comment", comment);
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Model " + model.get("code").asString() + " v" + row.path("model_version").asString(), payload)
                        .withSummary(model.get("risk_tier").asString() + "-risk model version submitted for approval"),
                "submit:" + id + ":" + row.get("submitted_at").asString());
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
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
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
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return row.toString();
    }

    /** True while the version is PENDING_APPROVAL for the submission identified by {@code submittedAt}. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String submittedAt) {
        return store.find("ml_model_version", id)
                .filter(v -> "PENDING_APPROVAL".equals(v.path("status").asString()))
                .filter(v -> submittedAt == null || submittedAt.equals(v.path("submitted_at").asString(null)))
                .isPresent();
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

    /** Largest inline artefact accepted (UTF-8 bytes). */
    public static final int MAX_ARTIFACT_BYTES = 2 * 1024 * 1024;

    /**
     * Stores the inline scoring artefact of a version (write-once, while the version is editable). The content must be
     * JSON and its SHA-256 must equal the version's {@code artifact_sha256}, so what was validated and approved is
     * byte-for-byte what consumers load (they verify the checksum again).
     */
    @Transactional
    public ObjectNode uploadArtifact(UUID versionId, String format, String content) {
        JsonNode v = store.requireLocked("ml_model_version", versionId);
        if (!Lifecycles.VERSION_EDITABLE.contains(v.get("status").asString())) {
            throw ApiException.conflict("version_locked", "The artefact of a version in status " + v.get("status").asString()
                    + " cannot be set; register a new version");
        }
        if (store.count("select count(*) from ml_model_artifact where ml_model_version_id = ?", versionId) > 0) {
            throw ApiException.conflict("artifact_exists", "The version already has an artefact (write-once); register a new version");
        }
        if (format == null || !format.matches("[a-z0-9][a-z0-9._/-]{0,63}")) {
            throw Fields.invalid("format", "invalid_format", "format is a lower-case identifier such as meridian-fraud-model/1");
        }
        if (content == null || content.isBlank()) {
            throw Fields.invalid("content", "required", "content is required");
        }
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            throw ApiException.unprocessable("artifact_too_large", "The artefact exceeds " + MAX_ARTIFACT_BYTES + " bytes");
        }
        try {
            if (!json.readTree(content).isObject()) {
                throw ApiException.unprocessable("invalid_artifact", "The artefact must be a JSON object");
            }
        } catch (tools.jackson.core.JacksonException e) {
            throw ApiException.unprocessable("invalid_artifact", "The artefact is not valid JSON");
        }
        String sha = sha256(bytes);
        if (!sha.equals(Fields.text(v, "artifact_sha256"))) {
            throw ApiException.unprocessable("checksum_mismatch", "SHA-256 of the content (" + sha
                    + ") differs from the version's artifact_sha256");
        }
        ObjectNode a = json.createObjectNode();
        a.put("ml_model_version_id", versionId.toString());
        a.put("format", format);
        a.put("content", content);
        a.put("sha256", sha);
        a.put("size_bytes", bytes.length);
        JsonNode row = json.readTree(crud.insert("ml_model_artifact", a));
        ObjectNode out = json.createObjectNode();
        out.put("ml_model_version_id", versionId.toString());
        out.put("format", format);
        out.put("sha256", sha);
        out.put("size_bytes", bytes.length);
        out.put("created_at", row.path("created_at").asString());
        out.put("created_by", row.path("created_by").asString(null));
        return out;
    }

    /** The inline artefact with its checksum and the version's declared checksum (404 when none was uploaded). */
    @Transactional(readOnly = true)
    public ObjectNode artifact(UUID versionId) {
        JsonNode v = store.require("ml_model_version", versionId);
        JsonNode a = store.row("select row_to_json(t)::text from ml_model_artifact t where t.ml_model_version_id = ?", versionId)
                .orElseThrow(() -> ApiException.notFound("artifact of model version", versionId));
        ObjectNode out = json.createObjectNode();
        out.put("ml_model_version_id", versionId.toString());
        out.put("ml_model_id", v.get("ml_model_id").asString());
        out.put("model_version", v.path("model_version").asString());
        out.put("status", v.path("status").asString());
        out.put("format", a.get("format").asString());
        out.put("sha256", a.get("sha256").asString());
        out.put("artifact_sha256", Fields.text(v, "artifact_sha256"));
        out.put("size_bytes", a.get("size_bytes").asInt());
        out.put("content", a.get("content").asString());
        out.put("created_at", a.path("created_at").asString());
        return out;
    }

    public static String contentSha(String content) {
        return sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static UUID uuid(JsonNode n, String f) {
        return UUID.fromString(n.get(f).asString());
    }
}
