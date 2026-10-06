package io.meridian.liquidity.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Basel III Liquidity Coverage Ratio (BCBS 238). HQLA lines are weighted by (1 - haircut), outflows by run-off rates
 * and inflows by inflow rates; a line may override the standard factor. Level 2 caps follow Annex 1 (without the
 * unwinding of secured transactions): L2B at most 15% and L2 at most 40% of total HQLA. Inflows count up to 75% of
 * outflows.
 */
public final class LcrCalculator {
    public enum Category { HQLA, OUTFLOW, INFLOW }

    /** HQLA weights (1 - haircut) by level code. */
    public static final Map<String, BigDecimal> HQLA_WEIGHTS = ordered(
            "L1", "1.00", "L2A", "0.85", "L2B_RMBS", "0.75", "L2B", "0.50");

    /** Standard 30-day outflow run-off rates. */
    public static final Map<String, BigDecimal> OUTFLOW_RATES = ordered(
            "RETAIL_STABLE", "0.05", "RETAIL_LESS_STABLE", "0.10", "OPERATIONAL_DEPOSITS", "0.25",
            "NON_OPERATIONAL_CORPORATE", "0.40", "NON_OPERATIONAL_CORPORATE_INSURED", "0.20",
            "FINANCIAL_INSTITUTION", "1.00", "SECURED_FUNDING_L1", "0.00", "SECURED_FUNDING_L2A", "0.15",
            "SECURED_FUNDING_L2B", "0.50", "SECURED_FUNDING_OTHER", "1.00", "CREDIT_FACILITY_RETAIL", "0.05",
            "CREDIT_FACILITY_CORPORATE", "0.10", "LIQUIDITY_FACILITY_CORPORATE", "0.30", "FACILITY_FINANCIAL", "0.40",
            "DERIVATIVE_OUTFLOWS", "1.00", "OTHER_CONTRACTUAL", "1.00");

    /** Standard 30-day inflow rates. */
    public static final Map<String, BigDecimal> INFLOW_RATES = ordered(
            "RETAIL_LOANS", "0.50", "CORPORATE_LOANS", "0.50", "FINANCIAL_INSTITUTION_LOANS", "1.00",
            "REVERSE_REPO_L1", "0.00", "REVERSE_REPO_L2A", "0.15", "REVERSE_REPO_L2B", "0.50",
            "REVERSE_REPO_OTHER", "1.00", "DERIVATIVE_INFLOWS", "1.00", "OPERATIONAL_DEPOSITS_HELD", "0.00");

    static final BigDecimal INFLOW_CAP = new BigDecimal("0.75");
    static final BigDecimal L2B_CAP_OF_L1_L2A = new BigDecimal("15").divide(new BigDecimal("85"), Amounts.MC);
    static final BigDecimal L2B_CAP_OF_L1 = new BigDecimal("15").divide(new BigDecimal("60"), Amounts.MC);
    static final BigDecimal L2_CAP_OF_L1 = new BigDecimal("2").divide(new BigDecimal("3"), Amounts.MC);

    /** One input line; {@code factor} (0..1) overrides the standard weight / run-off / inflow rate of the code. */
    public record Line(String line, String code, Category category, BigDecimal amount, BigDecimal factor) {}

    public record Component(String line, String code, Category category, BigDecimal amount, BigDecimal factor,
                            BigDecimal weighted) {}

    public record Result(BigDecimal level1, BigDecimal level2a, BigDecimal level2b, BigDecimal capAdjustment,
                         BigDecimal totalHqla, BigDecimal outflows, BigDecimal uncappedInflows, BigDecimal inflows,
                         BigDecimal netOutflows, BigDecimal ratio, boolean breach, List<Component> components) {}

    private LcrCalculator() {}

    /** Standard factor of a code within a category, or null when the code is not known there. */
    public static BigDecimal standardFactor(Category category, String code) {
        return switch (category) {
            case HQLA -> HQLA_WEIGHTS.get(code);
            case OUTFLOW -> OUTFLOW_RATES.get(code);
            case INFLOW -> INFLOW_RATES.get(code);
        };
    }

    /** Category of a code (codes are unique across the three tables), or null. */
    public static Category categoryOf(String code) {
        if (HQLA_WEIGHTS.containsKey(code)) {
            return Category.HQLA;
        }
        if (OUTFLOW_RATES.containsKey(code)) {
            return Category.OUTFLOW;
        }
        return INFLOW_RATES.containsKey(code) ? Category.INFLOW : null;
    }

    public static Result calculate(List<Line> lines, BigDecimal minimum) {
        if (lines.isEmpty()) {
            throw new DomainException("positions_required", "at least one position line is required");
        }
        BigDecimal l1 = BigDecimal.ZERO;
        BigDecimal l2a = BigDecimal.ZERO;
        BigDecimal l2b = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        BigDecimal in = BigDecimal.ZERO;
        List<Component> components = new ArrayList<>();
        for (Line l : lines) {
            BigDecimal standard = standardFactor(l.category(), l.code());
            if (standard == null) {
                throw new DomainException("unknown_code",
                        "line " + l.line() + ": code " + l.code() + " is not a standard " + l.category() + " code");
            }
            if (l.amount() == null || l.amount().signum() < 0) {
                throw new DomainException("invalid_amount", "line " + l.line() + ": amount must be zero or positive");
            }
            if (l.factor() != null && !Amounts.isFactor(l.factor())) {
                throw new DomainException("invalid_factor", "line " + l.line() + ": factor must be between 0 and 1");
            }
            BigDecimal factor = l.factor() == null ? standard : l.factor();
            BigDecimal weighted = l.amount().multiply(factor, Amounts.MC);
            components.add(new Component(l.line(), l.code(), l.category(), l.amount(), factor, Amounts.money(weighted)));
            switch (l.category()) {
                case HQLA -> {
                    switch (l.code()) {
                        case "L1" -> l1 = l1.add(weighted);
                        case "L2A" -> l2a = l2a.add(weighted);
                        default -> l2b = l2b.add(weighted);
                    }
                }
                case OUTFLOW -> out = out.add(weighted);
                case INFLOW -> in = in.add(weighted);
            }
        }
        BigDecimal adj15 = Amounts.max0(l2b.subtract(L2B_CAP_OF_L1_L2A.multiply(l1.add(l2a), Amounts.MC))
                .max(l2b.subtract(L2B_CAP_OF_L1.multiply(l1, Amounts.MC))));
        BigDecimal adj40 = Amounts.max0(l2a.add(l2b).subtract(adj15).subtract(L2_CAP_OF_L1.multiply(l1, Amounts.MC)));
        BigDecimal capAdjustment = Amounts.money(adj15.add(adj40));
        BigDecimal total = Amounts.money(l1.add(l2a).add(l2b).subtract(adj15).subtract(adj40));
        BigDecimal outflows = Amounts.money(out);
        BigDecimal uncapped = Amounts.money(in);
        BigDecimal inflows = Amounts.money(uncapped.min(outflows.multiply(INFLOW_CAP, Amounts.MC)));
        BigDecimal net = outflows.subtract(inflows);
        if (net.signum() <= 0) {
            throw new DomainException("no_net_outflows", "weighted outflows are zero; LCR is undefined");
        }
        BigDecimal ratio = Amounts.ratio(total, net);
        BigDecimal min = minimum == null ? BigDecimal.ONE : minimum;
        return new Result(Amounts.money(l1), Amounts.money(l2a), Amounts.money(l2b), capAdjustment, total, outflows,
                uncapped, inflows, net, ratio, ratio.compareTo(min) < 0, components);
    }

    static Map<String, BigDecimal> ordered(String... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], new BigDecimal(kv[i + 1]));
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
