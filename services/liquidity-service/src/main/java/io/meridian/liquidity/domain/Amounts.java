package io.meridian.liquidity.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Decimal conventions: money at 2 decimals and ratios at 10 decimals, HALF_EVEN; intermediate maths in DECIMAL128. */
public final class Amounts {
    public static final MathContext MC = MathContext.DECIMAL128;
    public static final int MONEY_SCALE = 2;
    public static final int RATE_SCALE = 10;

    private Amounts() {}

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal rate(BigDecimal v) {
        return v.setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    /** a / b as a ratio (10 decimals, HALF_EVEN). */
    public static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return a.divide(b, RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal max0(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    public static boolean isFactor(BigDecimal f) {
        return f != null && f.signum() >= 0 && f.compareTo(BigDecimal.ONE) <= 0;
    }
}
