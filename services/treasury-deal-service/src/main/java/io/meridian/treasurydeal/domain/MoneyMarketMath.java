package io.meridian.treasurydeal.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Money market deal arithmetic: simple interest paid at maturity, accruals and direction of the cash flows. */
public final class MoneyMarketMath {
    static final MathContext MC = MathContext.DECIMAL128;
    /** Deal types where we lend cash (asset; credit exposure on the counterparty). */
    public static final Set<String> LENDING = Set.of("PLACEMENT", "CALL_PLACEMENT", "REVERSE_REPO", "CD_PURCHASED");

    private MoneyMarketMath() {}

    public static boolean isLending(String dealType) {
        return LENDING.contains(dealType);
    }

    /** Interest for [from, to) on {@code principal} at the annual {@code rate}, rounded to the currency's minor unit. */
    public static BigDecimal interest(BigDecimal principal, BigDecimal rate, DayCount basis, LocalDate from, LocalDate to,
                                      String currency) {
        if (!to.isAfter(from)) {
            return Currencies.round(BigDecimal.ZERO, currency);
        }
        return Currencies.round(principal.multiply(rate, MC).multiply(basis.yearFraction(from, to), MC), currency);
    }

    /**
     * Interest accrued from the value date through {@code asOf} inclusive (the as-of day earns interest), capped at
     * the maturity date (the maturity day itself does not accrue).
     */
    public static BigDecimal accruedThrough(BigDecimal principal, BigDecimal rate, DayCount basis, LocalDate valueDate,
                                            LocalDate maturity, LocalDate asOf, String currency) {
        LocalDate end = asOf.plusDays(1);
        if (maturity != null && end.isAfter(maturity)) {
            end = maturity;
        }
        return interest(principal, rate, basis, valueDate, end, currency);
    }

    public static long tenorDays(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }

    /** Deal type that DayCountBasis enum values map to (ACT_360 money market convention by default). */
    public static DayCount dayCount(String value) {
        return value == null ? DayCount.ACT_360 : DayCount.parse(value);
    }
}
