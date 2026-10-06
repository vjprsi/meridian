package io.meridian.onboarding.domain;

import io.meridian.common.error.ApiException.FieldError;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Onboarding risk scoring and automated decision. Risk factors: failed KYC checks (+400), checks waiting for review
 * (+200), declared PEP (+300), assisted remote channels AGENT/USSD (+50). Levels: below 300 LOW, below 600 MEDIUM,
 * else HIGH. Decision rules {@code {"reject_if": [cond], "refer_if": [cond]}} run over the collected data plus the
 * facts risk_level, risk_score, failed_checks, inconclusive_checks and channel. Failed checks reject and checks
 * waiting for review refer by default; REJECT wins over REFER, REFER over AUTO_APPROVE.
 */
public final class DecisionEngine {
    private DecisionEngine() {}

    public record Risk(int score, String level, ObjectNode factors) {}

    public record Decision(String decision, List<String> reasons) {}

    public static Risk assess(int failedChecks, int waitingReview, JsonNode data, String channel) {
        ObjectNode f = JsonNodeFactory.instance.objectNode();
        int score = 0;
        if (failedChecks > 0) {
            f.put("failed_checks", 400);
            score += 400;
        }
        if (waitingReview > 0) {
            f.put("checks_waiting_review", 200);
            score += 200;
        }
        if (declaredPep(data)) {
            f.put("declared_pep", 300);
            score += 300;
        }
        if ("AGENT".equals(channel) || "USSD".equals(channel)) {
            f.put("assisted_channel", 50);
            score += 50;
        }
        score = Math.min(score, 1000);
        String level = score < 300 ? "LOW" : score < 600 ? "MEDIUM" : "HIGH";
        return new Risk(score, level, f);
    }

    public static Decision decide(JsonNode rules, ObjectNode facts, int failedChecks, int waitingReview) {
        List<String> reject = new ArrayList<>();
        List<String> refer = new ArrayList<>();
        if (failedChecks > 0) {
            reject.add("KYC_CHECK_FAILED");
        }
        if (waitingReview > 0) {
            refer.add("KYC_CHECK_INCONCLUSIVE");
        }
        if (rules != null && rules.isObject()) {
            int i = 0;
            for (JsonNode c : rules.path("reject_if")) {
                if (Conditions.eval(c, facts)) {
                    reject.add(c.path("reason").asString("REJECT_RULE_" + i));
                }
                i++;
            }
            i = 0;
            for (JsonNode c : rules.path("refer_if")) {
                if (Conditions.eval(c, facts)) {
                    refer.add(c.path("reason").asString("REFER_RULE_" + i));
                }
                i++;
            }
        }
        if (!reject.isEmpty()) {
            return new Decision("REJECT", reject);
        }
        if (!refer.isEmpty()) {
            return new Decision("REFER", refer);
        }
        return new Decision("AUTO_APPROVE", List.of("ALL_RULES_PASSED"));
    }

    public static List<FieldError> validateRules(JsonNode rules) {
        List<FieldError> errors = new ArrayList<>();
        if (rules == null || rules.isNull()) {
            return errors;
        }
        for (String k : List.of("reject_if", "refer_if")) {
            JsonNode list = rules.get(k);
            if (list == null || list.isNull()) {
                continue;
            }
            if (!list.isArray()) {
                errors.add(new FieldError("decision_rules." + k, "invalid_rules", k + " must be an array of conditions"));
                continue;
            }
            int i = 0;
            for (JsonNode c : list) {
                Conditions.validate(c, "decision_rules." + k + "[" + i++ + "]", errors);
            }
        }
        return errors;
    }

    private static boolean declaredPep(JsonNode data) {
        return data != null && (data.path("pep").asBoolean(false) || data.path("is_pep").asBoolean(false));
    }
}
