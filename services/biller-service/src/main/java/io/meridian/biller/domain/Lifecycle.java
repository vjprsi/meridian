package io.meridian.biller.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** State machines and amount rules of billers, bill payments / top-ups and settlements. */
public final class Lifecycle {
    private Lifecycle() {}

    private static final Map<String, Set<String>> BILLER = Map.of(
            "ACTIVE", Set.of("SUSPENDED", "RETIRED"),
            "SUSPENDED", Set.of("ACTIVE", "RETIRED"),
            "RETIRED", Set.of());

    private static final Map<String, Set<String>> PAYMENT = Map.of(
            "INITIATED", Set.of("PROCESSING", "FAILED"),
            "VALIDATED", Set.of("PROCESSING", "FAILED"),
            "PROCESSING", Set.of("COMPLETED", "FAILED", "PENDING_PROVIDER"),
            "PENDING_PROVIDER", Set.of("COMPLETED", "FAILED", "PENDING_PROVIDER"),
            "COMPLETED", Set.of("REVERSED"),
            "FAILED", Set.of(),
            "REVERSED", Set.of());

    private static final Map<String, Set<String>> SETTLEMENT = Map.of(
            "PENDING", Set.of("CALCULATED"),
            "CALCULATED", Set.of("APPROVED", "REJECTED"),
            "APPROVED", Set.of("PAID", "FAILED"),
            "FAILED", Set.of("PAID"),
            "PAID", Set.of(),
            "REJECTED", Set.of());

    public static void biller(String from, String to) {
        require(BILLER, from, to, "Biller");
    }

    public static void payment(String from, String to) {
        require(PAYMENT, from, to, "Payment");
    }

    public static void settlement(String from, String to) {
        require(SETTLEMENT, from, to, "Settlement");
    }

    private static void require(Map<String, Set<String>> m, String from, String to, String what) {
        if (!m.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", what + " cannot move from " + from + " to " + to);
        }
    }

    /**
     * Checks a payment amount against the amount due of an inquiry: less is allowed only with partial payments,
     * more only with overpayments.
     */
    public static void checkAgainstDue(BigDecimal amount, BigDecimal due, boolean partial, boolean over) {
        if (due == null) {
            return;
        }
        int c = amount.compareTo(due);
        if (c < 0 && !partial) {
            throw ApiException.unprocessable("partial_payment_not_allowed",
                    "The biller requires the full amount due " + due.toPlainString());
        }
        if (c > 0 && !over) {
            throw ApiException.unprocessable("overpayment_not_allowed",
                    "The biller does not accept more than the amount due " + due.toPlainString());
        }
    }
}
