package io.meridian.collateral.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** ISO 4217 minor-unit helpers. */
public final class Currencies {
    private Currencies() {}

    /** Number of decimals of the currency's minor unit (2 for EUR/USD, 0 for JPY, 3 for BHD). */
    public static int digits(String currency) {
        try {
            return Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        } catch (IllegalArgumentException | NullPointerException e) {
            return 2;
        }
    }

    /** Rounds to the currency's minor unit with banker's rounding. */
    public static BigDecimal round(BigDecimal amount, String currency) {
        return amount.setScale(digits(currency), RoundingMode.HALF_EVEN);
    }

    /** True when {@code amount} has no more decimals than the currency allows. */
    public static boolean fitsMinorUnit(BigDecimal amount, String currency) {
        return amount.stripTrailingZeros().scale() <= digits(currency);
    }
}
