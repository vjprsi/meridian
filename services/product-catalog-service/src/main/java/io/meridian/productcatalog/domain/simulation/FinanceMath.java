package io.meridian.productcatalog.domain.simulation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/** Annuities, effective rates and IRR-based APR in BigDecimal arithmetic. */
public final class FinanceMath {
    static final MathContext MC = MathContext.DECIMAL128;

    private FinanceMath() {}

    /** Level payment that amortises {@code principal} over {@code n} periods at {@code periodRate}. Unrounded. */
    public static BigDecimal annuityPayment(BigDecimal principal, BigDecimal periodRate, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        if (periodRate.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(n), MC);
        }
        BigDecimal growth = BigDecimal.ONE.add(periodRate, MC).pow(n, MC);
        return principal.multiply(periodRate, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE, MC), MC);
    }

    /** Annual equivalent rate of a nominal rate compounded {@code periodsPerYear} times. */
    public static BigDecimal aer(BigDecimal nominal, int periodsPerYear) {
        if (periodsPerYear <= 0) {
            return nominal;
        }
        return BigDecimal.ONE.add(nominal.divide(BigDecimal.valueOf(periodsPerYear), MC), MC)
                .pow(periodsPerYear, MC).subtract(BigDecimal.ONE, MC);
    }

    /**
     * Periodic internal rate of return of cash flows (index 0 = today), found by bisection on [-0.99, 10].
     * Returns null when the flows do not change sign.
     */
    public static BigDecimal irr(List<BigDecimal> flows) {
        boolean pos = flows.stream().anyMatch(f -> f.signum() > 0);
        boolean neg = flows.stream().anyMatch(f -> f.signum() < 0);
        if (!pos || !neg) {
            return null;
        }
        BigDecimal lo = new BigDecimal("-0.99");
        BigDecimal hi = BigDecimal.TEN;
        BigDecimal fLo = npv(flows, lo);
        for (int i = 0; i < 200; i++) {
            BigDecimal mid = lo.add(hi, MC).divide(BigDecimal.TWO, MC);
            BigDecimal fMid = npv(flows, mid);
            if (fMid.signum() == 0 || hi.subtract(lo, MC).abs().compareTo(new BigDecimal("1E-14")) < 0) {
                return mid;
            }
            if (fMid.signum() == fLo.signum()) {
                lo = mid;
                fLo = fMid;
            } else {
                hi = mid;
            }
        }
        return lo.add(hi, MC).divide(BigDecimal.TWO, MC);
    }

    static BigDecimal npv(List<BigDecimal> flows, BigDecimal rate) {
        BigDecimal factor = BigDecimal.ONE.add(rate, MC);
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ONE;
        for (BigDecimal f : flows) {
            total = total.add(f.divide(discount, MC), MC);
            discount = discount.multiply(factor, MC);
        }
        return total;
    }

    /** APR (nominal annual, x12) of a monthly loan: amount received today vs monthly outgoings. */
    public static BigDecimal monthlyApr(BigDecimal netReceived, List<BigDecimal> monthlyPayments) {
        java.util.ArrayList<BigDecimal> flows = new java.util.ArrayList<>();
        flows.add(netReceived);
        monthlyPayments.forEach(p -> flows.add(p.negate()));
        BigDecimal m = irr(flows);
        return m == null ? null : m.multiply(BigDecimal.valueOf(12), MC).setScale(10, RoundingMode.HALF_EVEN);
    }
}
