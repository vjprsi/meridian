package io.meridian.loanorigination.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Internal rate of return of dated cash flows, expressed as an effective annual rate: the rate {@code x} with
 * {@code sum(cf_i / (1 + x)^t_i) = 0}, where {@code t_i} is the time of the flow in years. Newton-Raphson from a
 * starting guess, with a bracketing bisection fallback when Newton leaves the domain or does not converge.
 * This is the APR definition of the EU Consumer Credit Directive (Annex I) and of UK CONC App 1.
 */
public final class Irr {
    private Irr() {}

    /** One cash flow: {@code years} after the drawdown; negative = paid to the borrower, positive = paid by them. */
    public record Flow(double years, double amount) {}

    private static final double TOLERANCE = 1e-12;
    private static final int MAX_NEWTON = 100;

    /** Effective annual IRR of the flows; fails when the flows do not change sign. */
    public static double annualRate(List<Flow> flows) {
        boolean pos = false;
        boolean neg = false;
        for (Flow f : flows) {
            pos |= f.amount() > 0;
            neg |= f.amount() < 0;
        }
        if (!pos || !neg) {
            throw new IllegalArgumentException("cash flows must contain both inflows and outflows");
        }
        double x = 0.1;
        for (int i = 0; i < MAX_NEWTON; i++) {
            double v = npv(flows, x);
            double d = derivative(flows, x);
            if (d == 0 || Double.isNaN(d)) {
                break;
            }
            double next = x - v / d;
            if (Double.isNaN(next) || next <= -0.999999) {
                break;
            }
            if (Math.abs(next - x) < TOLERANCE) {
                return next;
            }
            x = next;
        }
        return bisect(flows);
    }

    /** Annual rate rounded to 6 decimals (0.123456 = 12.3456%). */
    public static BigDecimal apr(List<Flow> flows) {
        return BigDecimal.valueOf(annualRate(flows)).setScale(6, RoundingMode.HALF_UP);
    }

    static double npv(List<Flow> flows, double x) {
        double s = 0;
        for (Flow f : flows) {
            s += f.amount() / Math.pow(1 + x, f.years());
        }
        return s;
    }

    private static double derivative(List<Flow> flows, double x) {
        double s = 0;
        for (Flow f : flows) {
            s += -f.years() * f.amount() / Math.pow(1 + x, f.years() + 1);
        }
        return s;
    }

    private static double bisect(List<Flow> flows) {
        double lo = -0.99;
        double hi = 10.0;
        double flo = npv(flows, lo);
        double fhi = npv(flows, hi);
        int expand = 0;
        while (flo * fhi > 0 && expand++ < 20) {
            hi *= 2;
            fhi = npv(flows, hi);
        }
        if (flo * fhi > 0) {
            throw new IllegalArgumentException("IRR not bracketed");
        }
        for (int i = 0; i < 500; i++) {
            double mid = (lo + hi) / 2;
            double fm = npv(flows, mid);
            if (Math.abs(fm) < 1e-10 || (hi - lo) / 2 < TOLERANCE) {
                return mid;
            }
            if (fm * flo < 0) {
                hi = mid;
            } else {
                lo = mid;
                flo = fm;
            }
        }
        return (lo + hi) / 2;
    }
}
