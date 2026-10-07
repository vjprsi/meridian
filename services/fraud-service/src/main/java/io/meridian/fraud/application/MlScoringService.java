package io.meridian.fraud.application;

import io.meridian.fraud.domain.DecisionEngine;
import io.meridian.fraud.domain.ml.MlExecution;
import io.meridian.fraud.domain.ml.ModelArtifact;
import io.meridian.fraud.domain.ml.ModelSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Governed ML scoring of an evaluation: resolves the policy's ai-platform model to its deployments and runs them
 * ({@link MlExecution}); builds the model-score log rows. A policy whose {@code model_id} is a built-in model (or
 * empty) has no governed model and never reaches ai-platform.
 */
@Service
public class MlScoringService {
    private static final Logger log = LoggerFactory.getLogger(MlScoringService.class);

    private final ModelRuntime runtime;
    private final ModelRegistry builtIn;
    private final JsonMapper json;

    public MlScoringService(ModelRuntime runtime, ModelRegistry builtIn, JsonMapper json) {
        this.runtime = runtime;
        this.builtIn = builtIn;
        this.json = json;
    }

    /** True when the policy names an ai-platform model rather than a built-in one. */
    public boolean governed(DecisionEngine.Policy policy) {
        return policy.modelId() != null && builtIn.byId(policy.modelId()) == null;
    }

    public MlExecution.Result score(DecisionEngine.Policy policy, UUID subjectId, Map<String, Double> features) {
        ModelSet set;
        try {
            ModelRuntime.TenantModels m = runtime.current();
            if (m.error() != null) {
                return new MlExecution.Result(null, m.error(), List.of());
            }
            set = m.sets().get(policy.modelId());
        } catch (RuntimeException e) {
            log.warn("fraud model runtime unavailable: {}", e.toString());
            return new MlExecution.Result(null, "MODEL_UNAVAILABLE", List.of());
        }
        long budget = Math.max(1, policy.modelTimeoutMs()) * 1_000_000L;
        MlExecution.Result r = MlExecution.execute(set, subjectId, features, budget, System::nanoTime);
        if (r.degradedReason() != null) {
            log.warn("fraud ML model {} degraded ({}); rules decide", policy.modelId(), r.degradedReason());
        }
        return r;
    }

    /** Input of the decision engine for the deciding run (null when degraded). */
    public static DecisionEngine.MlInput input(MlExecution.Result r, String channel) {
        if (r == null || r.deciding() == null) {
            return null;
        }
        MlExecution.Run d = r.deciding();
        ModelArtifact a = d.deployment().artifact();
        return new DecisionEngine.MlInput(d.score(), d.prediction().probability(), d.contributions(),
                a.reasonMinContribution(), a.thresholds(channel));
    }

    /** fraud_model_score rows of the runs; observers carry the decision they would have produced. */
    public ArrayNode rows(MlExecution.Result r, UUID modelId, UUID scoreId, String subjectType, String channel,
                          DecisionEngine.Outcome outcome, DecisionEngine.Policy policy) {
        ArrayNode rows = json.createArrayNode();
        if (r == null) {
            return rows;
        }
        for (MlExecution.Run run : r.runs()) {
            ModelSet.Deployment d = run.deployment();
            ObjectNode n = rows.addObject();
            n.put("fraud_score_id", scoreId.toString());
            n.put("subject_type", subjectType);
            if (channel != null) {
                n.put("channel", channel);
            }
            n.put("ml_model_id", modelId.toString());
            n.put("ml_model_version_id", d.versionId().toString());
            n.put("model_deployment_id", d.deploymentId().toString());
            if (d.version() != null) {
                n.put("model_version", d.version().length() > 32 ? d.version().substring(0, 32) : d.version());
            }
            n.put("role", run.role().name());
            n.put("decided", run.decided());
            n.put("latency_us", (int) Math.min(Integer.MAX_VALUE, run.latencyNanos() / 1000));
            if (run.prediction() != null) {
                n.put("score", run.score());
                n.put("probability", Math.round(run.prediction().probability() * 1e10) / 1e10);
                DecisionEngine.Decision would = run.decided() ? outcome.decision()
                        : DecisionEngine.wouldDecide(outcome, run.score(), run.prediction().probability(), policy, channel,
                                d.artifact().thresholds(channel));
                n.put("would_decide", would.name());
            }
            if (run.error() != null) {
                n.put("error", run.error());
            }
            if (run.vector() != null) {
                n.set("feature_vector", json.valueToTree(vector(d.artifact(), run.vector())));
            }
            n.set("top_contributions", json.valueToTree(top(run.contributions(), 5)));
        }
        return rows;
    }

    /** Explanation block stored with the score (features.ml). */
    public Map<String, Object> explanation(MlExecution.Result r, UUID modelId, DecisionEngine.Outcome o, String degraded) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ml_model_id", modelId.toString());
        m.put("combination", o.combination() == null ? null : o.combination().name());
        m.put("degraded_reason", degraded);
        if (r != null && r.deciding() != null) {
            MlExecution.Run d = r.deciding();
            m.put("deployment_id", d.deployment().deploymentId().toString());
            m.put("ml_model_version_id", d.deployment().versionId().toString());
            m.put("model_version", d.deployment().version());
            m.put("role", d.role().name());
            m.put("algorithm", d.deployment().artifact().algorithm());
            m.put("score", d.score());
            m.put("probability", FeatureService.round6(d.prediction().probability()));
            m.put("margin", FeatureService.round6(d.prediction().margin()));
            m.put("bias", FeatureService.round6(d.prediction().bias()));
            m.put("contributions", top(d.contributions(), 10));
        }
        List<Map<String, Object>> observers = new ArrayList<>();
        if (r != null) {
            for (MlExecution.Run run : r.runs()) {
                if (!run.decided()) {
                    Map<String, Object> om = new LinkedHashMap<>();
                    om.put("deployment_id", run.deployment().deploymentId().toString());
                    om.put("role", run.role().name());
                    om.put("score", run.score());
                    om.put("error", run.error());
                    observers.add(om);
                }
            }
        }
        m.put("observers", observers);
        return m;
    }

    static Map<String, Object> vector(ModelArtifact a, double[] x) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < x.length; i++) {
            m.put(a.features().get(i), Double.isNaN(x[i]) ? null : FeatureService.round6(x[i]));
        }
        return m;
    }

    static List<Map<String, Object>> top(List<ModelArtifact.Contribution> cs, int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelArtifact.Contribution c : cs) {
            if (out.size() >= n) {
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feature", c.feature());
            m.put("value", Double.isNaN(c.value()) ? null : FeatureService.round6(c.value()));
            m.put("contribution", FeatureService.round6(c.contribution()));
            m.put("reason_code", c.reasonCode());
            m.put("description", c.description());
            out.add(m);
        }
        return out;
    }
}
