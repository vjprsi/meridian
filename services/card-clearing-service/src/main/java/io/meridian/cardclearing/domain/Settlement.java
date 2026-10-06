package io.meridian.cardclearing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Scheme settlement arithmetic. Sign convention: {@code net = purchases - refunds - chargebacks - interchange +
 * scheme fees}; positive means the issuer pays the scheme, negative means the scheme pays the issuer.
 * Amounts are in the settlement currency.
 */
public final class Settlement {
    private Settlement() {}

    /** Mutable accumulator of one settlement position. */
    public static final class Totals {
        public int count;
        public BigDecimal purchases = BigDecimal.ZERO;
        public BigDecimal refunds = BigDecimal.ZERO;
        public BigDecimal chargebacks = BigDecimal.ZERO;
        public BigDecimal interchange = BigDecimal.ZERO;
        public BigDecimal schemeFees = BigDecimal.ZERO;

        /**
         * PRESENTMENT / REPRESENTMENT add purchases; REFUND / REVERSAL / FUNDS_DISBURSEMENT add refunds;
         * CHARGEBACK adds chargebacks; FEE_COLLECTION adds scheme fees. Interchange and scheme fees on any record
         * are added too.
         */
        public Totals add(String recordType, BigDecimal settlementAmount, BigDecimal interchangeAmount,
                          BigDecimal schemeFee) {
            count++;
            BigDecimal amt = settlementAmount == null ? BigDecimal.ZERO : settlementAmount;
            switch (recordType) {
                case "PRESENTMENT", "REPRESENTMENT" -> purchases = purchases.add(amt);
                case "REFUND", "REVERSAL", "FUNDS_DISBURSEMENT" -> refunds = refunds.add(amt);
                case "CHARGEBACK" -> chargebacks = chargebacks.add(amt);
                case "FEE_COLLECTION" -> schemeFees = schemeFees.add(amt);
                default -> throw new IllegalArgumentException("unknown record type " + recordType);
            }
            if (interchangeAmount != null) {
                interchange = interchange.add(interchangeAmount);
            }
            if (schemeFee != null) {
                schemeFees = schemeFees.add(schemeFee);
            }
            return this;
        }

        public BigDecimal net() {
            return Settlement.net(purchases, refunds, chargebacks, interchange, schemeFees);
        }
    }

    public static BigDecimal net(BigDecimal purchases, BigDecimal refunds, BigDecimal chargebacks,
                                 BigDecimal interchange, BigDecimal schemeFees) {
        return purchases.subtract(refunds).subtract(chargebacks).subtract(interchange).add(schemeFees);
    }

    /** Interchange earned on an amount at a rate, rounded HALF_EVEN to 2 decimals. */
    public static BigDecimal interchange(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
    }
}
