package io.meridian.transactionmonitoring.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AML monitoring scenarios evaluated over one customer's transaction legs. Each scenario looks at sliding windows
 * of {@code lookback_days} ending at every transaction inside the evaluation period and reports the strongest
 * detection (score 0-1000, contributing transactions, figures for the analyst).
 */
public final class Scenarios {
    private Scenarios() {}

    public static final Set<String> SUPPORTED = Set.of("STRUCTURING", "RAPID_MOVEMENT", "MULE_ACCOUNT", "FAN_IN", "FAN_OUT",
            "DORMANT_REACTIVATION", "LARGE_CASH", "HIGH_RISK_JURISDICTION", "ROUND_AMOUNTS", "PROFILE_DEVIATION");

    private static final MathContext MC = MathContext.DECIMAL64;

    /** One transaction leg of the customer. Direction: DEBIT = out of the customer, CREDIT = in. */
    public record Txn(UUID id, UUID transactionId, String transactionType, UUID accountId, UUID journalEntryId,
                      String direction, BigDecimal amount, String currency, boolean cash, String counterpartyName,
                      String counterpartyAccount, String counterpartyCountry, Instant occurredAt) {
        public boolean credit() {
            return "CREDIT".equals(direction);
        }

        public String counterparty() {
            return counterpartyAccount != null ? counterpartyAccount : counterpartyName;
        }
    }

    /** Thresholds of a scenario version. */
    public record Params(int lookbackDays, BigDecimal minAmount, Integer minCount, BigDecimal riskWeight,
                         Map<String, Object> values) {
        BigDecimal dec(String key, String dflt) {
            Object v = values.get(key);
            return v == null ? new BigDecimal(dflt) : new BigDecimal(v.toString());
        }

        int integer(String key, int dflt) {
            Object v = values.get(key);
            return v == null ? dflt : new BigDecimal(v.toString()).intValue();
        }

        boolean bool(String key, boolean dflt) {
            Object v = values.get(key);
            return v == null ? dflt : Boolean.parseBoolean(v.toString());
        }

        Set<String> strings(String key) {
            Set<String> out = new LinkedHashSet<>();
            if (values.get(key) instanceof Collection<?> c) {
                c.forEach(x -> out.add(x.toString().toUpperCase(java.util.Locale.ROOT)));
            }
            return out;
        }

        int minCountOr(int dflt) {
            return minCount != null ? minCount : dflt;
        }
    }

    public record Detection(int score, BigDecimal totalAmount, String currency, Instant periodStart, Instant periodEnd,
                            List<Txn> contributing, Map<String, Object> reason) {}

    /** History needed before the evaluation period (days), so the caller loads enough transactions. */
    public static int historyDays(String type, Params p) {
        return switch (type) {
            case "DORMANT_REACTIVATION" -> p.lookbackDays() + p.integer("dormant_days", 180) + 1;
            case "PROFILE_DEVIATION" -> p.lookbackDays() + p.integer("history_days", 90);
            default -> p.lookbackDays();
        };
    }

    /** Validates parameters when a version is created. Returns an error message or null. */
    public static String validate(String type, Params p) {
        if (!SUPPORTED.contains(type)) {
            return "scenario type " + type + " has no evaluator";
        }
        if (p.lookbackDays() < 1 || p.lookbackDays() > 400) {
            return "lookback_days must be between 1 and 400";
        }
        try {
            switch (type) {
                case "STRUCTURING", "LARGE_CASH" -> {
                    if (p.dec("threshold", "10000").signum() <= 0) {
                        return "threshold must be positive";
                    }
                    BigDecimal m = p.dec("margin_pct", "10");
                    if (m.signum() <= 0 || m.compareTo(new BigDecimal("50")) > 0) {
                        return "margin_pct must be in (0, 50]";
                    }
                }
                case "RAPID_MOVEMENT", "MULE_ACCOUNT" -> {
                    BigDecimal r = p.dec("min_out_ratio", "0.8");
                    if (r.signum() <= 0 || r.compareTo(BigDecimal.ONE) > 0) {
                        return "min_out_ratio must be in (0, 1]";
                    }
                }
                case "HIGH_RISK_JURISDICTION" -> {
                    if (p.strings("countries").isEmpty()) {
                        return "parameters.countries must list the high-risk countries";
                    }
                }
                case "ROUND_AMOUNTS" -> {
                    if (p.dec("round_unit", "1000").signum() <= 0) {
                        return "round_unit must be positive";
                    }
                }
                default -> { }
            }
        } catch (NumberFormatException e) {
            return "parameters must be numeric: " + e.getMessage();
        }
        return null;
    }

    /**
     * @param txns     the customer's legs (any order), including history before {@code from}
     * @param from     evaluation period start: windows end at transactions in [from, to]
     * @param to       evaluation period end
     */
    public static Optional<Detection> evaluate(String type, Params p, List<Txn> txns, Instant from, Instant to) {
        List<Txn> sorted = new ArrayList<>(txns);
        sorted.sort((a, b) -> a.occurredAt().compareTo(b.occurredAt()));
        Detection best = null;
        Duration lookback = Duration.ofDays(p.lookbackDays());
        int start = 0;
        for (int e = 0; e < sorted.size(); e++) {
            Txn end = sorted.get(e);
            if (end.occurredAt().isBefore(from) || end.occurredAt().isAfter(to)) {
                continue;
            }
            Instant windowStart = end.occurredAt().minus(lookback);
            while (start < e && !sorted.get(start).occurredAt().isAfter(windowStart)) {
                start++; // two pointers: the window start only moves forward
            }
            List<Txn> window = sorted.subList(start, e + 1);
            List<Txn> before = sorted.subList(0, start);
            Optional<Detection> d = switch (type) {
                case "STRUCTURING" -> structuring(p, window);
                case "RAPID_MOVEMENT" -> rapidMovement(p, window);
                case "FAN_IN" -> fan(p, window, true);
                case "FAN_OUT" -> fan(p, window, false);
                case "MULE_ACCOUNT" -> mule(p, window);
                case "DORMANT_REACTIVATION" -> dormant(p, window, before);
                case "LARGE_CASH" -> largeCash(p, window);
                case "HIGH_RISK_JURISDICTION" -> jurisdiction(p, window);
                case "ROUND_AMOUNTS" -> roundAmounts(p, window);
                case "PROFILE_DEVIATION" -> deviation(p, window, before, windowStart);
                default -> Optional.empty();
            };
            if (d.isPresent() && (best == null || d.get().score() > best.score())) {
                best = d.get();
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------ scenarios

    /** Many cash deposits just below the reporting threshold. */
    static Optional<Detection> structuring(Params p, List<Txn> window) {
        BigDecimal threshold = p.dec("threshold", "10000");
        BigDecimal low = threshold.multiply(BigDecimal.ONE.subtract(p.dec("margin_pct", "10").movePointLeft(2)), MC);
        boolean cashOnly = p.bool("cash_only", true);
        List<Txn> hits = window.stream().filter(Txn::credit).filter(t -> !cashOnly || t.cash())
                .filter(t -> t.amount().compareTo(low) >= 0 && t.amount().compareTo(threshold) < 0).toList();
        int min = p.minCountOr(3);
        BigDecimal total = sum(hits);
        if (hits.size() < min || (p.minAmount() != null && total.compareTo(p.minAmount()) < 0)) {
            return Optional.empty();
        }
        int score = 550 + 75 * (hits.size() - min) + (total.compareTo(threshold.multiply(BigDecimal.TWO)) >= 0 ? 100 : 0);
        Map<String, Object> reason = reason("threshold", threshold, "band_low", low, "deposits_in_band", hits.size(),
                "min_count", min, "total", total);
        return detection(score, p, hits, reason);
    }

    /** Money received and sent on quickly (pass-through). */
    static Optional<Detection> rapidMovement(Params p, List<Txn> window) {
        Duration within = Duration.ofHours(p.integer("window_hours", 48));
        BigDecimal ratio = p.dec("min_out_ratio", "0.8");
        BigDecimal minIn = p.minAmount() != null ? p.minAmount() : new BigDecimal("1000");
        Detection best = null;
        for (int i = 0; i < window.size(); i++) {
            Txn first = window.get(i);
            if (!first.credit()) {
                continue;
            }
            Instant limit = first.occurredAt().plus(within);
            List<Txn> in = new ArrayList<>();
            List<Txn> out = new ArrayList<>();
            for (int j = i; j < window.size() && !window.get(j).occurredAt().isAfter(limit); j++) {
                (window.get(j).credit() ? in : out).add(window.get(j));
            }
            BigDecimal inSum = sum(in);
            BigDecimal outSum = sum(out);
            if (inSum.compareTo(minIn) < 0 || out.isEmpty()) {
                continue;
            }
            BigDecimal r = outSum.divide(inSum, MC);
            if (r.compareTo(ratio) < 0 || in.size() + out.size() < p.minCountOr(2)) {
                continue;
            }
            BigDecimal capped = r.min(BigDecimal.ONE);
            int score = 550 + capped.subtract(ratio).multiply(new BigDecimal("1000"), MC).intValue() / 2
                    + Math.min(150, 25 * (in.size() + out.size() - 2));
            List<Txn> all = new ArrayList<>(in);
            all.addAll(out);
            all.sort((a, b) -> a.occurredAt().compareTo(b.occurredAt()));
            Map<String, Object> reason = reason("inflow", inSum, "outflow", outSum, "out_in_ratio",
                    r.setScale(4, RoundingMode.HALF_EVEN), "window_hours", within.toHours(), "min_out_ratio", ratio);
            Optional<Detection> d = detection(score, p, all, reason);
            if (d.isPresent() && (best == null || d.get().score() > best.score())) {
                best = d.get();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Many distinct counterparties paying in (fan-in) or being paid (fan-out). */
    static Optional<Detection> fan(Params p, List<Txn> window, boolean in) {
        List<Txn> legs = window.stream().filter(t -> t.credit() == in && t.counterparty() != null).toList();
        Set<String> parties = new HashSet<>();
        legs.forEach(t -> parties.add(t.counterparty().toUpperCase(java.util.Locale.ROOT)));
        int min = p.integer("min_counterparties", 5);
        BigDecimal total = sum(legs);
        if (parties.size() < min || (p.minAmount() != null && total.compareTo(p.minAmount()) < 0)) {
            return Optional.empty();
        }
        int score = 550 + 50 * (parties.size() - min);
        return detection(score, p, legs, reason("distinct_counterparties", parties.size(), "min_counterparties", min,
                "direction", in ? "IN" : "OUT", "total", total));
    }

    /** Mule pattern: funds from several sources forwarded on (fan-in plus pass-through). */
    static Optional<Detection> mule(Params p, List<Txn> window) {
        List<Txn> in = window.stream().filter(Txn::credit).toList();
        List<Txn> out = window.stream().filter(t -> !t.credit()).toList();
        Set<String> sources = new HashSet<>();
        in.forEach(t -> sources.add(t.counterparty() == null ? t.transactionId().toString()
                : t.counterparty().toUpperCase(java.util.Locale.ROOT)));
        int minSources = p.integer("min_counterparties", 3);
        BigDecimal inSum = sum(in);
        BigDecimal outSum = sum(out);
        BigDecimal ratio = p.dec("min_out_ratio", "0.7");
        if (sources.size() < minSources || inSum.signum() == 0 || window.size() < p.minCountOr(4)) {
            return Optional.empty();
        }
        if (p.minAmount() != null && inSum.compareTo(p.minAmount()) < 0) {
            return Optional.empty();
        }
        BigDecimal r = outSum.divide(inSum, MC);
        if (r.compareTo(ratio) < 0) {
            return Optional.empty();
        }
        int score = 700 + 40 * (sources.size() - minSources) + r.min(BigDecimal.ONE).subtract(ratio).multiply(new BigDecimal("300"), MC).intValue();
        return detection(score, p, window, reason("sources", sources.size(), "min_counterparties", minSources, "inflow", inSum,
                "outflow", outSum, "out_in_ratio", r.setScale(4, RoundingMode.HALF_EVEN)));
    }

    /** A long-dormant customer suddenly moves money. */
    static Optional<Detection> dormant(Params p, List<Txn> window, List<Txn> before) {
        if (window.isEmpty() || before.isEmpty()) {
            return Optional.empty(); // no history: a new relationship, not a dormant one
        }
        Instant lastBefore = before.getLast().occurredAt();
        Instant firstInWindow = window.getFirst().occurredAt();
        long gapDays = Duration.between(lastBefore, firstInWindow).toDays();
        int dormantDays = p.integer("dormant_days", 180);
        BigDecimal total = sum(window);
        BigDecimal min = p.minAmount() != null ? p.minAmount() : new BigDecimal("5000");
        if (gapDays < dormantDays || total.compareTo(min) < 0) {
            return Optional.empty();
        }
        int score = 550 + (int) Math.min(250, (gapDays - dormantDays) / 2) + (total.compareTo(min.multiply(BigDecimal.TEN)) >= 0 ? 100 : 0);
        return detection(score, p, window, reason("dormant_days", gapDays, "min_dormant_days", dormantDays,
                "reactivation_total", total, "last_activity_before", lastBefore.toString()));
    }

    static Optional<Detection> largeCash(Params p, List<Txn> window) {
        BigDecimal threshold = p.dec("threshold", "10000");
        List<Txn> hits = window.stream().filter(Txn::cash).filter(t -> t.amount().compareTo(threshold) >= 0).toList();
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal max = hits.stream().map(Txn::amount).reduce(BigDecimal.ZERO, BigDecimal::max);
        int score = 500 + Math.min(300, max.divide(threshold, MC).subtract(BigDecimal.ONE).multiply(new BigDecimal("100"), MC).intValue())
                + 25 * (hits.size() - 1);
        return detection(score, p, hits, reason("threshold", threshold, "largest", max, "count", hits.size()));
    }

    static Optional<Detection> jurisdiction(Params p, List<Txn> window) {
        Set<String> risky = p.strings("countries");
        List<Txn> hits = window.stream().filter(t -> t.counterpartyCountry() != null
                && risky.contains(t.counterpartyCountry().toUpperCase(java.util.Locale.ROOT))).toList();
        BigDecimal total = sum(hits);
        if (hits.size() < p.minCountOr(1) || (p.minAmount() != null && total.compareTo(p.minAmount()) < 0)) {
            return Optional.empty();
        }
        Set<String> seen = new LinkedHashSet<>();
        hits.forEach(t -> seen.add(t.counterpartyCountry()));
        return detection(600 + 50 * (hits.size() - 1), p, hits, reason("countries", List.copyOf(seen), "count", hits.size(), "total", total));
    }

    static Optional<Detection> roundAmounts(Params p, List<Txn> window) {
        BigDecimal unit = p.dec("round_unit", "1000");
        List<Txn> hits = window.stream().filter(t -> t.amount().compareTo(unit) >= 0
                && t.amount().remainder(unit).signum() == 0).toList();
        int min = p.minCountOr(5);
        if (hits.size() < min) {
            return Optional.empty();
        }
        return detection(500 + 40 * (hits.size() - min), p, hits, reason("round_unit", unit, "count", hits.size(), "min_count", min));
    }

    /** Activity far above the customer's own history (same window length). */
    static Optional<Detection> deviation(Params p, List<Txn> window, List<Txn> before, Instant windowStart) {
        int historyDays = p.integer("history_days", 90);
        Instant historyStart = windowStart.minus(Duration.ofDays(historyDays));
        List<Txn> history = before.stream().filter(t -> !t.occurredAt().isBefore(historyStart)).toList();
        if (history.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal periods = BigDecimal.valueOf(historyDays).divide(BigDecimal.valueOf(p.lookbackDays()), MC);
        BigDecimal baseline = sum(history).divide(periods, MC);
        BigDecimal current = sum(window);
        BigDecimal multiplier = p.dec("multiplier", "3");
        BigDecimal min = p.minAmount() != null ? p.minAmount() : BigDecimal.ZERO;
        if (baseline.signum() == 0 || current.compareTo(baseline.multiply(multiplier, MC)) < 0 || current.compareTo(min) < 0) {
            return Optional.empty();
        }
        BigDecimal factor = current.divide(baseline, MC);
        int score = 500 + Math.min(400, factor.subtract(multiplier).multiply(new BigDecimal("50"), MC).intValue());
        return detection(score, p, window, reason("baseline_per_window", baseline.setScale(2, RoundingMode.HALF_EVEN),
                "current", current, "factor", factor.setScale(2, RoundingMode.HALF_EVEN), "multiplier", multiplier));
    }

    // ------------------------------------------------------------------ helpers

    static Optional<Detection> detection(int rawScore, Params p, List<Txn> contributing, Map<String, Object> reason) {
        if (contributing.isEmpty()) {
            return Optional.empty();
        }
        int score = BigDecimal.valueOf(rawScore).multiply(p.riskWeight(), MC).setScale(0, RoundingMode.HALF_EVEN).intValue();
        score = Math.max(0, Math.min(1000, score));
        Instant start = contributing.stream().map(Txn::occurredAt).min(Instant::compareTo).orElseThrow();
        Instant end = contributing.stream().map(Txn::occurredAt).max(Instant::compareTo).orElseThrow();
        return Optional.of(new Detection(score, sum(contributing), contributing.getFirst().currency(), start, end,
                List.copyOf(contributing), reason));
    }

    static BigDecimal sum(Collection<Txn> txns) {
        return txns.stream().map(Txn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Object> reason(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put(kv[i].toString(), v instanceof BigDecimal d ? d.toPlainString() : v);
        }
        return m;
    }

    /** Severity band of an alert score. */
    public static String severity(int score) {
        return score >= 850 ? "CRITICAL" : score >= 700 ? "HIGH" : score >= 500 ? "MEDIUM" : "LOW";
    }
}
