package io.meridian.loanorigination.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Points-based (additive) credit scorecard. Every applicant starts with {@code base_points}; each feature adds the
 * points of the first bin its value falls into (numeric {@code [min, max)} ranges or categorical value lists), or the
 * feature's {@code missing_points} when the value is absent or matches no bin. The score maps to a probability of
 * default through the usual log-odds calibration ({@code odds = target_odds * 2^((score - target_score) / PDO)},
 * {@code PD = 1 / (1 + odds)}) and to a cut-off band (outcome, risk grade, pricing adjustment).
 *
 * <p>Explainability: each feature's contribution is reported, and the adverse-action reason codes are the features
 * that lost the most points against the best bin of that feature (ties broken by name, deterministic).
 */
public final class ScorecardEngine {
    private ScorecardEngine() {}

    static final MathContext MC = MathContext.DECIMAL64;
    public static final int MAX_REASONS = 4;
    private static final Set<String> OUTCOMES = Set.of("APPROVE", "REFER", "DECLINE");

    public record Bin(BigDecimal min, BigDecimal max, Set<String> values, int points, String label) {
        boolean matches(Object value) {
            if (value == null) {
                return false;
            }
            if (values != null && !values.isEmpty()) {
                return values.contains(value.toString().trim().toUpperCase(Locale.ROOT));
            }
            BigDecimal v;
            try {
                v = value instanceof BigDecimal b ? b : new BigDecimal(value.toString().trim());
            } catch (NumberFormatException e) {
                return false;
            }
            return (min == null || v.compareTo(min) >= 0) && (max == null || v.compareTo(max) < 0);
        }
    }

    public record Feature(String name, int missingPoints, String reasonCode, String description, List<Bin> bins) {
        int maxPoints() {
            int m = missingPoints;
            for (Bin b : bins) {
                m = Math.max(m, b.points());
            }
            return m;
        }
    }

    public record Cutoff(int minScore, String outcome, String riskGrade, BigDecimal rateAdjustment) {}

    public record Model(int basePoints, List<Feature> features, List<Cutoff> cutoffs, int targetScore,
                        BigDecimal targetOdds, BigDecimal pointsToDoubleOdds) {}

    public record Contribution(String feature, Object value, String bin, int points, int maxPoints) {}

    public record ReasonCode(String code, String feature, int pointsLost, String description) {}

    public record Result(int score, BigDecimal probabilityOfDefault, Cutoff band, List<Contribution> contributions,
                         List<ReasonCode> reasons, String inputsHash) {}

    /** Validation failure of a scorecard definition (mapped to 422 by the application layer). */
    public static final class InvalidScorecard extends RuntimeException {
        public InvalidScorecard(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ definition parsing / validation

    /** Builds a model from the version's json[] definitions (plain Java maps/lists from JSON). */
    @SuppressWarnings("unchecked")
    public static Model model(int basePoints, List<?> features, List<?> cutoffs, Integer targetScore,
                              BigDecimal targetOdds, BigDecimal pdo) {
        if (features == null || features.isEmpty()) {
            throw new InvalidScorecard("features must contain at least one feature");
        }
        if (cutoffs == null || cutoffs.isEmpty()) {
            throw new InvalidScorecard("cutoffs must contain at least one band");
        }
        List<Feature> fs = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Object o : features) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new InvalidScorecard("every feature must be an object");
            }
            String name = str(m.get("name"));
            if (name == null || name.isBlank()) {
                throw new InvalidScorecard("every feature needs a name");
            }
            if (!names.add(name)) {
                throw new InvalidScorecard("feature " + name + " is defined twice");
            }
            Object binsRaw = m.get("bins");
            if (!(binsRaw instanceof List<?> binList) || binList.isEmpty()) {
                throw new InvalidScorecard("feature " + name + " needs at least one bin");
            }
            List<Bin> bins = new ArrayList<>();
            for (Object bo : binList) {
                if (!(bo instanceof Map<?, ?> b)) {
                    throw new InvalidScorecard("bins of " + name + " must be objects");
                }
                Integer points = integer(b.get("points"));
                if (points == null) {
                    throw new InvalidScorecard("every bin of " + name + " needs integer points");
                }
                Set<String> values = null;
                if (b.get("values") instanceof List<?> vs) {
                    values = new LinkedHashSet<>();
                    for (Object v : vs) {
                        values.add(String.valueOf(v).trim().toUpperCase(Locale.ROOT));
                    }
                }
                BigDecimal min = decimal(b.get("min"), name);
                BigDecimal max = decimal(b.get("max"), name);
                if (min != null && max != null && min.compareTo(max) >= 0) {
                    throw new InvalidScorecard("bin of " + name + " has min >= max");
                }
                String label = str(b.get("label"));
                bins.add(new Bin(min, max, values, points, label != null ? label
                        : values != null ? String.join("|", values) : "[" + (min == null ? "-inf" : min.toPlainString())
                        + ", " + (max == null ? "inf" : max.toPlainString()) + ")"));
            }
            Integer missing = integer(m.get("missing_points"));
            String reason = str(m.get("reason_code"));
            fs.add(new Feature(name, missing == null ? 0 : missing,
                    reason == null ? name.toUpperCase(Locale.ROOT) : reason, str(m.get("description")), bins));
        }
        List<Cutoff> cs = new ArrayList<>();
        for (Object o : cutoffs) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new InvalidScorecard("every cut-off must be an object");
            }
            Integer min = integer(m.get("min_score"));
            String outcome = str(m.get("outcome"));
            String grade = str(m.get("risk_grade"));
            if (min == null || outcome == null || !OUTCOMES.contains(outcome.toUpperCase(Locale.ROOT)) || grade == null) {
                throw new InvalidScorecard("every cut-off needs min_score, outcome (APPROVE, REFER, DECLINE) and risk_grade");
            }
            BigDecimal adj = decimal(m.get("rate_adjustment"), "cutoffs");
            cs.add(new Cutoff(min, outcome.toUpperCase(Locale.ROOT), grade, adj == null ? BigDecimal.ZERO : adj));
        }
        cs.sort(Comparator.comparingInt(Cutoff::minScore).reversed());
        BigDecimal odds = targetOdds == null ? BigDecimal.valueOf(50) : targetOdds;
        BigDecimal p = pdo == null ? BigDecimal.valueOf(20) : pdo;
        if (odds.signum() <= 0 || p.signum() <= 0) {
            throw new InvalidScorecard("target_odds and points_to_double_odds must be positive");
        }
        return new Model(basePoints, List.copyOf(fs), List.copyOf(cs), targetScore == null ? 600 : targetScore, odds, p);
    }

    // ------------------------------------------------------------------ scoring

    public static Result score(Model model, Map<String, Object> inputs) {
        int total = model.basePoints();
        List<Contribution> contributions = new ArrayList<>();
        List<ReasonCode> reasons = new ArrayList<>();
        for (Feature f : model.features()) {
            Object value = inputs.get(f.name());
            Bin hit = null;
            for (Bin b : f.bins()) {
                if (b.matches(value)) {
                    hit = b;
                    break;
                }
            }
            int points = hit == null ? f.missingPoints() : hit.points();
            total += points;
            int max = f.maxPoints();
            contributions.add(new Contribution(f.name(), value, hit == null ? "MISSING" : hit.label(), points, max));
            if (max - points > 0) {
                reasons.add(new ReasonCode(f.reasonCode(), f.name(), max - points,
                        f.description() != null ? f.description() : describe(f.name(), hit == null)));
            }
        }
        reasons.sort(Comparator.comparingInt(ReasonCode::pointsLost).reversed().thenComparing(ReasonCode::feature));
        Cutoff band = band(model.cutoffs(), total);
        return new Result(total, pd(model, total), band, contributions,
                List.copyOf(reasons.subList(0, Math.min(MAX_REASONS, reasons.size()))), hash(inputs));
    }

    /** Highest band whose min_score the score reaches; below every band -> the lowest band. */
    public static Cutoff band(List<Cutoff> cutoffs, int score) {
        for (Cutoff c : cutoffs) {
            if (score >= c.minScore()) {
                return c;
            }
        }
        return cutoffs.getLast();
    }

    public static BigDecimal pd(Model m, int score) {
        double odds = m.targetOdds().doubleValue()
                * Math.pow(2, (score - m.targetScore()) / m.pointsToDoubleOdds().doubleValue());
        return BigDecimal.valueOf(1 / (1 + odds)).setScale(6, RoundingMode.HALF_UP);
    }

    /** SHA-256 over the canonical (key-sorted) inputs; identical inputs give identical hashes. */
    public static String hash(Map<String, Object> inputs) {
        StringBuilder sb = new StringBuilder("{");
        new TreeMap<>(inputs).forEach((k, v) -> sb.append('"').append(k).append("\":")
                .append(v == null ? "null" : v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : v)
                .append(','));
        sb.append('}');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deterministic traffic bucket in [0, 1) for champion/challenger routing. */
    public static double bucket(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            long v = ((long) (d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
            return v / 4294967296.0;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String describe(String feature, boolean missing) {
        String human = feature.replace('_', ' ');
        return missing ? "No information on " + human : "Unfavourable " + human;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer integer(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(Object o, String ctx) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new InvalidScorecard("non-numeric bound in " + ctx + ": " + o);
        }
    }
}
