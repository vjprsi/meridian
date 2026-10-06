package io.meridian.liquidity.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Liquidity stress test over a survival horizon. Position lines use the LCR codes: HQLA lines form the
 * counterbalancing capacity after the scenario haircuts (available on day 0); outflow and inflow lines are stressed
 * with the scenario run-off / inflow rates (default: LCR rates, run-offs scaled by runoff_multiplier and capped at
 * 100%). Lines with a contractual {@code days} flow on that day (beyond the horizon: ignored); the others flow
 * LINEAR (evenly over the horizon) or UPFRONT (all on day 1). The survival horizon is the last day the cumulative
 * position (capacity + inflows - outflows) is still non-negative.
 */
public final class StressEngine {
    public static final Set<String> PROFILES = Set.of("LINEAR", "UPFRONT");
    static final Set<String> KNOWN_KEYS = Set.of("runoff_rates", "inflow_rates", "hqla_haircuts", "runoff_multiplier",
            "runoff_profile", "min_survival_days");

    /** Scenario parameters (already validated). */
    public record Assumptions(Map<String, BigDecimal> runoffRates, Map<String, BigDecimal> inflowRates,
                              Map<String, BigDecimal> hqlaHaircuts, BigDecimal runoffMultiplier, String profile,
                              Integer minSurvivalDays) {
        public Assumptions {
            runoffRates = runoffRates == null ? Map.of() : Map.copyOf(runoffRates);
            inflowRates = inflowRates == null ? Map.of() : Map.copyOf(inflowRates);
            hqlaHaircuts = hqlaHaircuts == null ? Map.of() : Map.copyOf(hqlaHaircuts);
            runoffMultiplier = runoffMultiplier == null ? BigDecimal.ONE : runoffMultiplier;
            profile = profile == null ? "LINEAR" : profile;
        }
    }

    /** One position line; {@code days} = contractual day of the flow (outflows / inflows only). */
    public record Line(String line, String code, BigDecimal amount, Integer days) {}

    public record Day(int day, BigDecimal outflows, BigDecimal inflows, BigDecimal cumulativePosition) {}

    public record Result(BigDecimal stressedOutflows, BigDecimal stressedInflows, BigDecimal counterbalancingCapacity,
                         BigDecimal netPosition, int survivalDays, boolean breach, List<Day> profile) {}

    private StressEngine() {}

    /** Validates a scenario's assumptions object (as plain maps / numbers) and returns the typed form. */
    public static Assumptions validate(Map<String, Object> raw, int horizonDays) {
        if (horizonDays < 1 || horizonDays > 365) {
            throw new DomainException("invalid_horizon", "horizon_days must be between 1 and 365");
        }
        if (raw == null) {
            throw new DomainException("invalid_assumptions", "assumptions are required");
        }
        for (String k : raw.keySet()) {
            if (!KNOWN_KEYS.contains(k)) {
                throw new DomainException("invalid_assumptions", "unknown assumption " + k + "; allowed: " + KNOWN_KEYS);
            }
        }
        Map<String, BigDecimal> runoff = rates(raw.get("runoff_rates"), "runoff_rates", LcrCalculator.OUTFLOW_RATES.keySet());
        Map<String, BigDecimal> inflow = rates(raw.get("inflow_rates"), "inflow_rates", LcrCalculator.INFLOW_RATES.keySet());
        Map<String, BigDecimal> haircuts = rates(raw.get("hqla_haircuts"), "hqla_haircuts",
                LcrCalculator.HQLA_WEIGHTS.keySet());
        BigDecimal multiplier = null;
        if (raw.get("runoff_multiplier") != null) {
            multiplier = number(raw.get("runoff_multiplier"), "runoff_multiplier");
            if (multiplier.signum() <= 0 || multiplier.compareTo(BigDecimal.TEN) > 0) {
                throw new DomainException("invalid_assumptions", "runoff_multiplier must be > 0 and <= 10");
            }
        }
        String profile = null;
        if (raw.get("runoff_profile") != null) {
            profile = raw.get("runoff_profile").toString();
            if (!PROFILES.contains(profile)) {
                throw new DomainException("invalid_assumptions", "runoff_profile must be one of " + PROFILES);
            }
        }
        Integer minSurvival = null;
        if (raw.get("min_survival_days") != null) {
            BigDecimal m = number(raw.get("min_survival_days"), "min_survival_days");
            if (m.stripTrailingZeros().scale() > 0 || m.signum() < 0 || m.intValue() > horizonDays) {
                throw new DomainException("invalid_assumptions",
                        "min_survival_days must be a whole number between 0 and horizon_days");
            }
            minSurvival = m.intValue();
        }
        return new Assumptions(runoff, inflow, haircuts, multiplier, profile, minSurvival);
    }

    public static Result run(Assumptions a, int horizon, List<Line> lines) {
        if (lines.isEmpty()) {
            throw new DomainException("positions_required", "at least one position line is required");
        }
        BigDecimal capacity = BigDecimal.ZERO;
        BigDecimal[] out = zeros(horizon + 1);
        BigDecimal[] in = zeros(horizon + 1);
        for (Line l : lines) {
            if (l.amount() == null || l.amount().signum() < 0) {
                throw new DomainException("invalid_amount", "line " + l.line() + ": amount must be zero or positive");
            }
            LcrCalculator.Category cat = LcrCalculator.categoryOf(l.code());
            if (cat == null) {
                throw new DomainException("unknown_code", "line " + l.line() + ": code " + l.code() + " is not an LCR code");
            }
            switch (cat) {
                case HQLA -> {
                    BigDecimal haircut = a.hqlaHaircuts().containsKey(l.code()) ? a.hqlaHaircuts().get(l.code())
                            : BigDecimal.ONE.subtract(LcrCalculator.HQLA_WEIGHTS.get(l.code()));
                    capacity = capacity.add(l.amount().multiply(BigDecimal.ONE.subtract(haircut), Amounts.MC));
                }
                case OUTFLOW -> {
                    BigDecimal rate = a.runoffRates().containsKey(l.code()) ? a.runoffRates().get(l.code())
                            : LcrCalculator.OUTFLOW_RATES.get(l.code()).multiply(a.runoffMultiplier(), Amounts.MC)
                                    .min(BigDecimal.ONE);
                    spread(out, Amounts.money(l.amount().multiply(rate, Amounts.MC)), l.days(), horizon, a.profile());
                }
                case INFLOW -> {
                    BigDecimal rate = a.inflowRates().containsKey(l.code()) ? a.inflowRates().get(l.code())
                            : LcrCalculator.INFLOW_RATES.get(l.code());
                    spread(in, Amounts.money(l.amount().multiply(rate, Amounts.MC)), l.days(), horizon, a.profile());
                }
            }
        }
        BigDecimal cbc = Amounts.money(capacity);
        List<Day> days = new ArrayList<>();
        BigDecimal position = cbc;
        BigDecimal totalOut = BigDecimal.ZERO;
        BigDecimal totalIn = BigDecimal.ZERO;
        int survival = horizon + 1;
        for (int d = 1; d <= horizon; d++) {
            position = position.add(in[d]).subtract(out[d]);
            totalOut = totalOut.add(out[d]);
            totalIn = totalIn.add(in[d]);
            days.add(new Day(d, out[d], in[d], position));
            if (position.signum() < 0 && survival == horizon + 1) {
                survival = d - 1;
            }
        }
        int min = a.minSurvivalDays() == null ? horizon : a.minSurvivalDays();
        return new Result(totalOut, totalIn, cbc, position, survival, survival < min, days);
    }

    /** Places {@code amount} on the contractual day, or over the horizon per the profile (cents, remainder last). */
    static void spread(BigDecimal[] target, BigDecimal amount, Integer day, int horizon, String profile) {
        if (amount.signum() == 0) {
            return;
        }
        if (day != null) {
            int d = Math.max(1, day);
            if (d <= horizon) {
                target[d] = target[d].add(amount);
            }
            return;
        }
        if ("UPFRONT".equals(profile)) {
            target[1] = target[1].add(amount);
            return;
        }
        BigDecimal daily = amount.divide(BigDecimal.valueOf(horizon), Amounts.MONEY_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal placed = BigDecimal.ZERO;
        for (int d = 1; d <= horizon; d++) {
            BigDecimal part = d == horizon ? amount.subtract(placed) : daily;
            target[d] = target[d].add(part);
            placed = placed.add(part);
        }
    }

    private static Map<String, BigDecimal> rates(Object value, String name, Set<String> codes) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> m)) {
            throw new DomainException("invalid_assumptions", name + " must be an object of code -> rate");
        }
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String code = String.valueOf(e.getKey());
            if (!codes.contains(code)) {
                throw new DomainException("invalid_assumptions", name + ": unknown code " + code);
            }
            BigDecimal r = number(e.getValue(), name + "." + code);
            if (!Amounts.isFactor(r)) {
                throw new DomainException("invalid_assumptions", name + "." + code + " must be between 0 and 1");
            }
            out.put(code, r);
        }
        return out;
    }

    private static BigDecimal number(Object v, String name) {
        try {
            return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            throw new DomainException("invalid_assumptions", name + " must be a number");
        }
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }
}
