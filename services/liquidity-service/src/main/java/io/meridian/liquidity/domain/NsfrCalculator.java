package io.meridian.liquidity.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Basel III Net Stable Funding Ratio (BCBS 295): available stable funding (liabilities and capital weighted by ASF
 * factors) over required stable funding (assets and off-balance exposures weighted by RSF factors). A line may
 * override the standard factor of its code.
 */
public final class NsfrCalculator {
    public enum Side { ASF, RSF }

    public static final Map<String, BigDecimal> ASF_FACTORS = LcrCalculator.ordered(
            "REGULATORY_CAPITAL", "1.00", "LONG_TERM_LIABILITIES", "1.00", "RETAIL_STABLE", "0.95",
            "RETAIL_LESS_STABLE", "0.90", "WHOLESALE_OPERATIONAL", "0.50", "WHOLESALE_NONFINANCIAL_SHORT", "0.50",
            "FINANCIAL_SHORT", "0.00", "OTHER_LIABILITIES", "0.00");

    public static final Map<String, BigDecimal> RSF_FACTORS = LcrCalculator.ordered(
            "CASH_CENTRAL_BANK", "0.00", "L1_UNENCUMBERED", "0.05", "L2A", "0.15", "L2B", "0.50",
            "LOANS_FI_SHORT", "0.10", "LOANS_SHORT", "0.50", "RESIDENTIAL_MORTGAGES_LOW_RW", "0.65",
            "LOANS_LONG", "0.85", "NON_PERFORMING", "1.00", "FIXED_ASSETS", "1.00", "OTHER_ASSETS", "1.00",
            "OFF_BALANCE_COMMITTED", "0.05");

    /** {@code side} may be null (derived from the code). */
    public record Line(String line, String code, Side side, BigDecimal amount, BigDecimal factor) {}

    public record Component(String line, String code, Side side, BigDecimal amount, BigDecimal factor,
                            BigDecimal weighted) {}

    public record Result(BigDecimal asf, BigDecimal rsf, BigDecimal ratio, boolean breach, List<Component> components) {}

    private NsfrCalculator() {}

    public static Side sideOf(String code) {
        if (ASF_FACTORS.containsKey(code)) {
            return Side.ASF;
        }
        return RSF_FACTORS.containsKey(code) ? Side.RSF : null;
    }

    public static Result calculate(List<Line> lines, BigDecimal minimum) {
        if (lines.isEmpty()) {
            throw new DomainException("positions_required", "at least one balance-sheet line is required");
        }
        BigDecimal asf = BigDecimal.ZERO;
        BigDecimal rsf = BigDecimal.ZERO;
        List<Component> components = new ArrayList<>();
        for (Line l : lines) {
            Side side = sideOf(l.code());
            if (side == null) {
                throw new DomainException("unknown_code", "line " + l.line() + ": code " + l.code()
                        + " is not a standard ASF or RSF code");
            }
            if (l.side() != null && l.side() != side) {
                throw new DomainException("side_mismatch", "line " + l.line() + ": code " + l.code() + " is " + side);
            }
            if (l.amount() == null || l.amount().signum() < 0) {
                throw new DomainException("invalid_amount", "line " + l.line() + ": amount must be zero or positive");
            }
            if (l.factor() != null && !Amounts.isFactor(l.factor())) {
                throw new DomainException("invalid_factor", "line " + l.line() + ": factor must be between 0 and 1");
            }
            BigDecimal factor = l.factor() != null ? l.factor()
                    : side == Side.ASF ? ASF_FACTORS.get(l.code()) : RSF_FACTORS.get(l.code());
            BigDecimal weighted = l.amount().multiply(factor, Amounts.MC);
            components.add(new Component(l.line(), l.code(), side, l.amount(), factor, Amounts.money(weighted)));
            if (side == Side.ASF) {
                asf = asf.add(weighted);
            } else {
                rsf = rsf.add(weighted);
            }
        }
        BigDecimal a = Amounts.money(asf);
        BigDecimal r = Amounts.money(rsf);
        if (r.signum() <= 0) {
            throw new DomainException("no_required_funding", "required stable funding is zero; NSFR is undefined");
        }
        BigDecimal ratio = Amounts.ratio(a, r);
        BigDecimal min = minimum == null ? BigDecimal.ONE : minimum;
        return new Result(a, r, ratio, ratio.compareTo(min) < 0, components);
    }
}
