package io.meridian.fraud.domain;

import io.meridian.fraud.domain.expr.RuleExpression;
import io.meridian.fraud.domain.model.FraudModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Combines built-in hard controls, the rules (in priority order) and the model score into a decision.
 *
 * <ul>
 *   <li>Hard controls: a blocked device or a value on an active BLOCK list blocks.</li>
 *   <li>Rules: SHADOW rules only record hits. Active rules add their score adjustment; REVIEW / STEP_UP set a floor
 *       on the decision; ALLOW and BLOCK are terminal and short-circuit the remaining rules.</li>
 *   <li>Score = model score + rule adjustments, clamped to 0..1000, mapped to a decision by the policy thresholds;
 *       the final decision is the stricter of the threshold decision and the rule floor.</li>
 * </ul>
 */
public final class DecisionEngine {
    private DecisionEngine() {}

    public enum Decision {
        ALLOW, STEP_UP, REVIEW, BLOCK;

        Decision max(Decision o) {
            return o.ordinal() > ordinal() ? o : this;
        }
    }

    public record Policy(int stepUpThreshold, int reviewThreshold, int blockThreshold, UUID modelId, UUID shadowModelId,
                         int timeoutMs, Decision fallback) {
        public static Policy defaults() {
            return new Policy(450, 650, 850, null, null, 50, Decision.ALLOW);
        }

        public Decision forScore(int score) {
            if (score >= blockThreshold) {
                return Decision.BLOCK;
            }
            if (score >= reviewThreshold) {
                return Decision.REVIEW;
            }
            if (score >= stepUpThreshold) {
                return Decision.STEP_UP;
            }
            return Decision.ALLOW;
        }
    }

    public record Rule(UUID id, String code, int version, String action, int scoreAdjustment, int priority,
                       boolean shadow, RuleExpression expression) {}

    public record Hit(Rule rule, boolean shadow, Map<String, Object> matchedValues) {}

    public record Outcome(int score, int modelScore, int ruleScore, Decision decision, Decision shadowDecision,
                          List<String> reasonCodes, List<Hit> hits, List<String> ruleErrors) {}

    /**
     * @param rules       rules for the subject type, sorted by priority
     * @param facts       evaluation facts (also the rule variables)
     * @param listHits    codes of the active lists that contain a value of this event
     * @param blockHits   subset of listHits that are BLOCK lists
     * @param model       champion model output
     * @param shadowModel challenger output, or null
     */
    public static Outcome decide(List<Rule> rules, Map<String, Object> facts, Set<String> listHits, Set<String> blockHits,
                                 FraudModel.Output model, FraudModel.Output shadowModel, Policy policy) {
        List<String> reasons = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Decision terminal = null;
        Map<String, Object> device = FeatureEngineering.map(facts.get("device"));
        if (Boolean.TRUE.equals(device.get("blocked"))) {
            terminal = Decision.BLOCK;
            reasons.add("DEVICE_BLOCKED");
        }
        for (String code : blockHits) {
            terminal = Decision.BLOCK;
            reasons.add("BLOCK_LIST:" + code);
        }
        RuleExpression.Bindings bindings = RuleExpression.Bindings.of(facts, listHits::contains);
        int ruleScore = 0;
        Decision floor = Decision.ALLOW;
        for (Rule r : rules) {
            boolean fired;
            try {
                fired = r.expression().test(bindings);
            } catch (RuntimeException e) {
                errors.add(r.code() + ": " + e.getMessage());
                continue;
            }
            if (!fired) {
                continue;
            }
            hits.add(new Hit(r, r.shadow(), matched(r.expression(), bindings)));
            if (r.shadow()) {
                continue;
            }
            ruleScore += r.scoreAdjustment();
            if (!"TAG".equals(r.action()) && !"SCORE_ADJUST".equals(r.action())) {
                reasons.add("RULE:" + r.code());
            }
            switch (r.action()) {
                case "REVIEW" -> floor = floor.max(Decision.REVIEW);
                case "STEP_UP" -> floor = floor.max(Decision.STEP_UP);
                case "SCORE_ADJUST" -> {
                    if (r.scoreAdjustment() != 0) {
                        reasons.add("RULE:" + r.code());
                    }
                }
                case "ALLOW" -> {
                    if (terminal == null) {
                        terminal = Decision.ALLOW;
                    }
                }
                case "BLOCK" -> terminal = Decision.BLOCK;
                default -> { }
            }
            if ("ALLOW".equals(r.action()) || "BLOCK".equals(r.action())) {
                break; // terminal actions short-circuit the remaining rules
            }
        }
        int score = clamp(model.score() + ruleScore);
        Decision byScore = policy.forScore(score);
        Decision decision = terminal != null ? terminal : byScore.max(floor);
        model.contributions().stream()
                .filter(c -> c.contribution() >= 0.5)
                .limit(3)
                .forEach(c -> reasons.add(c.reasonCode()));
        if (decision != Decision.ALLOW && reasons.isEmpty()) {
            reasons.add("RISK_SCORE_" + decision.name());
        }
        Decision shadow = null;
        if (shadowModel != null) {
            int shadowScore = clamp(shadowModel.score() + ruleScore);
            shadow = terminal != null ? terminal : policy.forScore(shadowScore).max(floor);
        }
        return new Outcome(score, model.score(), ruleScore, decision, shadow,
                List.copyOf(new LinkedHashSet<>(reasons)), List.copyOf(hits), List.copyOf(errors));
    }

    /** Values of the identifiers the rule read, so analysts see why it fired. */
    static Map<String, Object> matched(RuleExpression e, RuleExpression.Bindings b) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String id : e.identifiers()) {
            Object v = b.resolve(id);
            m.put(id, v instanceof Collection<?> c ? new ArrayList<>(c) : v);
        }
        for (String code : e.listCodes()) {
            m.put("on_list(" + code + ")", b.onList(code));
        }
        return m;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(1000, v));
    }
}
