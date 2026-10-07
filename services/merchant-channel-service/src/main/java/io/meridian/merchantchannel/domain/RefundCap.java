package io.meridian.merchantchannel.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What can still be refunded on one merchant payment: the payment amount minus refunds already completed and minus
 * refunds pending anywhere (requested or approved in merchant-service, or awaiting approval in the merchant app). The
 * cumulative refunds of a payment never exceed it.
 *
 * @param amount   the original payment amount
 * @param refunded completed refunds
 * @param pending  refunds requested or approved but not completed yet
 */
public record RefundCap(BigDecimal amount, BigDecimal refunded, BigDecimal pending) {
    public RefundCap {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        refunded = (refunded == null ? BigDecimal.ZERO : refunded).setScale(2, RoundingMode.HALF_EVEN);
        pending = (pending == null ? BigDecimal.ZERO : pending).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Remaining refundable amount, never below zero. */
    public BigDecimal refundable() {
        BigDecimal r = amount.subtract(refunded).subtract(pending);
        return r.signum() < 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN) : r.setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Whether a further refund of {@code requested} fits. */
    public boolean allows(BigDecimal requested) {
        return requested != null && requested.signum() > 0 && requested.compareTo(refundable()) <= 0;
    }
}
