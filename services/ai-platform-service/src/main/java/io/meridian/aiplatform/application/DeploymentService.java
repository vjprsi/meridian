package io.meridian.aiplatform.application;

import io.meridian.aiplatform.domain.Drift;
import io.meridian.aiplatform.domain.Lifecycles;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deployments: promotion shadow -> canary (traffic %) -> champion, challenger -> champion (the replaced champion is
 * retired and remembered), rollback (restoring the replaced champion), retirement, monitoring metrics with
 * direction-aware thresholds, PSI drift from feature distributions, and the per-tenant monitoring job.
 */
@Service
public class DeploymentService {
    static final int STALE_DAYS = 7;

    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public DeploymentService(Store store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, TransactionTemplate tx) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
    }

    static void checkThresholds(JsonNode thresholds) {
        if (!thresholds.isObject()) {
            throw Fields.invalid("thresholds", "invalid_type", "thresholds must be an object of metric name -> number");
        }
        thresholds.propertyNames().forEach(k -> {
            if (!Drift.METRICS.contains(k) || !Fields.numeric(thresholds.get(k))) {
                throw Fields.invalid("thresholds." + k, "invalid_threshold", "thresholds must map known metrics " + Drift.METRICS + " to numbers");
            }
        });
    }

    @Transactional
    public String promote(UUID id, Integer traffic, String comment) {
        JsonNode d = store.requireLocked("model_deployment", id);
        if (!"ACTIVE".equals(d.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Only ACTIVE deployments can be promoted (deployment is " + d.get("status").asString() + ")");
        }
        String mode = d.get("mode").asString();
        int current = d.get("traffic_percent").asInt();
        String newMode;
        int newTraffic;
        switch (mode) {
            case "SHADOW" -> {
                newMode = "CANARY";
                newTraffic = traffic == null ? 10 : traffic;
                if (newTraffic < 1 || newTraffic > 50) {
                    throw Fields.invalid("traffic_percent", "invalid_traffic", "A canary serves 1-50% of traffic");
                }
            }
            case "CANARY" -> {
                if (traffic != null && traffic < 100) {
                    if (traffic <= current || traffic > 50) {
                        throw Fields.invalid("traffic_percent", "invalid_traffic", "Canary traffic can only grow (currently "
                                + current + "%, at most 50%); promote without traffic_percent to make it champion");
                    }
                    newMode = "CANARY";
                    newTraffic = traffic;
                } else {
                    newMode = "CHAMPION";
                    newTraffic = 100;
                }
            }
            case "CHALLENGER" -> {
                newMode = "CHAMPION";
                newTraffic = 100;
            }
            default -> throw ApiException.conflict("already_champion", "The deployment is already the champion");
        }
        UUID previous = null;
        if ("CHAMPION".equals(newMode)) {
            Optional<JsonNode> champion = store.row("""
                    select row_to_json(t)::text from model_deployment t where t.ml_model_id = ?::uuid and t.environment = ?
                      and t.mode = 'CHAMPION' and t.status = 'ACTIVE' and t.id <> ? for update""",
                    d.get("ml_model_id").asString(), d.get("environment").asString(), id);
            if (champion.isPresent()) {
                previous = UUID.fromString(champion.get().get("id").asString());
                JsonNode retired = store.updateReturning("""
                        update model_deployment t set status = 'RETIRED', retired_at = now(), traffic_percent = 0,
                               status_reason = ?, updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                        "Replaced by deployment " + id, TenantContext.principal(), previous).orElseThrow();
                outbox.publish("reporting.model_deployment.retired", "model_deployment", previous, retired);
            }
            if ("PRODUCTION".equals(d.get("environment").asString())) {
                store.update("update ml_model set champion_version_id = ?::uuid, updated_by = ? where id = ?::uuid",
                        d.get("ml_model_version_id").asString(), TenantContext.principal(), d.get("ml_model_id").asString());
            }
        }
        JsonNode row = store.updateReturning("""
                update model_deployment t set mode = ?, traffic_percent = ?, previous_deployment_id = coalesce(?, t.previous_deployment_id),
                       status_reason = ?, updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                newMode, newTraffic, previous, comment == null ? "Promoted from " + mode : comment, TenantContext.principal(), id)
                .orElseThrow();
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        payload.put("from_mode", mode);
        payload.put("from_traffic_percent", current);
        outbox.publish("reporting.model_deployment.promoted", "model_deployment", id, payload);
        if ("CHAMPION".equals(newMode)) {
            outbox.publish("reporting.model_deployment.activated", "model_deployment", id, row);
        }
        return row.toString();
    }

    @Transactional
    public String rollback(UUID id, String reason) {
        return rollbackLocked(store.requireLocked("model_deployment", id), reason);
    }

    private String rollbackLocked(JsonNode d, String reason) {
        UUID id = UUID.fromString(d.get("id").asString());
        Lifecycles.deployment(d.get("status").asString(), "ROLLED_BACK");
        JsonNode row = store.updateReturning("""
                update model_deployment t set status = 'ROLLED_BACK', traffic_percent = 0, status_reason = ?, updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", reason, TenantContext.principal(), id).orElseThrow();
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        if ("CHAMPION".equals(d.get("mode").asString())) {
            String restoredVersion = null;
            if (d.hasNonNull("previous_deployment_id")) {
                UUID prev = UUID.fromString(d.get("previous_deployment_id").asString());
                JsonNode p = store.requireLocked("model_deployment", prev);
                if ("RETIRED".equals(p.get("status").asString())) {
                    JsonNode restored = store.updateReturning("""
                            update model_deployment t set status = 'ACTIVE', mode = 'CHAMPION', traffic_percent = 100, retired_at = null,
                                   status_reason = ?, updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                            "Restored by rollback of " + id, TenantContext.principal(), prev).orElseThrow();
                    restoredVersion = restored.get("ml_model_version_id").asString();
                    payload.put("restored_deployment_id", prev.toString());
                    outbox.publish("reporting.model_deployment.activated", "model_deployment", prev, restored);
                }
            }
            if ("PRODUCTION".equals(d.get("environment").asString())) {
                store.update("update ml_model set champion_version_id = ?::uuid, updated_by = ? where id = ?::uuid",
                        restoredVersion, TenantContext.principal(), d.get("ml_model_id").asString());
            }
        }
        outbox.publish("reporting.model_deployment.rolled_back", "model_deployment", id, payload);
        return row.toString();
    }

    @Transactional
    public String retire(UUID id, String reason) {
        JsonNode d = store.requireLocked("model_deployment", id);
        if ("CHAMPION".equals(d.get("mode").asString()) && "ACTIVE".equals(d.get("status").asString())) {
            throw ApiException.conflict("champion_in_service", "The active champion cannot be retired; promote another version or roll back");
        }
        Lifecycles.deployment(d.get("status").asString(), "RETIRED");
        if ("RETIRED".equals(d.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "The deployment is already retired");
        }
        JsonNode row = store.updateReturning("""
                update model_deployment t set status = 'RETIRED', traffic_percent = 0, retired_at = now(), status_reason = ?, updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", reason, TenantContext.principal(), id).orElseThrow();
        outbox.publish("reporting.model_deployment.retired", "model_deployment", id, row);
        return row.toString();
    }

    /** Records a monitoring metric (threshold defaulted, breach computed by the metric's direction). */
    @Transactional
    public String recordMetric(JsonNode req) {
        UUID deploymentId = Fields.requireUuid(req, "model_deployment_id");
        JsonNode d = store.find("model_deployment", deploymentId).orElseThrow(() ->
                ApiException.unprocessable("deployment_not_found", "Deployment " + deploymentId + " does not exist"));
        String name = Fields.requireText(req, "metric_name");
        if (!Drift.METRICS.contains(name)) {
            throw Fields.invalid("metric_name", "invalid_metric", "metric_name must be one of " + Drift.METRICS);
        }
        return insertMetric(d, name, Fields.text(req, "feature"), Fields.requireDecimal(req, "metric_value"),
                Fields.decimal(req, "threshold"), Fields.requireText(req, "window_start"), Fields.requireText(req, "window_end")).toString();
    }

    private JsonNode insertMetric(JsonNode d, String name, String feature, BigDecimal value, BigDecimal threshold,
                                  String windowStart, String windowEnd) {
        if (!"ACTIVE".equals(d.get("status").asString())) {
            throw ApiException.conflict("deployment_not_active", "Metrics are recorded for ACTIVE deployments only");
        }
        OffsetDateTime ws;
        OffsetDateTime we;
        try {
            ws = OffsetDateTime.parse(windowStart);
            we = OffsetDateTime.parse(windowEnd);
        } catch (java.time.format.DateTimeParseException e) {
            throw Fields.invalid("window_start", "invalid_timestamp", "window_start / window_end must be ISO timestamps");
        }
        if (!we.isAfter(ws)) {
            throw Fields.invalid("window_end", "invalid_range", "window_end must be after window_start");
        }
        BigDecimal t = threshold;
        if (t == null && d.path("thresholds").hasNonNull(name)) {
            t = new BigDecimal(d.get("thresholds").get(name).asString());
        }
        if (t == null) {
            t = Drift.DEFAULT_THRESHOLDS.get(name);
        }
        boolean breached = Drift.breached(name, value, t);
        ObjectNode m = json.createObjectNode();
        m.put("model_deployment_id", d.get("id").asString());
        m.put("metric_name", name);
        if (feature != null) {
            m.put("feature", feature);
        }
        m.put("metric_value", value.toPlainString());
        if (t != null) {
            m.put("threshold", t.toPlainString());
        }
        m.put("breached", breached);
        m.put("window_start", ws.toString());
        m.put("window_end", we.toString());
        JsonNode row = json.readTree(crud.insert("model_monitoring_metric", m));
        if (breached) {
            ObjectNode payload = ((ObjectNode) row).deepCopy();
            payload.put("ml_model_id", d.get("ml_model_id").asString());
            payload.put("environment", d.get("environment").asString());
            payload.put("mode", d.get("mode").asString());
            outbox.publish("reporting.model_metric.breached", "model_monitoring_metric",
                    UUID.fromString(row.get("id").asString()), payload);
        }
        return row;
    }

    /** PSI per feature from expected / actual bin distributions. */
    @Transactional
    public ObjectNode drift(UUID id, JsonNode req) {
        JsonNode d = store.require("model_deployment", id);
        JsonNode features = req.get("features");
        if (features == null || !features.isArray() || features.isEmpty() || features.size() > 200) {
            throw Fields.invalid("features", "required", "features must list 1-200 {name, expected[], actual[]}");
        }
        BigDecimal threshold = Fields.decimal(req, "threshold");
        String ws = Fields.requireText(req, "window_start");
        String we = Fields.requireText(req, "window_end");
        ObjectNode out = json.createObjectNode();
        out.put("model_deployment_id", id.toString());
        ArrayNode results = out.putArray("features");
        BigDecimal max = BigDecimal.ZERO;
        int breaches = 0;
        for (int i = 0; i < features.size(); i++) {
            JsonNode f = features.get(i);
            String name = f.path("name").asString("");
            if (name.isBlank() || name.length() > 128) {
                throw Fields.invalid("features[" + i + "].name", "required", "each feature needs a name (max 128)");
            }
            BigDecimal psi;
            try {
                psi = Drift.psi(bins(f.get("expected")), bins(f.get("actual")));
            } catch (IllegalArgumentException e) {
                throw Fields.invalid("features[" + i + "]", "invalid_distribution", name + ": " + e.getMessage());
            }
            JsonNode metric = insertMetric(d, "PSI", name, psi, threshold, ws, we);
            boolean breached = metric.get("breached").asBoolean();
            breaches += breached ? 1 : 0;
            max = max.max(psi);
            ObjectNode r = results.addObject();
            r.put("feature", name);
            r.put("psi", psi.toPlainString());
            r.put("stability", Drift.stability(psi));
            r.put("breached", breached);
            r.put("metric_id", metric.get("id").asString());
        }
        out.put("max_psi", max.toPlainString());
        out.put("breached_count", breaches);
        out.put("stability", Drift.stability(max));
        return out;
    }

    private static List<BigDecimal> bins(JsonNode arr) {
        List<BigDecimal> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("expected and actual must be lists of numbers");
        }
        for (JsonNode n : arr) {
            try {
                out.add(new BigDecimal(n.asString()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bins must be numbers");
            }
        }
        return out;
    }

    /** Scheduled job body: monitoring of every ACTIVE deployment for the business date. */
    public void monitor(LocalDate businessDate, Map<String, Integer> counts, List<String> errors) {
        List<JsonNode> deployments = store.rows("select row_to_json(t)::text from model_deployment t where t.status = 'ACTIVE' order by t.id");
        int breachedDeployments = 0;
        int stale = 0;
        int rolledBack = 0;
        for (JsonNode d : deployments) {
            UUID id = UUID.fromString(d.get("id").asString());
            long breaches = store.count("""
                    select count(*) from model_monitoring_metric where model_deployment_id = ? and breached
                      and (window_end at time zone 'UTC')::date = ?""", id, businessDate);
            long recent = store.count("""
                    select count(*) from model_monitoring_metric where model_deployment_id = ?
                      and (window_end at time zone 'UTC')::date > ?""", id, businessDate.minusDays(STALE_DAYS));
            if (recent == 0) {
                stale++;
            }
            if (breaches == 0) {
                continue;
            }
            breachedDeployments++;
            String mode = d.get("mode").asString();
            if (d.path("auto_rollback").asBoolean(false) && ("CANARY".equals(mode) || "CHALLENGER".equals(mode))) {
                try {
                    tx.executeWithoutResult(s -> rollbackLocked(store.requireLocked("model_deployment", id),
                            "Auto-rollback: " + breaches + " threshold breach(es) on " + businessDate));
                    rolledBack++;
                } catch (RuntimeException e) {
                    errors.add("deployment " + id + ": " + e.getMessage());
                }
            }
        }
        int reviews = tx.execute(s -> store.update("""
                update ml_model set status = 'UNDER_REVIEW', updated_by = ? where status = 'ACTIVE' and next_review_date <= ?""",
                TenantContext.principal(), businessDate));
        counts.put("active_deployments", deployments.size());
        counts.put("breached_deployments", breachedDeployments);
        counts.put("stale_deployments", stale);
        counts.put("auto_rolled_back", rolledBack);
        counts.put("models_due_for_review", reviews);
    }
}
