package io.meridian.common.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in one ISO 4217 currency. Immutable; never uses floating point.
 * Stored as numeric(28,8); rounding to the currency's minor unit happens only when explicitly requested.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public static final MathContext MC = MathContext.DECIMAL128;
    public static final int STORAGE_SCALE = 8;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be ISO 4217 alpha-3: " + currency);
        }
        if (amount.stripTrailingZeros().scale() > STORAGE_SCALE) {
            throw new IllegalArgumentException("amount has more than " + STORAGE_SCALE + " decimals: " + amount);
        }
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount, MC), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount, MC), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** Rounds to the currency's minor unit (e.g. 2 for USD, 0 for JPY) with the given rounding mode. */
    public Money roundToMinorUnit(RoundingMode mode) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return new Money(amount.setScale(digits, mode), currency);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    /** Value equality ignores scale: 10.0 USD equals 10.00 USD. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && currency.equals(m.currency) && amount.compareTo(m.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
