package io.meridian.alm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A balance-sheet position for interest rate risk: an asset, a liability or a signed off-balance-sheet amount with its
 * rate and repricing / maturity information.
 */
public record Position(String code, UUID productId, String segment, Side side, BigDecimal amount, String currency,
                       BigDecimal rate, RateType rateType, LocalDate repricingDate, LocalDate maturityDate,
                       boolean nonMaturity, int paymentFrequencyMonths) {

    public enum Side {
        ASSET, LIABILITY, OFF_BALANCE;

        /** +1 for assets and off-balance (signed amounts), -1 for liabilities. */
        public int sign() {
            return this == LIABILITY ? -1 : 1;
        }
    }

    public enum RateType { FIXED, FLOATING }

    public Position {
        if (side == null) {
            throw new IllegalArgumentException("side is required (ASSET, LIABILITY or OFF_BALANCE)");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (side != Side.OFF_BALANCE && amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative for " + side);
        }
        code = code == null || code.isBlank() ? "UNSPECIFIED" : code;
        rate = rate == null ? BigDecimal.ZERO : rate;
        rateType = rateType == null ? RateType.FIXED : rateType;
        if (paymentFrequencyMonths <= 0) {
            paymentFrequencyMonths = 12;
        }
        if (paymentFrequencyMonths > 120) {
            throw new IllegalArgumentException("payment_frequency_months must be 1-120");
        }
        if (repricingDate != null && maturityDate != null && repricingDate.isAfter(maturityDate)) {
            throw new IllegalArgumentException("repricing_date must not be after maturity_date");
        }
    }

    /**
     * Date the position reprices: floating positions at their next reset (else maturity), fixed positions at maturity
     * (else an explicit repricing date). Null for non-maturity items without dates.
     */
    public LocalDate repricesOn() {
        return rateType == RateType.FLOATING
                ? (repricingDate != null ? repricingDate : maturityDate)
                : (maturityDate != null ? maturityDate : repricingDate);
    }

    /** Signed amount: positive for assets and receive-side off-balance, negative for liabilities. */
    public BigDecimal signedAmount() {
        return side == Side.LIABILITY ? amount.negate() : amount;
    }
}
