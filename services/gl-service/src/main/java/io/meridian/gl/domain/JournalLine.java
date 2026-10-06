package io.meridian.gl.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** One debit or credit line of a GL journal. */
public record JournalLine(UUID glAccountId, Direction direction, BigDecimal amount, String currency,
                          BigDecimal baseAmount, String costCenter, UUID branchId, UUID productId) {

    public enum Direction { DEBIT, CREDIT }

    /** Signed amount in debit-positive convention. */
    public BigDecimal signedAmount() {
        return direction == Direction.DEBIT ? amount : amount.negate();
    }

    public BigDecimal signedBase() {
        return direction == Direction.DEBIT ? baseAmount : baseAmount.negate();
    }
}
