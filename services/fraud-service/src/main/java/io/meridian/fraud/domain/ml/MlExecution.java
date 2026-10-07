package io.meridian.fraud.domain.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Runs the deployments of a {@link ModelSet} for one evaluation: the deciding one first (with its time budget), then
 * every observer (challenger, shadow, canaries outside their slice) for the log. Observers can never change the
 * outcome: their results only land in {@link Run}s with {@code decided = false}.
 *
 * <p>Fail-safe: no deciding deployment, an unloadable artefact, an exception or a deciding run slower than the budget
 * yields {@code degradedReason} (and no deciding run), so the caller falls back to the rule-based score.
 */
public final class MlExecution {
    /** At most this many observers are scored per evaluation (bounded latency). */
    public static final int MAX_OBSERVERS = 4;

    private MlExecution() {}

    /**
     * @param error       null on success; MODEL_UNAVAILABLE, CHECKSUM_MISMATCH, INVALID_ARTIFACT, MODEL_ERROR,
     *                    MODEL_TIMEOUT
     * @param vector      model input in the artefact's feature order (null when not built)
     */
    public record Run(ModelSet.Deployment deployment, ModelSet.Role role, boolean decided, CompiledModel.Prediction prediction,
                      double[] vector, List<ModelArtifact.Contribution> contributions, long latencyNanos, String error) {
        public Integer score() {
            return prediction == null ? null : prediction.score();
        }
    }

    public record Result(Run deciding, String degradedReason, List<Run> runs) {}

    public static Result execute(ModelSet set, UUID subjectId, Map<String, Double> features, long budgetNanos,
                                 LongSupplier clock) {
        List<Run> runs = new ArrayList<>();
        if (set == null) {
            return new Result(null, "MODEL_UNAVAILABLE", runs);
        }
        ModelSet.Deployment d = set.deciding(subjectId);
        Run deciding = null;
        String degraded = null;
        if (d == null) {
            degraded = "NO_CHAMPION";
        } else {
            Run r = run(d, features, clock);
            if (r.error() == null && r.latencyNanos() > budgetNanos) {
                r = new Run(r.deployment(), r.role(), false, r.prediction(), r.vector(), r.contributions(), r.latencyNanos(),
                        "MODEL_TIMEOUT");
            }
            if (r.error() == null) {
                deciding = new Run(r.deployment(), r.role(), true, r.prediction(), r.vector(), r.contributions(),
                        r.latencyNanos(), null);
                runs.add(deciding);
            } else {
                degraded = r.error();
                runs.add(r);
            }
        }
        int observed = 0;
        for (ModelSet.Deployment o : set.observers(d)) {
            if (observed++ >= MAX_OBSERVERS) {
                break;
            }
            Run r = run(o, features, clock);
            // an observer never decides, whatever it scores
            runs.add(new Run(r.deployment(), r.role(), false, r.prediction(), r.vector(), r.contributions(), r.latencyNanos(),
                    r.error()));
        }
        return new Result(deciding, degraded, List.copyOf(runs));
    }

    static Run run(ModelSet.Deployment d, Map<String, Double> features, LongSupplier clock) {
        if (!d.usable()) {
            return new Run(d, d.role(), false, null, null, List.of(), 0, d.error() == null ? "MODEL_UNAVAILABLE" : d.error());
        }
        long t0 = clock.getAsLong();
        try {
            double[] x = d.artifact().vector(features);
            CompiledModel.Prediction p = d.artifact().model().predict(x);
            if (!Double.isFinite(p.margin()) || !Double.isFinite(p.probability())) {
                return new Run(d, d.role(), false, null, x, List.of(), clock.getAsLong() - t0, "MODEL_ERROR");
            }
            List<ModelArtifact.Contribution> contributions = d.artifact().contributions(x, p);
            return new Run(d, d.role(), false, p, x, contributions, clock.getAsLong() - t0, null);
        } catch (RuntimeException e) {
            return new Run(d, d.role(), false, null, null, List.of(), clock.getAsLong() - t0, "MODEL_ERROR");
        }
    }
}
