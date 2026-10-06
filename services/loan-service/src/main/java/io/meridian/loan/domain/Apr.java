package io.meridian.loan.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Annual percentage rate as the internal rate of return of the borrower's cash flows on actual dates
 * (EU Consumer Credit Directive / XIRR form): find X with sum(CF_k / (1 + X)^(t_k)) = 0, t_k in years of 365 days
 * from the first flow. Newton-Raphson with a bisection fallback.
 */
public final class Apr {
    /** A dated cash flow from the lender's point of view: negative = paid to the borrower, positive = received. */
    public record Flow(LocalDate date, BigDecimal amount) {}

    private static final double TOLERANCE = 1e-12;
    private static final int MAX_ITER = 200;

    private Apr() {}

    /** Effective annual rate (fraction), rounded to 6 decimals; zero when the flows carry no cost. */
    public static BigDecimal of(List<Flow> flows) {
        return BigDecimal.valueOf(irr(flows)).setScale(6, RoundingMode.HALF_UP);
    }

    /** APR of a schedule: {@code netAdvance} paid out at {@code start}, then each instalment total at its due date. */
    public static BigDecimal ofSchedule(BigDecimal netAdvance, LocalDate start, List<ScheduleGenerator.Line> lines,
                                        BigDecimal finalExtra) {
        List<Flow> flows = new java.util.ArrayList<>();
        flows.add(new Flow(start, netAdvance.negate()));
        for (int i = 0; i < lines.size(); i++) {
            ScheduleGenerator.Line l = lines.get(i);
            BigDecimal amt = l.total();
            if (i == lines.size() - 1 && finalExtra != null) {
                amt = amt.add(finalExtra);
            }
            flows.add(new Flow(l.dueDate(), amt));
        }
        return of(flows);
    }

    static double irr(List<Flow> flows) {
        if (flows.size() < 2) {
            throw new IllegalArgumentException("at least two cash flows are needed");
        }
        LocalDate t0 = flows.getFirst().date();
        int n = flows.size();
        double[] t = new double[n];
        double[] c = new double[n];
        boolean pos = false;
        boolean neg = false;
        for (int i = 0; i < n; i++) {
            t[i] = ChronoUnit.DAYS.between(t0, flows.get(i).date()) / 365.0;
            c[i] = flows.get(i).amount().doubleValue();
            pos |= c[i] > 0;
            neg |= c[i] < 0;
        }
        if (!pos || !neg) {
            throw new IllegalArgumentException("cash flows need both signs");
        }
        double x = 0.1;
        for (int i = 0; i < MAX_ITER; i++) {
            double f = npv(c, t, x);
            double d = dnpv(c, t, x);
            if (Math.abs(f) < TOLERANCE) {
                return x;
            }
            if (d == 0 || Double.isNaN(d)) {
                break;
            }
            double next = x - f / d;
            if (next <= -0.999999 || Double.isNaN(next) || Double.isInfinite(next)) {
                break;
            }
            if (Math.abs(next - x) < TOLERANCE) {
                return next;
            }
            x = next;
        }
        return bisection(c, t);
    }

    private static double bisection(double[] c, double[] t) {
        double lo = -0.99;
        double hi = 10.0;
        double flo = npv(c, t, lo);
        double fhi = npv(c, t, hi);
        while (flo * fhi > 0 && hi < 1e6) {
            hi *= 2;
            fhi = npv(c, t, hi);
        }
        if (flo * fhi > 0) {
            throw new IllegalArgumentException("no rate solves the cash flows");
        }
        for (int i = 0; i < 500; i++) {
            double mid = (lo + hi) / 2;
            double fm = npv(c, t, mid);
            if (Math.abs(fm) < TOLERANCE || (hi - lo) / 2 < TOLERANCE) {
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

    private static double npv(double[] c, double[] t, double x) {
        double s = 0;
        for (int i = 0; i < c.length; i++) {
            s += c[i] / Math.pow(1 + x, t[i]);
        }
        return s;
    }

    private static double dnpv(double[] c, double[] t, double x) {
        double s = 0;
        for (int i = 0; i < c.length; i++) {
            s -= t[i] * c[i] / Math.pow(1 + x, t[i] + 1);
        }
        return s;
    }
}
