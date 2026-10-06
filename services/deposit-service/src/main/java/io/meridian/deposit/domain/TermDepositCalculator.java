package io.meridian.deposit.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Term deposit arithmetic: simple interest to maturity, premature-break quotes with penalty, and withholding tax.
 * Penalty rule: on a break the deposit earns interest only for the days actually held, at the contracted rate
 * reduced by {@code premature_penalty_rate} (never below zero). The penalty is the interest given up compared with
 * the contracted rate for the same days. All amounts are rounded to the currency's minor unit (HALF_EVEN).
 */
public final class TermDepositCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;

    private TermDepositCalculator() {}

    public record BreakQuote(LocalDate asOf, long daysHeld, BigDecimal principal, BigDecimal accruedAtContractRate,
                             BigDecimal effectiveRate, BigDecimal interestPayable, BigDecimal penalty, BigDecimal tax,
                             BigDecimal payout) {}

    public record Maturity(BigDecimal grossInterest, BigDecimal tax, BigDecimal netInterest) {}

    public static LocalDate maturityDate(LocalDate start, int termDays, Schedule.BusinessCalendar cal) {
        return cal.following(start.plusDays(termDays));
    }

    /** Interest earned from start (incl.) to {@code to} (excl.) at {@code rate}. */
    public static BigDecimal interest(BigDecimal principal, BigDecimal rate, DayCount dc, LocalDate start, LocalDate to,
                                      String currency) {
        if (!to.isAfter(start) || rate.signum() <= 0) {
            return Currencies.round(BigDecimal.ZERO, currency);
        }
        return Currencies.round(principal.multiply(rate, MC).multiply(dc.yearFraction(start, to), MC), currency);
    }

    public static BigDecimal expectedInterest(BigDecimal principal, BigDecimal rate, DayCount dc, LocalDate start,
                                              LocalDate maturity, String currency) {
        return interest(principal, rate, dc, start, maturity, currency);
    }

    public static Maturity atMaturity(BigDecimal principal, BigDecimal rate, DayCount dc, LocalDate start,
                                      LocalDate maturity, BigDecimal taxRate, String currency) {
        BigDecimal gross = expectedInterest(principal, rate, dc, start, maturity, currency);
        BigDecimal tax = tax(gross, taxRate, currency);
        return new Maturity(gross, tax, gross.subtract(tax));
    }

    public static BreakQuote breakQuote(BigDecimal principal, BigDecimal rate, BigDecimal penaltyRate, DayCount dc,
                                        LocalDate start, LocalDate asOf, BigDecimal taxRate, String currency) {
        return breakQuote(principal, rate, penaltyRate, dc, start, start, asOf, BigDecimal.ZERO, taxRate, currency);
    }

    /**
     * Break quote when the principal changed during the term (top-ups / partial withdrawals): {@code crystallised}
     * is the contract-rate interest earned up to {@code since}, the date of the last principal change.
     */
    public static BreakQuote breakQuote(BigDecimal principal, BigDecimal rate, BigDecimal penaltyRate, DayCount dc,
                                        LocalDate start, LocalDate since, LocalDate asOf, BigDecimal crystallised,
                                        BigDecimal taxRate, String currency) {
        LocalDate end = asOf.isBefore(since) ? since : asOf;
        long days = ChronoUnit.DAYS.between(start, end);
        BigDecimal contract = Currencies.round(crystallised, currency)
                .add(interest(principal, rate, dc, since, end, currency));
        BigDecimal penaltyReduction = penaltyRate == null ? BigDecimal.ZERO : penaltyRate;
        BigDecimal effective = rate.subtract(penaltyReduction).max(BigDecimal.ZERO);
        BigDecimal payable = rate.signum() <= 0 ? Currencies.round(BigDecimal.ZERO, currency)
                : Currencies.round(contract.multiply(effective, MC).divide(rate, MC), currency);
        BigDecimal penalty = contract.subtract(payable);
        BigDecimal tax = tax(payable, taxRate, currency);
        BigDecimal payout = principal.add(payable).subtract(tax);
        return new BreakQuote(asOf, days, principal, contract, effective, payable, penalty, tax,
                Currencies.round(payout, currency));
    }

    public static BigDecimal tax(BigDecimal interest, BigDecimal taxRate, String currency) {
        if (taxRate == null || taxRate.signum() <= 0 || interest.signum() <= 0) {
            return Currencies.round(BigDecimal.ZERO, currency);
        }
        return Currencies.round(interest.multiply(taxRate, MC), currency);
    }
}
