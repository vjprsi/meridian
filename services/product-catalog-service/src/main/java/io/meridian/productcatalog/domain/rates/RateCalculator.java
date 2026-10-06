package io.meridian.productcatalog.domain.rates;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Effective annual interest rate of an interest-rate configuration for a balance on a date.
 * <ul>
 *   <li>FIXED: the fixed rate; ZERO: 0</li>
 *   <li>TIERED: the whole balance earns the rate of the tier containing it ([from, to))</li>
 *   <li>BANDED: each slice of the balance earns its band's rate; the effective rate is the blended rate</li>
 *   <li>STEPPED: the rate of the step containing the day number since the configuration's start</li>
 *   <li>FLOATING: index value (latest at the date) + spread (tier spread when tiers exist)</li>
 * </ul>
 * Floor and cap apply last. Balances below {@code min_balance_for_interest} earn nothing.
 */
public final class RateCalculator {
    public static final int RATE_SCALE = 10;
    private static final MathContext MC = MathContext.DECIMAL128;

    private RateCalculator() {}

    public record Tier(int tierNo, BigDecimal fromAmount, BigDecimal toAmount, Integer fromDay, Integer toDay,
                       BigDecimal rate, BigDecimal spread) {
        boolean containsAmount(BigDecimal b) {
            return b.compareTo(fromAmount) >= 0 && (toAmount == null || b.compareTo(toAmount) < 0);
        }

        boolean containsDay(long day) {
            return (fromDay == null || day >= fromDay) && (toDay == null || day <= toDay);
        }
    }

    public record Config(String rateType, BigDecimal fixedRate, BigDecimal spread, BigDecimal floorRate,
                         BigDecimal capRate, BigDecimal minBalanceForInterest, DayCount dayCount, RoundingMode rounding,
                         int roundingScale, LocalDate effectiveFrom, LocalDate effectiveTo, List<Tier> tiers) {
        public Config {
            tiers = tiers == null ? List.of() : tiers.stream().sorted(Comparator.comparing(Tier::fromAmount)
                    .thenComparingInt(Tier::tierNo)).toList();
            minBalanceForInterest = minBalanceForInterest == null ? BigDecimal.ZERO : minBalanceForInterest;
            dayCount = dayCount == null ? DayCount.ACT_365 : dayCount;
            rounding = rounding == null ? RoundingMode.HALF_EVEN : rounding;
        }

        public boolean isFloating() {
            return "FLOATING".equals(rateType);
        }
    }

    public record Band(int tierNo, BigDecimal from, BigDecimal to, BigDecimal portion, BigDecimal rate,
                       BigDecimal annualInterest) {}

    public record Result(BigDecimal effectiveRate, BigDecimal baseRate, BigDecimal indexRate, BigDecimal spread,
                         boolean floorApplied, boolean capApplied, boolean belowMinimumBalance, Integer appliedTier,
                         List<Band> bands, BigDecimal annualInterest, BigDecimal dailyInterest) {}

    /** Maps the RoundingRule enum to a JDK rounding mode. */
    public static RoundingMode rounding(String rule) {
        if (rule == null) {
            return RoundingMode.HALF_EVEN;
        }
        return switch (rule) {
            case "HALF_UP" -> RoundingMode.HALF_UP;
            case "HALF_DOWN" -> RoundingMode.HALF_DOWN;
            case "UP" -> RoundingMode.UP;
            case "DOWN" -> RoundingMode.DOWN;
            default -> RoundingMode.HALF_EVEN;
        };
    }

    /**
     * @param indexRate the benchmark value applicable at {@code asOf} (required for FLOATING), else ignored
     */
    public static Result compute(Config c, BigDecimal balance, LocalDate asOf, BigDecimal indexRate) {
        if (balance == null) {
            throw ApiException.unprocessable("balance_required", "balance is required");
        }
        if (c.effectiveFrom() != null && asOf.isBefore(c.effectiveFrom())
                || c.effectiveTo() != null && asOf.isAfter(c.effectiveTo())) {
            throw ApiException.unprocessable("rate_not_effective",
                    "The rate configuration is not effective on " + asOf + " (" + c.effectiveFrom() + " .. "
                            + (c.effectiveTo() == null ? "open" : c.effectiveTo()) + ")");
        }
        BigDecimal b = balance.abs();
        if (b.compareTo(c.minBalanceForInterest()) < 0) {
            return new Result(BigDecimal.ZERO.setScale(RATE_SCALE), BigDecimal.ZERO, indexRate, null, false, false, true,
                    null, List.of(), BigDecimal.ZERO.setScale(c.roundingScale()), BigDecimal.ZERO.setScale(c.roundingScale()));
        }
        BigDecimal base;
        BigDecimal spread = null;
        Integer appliedTier = null;
        List<Band> bands = new ArrayList<>();
        BigDecimal bandedInterest = null;
        switch (c.rateType()) {
            case "ZERO" -> base = BigDecimal.ZERO;
            case "FIXED" -> base = require(c.fixedRate(), "fixed_rate", "FIXED");
            case "TIERED" -> {
                Tier t = tierFor(c, b);
                appliedTier = t.tierNo();
                base = t.rate();
            }
            case "BANDED" -> {
                requireTiers(c);
                BigDecimal interest = BigDecimal.ZERO;
                for (Tier t : c.tiers()) {
                    BigDecimal top = t.toAmount() == null ? b : b.min(t.toAmount());
                    BigDecimal portion = top.subtract(t.fromAmount()).max(BigDecimal.ZERO);
                    if (portion.signum() == 0) {
                        continue;
                    }
                    BigDecimal bandInterest = portion.multiply(t.rate(), MC);
                    interest = interest.add(bandInterest, MC);
                    bands.add(new Band(t.tierNo(), t.fromAmount(), t.toAmount(), portion, t.rate(),
                            bandInterest.setScale(c.roundingScale(), c.rounding())));
                }
                bandedInterest = interest;
                base = b.signum() == 0 ? c.tiers().getFirst().rate() : interest.divide(b, MC);
            }
            case "STEPPED" -> {
                requireTiers(c);
                LocalDate start = c.effectiveFrom() == null ? asOf : c.effectiveFrom();
                long day = ChronoUnit.DAYS.between(start, asOf) + 1;
                Tier t = c.tiers().stream().filter(x -> x.containsDay(day)).findFirst()
                        .orElseThrow(() -> ApiException.unprocessable("no_matching_step", "No rate step covers day " + day));
                appliedTier = t.tierNo();
                base = t.rate();
            }
            case "FLOATING" -> {
                if (indexRate == null) {
                    throw ApiException.unprocessable("index_value_missing",
                            "No published index value is available on or before " + asOf);
                }
                spread = c.spread() == null ? BigDecimal.ZERO : c.spread();
                if (!c.tiers().isEmpty()) {
                    Tier t = tierFor(c, b);
                    appliedTier = t.tierNo();
                    if (t.spread() != null) {
                        spread = t.spread();
                    }
                }
                base = indexRate.add(spread, MC);
            }
            default -> throw ApiException.unprocessable("unsupported_rate_type", "Unsupported rate type " + c.rateType());
        }
        BigDecimal effective = base;
        boolean floor = false;
        boolean cap = false;
        if (c.floorRate() != null && effective.compareTo(c.floorRate()) < 0) {
            effective = c.floorRate();
            floor = true;
        }
        if (c.capRate() != null && effective.compareTo(c.capRate()) > 0) {
            effective = c.capRate();
            cap = true;
        }
        BigDecimal annualRaw = (bandedInterest != null && !floor && !cap) ? bandedInterest : b.multiply(effective, MC);
        BigDecimal daily = annualRaw.divide(BigDecimal.valueOf(c.dayCount().daysInYear(asOf)), MC);
        return new Result(effective.setScale(RATE_SCALE, RoundingMode.HALF_EVEN), base.setScale(RATE_SCALE, RoundingMode.HALF_EVEN),
                indexRate, spread, floor, cap, false, appliedTier, List.copyOf(bands),
                annualRaw.setScale(c.roundingScale(), c.rounding()), daily.setScale(c.roundingScale(), c.rounding()));
    }

    /**
     * Interest accrued on a constant balance for [start, end) using the configuration's day count, unrounded.
     */
    public static BigDecimal accrue(BigDecimal balance, BigDecimal annualRate, DayCount dayCount, LocalDate start, LocalDate end) {
        return balance.multiply(annualRate, MC).multiply(dayCount.yearFraction(start, end), MC);
    }

    private static Tier tierFor(Config c, BigDecimal b) {
        requireTiers(c);
        return c.tiers().stream().filter(t -> t.containsAmount(b)).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("no_matching_tier", "No tier covers balance " + b.toPlainString()));
    }

    private static void requireTiers(Config c) {
        if (c.tiers().isEmpty()) {
            throw ApiException.unprocessable("tiers_required", c.rateType() + " rates need at least one tier");
        }
    }

    private static BigDecimal require(BigDecimal v, String field, String type) {
        if (v == null) {
            throw ApiException.unprocessable("incomplete_rate_config", type + " rates need " + field);
        }
        return v;
    }
}
