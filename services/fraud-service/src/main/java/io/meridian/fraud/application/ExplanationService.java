package io.meridian.fraud.application;

import io.meridian.fraud.domain.DecisionEngine;
import io.meridian.fraud.domain.model.FraudModel;
import io.meridian.fraud.infrastructure.Db;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain-language explanation of a stored evaluation, reproduced from its recorded features. */
@Service
public class ExplanationService {
    private final Db db;
    private final ModelRegistry models;
    private final FraudConfigCache cache;
    private final JsonMapper json;

    public ExplanationService(Db db, ModelRegistry models, FraudConfigCache cache, JsonMapper json) {
        this.db = db;
        this.models = models;
        this.cache = cache;
        this.json = json;
    }

    public Map<String, Object> explain(UUID scoreId) {
        JsonNode s = db.require("fraud_score", scoreId, false);
        UUID modelId = Db.uuid(s, "model_id");
        FraudModel model = modelId == null ? models.champion(null) : models.byId(modelId);
        if (model == null) {
            model = models.champion(null);
        }
        Map<String, Double> engineered = new LinkedHashMap<>();
        s.path("features").path("engineered").properties().forEach(e -> engineered.put(e.getKey(), e.getValue().asDouble()));
        FraudModel.Output out = model.score(engineered);
        Map<String, String> descriptions = new HashMap<>();
        List<Map<String, Object>> contributions = new ArrayList<>();
        for (FraudModel.Contribution c : out.contributions()) {
            descriptions.put(c.reasonCode(), c.description());
            if (c.value() == 0.0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feature", c.feature());
            m.put("value", c.value());
            m.put("weight", c.weight());
            m.put("contribution", c.contribution());
            m.put("direction", c.contribution() >= 0 ? "INCREASES_RISK" : "DECREASES_RISK");
            m.put("description", c.description());
            contributions.add(m);
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        Map<String, String> ruleNames = new HashMap<>();
        for (JsonNode h : db.list("""
                select json_build_object('rule_id', h.fraud_rule_id, 'code', r.code, 'name', r.name,
                       'description', r.description, 'action', h.action, 'score_adjustment', h.score_adjustment,
                       'shadow', h.shadow, 'rule_version', h.rule_version, 'matched_values', h.matched_values)::text
                from fraud_rule_hit h join fraud_rule r on r.id = h.fraud_rule_id
                where h.fraud_score_id = ? order by h.id""", scoreId)) {
            ruleNames.put(h.get("code").asString(), h.get("name").asString());
            hits.add(json.convertValue(h, LinkedHashMap.class));
        }
        List<Map<String, Object>> reasons = new ArrayList<>();
        s.path("reason_codes").forEach(r -> {
            String code = r.asString();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("description", describe(code, descriptions, ruleNames));
            reasons.add(m);
        });
        DecisionEngine.Policy policy = cache.current().policy(s.get("subject_type").asString());
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("step_up", policy.stepUpThreshold());
        thresholds.put("review", policy.reviewThreshold());
        thresholds.put("block", policy.blockThreshold());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fraud_score_id", scoreId.toString());
        result.put("decision", s.get("decision").asString());
        result.put("score", s.get("score").asInt());
        result.put("model_score", Db.integer(s, "model_score"));
        result.put("rule_score", Db.integer(s, "rule_score"));
        result.put("model", model.name() + "@" + model.version());
        result.put("model_probability", out.probability());
        result.put("current_thresholds", thresholds);
        result.put("reasons", reasons);
        result.put("feature_contributions", contributions);
        result.put("rule_hits", hits);
        JsonNode ml = s.path("features").path("ml");
        if (ml.isObject()) {
            // governed ML model: contributions as recorded at scoring time (logistic: coefficient x value; trees: Saabas path)
            ml.path("contributions").forEach(c -> descriptions.put(c.path("reason_code").asString(), c.path("description").asString()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ml_model_id", Db.str(ml, "ml_model_id"));
            m.put("ml_model_version_id", Db.str(ml, "ml_model_version_id"));
            m.put("deployment_id", Db.str(ml, "deployment_id"));
            m.put("model_version", Db.str(ml, "model_version"));
            m.put("role", Db.str(ml, "role"));
            m.put("algorithm", Db.str(ml, "algorithm"));
            m.put("score", Db.integer(ml, "score"));
            m.put("probability", ml.hasNonNull("probability") ? ml.get("probability").asDouble() : null);
            m.put("bias", ml.hasNonNull("bias") ? ml.get("bias").asDouble() : null);
            m.put("combination", Db.str(ml, "combination"));
            m.put("degraded_reason", Db.str(ml, "degraded_reason"));
            List<Map<String, Object>> mlContributions = new ArrayList<>();
            ml.path("contributions").forEach(c -> {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("feature", c.path("feature").asString());
                cm.put("value", c.hasNonNull("value") ? c.get("value").asDouble() : null);
                cm.put("contribution", c.path("contribution").asDouble());
                cm.put("direction", c.path("contribution").asDouble() >= 0 ? "INCREASES_RISK" : "DECREASES_RISK");
                cm.put("reason_code", c.path("reason_code").asString());
                cm.put("description", c.path("description").asString());
                mlContributions.add(cm);
            });
            m.put("contributions", mlContributions);
            m.put("rules_component_score", Db.integer(s, "rules_component_score"));
            result.put("ml", m);
            if (ml.hasNonNull("score")) {
                result.put("model", "ml:" + Db.str(ml, "ml_model_id") + "@" + Db.str(ml, "model_version"));
                result.put("model_probability", ml.path("probability").asDouble());
            }
            result.put("degraded", Db.bool(s, "degraded"));
            reasons.forEach(r -> r.put("description", describe((String) r.get("code"), descriptions, ruleNames)));
        }
        result.put("summary", summary(s, reasons));
        return result;
    }

    static String describe(String code, Map<String, String> model, Map<String, String> rules) {
        if (code.equals("DEVICE_BLOCKED")) {
            return "The device used is blocked";
        }
        if (code.startsWith("BLOCK_LIST:")) {
            return "A value of the event is on block list " + code.substring(11);
        }
        if (code.startsWith("RULE:")) {
            String rule = code.substring(5);
            return "Rule " + rule + (rules.containsKey(rule) ? " (" + rules.get(rule) + ")" : "") + " fired";
        }
        if (code.startsWith("RISK_SCORE_")) {
            return "The overall risk score reached the " + code.substring(11).toLowerCase(java.util.Locale.ROOT) + " threshold";
        }
        return model.getOrDefault(code, code);
    }

    private static String summary(JsonNode s, List<Map<String, Object>> reasons) {
        StringBuilder sb = new StringBuilder();
        sb.append("Decision ").append(s.get("decision").asString()).append(" with risk score ")
                .append(s.get("score").asInt()).append("/1000");
        if (!reasons.isEmpty()) {
            sb.append(" because: ");
            for (int i = 0; i < reasons.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(String.valueOf(reasons.get(i).get("description")).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return sb.append('.').toString();
    }
}
