package io.meridian.fraud.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.domain.features.FeatureCatalog;
import io.meridian.fraud.domain.ml.ModelArtifact;
import io.meridian.fraud.domain.ml.ModelSet;
import io.meridian.fraud.infrastructure.AiPlatformClient;
import io.meridian.fraud.infrastructure.Db;
import io.meridian.fraud.infrastructure.FeatureStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operations around the governed models: feature catalogue registration, monitoring feeds to ai-platform (score and
 * feature distributions for PSI, latency, error rate, label-based precision / recall / AUC) and the labelled training
 * set for offline retraining.
 */
@Service
public class MlOperationsService {
    static final int MONITOR_MAX_ROWS = 20_000;
    static final int TRAINING_MAX_ROWS = 10_000;

    private final AiPlatformClient ai;
    private final ModelRuntime runtime;
    private final FeatureStore store;
    private final Db db;
    private final JsonMapper json;

    public MlOperationsService(AiPlatformClient ai, ModelRuntime runtime, FeatureStore store, Db db, JsonMapper json) {
        this.ai = ai;
        this.runtime = runtime;
        this.store = store;
        this.db = db;
        this.json = json;
    }

    // ------------------------------------------------------------------ feature catalogue

    /** Creates missing catalogue entries and updates changed ones (idempotent: unchanged entries are left alone). */
    public Map<String, Object> registerFeatures() {
        TenantContext.require();
        Map<String, JsonNode> existing = new HashMap<>();
        for (JsonNode f : ai.features(FeatureCatalog.FEATURE_SET)) {
            existing.put(f.path("name").asString(), f);
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<String> errors = new ArrayList<>();
        for (FeatureCatalog.Def d : FeatureCatalog.all()) {
            Map<String, Object> body = body(d);
            JsonNode current = existing.get(d.name());
            try {
                if (current == null) {
                    ai.createFeature(body, "fraud-feature:" + d.name() + ":" + fingerprint(body));
                    created++;
                } else if (differs(current, body)) {
                    Map<String, Object> patch = new LinkedHashMap<>(body);
                    patch.remove("name");
                    ai.updateFeature(UUID.fromString(current.get("id").asString()), patch, current.path("row_version").asInt(1));
                    updated++;
                } else {
                    unchanged++;
                }
            } catch (ApiException e) {
                errors.add(d.name() + ": " + e.code() + " " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feature_set", FeatureCatalog.FEATURE_SET);
        out.put("features", FeatureCatalog.all().size());
        out.put("created", created);
        out.put("updated", updated);
        out.put("unchanged", unchanged);
        out.put("errors", errors);
        return out;
    }

    static Map<String, Object> body(FeatureCatalog.Def d) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", d.name());
        b.put("entity", d.entity());
        b.put("dtype", d.dtype());
        b.put("description", d.description());
        Object src = d.definition().get("source_events");
        b.put("source_dataset", src == null ? "fraud-service:evaluation"
                : String.valueOf(src).contains("risk.fraud_score") ? "kafka:meridian.risk.v1"
                : "kafka:meridian.payments.v1,meridian.cards.v1,meridian.wallets.v1");
        b.put("online", true);
        b.put("freshness_sla_seconds", src == null ? 0 : 60);
        b.put("owner", "fraud-service");
        b.put("contains_pii", d.pii());
        b.put("feature_set", FeatureCatalog.FEATURE_SET);
        b.put("definition", d.definition());
        return b;
    }

    private boolean differs(JsonNode current, Map<String, Object> body) {
        JsonNode want = json.valueToTree(body);
        for (String k : List.of("entity", "dtype", "description", "source_dataset", "freshness_sla_seconds", "feature_set",
                "definition")) {
            if (!Objects.equals(current.get(k), want.get(k))) {
                return true;
            }
        }
        return false;
    }

    private String fingerprint(Map<String, Object> body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ monitoring

    /**
     * Pushes the window's monitoring of every deployment that scored in it: PSI of the score and of each feature with
     * a reference distribution in the artefact, LATENCY_P99 (ms), ERROR_RATE and, where outcome labels exist,
     * PRECISION / RECALL (positive = would REVIEW or BLOCK) and AUC. Idempotent per deployment, metric and window.
     */
    public Map<String, Object> publishMonitoring(OffsetDateTime from, OffsetDateTime to) {
        TenantContext.require();
        if (!to.isAfter(from)) {
            throw ApiException.unprocessable("invalid_range", "window_end must be after window_start");
        }
        List<JsonNode> rows = db.list("""
                select json_build_object('deployment', m.model_deployment_id, 'model', m.ml_model_id, 'score', m.score,
                       'would', m.would_decide, 'latency_us', m.latency_us, 'error', m.error, 'x', m.feature_vector,
                       'label', (select fb.label from fraud_feedback fb where fb.fraud_score_id = m.fraud_score_id
                                 and fb.label in ('FRAUD', 'NOT_FRAUD') order by fb.reported_at desc, fb.id desc limit 1))::text
                from fraud_model_score m
                where m.created_at >= ? and m.created_at < ? and m.model_deployment_id is not null
                order by m.created_at limit ?""", from, to, MONITOR_MAX_ROWS);
        Map<String, List<JsonNode>> byDeployment = new LinkedHashMap<>();
        rows.forEach(r -> byDeployment.computeIfAbsent(r.get("deployment").asString(), k -> new ArrayList<>()).add(r));
        Map<UUID, ModelSet.Deployment> loaded = new HashMap<>();
        runtime.current().sets().values().forEach(s -> s.deployments().forEach(d -> loaded.put(d.deploymentId(), d)));
        String ws = from.toString();
        String we = to.toString();
        String window = from.toEpochSecond() + "-" + to.toEpochSecond();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> e : byDeployment.entrySet()) {
            UUID deploymentId = UUID.fromString(e.getKey());
            List<JsonNode> rs = e.getValue();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("deployment_id", deploymentId.toString());
            r.put("scores", rs.size());
            List<String> errors = new ArrayList<>();
            Map<String, Double> metrics = new LinkedHashMap<>();
            long[] lat = rs.stream().mapToLong(x -> x.path("latency_us").asLong()).sorted().toArray();
            metrics.put("LATENCY_P99", lat.length == 0 ? 0.0 : lat[Math.max(0, (int) Math.ceil(lat.length * 0.99) - 1)] / 1000.0);
            metrics.put("ERROR_RATE", rs.stream().filter(x -> x.hasNonNull("error")).count() / (double) rs.size());
            labelMetrics(rs, metrics);
            for (Map.Entry<String, Double> m : metrics.entrySet()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model_deployment_id", deploymentId.toString());
                body.put("metric_name", m.getKey());
                body.put("metric_value", String.format(java.util.Locale.ROOT, "%.6f", m.getValue()));
                body.put("window_start", ws);
                body.put("window_end", we);
                try {
                    ai.metric(body, "fraud-monitor:" + deploymentId + ":" + m.getKey() + ":" + window);
                } catch (ApiException ex) {
                    errors.add(m.getKey() + ": " + ex.code());
                }
            }
            r.put("metrics", metrics);
            ModelSet.Deployment d = loaded.get(deploymentId);
            List<Map<String, Object>> psi = d == null || d.artifact() == null ? List.of() : distributions(d.artifact(), rs);
            r.put("drift_features", psi.stream().map(p -> p.get("name")).toList());
            if (!psi.isEmpty()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("features", psi);
                body.put("window_start", ws);
                body.put("window_end", we);
                try {
                    JsonNode drift = ai.drift(deploymentId, body, "fraud-drift:" + deploymentId + ":" + window);
                    r.put("max_psi", drift == null ? null : Db.str(drift, "max_psi"));
                    r.put("stability", drift == null ? null : Db.str(drift, "stability"));
                } catch (ApiException ex) {
                    errors.add("PSI: " + ex.code());
                }
            } else {
                r.put("drift_skipped", d == null ? "deployment_not_loaded" : "no_reference_distributions");
            }
            r.put("errors", errors);
            results.add(r);
        }
        JsonNode degraded = db.one("""
                select json_build_object('governed', count(*) filter (where combination is not null),
                       'degraded', count(*) filter (where degraded))::text
                from fraud_score where created_at >= ? and created_at < ?""", from, to).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_start", ws);
        out.put("window_end", we);
        out.put("deployments", results);
        out.put("governed_evaluations", degraded.path("governed").asLong());
        out.put("degraded_evaluations", degraded.path("degraded").asLong());
        out.put("truncated", rows.size() >= MONITOR_MAX_ROWS);
        return out;
    }

    /** Actual histograms with the artefact's reference bins: "score" plus every feature with a reference. */
    static List<Map<String, Object>> distributions(ModelArtifact a, List<JsonNode> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, ModelArtifact.Distribution> e : a.distributions().entrySet()) {
            String name = e.getKey();
            ModelArtifact.Distribution ref = e.getValue();
            double[] actual = new double[ref.counts().length];
            int n = 0;
            for (JsonNode r : rows) {
                JsonNode v = "score".equals(name) ? r.get("score") : r.path("x").get(name);
                if (v != null && v.isNumber()) {
                    actual[ref.bin(v.asDouble())]++;
                    n++;
                }
            }
            if (n == 0) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", name);
            f.put("expected", Arrays.stream(ref.counts()).boxed().toList());
            f.put("actual", Arrays.stream(actual).boxed().toList());
            out.add(f);
        }
        return out;
    }

    /** Precision / recall of "would review or block" and the AUC of the score against outcome labels. */
    static void labelMetrics(List<JsonNode> rows, Map<String, Double> metrics) {
        List<double[]> labelled = new ArrayList<>(); // {score, fraud?1:0, predictedPositive?1:0}
        for (JsonNode r : rows) {
            if (r.hasNonNull("label") && r.hasNonNull("score")) {
                String w = r.path("would").asString("");
                labelled.add(new double[] {r.get("score").asDouble(), "FRAUD".equals(r.get("label").asString()) ? 1 : 0,
                        "REVIEW".equals(w) || "BLOCK".equals(w) ? 1 : 0});
            }
        }
        if (labelled.isEmpty()) {
            return;
        }
        long tp = labelled.stream().filter(l -> l[1] == 1 && l[2] == 1).count();
        long fp = labelled.stream().filter(l -> l[1] == 0 && l[2] == 1).count();
        long fn = labelled.stream().filter(l -> l[1] == 1 && l[2] == 0).count();
        if (tp + fp > 0) {
            metrics.put("PRECISION", tp / (double) (tp + fp));
        }
        if (tp + fn > 0) {
            metrics.put("RECALL", tp / (double) (tp + fn));
        }
        Double auc = auc(labelled);
        if (auc != null) {
            metrics.put("AUC", auc);
        }
    }

    /** Mann-Whitney AUC with average ranks for ties; null without both classes. */
    static Double auc(List<double[]> labelled) {
        long pos = labelled.stream().filter(l -> l[1] == 1).count();
        long neg = labelled.size() - pos;
        if (pos == 0 || neg == 0) {
            return null;
        }
        List<double[]> sorted = new ArrayList<>(labelled);
        sorted.sort((a, b) -> Double.compare(a[0], b[0]));
        double rankSum = 0;
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1)[0] == sorted.get(i)[0]) {
                j++;
            }
            double avgRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                if (sorted.get(k)[1] == 1) {
                    rankSum += avgRank;
                }
            }
            i = j + 1;
        }
        return (rankSum - pos * (pos + 1) / 2.0) / (pos * (double) neg);
    }

    // ------------------------------------------------------------------ training set

    /** Latest FRAUD / NOT_FRAUD label per evaluation of the period with the features recorded at scoring time. */
    public Map<String, Object> trainingSet(OffsetDateTime from, OffsetDateTime to, Integer limit) {
        TenantContext.require();
        if (!to.isAfter(from)) {
            throw ApiException.unprocessable("invalid_range", "to_ts must be after from_ts");
        }
        int max = limit == null ? 1000 : limit;
        if (max < 1 || max > TRAINING_MAX_ROWS) {
            throw ApiException.unprocessable("invalid_limit", "limit must be between 1 and " + TRAINING_MAX_ROWS);
        }
        List<JsonNode> rows = db.list("""
                select json_build_object('fraud_score_id', s.id, 'created_at', s.created_at, 'subject_type', s.subject_type,
                       'channel', s.channel, 'label', f.label, 'typology', f.typology, 'label_source', f.source,
                       'score', s.score, 'decision', s.decision, 'model_version', s.model_version,
                       'features', coalesce(s.features->'engineered', '{}'::jsonb) || coalesce(s.features->'store', '{}'::jsonb))::text
                from fraud_score s
                join lateral (select fb.label, fb.typology, fb.source from fraud_feedback fb where fb.fraud_score_id = s.id
                              and fb.label in ('FRAUD', 'NOT_FRAUD') order by fb.reported_at desc, fb.id desc limit 1) f on true
                where s.created_at >= ? and s.created_at < ?
                order by s.created_at, s.id limit ?""", from, to, max);
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.forEach(r -> counts.merge(r.get("label").asString(), 1L, Long::sum));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from_ts", from.toString());
        out.put("to_ts", to.toString());
        out.put("feature_set", FeatureCatalog.FEATURE_SET);
        out.put("label_counts", counts);
        out.put("count", rows.size());
        out.put("truncated", rows.size() >= max);
        out.put("rows", rows);
        return out;
    }

    public Map<String, Object> runtime() {
        Map<String, Object> out = runtime.status(runtime.current());
        out.put("feature_store", store.sizes());
        out.put("feature_set", FeatureCatalog.FEATURE_SET);
        out.put("catalogued_features", FeatureCatalog.all().size());
        return out;
    }

    public Map<String, Object> reload() {
        return runtime.status(runtime.reload());
    }
}
