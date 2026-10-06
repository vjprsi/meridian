package io.meridian.collateral.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Loan-to-value of an exposure: outstanding exposure divided by the lending value allocated to it by all active
 * collateral links securing it. Status: OK, WARNING (at or above {@link #WARNING_SHARE} of the limit) or BREACH
 * (above the limit). Pure and unit-testable.
 */
public final class LtvCalculator {
    /** Share of max_ltv at which a link moves to WARNING. */
    public static final BigDecimal WARNING_SHARE = new BigDecimal("0.90");
    /** LTV reported when an exposure is outstanding but no value secures it (fits numeric(18,10)). */
    public static final BigDecimal UNSECURED_LTV = new BigDecimal("99999999");
    static final int SCALE = 6;

    private LtvCalculator() {}

    public enum Status { OK, WARNING, BREACH }

    public record Result(BigDecimal exposure, BigDecimal securedValue, BigDecimal ltv, Status status) {}

    /** LTV of an exposure against the sum of allocated values; {@code maxLtv} null means no limit (always OK). */
    public static Result calculate(BigDecimal exposure, Collection<BigDecimal> allocatedValues, BigDecimal maxLtv) {
        BigDecimal secured = allocatedValues.stream().filter(v -> v != null && v.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return calculate(exposure, secured, maxLtv);
    }

    public static Result calculate(BigDecimal exposure, BigDecimal securedValue, BigDecimal maxLtv) {
        BigDecimal e = exposure == null || exposure.signum() < 0 ? BigDecimal.ZERO : exposure;
        BigDecimal s = securedValue == null || securedValue.signum() < 0 ? BigDecimal.ZERO : securedValue;
        BigDecimal ltv;
        if (e.signum() == 0) {
            ltv = BigDecimal.ZERO.setScale(SCALE);
        } else if (s.signum() == 0) {
            ltv = UNSECURED_LTV.setScale(SCALE);
        } else {
            ltv = e.divide(s, MathContext.DECIMAL128).setScale(SCALE, RoundingMode.HALF_UP).min(UNSECURED_LTV.setScale(SCALE));
        }
        return new Result(e, s, ltv, status(ltv, maxLtv));
    }

    public static Status status(BigDecimal ltv, BigDecimal maxLtv) {
        if (maxLtv == null || maxLtv.signum() <= 0) {
            return Status.OK;
        }
        if (ltv.compareTo(maxLtv) > 0) {
            return Status.BREACH;
        }
        if (ltv.compareTo(maxLtv.multiply(WARNING_SHARE)) >= 0) {
            return Status.WARNING;
        }
        return Status.OK;
    }

    /** True when {@code next} is worse than {@code previous} (null previous counts as OK). */
    public static boolean worsened(Status previous, Status next) {
        return next.ordinal() > (previous == null ? Status.OK : previous).ordinal();
    }
}
