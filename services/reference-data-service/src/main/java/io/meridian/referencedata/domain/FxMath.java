package io.meridian.referencedata.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** FX arithmetic: rates are stored at scale 10 (numeric(18,10)); conversions round to the target minor units. */
public final class FxMath {
    public static final int RATE_SCALE = 10;
    /** Relative tolerance between rate x inverse_rate and 1 caused by rounding both to 10 decimals. */
    private static final BigDecimal INVERSE_TOLERANCE = new BigDecimal("0.00001");

    private FxMath() {}

    public static BigDecimal inverse(BigDecimal rate) {
        requirePositive(rate, "rate");
        return BigDecimal.ONE.divide(rate, MathContext.DECIMAL128).setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal cross(BigDecimal first, BigDecimal second) {
        return first.multiply(second, MathContext.DECIMAL128);
    }

    public static BigDecimal display(BigDecimal rate) {
        return rate.setScale(RATE_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    /** amount x rate rounded HALF_EVEN to the target currency's minor units. */
    public static BigDecimal convert(BigDecimal amount, BigDecimal rate, int minorUnits) {
        return amount.multiply(rate, MathContext.DECIMAL128).setScale(minorUnits, RoundingMode.HALF_EVEN);
    }

    public static void requirePositive(BigDecimal v, String field) {
        if (v == null || v.signum() <= 0) {
            throw ApiException.unprocessable("invalid_rate", field + " must be positive");
        }
    }

    /** True when inverse is (within rounding) 1 / rate. */
    public static boolean consistent(BigDecimal rate, BigDecimal inverse) {
        BigDecimal product = rate.multiply(inverse, MathContext.DECIMAL128);
        return product.subtract(BigDecimal.ONE).abs().compareTo(INVERSE_TOLERANCE) <= 0;
    }
}
