package io.meridian.alm.domain;

import io.meridian.alm.domain.Assumption.AssumptionType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Behavioural models: parameter validation per assumption type, CPR prepayment run-off of fixed-rate assets, core /
 * volatile decay of non-maturity deposits and the resulting average life.
 *
 * <ul>
 *   <li>PREPAYMENT {cpr}: constant annual prepayment rate; monthly SMM = 1 - (1 - CPR)^(1/12).</li>
 *   <li>DEPOSIT_DECAY {core_ratio, decay_rate, core_max_months (default 60), pass_through (default 1)}: the volatile
 *       share (1 - core_ratio) reprices overnight; the core decays monthly at 1 - (1 - decay_rate)^(1/12) with the
 *       remainder at core_max_months.</li>
 *   <li>NON_MATURITY_REPRICING {repricing_months, pass_through}: the whole balance reprices after repricing_months.</li>
 *   <li>EARLY_WITHDRAWAL / DRAWDOWN {rate}: annual rate (validated; informational for the gap and EVE engines).</li>
 * </ul>
 */
public final class Behaviour {
    static final MathContext MC = MathContext.DECIMAL128;

    private Behaviour() {}

    /** A dated cash flow (principal repricing). */
    public record Flow(LocalDate date, BigDecimal amount) {}

    /** Validates and normalises parameters; throws IllegalArgumentException listing every problem. */
    public static Map<String, BigDecimal> validate(AssumptionType type, Map<String, ?> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("parameters are required");
        }
        List<String> errors = new ArrayList<>();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        Set<String> allowed = switch (type) {
            case PREPAYMENT -> Set.of("cpr");
            case DEPOSIT_DECAY -> Set.of("core_ratio", "decay_rate", "core_max_months", "pass_through");
            case NON_MATURITY_REPRICING -> Set.of("repricing_months", "pass_through");
            case EARLY_WITHDRAWAL, DRAWDOWN -> Set.of("rate");
        };
        for (String k : raw.keySet()) {
            if (!allowed.contains(k)) {
                errors.add("unknown parameter " + k + " for " + type + " (allowed: " + allowed + ")");
            }
        }
        switch (type) {
            case PREPAYMENT -> fraction(raw, "cpr", true, false, errors, out);
            case DEPOSIT_DECAY -> {
                fraction(raw, "core_ratio", true, true, errors, out);
                fraction(raw, "decay_rate", true, false, errors, out);
                months(raw, "core_max_months", false, 1, 360, 60, errors, out);
                fraction(raw, "pass_through", false, true, errors, out);
                out.putIfAbsent("pass_through", BigDecimal.ONE);
            }
            case NON_MATURITY_REPRICING -> {
                months(raw, "repricing_months", true, 0, 360, null, errors, out);
                fraction(raw, "pass_through", false, true, errors, out);
                out.putIfAbsent("pass_through", BigDecimal.ONE);
            }
            case EARLY_WITHDRAWAL, DRAWDOWN -> fraction(raw, "rate", true, true, errors, out);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return out;
    }

    private static BigDecimal number(Map<String, ?> raw, String name, List<String> errors) {
        Object v = raw.get(name);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            errors.add(name + " must be a number");
            return null;
        }
    }

    private static void fraction(Map<String, ?> raw, String name, boolean required, boolean oneInclusive,
                                 List<String> errors, Map<String, BigDecimal> out) {
        BigDecimal v = number(raw, name, errors);
        if (v == null) {
            if (required && !raw.containsKey(name)) {
                errors.add(name + " is required");
            }
            return;
        }
        boolean ok = v.signum() >= 0 && (oneInclusive ? v.compareTo(BigDecimal.ONE) <= 0 : v.compareTo(BigDecimal.ONE) < 0);
        if (!ok) {
            errors.add(name + " must be between 0 and 1" + (oneInclusive ? "" : " (exclusive)"));
            return;
        }
        out.put(name, v);
    }

    private static void months(Map<String, ?> raw, String name, boolean required, int min, int max, Integer dflt,
                               List<String> errors, Map<String, BigDecimal> out) {
        BigDecimal v = number(raw, name, errors);
        if (v == null) {
            if (required && !raw.containsKey(name)) {
                errors.add(name + " is required");
            } else if (dflt != null) {
                out.put(name, BigDecimal.valueOf(dflt));
            }
            return;
        }
        if (v.stripTrailingZeros().scale() > 0 || v.intValue() < min || v.intValue() > max) {
            errors.add(name + " must be a whole number of months between " + min + " and " + max);
            return;
        }
        out.put(name, BigDecimal.valueOf(v.intValue()));
    }

    /** Monthly single mortality from an annual CPR: 1 - (1 - CPR)^(1/12). */
    public static BigDecimal monthly(BigDecimal annual) {
        return BigDecimal.valueOf(1 - Math.pow(1 - annual.doubleValue(), 1.0 / 12));
    }

    /**
     * Principal flows of a fixed-rate amortising-by-prepayment asset: each month SMM of the outstanding prepays; the
     * remaining balance repays at maturity. Flows sum to the amount exactly.
     */
    public static List<Flow> prepayment(LocalDate asOf, LocalDate maturity, BigDecimal amount, BigDecimal cpr) {
        List<Flow> flows = new ArrayList<>();
        if (!maturity.isAfter(asOf)) {
            flows.add(new Flow(maturity, amount));
            return flows;
        }
        long n = ChronoUnit.MONTHS.between(asOf, maturity);
        if (asOf.plusMonths(n).isBefore(maturity)) {
            n++;
        }
        BigDecimal smm = monthly(cpr);
        BigDecimal balance = amount;
        for (int m = 1; m < n; m++) {
            BigDecimal prepaid = balance.multiply(smm, MC);
            flows.add(new Flow(asOf.plusMonths(m), prepaid));
            balance = balance.subtract(prepaid, MC);
        }
        flows.add(new Flow(maturity, balance));
        return flows;
    }

    /**
     * Repricing profile of a non-maturity deposit: the volatile share reprices the next day; the core runs off monthly
     * at the decay rate with the rest at core_max_months. Flows sum to the amount exactly.
     */
    public static List<Flow> decay(LocalDate asOf, BigDecimal amount, Map<String, BigDecimal> p) {
        List<Flow> flows = new ArrayList<>();
        BigDecimal core = amount.multiply(p.get("core_ratio"), MC);
        BigDecimal volatile_ = amount.subtract(core, MC);
        if (volatile_.signum() != 0) {
            flows.add(new Flow(asOf.plusDays(1), volatile_));
        }
        int maxMonths = p.getOrDefault("core_max_months", BigDecimal.valueOf(60)).intValue();
        BigDecimal monthly = monthly(p.get("decay_rate"));
        BigDecimal balance = core;
        for (int m = 1; m < maxMonths; m++) {
            BigDecimal runoff = balance.multiply(monthly, MC);
            if (runoff.signum() != 0) {
                flows.add(new Flow(asOf.plusMonths(m), runoff));
            }
            balance = balance.subtract(runoff, MC);
        }
        if (balance.signum() != 0) {
            flows.add(new Flow(asOf.plusMonths(maxMonths), balance));
        }
        return flows;
    }

    /** Balance-weighted average life in days of a DEPOSIT_DECAY profile (volatile share counted as one day). */
    public static int averageLifeDays(Map<String, BigDecimal> p) {
        LocalDate origin = LocalDate.of(2000, 1, 1);
        List<Flow> flows = decay(origin, BigDecimal.ONE, p);
        BigDecimal weighted = BigDecimal.ZERO;
        for (Flow f : flows) {
            weighted = weighted.add(f.amount().multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(origin, f.date())), MC), MC);
        }
        return Math.max(1, weighted.setScale(0, java.math.RoundingMode.HALF_EVEN).intValue());
    }
}
