package io.meridian.deposit.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Fee arithmetic: flat, percentage or flat-plus-percentage fees with min/max per occurrence, VAT/GST on top,
 * and pro-rata tax on partial refunds. All results are rounded to the currency's minor unit (HALF_EVEN).
 */
public final class FeeCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;

    private FeeCalculator() {}

    /** Fee definition from the product snapshot (catalog fee_definition shape). */
    public record FeeDefinition(String code, String method, BigDecimal flatAmount, BigDecimal percentage,
                                BigDecimal minAmount, BigDecimal maxAmount, BigDecimal taxRate, boolean refundable) {
        public FeeDefinition {
            method = method == null ? (percentage != null && flatAmount == null ? "PERCENTAGE" : "FLAT")
                    : method.toUpperCase(Locale.ROOT);
            taxRate = taxRate == null ? BigDecimal.ZERO : taxRate;
        }
    }

    /** Fee amount and the tax charged on it. */
    public record Charge(BigDecimal amount, BigDecimal tax) {
        public BigDecimal total() {
            return amount.add(tax);
        }
    }

    /** Computes a fee for a base amount (the amount of the triggering transaction; may be null for flat fees). */
    public static Charge compute(FeeDefinition def, BigDecimal base, String currency) {
        BigDecimal flat = def.flatAmount() == null ? BigDecimal.ZERO : def.flatAmount();
        BigDecimal pct = def.percentage() == null ? BigDecimal.ZERO : def.percentage();
        BigDecimal b = base == null ? BigDecimal.ZERO : base.abs();
        BigDecimal fee = switch (def.method()) {
            case "FLAT", "PER_UNIT", "TIERED_FLAT" -> flat;
            case "PERCENTAGE", "TIERED_PERCENTAGE" -> {
                if (base == null) {
                    throw ApiException.unprocessable("base_amount_required",
                            "Fee " + def.code() + " is a percentage fee; a base amount is required");
                }
                yield b.multiply(pct, MC);
            }
            case "FLAT_PLUS_PERCENTAGE" -> flat.add(b.multiply(pct, MC), MC);
            default -> throw ApiException.unprocessable("unsupported_fee_method",
                    "Fee calculation method " + def.method() + " is not supported");
        };
        if (def.minAmount() != null && fee.compareTo(def.minAmount()) < 0) {
            fee = def.minAmount();
        }
        if (def.maxAmount() != null && fee.compareTo(def.maxAmount()) > 0) {
            fee = def.maxAmount();
        }
        return withTax(Currencies.round(fee, currency), def.taxRate(), currency);
    }

    /** A fee with an explicit amount plus the tax rate of its definition. */
    public static Charge withTax(BigDecimal amount, BigDecimal taxRate, String currency) {
        if (amount.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "Fee amount must not be negative");
        }
        BigDecimal rounded = Currencies.round(amount, currency);
        BigDecimal tax = taxRate == null ? BigDecimal.ZERO : rounded.multiply(taxRate, MC);
        return new Charge(rounded, Currencies.round(tax, currency));
    }

    /**
     * Tax refunded with a (partial) fee refund: proportional to the refunded share of the original fee; a full
     * refund returns exactly the original tax so the fee and its tax net to zero.
     */
    public static BigDecimal refundTax(BigDecimal originalAmount, BigDecimal originalTax, BigDecimal refundAmount,
                                       String currency) {
        return refundTax(originalAmount, originalTax, refundAmount, BigDecimal.ZERO, BigDecimal.ZERO, currency);
    }

    /**
     * Tax for one refund in a series: pro rata, but never more than the tax not yet refunded, and the refund that
     * completes the fee returns exactly the remaining tax (so rounding never leaves tax behind or over-refunds it).
     */
    public static BigDecimal refundTax(BigDecimal originalAmount, BigDecimal originalTax, BigDecimal refundAmount,
                                       BigDecimal alreadyRefunded, BigDecimal alreadyRefundedTax, String currency) {
        BigDecimal remainingTax = originalTax.subtract(alreadyRefundedTax);
        if (alreadyRefunded.add(refundAmount).compareTo(originalAmount) >= 0 || originalAmount.signum() == 0) {
            return remainingTax;
        }
        BigDecimal share = originalTax.multiply(refundAmount, MC).divide(originalAmount, MC)
                .setScale(Currencies.digits(currency), RoundingMode.HALF_EVEN);
        return share.min(remainingTax).max(BigDecimal.ZERO);
    }
}
