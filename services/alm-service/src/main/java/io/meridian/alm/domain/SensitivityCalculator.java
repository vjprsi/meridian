package io.meridian.alm.domain;

import io.meridian.alm.domain.IrrbbShocks.Params;
import io.meridian.alm.domain.IrrbbShocks.ShockType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * NII and EVE sensitivity of a set of positions under an IRRBB shock.
 *
 * <p>EVE: every position is turned into cash flows (interest every payment_frequency_months at its rate, ACT/365,
 * principal at its repricing date for floating positions or maturity for fixed ones) discounted with
 * DF = exp(-r(t) t) on the base curve, and on the shocked (floored) curve. EVE = PV(assets) - PV(liabilities).
 *
 * <p>NII over a horizon H (years), constant balance sheet: base = sum(side * amount * rate * H); positions repricing
 * at t &lt; H earn the shock from t to H: shocked = base + sum(side * amount * shock(t) * pass_through * (H - t)).
 */
public final class SensitivityCalculator {
    static final MathContext MC = MathContext.DECIMAL128;
    static final BigDecimal DAYS = BigDecimal.valueOf(365);

    private SensitivityCalculator() {}

    public record CashFlow(LocalDate date, BigDecimal amount) {}

    public record PositionResult(String code, BigDecimal base, BigDecimal shocked, BigDecimal delta) {}

    public record Result(BigDecimal base, BigDecimal shocked, BigDecimal delta, List<PositionResult> positions) {}

    /** Pass-through of market rate changes per position (1 = full); supplied by the behavioural assumptions. */
    public interface PassThrough extends Function<Position, BigDecimal> {}

    /** Date at which the position's principal reprices or repays; non-maturity items without dates: next day. */
    static LocalDate end(LocalDate asOf, Position p) {
        LocalDate d = p.repricesOn();
        if (d == null) {
            if (!p.nonMaturity()) {
                throw new IllegalArgumentException("position " + p.code()
                        + " needs repricing_date or maturity_date (or non_maturity)");
            }
            return asOf.plusDays(1);
        }
        return d.isAfter(asOf) ? d : asOf.plusDays(1);
    }

    /** Contractual cash flows (positive amounts) until repricing / maturity. */
    public static List<CashFlow> cashFlows(LocalDate asOf, Position p) {
        LocalDate end = end(asOf, p);
        List<CashFlow> flows = new ArrayList<>();
        BigDecimal amount = p.amount().abs();
        LocalDate prev = asOf;
        int k = 1;
        while (true) {
            LocalDate next = asOf.plusMonths((long) k * p.paymentFrequencyMonths());
            if (!next.isBefore(end)) {
                break;
            }
            flows.add(new CashFlow(next, interest(amount, p.rate(), prev, next)));
            prev = next;
            k++;
        }
        flows.add(new CashFlow(end, interest(amount, p.rate(), prev, end).add(amount)));
        return flows;
    }

    static BigDecimal interest(BigDecimal amount, BigDecimal rate, LocalDate from, LocalDate to) {
        return amount.multiply(rate, MC).multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to)), MC).divide(DAYS, MC);
    }

    static double years(LocalDate asOf, LocalDate d) {
        return ChronoUnit.DAYS.between(asOf, d) / 365.0;
    }

    /** Present value of cash flows with continuous discounting on rate(t_days). */
    public static BigDecimal pv(LocalDate asOf, List<CashFlow> flows, Function<Long, BigDecimal> rateAtDays) {
        BigDecimal total = BigDecimal.ZERO;
        for (CashFlow f : flows) {
            long days = ChronoUnit.DAYS.between(asOf, f.date());
            double t = days / 365.0;
            double r = rateAtDays.apply(days).doubleValue();
            total = total.add(f.amount().multiply(BigDecimal.valueOf(Math.exp(-r * t)), MC), MC);
        }
        return total;
    }

    public static Result eve(LocalDate asOf, List<Position> positions, Curve base, ShockType shock, Params params) {
        Function<Long, BigDecimal> baseRate = d -> base.rate(BigDecimal.valueOf(d));
        Function<Long, BigDecimal> shockedRate = d -> {
            double t = d / 365.0;
            return IrrbbShocks.shocked(base.rate(BigDecimal.valueOf(d)), IrrbbShocks.delta(shock, t, params), t);
        };
        List<PositionResult> out = new ArrayList<>();
        BigDecimal b = BigDecimal.ZERO;
        BigDecimal s = BigDecimal.ZERO;
        for (Position p : positions) {
            List<CashFlow> flows = cashFlows(asOf, p);
            int sign = p.side() == Position.Side.LIABILITY ? -1 : (p.amount().signum() < 0 ? -1 : 1);
            BigDecimal pb = pv(asOf, flows, baseRate).multiply(BigDecimal.valueOf(sign));
            BigDecimal ps = pv(asOf, flows, shockedRate).multiply(BigDecimal.valueOf(sign));
            b = b.add(pb, MC);
            s = s.add(ps, MC);
            out.add(new PositionResult(p.code(), money(pb), money(ps), money(ps).subtract(money(pb))));
        }
        return new Result(money(b), money(s), money(s).subtract(money(b)), out);
    }

    public static Result nii(LocalDate asOf, List<Position> positions, int horizonMonths, ShockType shock, Params params,
                             PassThrough passThrough) {
        double h = horizonMonths / 12.0;
        BigDecimal hb = BigDecimal.valueOf(horizonMonths).divide(BigDecimal.valueOf(12), MC);
        List<PositionResult> out = new ArrayList<>();
        BigDecimal b = BigDecimal.ZERO;
        BigDecimal s = BigDecimal.ZERO;
        for (Position p : positions) {
            BigDecimal signed = p.signedAmount();
            BigDecimal base = signed.multiply(p.rate(), MC).multiply(hb, MC);
            LocalDate d = p.repricesOn();
            double tr = d == null ? (p.nonMaturity() ? 0.0 : Double.NaN) : Math.max(0.0, years(asOf, d));
            if (Double.isNaN(tr)) {
                throw new IllegalArgumentException("position " + p.code()
                        + " needs repricing_date or maturity_date (or non_maturity)");
            }
            BigDecimal shocked = base;
            if (tr < h) {
                BigDecimal pt = passThrough == null ? BigDecimal.ONE : passThrough.apply(p);
                BigDecimal change = signed.multiply(IrrbbShocks.delta(shock, tr, params), MC).multiply(pt, MC)
                        .multiply(BigDecimal.valueOf(h - tr), MC);
                shocked = base.add(change, MC);
            }
            b = b.add(base, MC);
            s = s.add(shocked, MC);
            out.add(new PositionResult(p.code(), money(base), money(shocked), money(shocked).subtract(money(base))));
        }
        return new Result(money(b), money(s), money(s).subtract(money(b)), out);
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN);
    }
}
