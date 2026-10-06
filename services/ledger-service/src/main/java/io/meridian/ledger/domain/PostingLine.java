package io.meridian.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** One requested debit or credit of a posting, before it is applied to an account. */
public record PostingLine(
        UUID accountId,
        Direction direction,
        BigDecimal amount,
        String currency,
        BigDecimal fxRate,
        String description) {

    public enum Direction { DEBIT, CREDIT }

    public PostingLine reversed() {
        return new PostingLine(accountId, direction == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT,
                amount, currency, fxRate, description);
    }
}
