package io.meridian.fraud.domain.ml;

/**
 * How a governed ML score {@code M} combines with the rule-based score {@code R} (built-in heuristic + rule
 * adjustments, clamped). {@code A} is the net rule adjustment. All results are clamped to 0..1000.
 *
 * <ul>
 *   <li>ADDITIVE: {@code M + A} (the ML model replaces the heuristic; rule adjustments still apply)</li>
 *   <li>MAX: {@code max(M, R)}</li>
 *   <li>WEIGHTED: {@code round(w * M + (1 - w) * R)}</li>
 *   <li>MODEL_ONLY_ABOVE_CONFIDENCE: {@code M} when {@code |2p - 1| >= confidence}, else {@code max(M, R)}</li>
 *   <li>RULES_ONLY: {@code R} (the ML score is only logged)</li>
 * </ul>
 * Rule floors, terminal rules and hard controls apply after the combination in every case.
 */
public enum ScoreCombination {
    ADDITIVE, MAX, WEIGHTED, MODEL_ONLY_ABOVE_CONFIDENCE, RULES_ONLY;

    /**
     * @param score         combined score
     * @param modelUsed     the ML score influenced the result (its reason codes and thresholds apply)
     * @param heuristicUsed the rule-based score (heuristic model) influenced the result
     */
    public record Result(int score, boolean modelUsed, boolean heuristicUsed) {}

    public Result combine(int rulesScore, int ruleAdjustment, int modelScore, double probability, double weight,
                          double confidence) {
        return switch (this) {
            case ADDITIVE -> new Result(clamp(modelScore + ruleAdjustment), true, false);
            case MAX -> new Result(Math.max(modelScore, rulesScore), modelScore >= rulesScore, rulesScore >= modelScore);
            case WEIGHTED -> {
                double w = Math.max(0.0, Math.min(1.0, weight));
                yield new Result(clamp((int) Math.round(w * modelScore + (1 - w) * rulesScore)), w > 0, w < 1);
            }
            case MODEL_ONLY_ABOVE_CONFIDENCE -> Math.abs(2 * probability - 1) >= confidence
                    ? new Result(modelScore, true, false)
                    : new Result(Math.max(modelScore, rulesScore), modelScore >= rulesScore, rulesScore >= modelScore);
            case RULES_ONLY -> new Result(rulesScore, false, true);
        };
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(1000, v));
    }
}
