package io.meridian.deposit.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Interest arithmetic for deposit accounts: daily accrual on an end-of-day balance with FIXED, TIERED (whole
 * balance at the rate of the band it falls in) or BANDED (each slice of the balance at its band's rate) structures,
 * account-level overrides, floors/caps and the usual day-count conventions. Accruals keep 8 decimals
 * (storage scale); rounding to the currency's minor unit happens only when interest is posted.
 */
public final class InterestCalculator {
    public static final int ACCRUAL_SCALE = 8;
    private static final MathContext MC = MathContext.DECIMAL128;

    private InterestCalculator() {}

    /** One band of a tiered/banded rate: applies from {@code from} (inclusive) up to {@code to} (exclusive). */
    public record Tier(BigDecimal from, BigDecimal to, BigDecimal rate) {
        public Tier {
            if (from == null) {
                from = BigDecimal.ZERO;
            }
            if (rate == null) {
                throw new IllegalArgumentException("tier rate is required");
            }
            if (to != null && to.compareTo(from) <= 0) {
                throw new IllegalArgumentException("tier upper bound must be above its lower bound");
            }
        }
    }

    public enum Structure { FIXED, TIERED, BANDED, ZERO }

    /** A rate definition taken from the product snapshot (catalog interest_rate_config shape). */
    public record RateConfig(String kind, Structure structure, BigDecimal fixedRate, List<Tier> tiers, DayCount dayCount,
                             BigDecimal minBalance, BigDecimal floor, BigDecimal cap) {
        public RateConfig {
            structure = structure == null ? Structure.FIXED : structure;
            tiers = tiers == null ? List.of() : tiers.stream().sorted(Comparator.comparing(Tier::from)).toList();
            dayCount = dayCount == null ? DayCount.ACT_365 : dayCount;
            minBalance = minBalance == null ? BigDecimal.ZERO : minBalance;
            if ((structure == Structure.TIERED || structure == Structure.BANDED) && tiers.isEmpty()) {
                throw new IllegalArgumentException(structure + " rate needs tiers");
            }
            if (structure == Structure.FIXED && fixedRate == null) {
                throw new IllegalArgumentException("FIXED rate needs fixed_rate");
            }
        }

        public static RateConfig fixed(String kind, BigDecimal rate, DayCount dayCount) {
            return new RateConfig(kind, Structure.FIXED, rate, List.of(), dayCount, null, null, null);
        }

        public static Structure structureOf(String rateType) {
            if (rateType == null) {
                return Structure.FIXED;
            }
            return switch (rateType.toUpperCase(Locale.ROOT)) {
                case "TIERED" -> Structure.TIERED;
                case "BANDED" -> Structure.BANDED;
                case "ZERO" -> Structure.ZERO;
                default -> Structure.FIXED; // FIXED, FLOATING/STEPPED resolved to a fixed rate by the snapshot
            };
        }

        /** Applies an account-level override: an absolute rate replaces the structure, an adjustment shifts every rate. */
        public RateConfig withOverride(BigDecimal overrideRate, BigDecimal adjustment) {
            if (overrideRate != null) {
                return new RateConfig(kind, Structure.FIXED, overrideRate, List.of(), dayCount, minBalance, floor, cap);
            }
            if (adjustment != null && adjustment.signum() != 0) {
                List<Tier> shifted = tiers.stream().map(t -> new Tier(t.from(), t.to(), t.rate().add(adjustment))).toList();
                BigDecimal fixed = fixedRate == null ? null : fixedRate.add(adjustment);
                return new RateConfig(kind, structure, fixed, shifted, dayCount, minBalance, floor, cap);
            }
            return this;
        }

        private BigDecimal clamp(BigDecimal rate) {
            BigDecimal r = rate;
            if (floor != null && r.compareTo(floor) < 0) {
                r = floor;
            }
            if (cap != null && r.compareTo(cap) > 0) {
                r = cap;
            }
            return r;
        }
    }

    /** The annual rate applicable to {@code balance} (blended effective rate for BANDED structures). */
    public static BigDecimal annualRate(RateConfig cfg, BigDecimal balance) {
        BigDecimal b = balance.abs();
        return switch (cfg.structure()) {
            case ZERO -> BigDecimal.ZERO;
            case FIXED -> cfg.clamp(cfg.fixedRate());
            case TIERED -> cfg.clamp(tierFor(cfg.tiers(), b).rate());
            case BANDED -> {
                if (b.signum() == 0) {
                    yield cfg.clamp(cfg.tiers().getFirst().rate());
                }
                yield bandedInterest(cfg, b, BigDecimal.ONE).divide(b, MC);
            }
        };
    }

    /**
     * Interest for one calendar day on an end-of-day balance, at storage scale (8 dp, HALF_EVEN).
     * Balances below the configured minimum earn nothing. The sign follows the balance magnitude: callers pick
     * the kind (credit / overdraft) and pass the absolute balance.
     */
    public static BigDecimal dailyInterest(RateConfig cfg, BigDecimal balance, LocalDate day) {
        return interest(cfg, balance, day, day.plusDays(1));
    }

    /** Simple (non-compounded) interest on a constant balance from {@code from} (incl.) to {@code to} (excl.). */
    public static BigDecimal interest(RateConfig cfg, BigDecimal balance, LocalDate from, LocalDate to) {
        BigDecimal b = balance.abs();
        if (b.signum() == 0 || b.compareTo(cfg.minBalance()) < 0 || !to.isAfter(from)) {
            return BigDecimal.ZERO.setScale(ACCRUAL_SCALE);
        }
        BigDecimal fraction = cfg.dayCount().yearFraction(from, to);
        BigDecimal raw = switch (cfg.structure()) {
            case ZERO -> BigDecimal.ZERO;
            case FIXED, TIERED -> b.multiply(annualRate(cfg, b), MC).multiply(fraction, MC);
            case BANDED -> bandedInterest(cfg, b, fraction);
        };
        return raw.setScale(ACCRUAL_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Rounds an amount to the currency's minor unit (e.g. 2 dp for EUR, 0 for JPY). */
    public static BigDecimal toMinorUnit(BigDecimal amount, String currency, RoundingMode mode) {
        return amount.setScale(Currencies.digits(currency), mode);
    }

    /** Day-by-day projection of interest on a constant balance (no compounding). */
    public static List<BigDecimal> project(RateConfig cfg, BigDecimal balance, LocalDate from, int days) {
        List<BigDecimal> out = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            out.add(dailyInterest(cfg, balance, from.plusDays(i)));
        }
        return out;
    }

    private static BigDecimal bandedInterest(RateConfig cfg, BigDecimal balance, BigDecimal fraction) {
        BigDecimal total = BigDecimal.ZERO;
        for (Tier t : cfg.tiers()) {
            if (balance.compareTo(t.from()) <= 0) {
                break;
            }
            BigDecimal upper = t.to() == null || balance.compareTo(t.to()) < 0 ? balance : t.to();
            BigDecimal slice = upper.subtract(t.from());
            total = total.add(slice.multiply(cfg.clamp(t.rate()), MC).multiply(fraction, MC), MC);
        }
        return total;
    }

    private static Tier tierFor(List<Tier> tiers, BigDecimal balance) {
        Tier match = tiers.getFirst();
        for (Tier t : tiers) {
            if (balance.compareTo(t.from()) >= 0 && (t.to() == null || balance.compareTo(t.to()) < 0)) {
                return t;
            }
            if (balance.compareTo(t.from()) >= 0) {
                match = t;
            }
        }
        return match;
    }
}
