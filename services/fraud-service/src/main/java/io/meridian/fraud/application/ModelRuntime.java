package io.meridian.fraud.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.domain.features.FeatureCatalog;
import io.meridian.fraud.domain.ml.ArtifactException;
import io.meridian.fraud.domain.ml.ModelArtifact;
import io.meridian.fraud.domain.ml.ModelSet;
import io.meridian.fraud.infrastructure.AiPlatformClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The governed fraud models of each tenant, loaded from ai-platform-service: every ACTIVE deployment (in
 * {@code meridian.fraud.ml.environment}) of a FRAUD model, with its version's artefact. Artefacts are verified
 * against the version's {@code artifact_sha256} and compiled once per (version, checksum).
 *
 * <p>The first evaluation of a tenant whose policy names a governed model loads synchronously; afterwards the
 * snapshot is refreshed in the background after {@code refresh-ttl-ms}, immediately on deployment / version events
 * (hot reload) and on {@code POST /model-scores/reload}. A failed load is remembered for {@code failure-backoff-ms}
 * (evaluations degrade to rules meanwhile) and keeps the previous good snapshot when there is one.
 */
@Component
public class ModelRuntime {
    private static final Logger log = LoggerFactory.getLogger(ModelRuntime.class);
    static final int MAX_TENANTS = 2000;
    static final int MAX_COMPILED = 256;

    /** Models of one tenant. {@code error} is set when nothing could be loaded. */
    public record TenantModels(Instant loadedAt, long loadedNanos, Map<UUID, ModelSet> sets, String error,
                               String errorDetail) {}

    private final AiPlatformClient ai;
    private final JsonMapper json;
    private final String environment;
    private final long ttlNanos;
    private final long backoffNanos;
    private final Map<UUID, TenantModels> tenants = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Map<String, ModelArtifact> compiled = new ConcurrentHashMap<>();

    public ModelRuntime(AiPlatformClient ai, JsonMapper json,
                        @Value("${meridian.fraud.ml.environment:PRODUCTION}") String environment,
                        @Value("${meridian.fraud.ml.refresh-ttl-ms:300000}") long ttlMs,
                        @Value("${meridian.fraud.ml.failure-backoff-ms:30000}") long backoffMs) {
        this.ai = ai;
        this.json = json;
        this.environment = environment;
        this.ttlNanos = ttlMs * 1_000_000L;
        this.backoffNanos = backoffMs * 1_000_000L;
    }

    /** Current models of the bound tenant (loads on first use; stale snapshots refresh in the background). */
    public TenantModels current() {
        UUID tenant = TenantContext.require();
        TenantModels m = tenants.get(tenant);
        if (m == null) {
            return reload();
        }
        long age = System.nanoTime() - m.loadedNanos();
        if (m.error() != null ? age > backoffNanos : age > ttlNanos) {
            refreshAsync(tenant);
        }
        return m;
    }

    /** Loads the bound tenant's deployments now. */
    public TenantModels reload() {
        UUID tenant = TenantContext.require();
        ReentrantLock lock = locks.computeIfAbsent(tenant, t -> new ReentrantLock());
        lock.lock();
        try {
            TenantModels previous = tenants.get(tenant);
            TenantModels loaded = load();
            if (loaded.error() != null && previous != null && previous.error() == null) {
                // keep serving the last good snapshot, retry after the backoff
                loaded = new TenantModels(previous.loadedAt(), System.nanoTime() - ttlNanos + backoffNanos, previous.sets(),
                        null, null);
                log.warn("Reloading fraud models failed; keeping the snapshot of {}", previous.loadedAt());
            }
            if (tenants.size() >= MAX_TENANTS && !tenants.containsKey(tenant)) {
                tenants.keySet().stream().findFirst().ifPresent(tenants::remove);
            }
            tenants.put(tenant, loaded);
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    /** Background reload of a tenant that is already loaded (deployment events, stale snapshot). */
    public void refreshAsync(UUID tenant) {
        if (!tenants.containsKey(tenant)) {
            return;
        }
        dirty.add(tenant); // an event during a running reload triggers one more
        if (!refreshing.add(tenant)) {
            return;
        }
        Thread.ofVirtual().name("fraud-model-reload").start(() -> {
            try {
                while (dirty.remove(tenant)) {
                    TenantContext.callAs(tenant, "svc:fraud-model-runtime", this::reload);
                }
            } catch (Exception e) {
                log.warn("Background reload of fraud models failed: {}", e.toString());
            } finally {
                refreshing.remove(tenant);
                if (dirty.contains(tenant)) {
                    refreshAsync(tenant);
                }
            }
        });
    }

    public boolean loaded(UUID tenant) {
        return tenants.containsKey(tenant);
    }

    TenantModels load() {
        List<JsonNode> models;
        List<JsonNode> deployments;
        try {
            models = ai.fraudModels();
            deployments = ai.activeDeployments(environment);
        } catch (RuntimeException e) {
            log.warn("ai-platform-service unavailable for fraud models: {}", e.toString());
            return new TenantModels(Instant.now(), System.nanoTime(), Map.of(), "MODEL_UNAVAILABLE", e.getMessage());
        }
        Map<UUID, String> codes = new LinkedHashMap<>();
        for (JsonNode m : models) {
            if (m.hasNonNull("id") && !"RETIRED".equals(m.path("status").asString(""))) {
                codes.put(UUID.fromString(m.get("id").asString()), m.path("code").asString(""));
            }
        }
        Map<UUID, List<ModelSet.Deployment>> byModel = new LinkedHashMap<>();
        for (JsonNode d : deployments) {
            UUID modelId = d.hasNonNull("ml_model_id") ? UUID.fromString(d.get("ml_model_id").asString()) : null;
            if (modelId == null || !codes.containsKey(modelId) || !"ACTIVE".equals(d.path("status").asString(""))) {
                continue;
            }
            ModelSet.Role role;
            try {
                role = ModelSet.Role.valueOf(d.path("mode").asString(""));
            } catch (IllegalArgumentException e) {
                continue;
            }
            byModel.computeIfAbsent(modelId, k -> new ArrayList<>()).add(deployment(d, role));
        }
        Map<UUID, ModelSet> sets = new LinkedHashMap<>();
        byModel.forEach((id, ds) -> sets.put(id, new ModelSet(id, codes.get(id), ds)));
        return new TenantModels(Instant.now(), System.nanoTime(), Collections.unmodifiableMap(sets), null, null);
    }

    private ModelSet.Deployment deployment(JsonNode d, ModelSet.Role role) {
        UUID deploymentId = UUID.fromString(d.get("id").asString());
        UUID versionId = UUID.fromString(d.get("ml_model_version_id").asString());
        int traffic = d.path("traffic_percent").asInt(0);
        String label = null;
        String expected = null;
        try {
            JsonNode v = ai.version(versionId);
            label = v.path("model_version").asString(null);
            expected = v.path("artifact_sha256").asString(null);
            String key = versionId + ":" + expected;
            ModelArtifact artifact = compiled.get(key);
            if (artifact == null) {
                JsonNode a = ai.artifact(versionId);
                String content = a.path("content").asString(null);
                if (content == null) {
                    return failed(deploymentId, versionId, label, role, traffic, expected, "MODEL_UNAVAILABLE", "artifact_missing");
                }
                String actual = ModelArtifact.sha256(content);
                if (expected == null || !expected.equals(actual)) {
                    log.error("Artefact of model version {} fails its checksum (expected {}, got {})", versionId, expected, actual);
                    return failed(deploymentId, versionId, label, role, traffic, expected, "CHECKSUM_MISMATCH",
                            "sha256 " + actual + " != artifact_sha256 " + expected);
                }
                artifact = ModelArtifact.parse(content, json, FeatureCatalog.names());
                List<String> declared = new ArrayList<>();
                v.path("features").forEach(f -> declared.add(f.asString()));
                if (!declared.isEmpty() && !declared.equals(artifact.features())) {
                    return failed(deploymentId, versionId, label, role, traffic, expected, "INVALID_ARTIFACT",
                            "feature_list_mismatch: the version's features differ from the artefact's");
                }
                if (compiled.size() >= MAX_COMPILED) {
                    compiled.clear();
                }
                compiled.put(key, artifact);
            }
            return new ModelSet.Deployment(deploymentId, versionId, label, role, traffic, expected, artifact, null, null);
        } catch (ArtifactException e) {
            return failed(deploymentId, versionId, label, role, traffic, expected, "INVALID_ARTIFACT", e.code() + ": " + e.getMessage());
        } catch (ApiException e) {
            return failed(deploymentId, versionId, label, role, traffic, expected, "MODEL_UNAVAILABLE", e.code() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return failed(deploymentId, versionId, label, role, traffic, expected, "MODEL_UNAVAILABLE", e.toString());
        }
    }

    private static ModelSet.Deployment failed(UUID deploymentId, UUID versionId, String label, ModelSet.Role role, int traffic,
                                              String sha, String error, String detail) {
        return new ModelSet.Deployment(deploymentId, versionId, label, role, traffic, sha, null, error, detail);
    }

    /** Runtime view for operators. */
    public Map<String, Object> status(TenantModels m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("environment", environment);
        out.put("loaded_at", m.loadedAt().toString());
        out.put("error", m.error());
        out.put("error_detail", m.errorDetail());
        List<Map<String, Object>> sets = new ArrayList<>();
        m.sets().forEach((id, s) -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("ml_model_id", id.toString());
            sm.put("model_code", s.modelCode());
            List<Map<String, Object>> ds = new ArrayList<>();
            for (ModelSet.Deployment d : s.deployments()) {
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("deployment_id", d.deploymentId().toString());
                dm.put("ml_model_version_id", d.versionId().toString());
                dm.put("model_version", d.version());
                dm.put("role", d.role().name());
                dm.put("traffic_percent", d.trafficPercent());
                dm.put("artifact_sha256", d.sha256());
                dm.put("loaded", d.usable());
                dm.put("algorithm", d.artifact() == null ? null : d.artifact().algorithm());
                dm.put("features", d.artifact() == null ? null : d.artifact().features().size());
                dm.put("error", d.error());
                dm.put("error_detail", d.errorDetail());
                ds.add(dm);
            }
            sm.put("deployments", ds);
            sets.add(sm);
        });
        out.put("models", sets);
        return out;
    }
}
