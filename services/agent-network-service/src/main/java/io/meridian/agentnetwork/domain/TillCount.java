package io.meridian.agentnetwork.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.Map;

/** Till cash counts: expected cash, variance and its classification; denomination counts must add up. */
public final class TillCount {
    private TillCount() {}

    public record Result(BigDecimal expected, BigDecimal variance, String status) {}

    /** expected = opening + cash received (cash-in) - cash paid out (cash-out); variance = declared - expected. */
    public static Result close(BigDecimal opening, BigDecimal cashIn, BigDecimal cashOut, BigDecimal declared) {
        BigDecimal expected = opening.add(cashIn).subtract(cashOut);
        BigDecimal variance = declared.subtract(expected);
        String status = variance.signum() == 0 ? "BALANCED" : variance.signum() > 0 ? "OVER" : "SHORT";
        return new Result(expected, variance, status);
    }

    /** Validates a non-negative amount and, when denominations are given, that face value x count adds up to it. */
    public static void validateCount(BigDecimal amount, Map<String, ?> denominations) {
        if (amount == null || amount.signum() < 0) {
            throw ApiException.unprocessable("invalid_cash_count", "The cash amount must not be negative");
        }
        if (denominations == null || denominations.isEmpty()) {
            return;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, ?> e : denominations.entrySet()) {
            BigDecimal face;
            long count;
            try {
                face = new BigDecimal(e.getKey());
                count = Long.parseLong(String.valueOf(e.getValue()));
            } catch (NumberFormatException ex) {
                throw ApiException.unprocessable("invalid_denominations",
                        "denominations must map a face value to a count, e.g. {\"1000\": 12}");
            }
            if (face.signum() <= 0 || count < 0) {
                throw ApiException.unprocessable("invalid_denominations", "Face values must be positive and counts not negative");
            }
            total = total.add(face.multiply(BigDecimal.valueOf(count)));
        }
        if (total.compareTo(amount) != 0) {
            throw ApiException.unprocessable("denominations_mismatch",
                    "The denominations add up to " + total.toPlainString() + ", not " + amount.toPlainString());
        }
    }
}
