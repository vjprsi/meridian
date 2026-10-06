package io.meridian.deposit.domain;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Closure settlement: the customer receives the ledger balance plus accrued credit interest (net of withholding
 * tax), less accrued debit/overdraft interest and closure fees (with their tax).
 */
public final class ClosureCalculator {
    private ClosureCalculator() {}

    public record Quote(BigDecimal ledgerBalance, BigDecimal creditInterest, BigDecimal interestTax,
                        BigDecimal debitInterest, BigDecimal fees, BigDecimal feeTax, BigDecimal finalPayout) {
        /** Net interest minus debit interest and fees: what closing adds to (or takes from) the balance. */
        public BigDecimal netAdjustment() {
            return creditInterest.subtract(interestTax).subtract(debitInterest).subtract(fees).subtract(feeTax);
        }
    }

    public static Quote quote(BigDecimal ledgerBalance, BigDecimal accruedCredit, BigDecimal accruedDebit,
                              BigDecimal withholdingRate, BigDecimal closureFee, BigDecimal feeTaxRate, String currency) {
        BigDecimal credit = Currencies.round(nz(accruedCredit), currency);
        BigDecimal tax = withholdingRate == null || credit.signum() <= 0 ? Currencies.round(BigDecimal.ZERO, currency)
                : Currencies.round(credit.multiply(withholdingRate, MathContext.DECIMAL128), currency);
        BigDecimal debit = Currencies.round(nz(accruedDebit), currency);
        BigDecimal fee = Currencies.round(nz(closureFee), currency);
        BigDecimal feeTax = feeTaxRate == null ? Currencies.round(BigDecimal.ZERO, currency)
                : Currencies.round(fee.multiply(feeTaxRate, MathContext.DECIMAL128), currency);
        BigDecimal payout = nz(ledgerBalance).add(credit).subtract(tax).subtract(debit).subtract(fee).subtract(feeTax);
        return new Quote(nz(ledgerBalance), credit, tax, debit, fee, feeTax, Currencies.round(payout, currency));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
