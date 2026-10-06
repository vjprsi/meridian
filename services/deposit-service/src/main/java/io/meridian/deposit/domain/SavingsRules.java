package io.meridian.deposit.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Automatic-saving arithmetic for savings pot rules. */
public final class SavingsRules {
    private SavingsRules() {}

    /**
     * Round-up of a card/payment amount to the next whole currency unit, times {@code multiplier}
     * (e.g. 3.40 with multiplier 2 saves 1.20). Whole amounts save nothing.
     */
    public static BigDecimal roundUp(BigDecimal spend, int multiplier, String currency) {
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier must be at least 1");
        }
        BigDecimal abs = spend.abs();
        BigDecimal next = abs.setScale(0, RoundingMode.CEILING);
        return Currencies.round(next.subtract(abs).multiply(BigDecimal.valueOf(multiplier)), currency);
    }

    /** Share of an income credit moved to the pot (percentage as a fraction, 0.10 = 10%), rounded down. */
    public static BigDecimal percentOf(BigDecimal income, BigDecimal percentage, String currency) {
        if (percentage.signum() <= 0 || percentage.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("percentage must be in (0, 1]");
        }
        return income.abs().multiply(percentage, MathContext.DECIMAL128)
                .setScale(Currencies.digits(currency), RoundingMode.DOWN);
    }
}
