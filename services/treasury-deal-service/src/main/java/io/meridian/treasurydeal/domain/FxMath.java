package io.meridian.treasurydeal.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** FX arithmetic: forward points, quote amounts, mark-to-market and NDF net settlement. */
public final class FxMath {
    static final MathContext MC = MathContext.DECIMAL128;
    /** Tolerance between deal_rate and spot + points (half a hundredth of a pip). */
    static final BigDecimal TOLERANCE = new BigDecimal("0.0000005");

    private FxMath() {}

    /** Size of one pip in the quote currency: 0.01 for JPY quotes, 0.0001 otherwise. */
    public static BigDecimal pip(String quoteCurrency) {
        return "JPY".equals(quoteCurrency) ? new BigDecimal("0.01") : new BigDecimal("0.0001");
    }

    /** Outright rate = spot + forward points x pip. */
    public static BigDecimal outright(BigDecimal spot, BigDecimal points, String quoteCurrency) {
        return spot.add(points.multiply(pip(quoteCurrency), MC), MC).setScale(10, RoundingMode.HALF_EVEN);
    }

    /** True when deal_rate agrees with spot + points (within a fraction of a pip). */
    public static boolean consistent(BigDecimal dealRate, BigDecimal spot, BigDecimal points, String quoteCurrency) {
        BigDecimal tolerance = pip(quoteCurrency).multiply(new BigDecimal("0.005"));
        return dealRate.subtract(outright(spot, points, quoteCurrency)).abs().compareTo(tolerance.max(TOLERANCE)) <= 0;
    }

    /** Quote amount = base amount x rate, rounded to the quote currency. */
    public static BigDecimal quoteAmount(BigDecimal baseAmount, BigDecimal rate, String quoteCurrency) {
        return Currencies.round(baseAmount.multiply(rate, MC), quoteCurrency);
    }

    /** Undiscounted MTM of one leg in the quote currency: +base x (market - deal) when we buy the base, negated when we sell. */
    public static BigDecimal legMtm(String buySell, BigDecimal baseAmount, BigDecimal dealRate, BigDecimal marketRate,
                                    String quoteCurrency) {
        BigDecimal v = baseAmount.multiply(marketRate.subtract(dealRate), MC);
        return Currencies.round("BUY".equals(buySell) ? v : v.negate(), quoteCurrency);
    }

    /**
     * NDF net settlement (positive = we receive) in the settlement currency: settled in the quote currency it is
     * base x (fixing - deal); settled in the base currency it is base x (fixing - deal) / fixing; negated when we sell.
     */
    public static BigDecimal ndfSettlement(String buySell, BigDecimal baseAmount, BigDecimal dealRate, BigDecimal fixing,
                                           String baseCurrency, String settlementCurrency) {
        BigDecimal v = baseAmount.multiply(fixing.subtract(dealRate), MC);
        if (settlementCurrency.equals(baseCurrency)) {
            v = v.divide(fixing, MC);
        }
        return Currencies.round("BUY".equals(buySell) ? v : v.negate(), settlementCurrency);
    }

    /**
     * Basel current-exposure-method add-on factor for potential future exposure by residual maturity:
     * FX 1% up to one year, 5% up to five years, 7.5% beyond; interest rate 0%, 0.5%, 1.5%. Spot (two days or less): 0.
     */
    public static BigDecimal pfeAddOn(boolean fx, long residualDays) {
        if (residualDays <= 2) {
            return BigDecimal.ZERO;
        }
        if (residualDays <= 365) {
            return fx ? new BigDecimal("0.01") : BigDecimal.ZERO;
        }
        if (residualDays <= 1825) {
            return fx ? new BigDecimal("0.05") : new BigDecimal("0.005");
        }
        return fx ? new BigDecimal("0.075") : new BigDecimal("0.015");
    }
}
