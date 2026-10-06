package io.meridian.fx.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

/**
 * FX pricing arithmetic. Rates are "buy units per 1 sell unit" from the customer's view. Rates carry 10 decimals,
 * amounts the minor units of their currency; all rounding is HALF_EVEN with DECIMAL128 intermediates.
 */
public final class FxPricing {
    public static final int RATE_SCALE = 10;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

    private FxPricing() {}

    /** A priced conversion: both amounts, the customer rate, the fee (sell currency) and the total debit. */
    public record Priced(BigDecimal sellAmount, BigDecimal buyAmount, BigDecimal midRate, BigDecimal customerRate,
                         int marginBps, BigDecimal fee, BigDecimal totalDebit) {}

    public static boolean isCurrency(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isCountry(String code) {
        return code != null && code.matches("[A-Z]{2}") && COUNTRIES.contains(code);
    }

    public static int minorUnits(String currency) {
        int d = Currency.getInstance(currency).getDefaultFractionDigits();
        return d < 0 ? 2 : d;
    }

    /** Customer rate = mid x (1 - margin), the margin taken from the amount the customer receives. */
    public static BigDecimal customerRate(BigDecimal mid, int marginBps) {
        if (mid == null || mid.signum() <= 0) {
            throw new IllegalArgumentException("mid rate must be positive");
        }
        if (marginBps < 0 || marginBps >= 10_000) {
            throw new IllegalArgumentException("margin must be 0..9999 bps");
        }
        BigDecimal factor = BigDecimal.ONE.subtract(BigDecimal.valueOf(marginBps).divide(BPS, MathContext.DECIMAL128));
        return mid.multiply(factor, MathContext.DECIMAL128).setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Rounds an amount to the currency's minor units (HALF_EVEN). */
    public static BigDecimal round(BigDecimal amount, String currency) {
        return amount.setScale(minorUnits(currency), RoundingMode.HALF_EVEN);
    }

    /**
     * Prices a conversion with exactly one side fixed. {@code fee} is in the sell currency (null = 0).
     *
     * @throws IllegalArgumentException when both or neither side is given, or an amount is not positive
     */
    public static Priced price(String sellCcy, String buyCcy, BigDecimal sellAmount, BigDecimal buyAmount,
                               BigDecimal mid, int marginBps, BigDecimal fee) {
        if ((sellAmount == null) == (buyAmount == null)) {
            throw new IllegalArgumentException("exactly one of sell_amount and buy_amount must be given");
        }
        BigDecimal rate = customerRate(mid, marginBps);
        BigDecimal sell;
        BigDecimal buy;
        if (sellAmount != null) {
            requirePositive(sellAmount);
            sell = round(sellAmount, sellCcy);
            buy = round(sell.multiply(rate, MathContext.DECIMAL128), buyCcy);
        } else {
            requirePositive(buyAmount);
            buy = round(buyAmount, buyCcy);
            sell = round(buy.divide(rate, MathContext.DECIMAL128), sellCcy);
        }
        BigDecimal f = round(fee == null || fee.signum() < 0 ? BigDecimal.ZERO : fee, sellCcy);
        return new Priced(sell, buy, mid.setScale(RATE_SCALE, RoundingMode.HALF_EVEN), rate, marginBps, f, sell.add(f));
    }

    /** Remittance fee: fixed fee plus a percentage of the send amount, in the send currency. */
    public static BigDecimal remittanceFee(BigDecimal fixedFee, BigDecimal percentFee, BigDecimal sendAmount, String ccy) {
        BigDecimal fixed = fixedFee == null ? BigDecimal.ZERO : fixedFee;
        BigDecimal pct = percentFee == null ? BigDecimal.ZERO : percentFee;
        return round(fixed.add(sendAmount.multiply(pct, MathContext.DECIMAL128)), ccy);
    }

    /** Adds business days, skipping Saturdays and Sundays. */
    public static LocalDate addBusinessDays(LocalDate from, int days) {
        LocalDate d = from;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return d;
    }

    private static void requirePositive(BigDecimal v) {
        if (v.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
