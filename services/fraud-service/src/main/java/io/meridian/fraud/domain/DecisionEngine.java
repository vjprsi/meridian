package io.meridian.fraud.domain;

import io.meridian.fraud.domain.expr.RuleExpression;
import io.meridian.fraud.domain.ml.ModelArtifact;
import io.meridian.fraud.domain.ml.ScoreCombination;
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
 * Combines built-in hard controls, the rules (in priority order) and the model scores into a decision.
 *
 * <ul>
 *   <li>Hard controls: a blocked device or a value on an active BLOCK list blocks.</li>
 *   <li>Rules: SHADOW rules only record hits. Active rules add their score adjustment; REVIEW / STEP_UP set a floor
 *       on the decision; ALLOW and BLOCK are terminal and short-circuit the remaining rules.</li>
 *   <li>Rule-based score = built-in heuristic model score + rule adjustments, clamped to 0..1000. Without an ML model
 *       (or when it is degraded) this is the score. With a governed ML model the policy's {@link ScoreCombination}
 *       combines the ML score with it.</li>
 *   <li>The score maps to a decision by the thresholds (channel override, else the ML artefact's when the policy
 *       says so and the ML model decided, else the policy's); the final decision is the stricter of that and the rule
 *       floor; terminal rules and hard controls win.</li>
 * </ul>
 */
public final class DecisionEngine {
    private DecisionEngine() {}

    public enum Decision {
        ALLOW, STEP_UP, REVIEW, BLOCK;

        public Decision max(Decision o) {
            return o.ordinal() > ordinal() ? o : this;
        }
    }

    /** Score thresholds 0-1000. */
    public record Thresholds(int stepUp, int review, int block) {
        public Decision forScore(int score) {
            if (score >= block) {
                return Decision.BLOCK;
            }
            if (score >= review) {
                return Decision.REVIEW;
            }
            if (score >= stepUp) {
                return Decision.STEP_UP;
            }
            return Decision.ALLOW;
        }
    }

    public record Policy(int stepUpThreshold, int reviewThreshold, int blockThreshold, UUID modelId, UUID shadowModelId,
                         int timeoutMs, Decision fallback, ScoreCombination combination, double modelWeight,
                         double modelConfidence, int modelTimeoutMs, Map<String, Thresholds> channelThresholds,
                         boolean useModelThresholds) {
        public Policy(int stepUpThreshold, int reviewThreshold, int blockThreshold, UUID modelId, UUID shadowModelId,
                      int timeoutMs, Decision fallback) {
            this(stepUpThreshold, reviewThreshold, blockThreshold, modelId, shadowModelId, timeoutMs, fallback,
                    ScoreCombination.ADDITIVE, 0.5, 0.8, 10, Map.of(), false);
        }

        public static Policy defaults() {
            return new Policy(450, 650, 850, null, null, 50, Decision.ALLOW);
        }

        public Decision forScore(int score) {
            return base().forScore(score);
        }

        public Thresholds base() {
            return new Thresholds(stepUpThreshold, reviewThreshold, blockThreshold);
        }

        /** Channel override, else the model's thresholds (when allowed and the ML model decided), else the policy's. */
        public Thresholds thresholds(String channel, ModelArtifact.Thresholds model) {
            Thresholds t = channel == null ? null : channelThresholds.get(channel);
            if (t != null) {
                return t;
            }
            if (useModelThresholds && model != null) {
                return new Thresholds(model.stepUp(), model.review(), model.block());
            }
            return base();
        }
    }

    public record Rule(UUID id, String code, int version, String action, int scoreAdjustment, int priority,
                       boolean shadow, RuleExpression expression) {}

    public record Hit(Rule rule, boolean shadow, Map<String, Object> matchedValues) {}

    /**
     * Output of the governed ML model that decides (champion, or the canary of this slice).
     *
     * @param contributions sorted, largest first (margin units)
     * @param reasonMin     smallest contribution that becomes a reason code
     * @param thresholds    thresholds shipped in the artefact (or null)
     */
    public record MlInput(int score, double probability, List<ModelArtifact.Contribution> contributions, double reasonMin,
                          ModelArtifact.Thresholds thresholds) {}

    /**
     * @param rulesComponentScore heuristic model + rule adjustments, clamped (the score without ML)
     * @param combination         applied combination, null when no ML model took part
     */
    public record Outcome(int score, int modelScore, int ruleScore, Decision decision, Decision shadowDecision,
                          List<String> reasonCodes, List<Hit> hits, List<String> ruleErrors, int rulesComponentScore,
                          ScoreCombination combination, Thresholds thresholds, Decision floor, Decision terminal) {}

    public static Outcome decide(List<Rule> rules, Map<String, Object> facts, Set<String> listHits, Set<String> blockHits,
                                 FraudModel.Output model, FraudModel.Output shadowModel, Policy policy) {
        return decide(rules, facts, listHits, blockHits, model, shadowModel, policy, null, null);
    }

    /**
     * @param rules       rules for the subject type, sorted by priority
     * @param facts       evaluation facts (also the rule variables)
     * @param listHits    codes of the active lists that contain a value of this event
     * @param blockHits   subset of listHits that are BLOCK lists
     * @param model       built-in heuristic model output (part of the rule-based score)
     * @param shadowModel built-in challenger output, or null
     * @param channel     channel of the event (threshold overrides), or null
     * @param ml          governed ML model output, or null when the policy has none or it is degraded
     */
    public static Outcome decide(List<Rule> rules, Map<String, Object> facts, Set<String> listHits, Set<String> blockHits,
                                 FraudModel.Output model, FraudModel.Output shadowModel, Policy policy, String channel,
                                 MlInput ml) {
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
        int rulesScore = clamp(model.score() + ruleScore);
        ScoreCombination.Result combined = ml == null
                ? new ScoreCombination.Result(rulesScore, false, true)
                : policy.combination().combine(rulesScore, ruleScore, ml.score(), ml.probability(), policy.modelWeight(),
                        policy.modelConfidence());
        int score = combined.score();
        Thresholds thresholds = policy.thresholds(channel, combined.modelUsed() && ml != null ? ml.thresholds() : null);
        Decision byScore = thresholds.forScore(score);
        Decision decision = terminal != null ? terminal : byScore.max(floor);
        if (combined.modelUsed() && ml != null) {
            ml.contributions().stream()
                    .filter(c -> c.contribution() >= ml.reasonMin())
                    .limit(3)
                    .forEach(c -> reasons.add(c.reasonCode()));
        }
        if (combined.heuristicUsed()) {
            model.contributions().stream()
                    .filter(c -> c.contribution() >= 0.5)
                    .limit(3)
                    .forEach(c -> reasons.add(c.reasonCode()));
        }
        if (decision != Decision.ALLOW && reasons.isEmpty()) {
            reasons.add("RISK_SCORE_" + decision.name());
        }
        Decision shadow = null;
        if (shadowModel != null) {
            int shadowScore = clamp(shadowModel.score() + ruleScore);
            shadow = terminal != null ? terminal : policy.thresholds(channel, null).forScore(shadowScore).max(floor);
        }
        return new Outcome(score, ml != null && combined.modelUsed() ? ml.score() : model.score(), ruleScore, decision,
                shadow, List.copyOf(new LinkedHashSet<>(reasons)), List.copyOf(hits), List.copyOf(errors), rulesScore,
                ml == null ? null : policy.combination(), thresholds, floor, terminal);
    }

    /**
     * Decision a non-deciding (challenger / shadow) ML score would have produced with the same rules, floors and
     * hard controls; it is logged and never returned.
     */
    public static Decision wouldDecide(Outcome actual, int modelScore, double probability, Policy policy, String channel,
                                       ModelArtifact.Thresholds modelThresholds) {
        ScoreCombination.Result r = policy.combination().combine(actual.rulesComponentScore(), actual.ruleScore(),
                modelScore, probability, policy.modelWeight(), policy.modelConfidence());
        Decision byScore = policy.thresholds(channel, r.modelUsed() ? modelThresholds : null).forScore(r.score());
        return actual.terminal() != null ? actual.terminal() : byScore.max(actual.floor());
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

    public static int clamp(int v) {
        return Math.max(0, Math.min(1000, v));
    }
}
