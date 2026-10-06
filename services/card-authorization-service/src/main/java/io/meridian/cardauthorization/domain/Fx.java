package io.meridian.cardauthorization.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Transaction to billing currency conversion with the issuer FX markup (HALF_EVEN, currency minor units). */
public final class Fx {
    private Fx() {}

    /** Converted amount (incl. markup) and the markup part, both in billing currency minor units. */
    public record Billing(BigDecimal amount, BigDecimal markup, BigDecimal rate) {}

    public static int scale(String currency) {
        try {
            int d = Currency.getInstance(currency).getDefaultFractionDigits();
            return d < 0 ? 2 : d;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    /**
     * @param rate       billing units per transaction unit (ignored when the currencies are equal)
     * @param markupRate issuer markup, e.g. 0.02; applied only to cross-currency transactions
     */
    public static Billing convert(BigDecimal amount, String txCurrency, String billingCurrency, BigDecimal rate,
                                  BigDecimal markupRate) {
        int scale = scale(billingCurrency);
        if (txCurrency.equals(billingCurrency)) {
            return new Billing(amount.setScale(scale, RoundingMode.HALF_EVEN), BigDecimal.ZERO.setScale(scale), BigDecimal.ONE);
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("no conversion rate " + txCurrency + "->" + billingCurrency);
        }
        BigDecimal converted = amount.multiply(rate).setScale(scale, RoundingMode.HALF_EVEN);
        BigDecimal markup = markupRate == null || markupRate.signum() <= 0 ? BigDecimal.ZERO.setScale(scale)
                : converted.multiply(markupRate).setScale(scale, RoundingMode.HALF_EVEN);
        return new Billing(converted.add(markup), markup, rate);
    }

    /** Converts a later amount (reversal / incremental) with the parent's effective billing ratio. */
    public static BigDecimal proportional(BigDecimal amount, BigDecimal parentTx, BigDecimal parentBilling, String billingCurrency) {
        if (parentTx.signum() == 0 || parentTx.compareTo(parentBilling) == 0) {
            return amount;
        }
        return amount.multiply(parentBilling).divide(parentTx, scale(billingCurrency), RoundingMode.HALF_EVEN);
    }
}
