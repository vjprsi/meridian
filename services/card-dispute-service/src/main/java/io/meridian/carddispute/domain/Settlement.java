package io.meridian.carddispute.domain;

import java.math.BigDecimal;

/**
 * Final money movement of a dispute. Inputs: provisional credit still outstanding ({@code provisional}), amount
 * recovered from the acquirer ({@code recovered}) and the total the cardholder ends up with ({@code customerTotal}).
 * The issuer writes off {@code customerTotal - recovered}. One balanced journal entry:
 * <pre>
 *   DEBIT  scheme receivable   recovered
 *   DEBIT  loss expense        writeOff
 *   CREDIT dispute suspense    provisional            (clears the provisional credit funding)
 *   CREDIT / DEBIT cardholder  customerTotal - provisional
 * </pre>
 */
public record Settlement(BigDecimal provisional, BigDecimal recovered, BigDecimal customerTotal) {

    public Settlement {
        if (provisional.signum() < 0 || recovered.signum() < 0 || customerTotal.compareTo(recovered) < 0) {
            throw new IllegalArgumentException("invalid settlement " + provisional + "/" + recovered + "/" + customerTotal);
        }
    }

    public BigDecimal writeOff() {
        return customerTotal.subtract(recovered);
    }

    /** Positive: credit the cardholder; negative: claw back from the cardholder. */
    public BigDecimal cardholderDelta() {
        return customerTotal.subtract(provisional);
    }

    /** True when the whole outcome is just undoing the provisional credit (posted as a ledger reversal). */
    public boolean isPureReversal() {
        return provisional.signum() > 0 && recovered.signum() == 0 && customerTotal.signum() == 0;
    }

    public boolean isEmpty() {
        return provisional.signum() == 0 && recovered.signum() == 0 && customerTotal.signum() == 0;
    }

    public BigDecimal totalDebits() {
        BigDecimal d = recovered.add(writeOff());
        return cardholderDelta().signum() < 0 ? d.subtract(cardholderDelta()) : d;
    }

    public BigDecimal totalCredits() {
        BigDecimal c = provisional;
        return cardholderDelta().signum() > 0 ? c.add(cardholderDelta()) : c;
    }
}
