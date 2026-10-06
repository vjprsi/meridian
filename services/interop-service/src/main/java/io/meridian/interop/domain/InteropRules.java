package io.meridian.interop.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/** Transfer state machine, fee and position arithmetic for interoperable transfers. */
public final class InteropRules {
    private InteropRules() {}

    private static final Map<String, Set<String>> TRANSFER = Map.of(
            "QUOTE_REQUESTED", Set.of("QUOTED", "RESERVED", "ABORTED", "FAILED"),
            "QUOTED", Set.of("RESERVED", "ABORTED", "FAILED", "EXPIRED"),
            "RESERVED", Set.of("COMMITTED", "ABORTED", "EXPIRED", "FAILED"),
            "COMMITTED", Set.of(),
            "ABORTED", Set.of(),
            "FAILED", Set.of(),
            "EXPIRED", Set.of());

    public static boolean isFinal(String status) {
        return TRANSFER.getOrDefault(status, Set.of()).isEmpty();
    }

    public static void requireTransition(String from, String to) {
        if (!TRANSFER.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "Transfer cannot move from " + from + " to " + to);
        }
    }

    /** Our outbound fee: flat + percentage x amount, 2 decimals half-even. */
    public static BigDecimal fee(BigDecimal amount, BigDecimal flat, BigDecimal percentage) {
        BigDecimal f = (flat == null ? BigDecimal.ZERO : flat)
                .add(amount.multiply(percentage == null ? BigDecimal.ZERO : percentage));
        return f.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Net debit cap check: what we already owe the switch (committed outbound minus inbound), plus reservations in
     * flight, plus the new amount must stay within the cap. A null cap means unlimited.
     */
    public static BigDecimal headroom(BigDecimal cap, BigDecimal netPosition, BigDecimal reserved) {
        return cap == null ? null : cap.subtract(netPosition).subtract(reserved);
    }

    public static boolean withinCap(BigDecimal cap, BigDecimal netPosition, BigDecimal reserved, BigDecimal amount) {
        BigDecimal h = headroom(cap, netPosition, reserved);
        return h == null || amount.compareTo(h) <= 0;
    }
}
