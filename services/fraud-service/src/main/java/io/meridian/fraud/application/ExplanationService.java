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
