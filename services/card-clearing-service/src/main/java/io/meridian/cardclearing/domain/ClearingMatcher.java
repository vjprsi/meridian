package io.meridian.cardclearing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decides whether a presentment can be posted against an authorisation: same billing currency, the authorisation
 * still holds funds, and the cleared amount is within the tolerance over the remaining authorised amount
 * (tips, fuel and hotel final amounts exceed the authorisation by up to {@code tolerancePct}).
 */
public final class ClearingMatcher {
    public static final Set<String> MATCHABLE = Set.of("APPROVED", "PARTIALLY_CLEARED", "PARTIALLY_REVERSED");
    /** Authorisations whose hold is gone: the presentment must still be paid (force post). */
    public static final Set<String> HOLD_GONE = Set.of("EXPIRED", "REVERSED");

    private ClearingMatcher() {}

    /** The fields of a card authorisation clearing needs. */
    public record AuthView(UUID id, UUID cardId, UUID programId, UUID parentId, String messageType, String status,
                           String billingCurrency, BigDecimal billingAmount, BigDecimal approved, BigDecimal cleared,
                           BigDecimal reversed, UUID holdId, UUID holdAccountId, BigDecimal fxMarkup) {

        /** Approved minus cleared minus reversed, never negative. */
        public BigDecimal remaining() {
            BigDecimal r = nz(approved).subtract(nz(cleared)).subtract(nz(reversed));
            return r.signum() < 0 ? BigDecimal.ZERO : r;
        }
    }

    public enum Kind { MATCHED, EXCEPTION, HOLD_GONE }

    public record Decision(Kind kind, BigDecimal remaining, String reason) {}

    public static Decision evaluate(AuthView a, BigDecimal clearedAmount, String billingCurrency,
                                    BigDecimal tolerancePct) {
        if (clearedAmount == null || clearedAmount.signum() <= 0) {
            return new Decision(Kind.EXCEPTION, BigDecimal.ZERO, "cleared amount must be positive");
        }
        if (a.billingCurrency() != null && !a.billingCurrency().equals(billingCurrency)) {
            return new Decision(Kind.EXCEPTION, BigDecimal.ZERO, "billing currency " + billingCurrency
                    + " differs from the authorisation currency " + a.billingCurrency());
        }
        if (HOLD_GONE.contains(a.status())) {
            return new Decision(Kind.HOLD_GONE, BigDecimal.ZERO, "authorisation is " + a.status());
        }
        if (!MATCHABLE.contains(a.status())) {
            return new Decision(Kind.EXCEPTION, BigDecimal.ZERO, "authorisation is " + a.status());
        }
        BigDecimal remaining = a.remaining();
        BigDecimal ceiling = remaining.multiply(BigDecimal.ONE.add(nz(tolerancePct)));
        if (clearedAmount.compareTo(ceiling) > 0) {
            return new Decision(Kind.EXCEPTION, remaining, "cleared " + clearedAmount.stripTrailingZeros().toPlainString()
                    + " exceeds the remaining authorised " + remaining.stripTrailingZeros().toPlainString()
                    + " beyond the tolerance");
        }
        return new Decision(Kind.MATCHED, remaining, null);
    }

    /** Prefers an original (non-incremental, non-reversal) authorisation in a matchable status. */
    public static AuthView select(List<AuthView> candidates) {
        AuthView fallback = null;
        for (AuthView a : candidates) {
            if (a.parentId() != null) {
                continue;
            }
            if ("DECLINED".equals(a.status())) {
                continue;
            }
            if (MATCHABLE.contains(a.status())) {
                return a;
            }
            if (fallback == null) {
                fallback = a;
            }
        }
        return fallback;
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
