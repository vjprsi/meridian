package io.meridian.treasurydeal.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Fixed-income arithmetic on prices quoted in % of nominal: coupon schedule, accrued interest, dirty price,
 * settlement amount and simple yield to maturity.
 *
 * <p>Accrued interest per day count: 30/360 and 30E/360 use the 30-day month rules over (last coupon, settlement);
 * ACT/ACT (bond convention, ICMA) uses actual days in the current coupon period: coupon / frequency x
 * days(last, settle) / days(last, next); ACT/360 and ACT/365 use actual days over 360 / 365.
 */
public final class BondMath {
    static final MathContext MC = MathContext.DECIMAL128;
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private BondMath() {}

    /** A bond's terms (coupon as a decimal annual rate; null coupon = discount instrument). */
    public record Terms(BigDecimal couponRate, int frequency, LocalDate issueDate, LocalDate maturity, DayCount dayCount) {
        public Terms {
            if (frequency <= 0) {
                frequency = 1;
            }
        }

        boolean zeroCoupon() {
            return couponRate == null || couponRate.signum() == 0;
        }
    }

    /** Number of coupon periods to roll back from maturity to reach the last coupon date on or before settle. */
    private static int periodsBack(Terms t, LocalDate settle) {
        int months = 12 / t.frequency();
        int n = 0;
        while (t.maturity().minusMonths((long) months * n).isAfter(settle)) {
            n++;
        }
        return n;
    }

    /** Last coupon date on or before {@code settle} (rolled back from maturity; never before the issue date). */
    public static LocalDate previousCoupon(Terms t, LocalDate settle) {
        LocalDate d = t.maturity().minusMonths((long) (12 / t.frequency()) * periodsBack(t, settle));
        return t.issueDate() != null && d.isBefore(t.issueDate()) ? t.issueDate() : d;
    }

    /** First coupon date after {@code settle} (maturity at the latest). */
    public static LocalDate nextCoupon(Terms t, LocalDate settle) {
        int n = periodsBack(t, settle);
        return n == 0 ? t.maturity() : t.maturity().minusMonths((long) (12 / t.frequency()) * (n - 1));
    }

    /** Regular coupon period start (ignores the issue date; used as the ACT/ACT period length). */
    private static LocalDate regularPrevious(Terms t, LocalDate settle) {
        return t.maturity().minusMonths((long) (12 / t.frequency()) * periodsBack(t, settle));
    }

    /** Accrued interest in % of nominal at {@code settle}. */
    public static BigDecimal accruedPct(Terms t, LocalDate settle) {
        if (t.zeroCoupon() || !settle.isBefore(t.maturity())) {
            return BigDecimal.ZERO;
        }
        LocalDate last = previousCoupon(t, settle);
        if (!settle.isAfter(last)) {
            return BigDecimal.ZERO;
        }
        BigDecimal annualPct = t.couponRate().multiply(HUNDRED, MC);
        BigDecimal fraction = switch (t.dayCount()) {
            case THIRTY_360 -> BigDecimal.valueOf(DayCount.thirty360(last, settle, false)).divide(BigDecimal.valueOf(360), MC);
            case THIRTY_E_360 -> BigDecimal.valueOf(DayCount.thirty360(last, settle, true)).divide(BigDecimal.valueOf(360), MC);
            case ACT_ACT_ISDA -> {
                LocalDate next = nextCoupon(t, settle);
                BigDecimal periodDays = BigDecimal.valueOf(ChronoUnit.DAYS.between(regularPrevious(t, settle), next));
                yield BigDecimal.valueOf(ChronoUnit.DAYS.between(last, settle))
                        .divide(periodDays.multiply(BigDecimal.valueOf(t.frequency())), MC);
            }
            default -> t.dayCount().yearFraction(last, settle);
        };
        return annualPct.multiply(fraction, MC);
    }

    /** Accrued interest amount on {@code nominal}, rounded to the currency. */
    public static BigDecimal accruedAmount(Terms t, BigDecimal nominal, LocalDate settle, String currency) {
        return Currencies.round(nominal.multiply(accruedPct(t, settle), MC).divide(HUNDRED, MC), currency);
    }

    /** Dirty price = clean price + accrued (both % of nominal), 10 decimals. */
    public static BigDecimal dirtyPrice(Terms t, BigDecimal cleanPrice, LocalDate settle) {
        return cleanPrice.add(accruedPct(t, settle), MC).setScale(10, RoundingMode.HALF_EVEN);
    }

    /** Clean consideration = nominal x clean / 100, rounded to the currency. */
    public static BigDecimal cleanConsideration(BigDecimal nominal, BigDecimal cleanPrice, String currency) {
        return Currencies.round(nominal.multiply(cleanPrice, MC).divide(HUNDRED, MC), currency);
    }

    /**
     * Simple yield to maturity (decimal): (annual coupon + (100 - clean) / years to maturity) / clean, years on ACT/365.
     * Discount instruments use a coupon of zero.
     */
    public static BigDecimal simpleYield(Terms t, BigDecimal cleanPrice, LocalDate settle) {
        if (!settle.isBefore(t.maturity()) || cleanPrice.signum() <= 0) {
            return BigDecimal.ZERO.setScale(10);
        }
        BigDecimal years = BigDecimal.valueOf(ChronoUnit.DAYS.between(settle, t.maturity())).divide(BigDecimal.valueOf(365), MC);
        BigDecimal coupon = t.zeroCoupon() ? BigDecimal.ZERO : t.couponRate().multiply(HUNDRED, MC);
        BigDecimal pull = HUNDRED.subtract(cleanPrice).divide(years, MC);
        return coupon.add(pull, MC).divide(cleanPrice, MC).setScale(10, RoundingMode.HALF_EVEN);
    }
}
