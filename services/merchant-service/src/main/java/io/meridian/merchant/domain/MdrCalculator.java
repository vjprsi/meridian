package io.meridian.merchant.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Merchant discount rate: per payment fee = amount x mdr_percentage + fixed_fee, clamped to [min_fee, max_fee] and
 * rounded to 2 decimals half-even. A settlement sums the per-payment fees and adds the plan's settlement fee.
 */
public final class MdrCalculator {
    private MdrCalculator() {}

    public record Plan(BigDecimal mdrPercentage, BigDecimal fixedFee, BigDecimal minFee, BigDecimal maxFee,
                       BigDecimal refundFee, BigDecimal settlementFee) {
        public static final Plan NONE = new Plan(BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null);
    }

    public record Totals(int count, BigDecimal gross, BigDecimal mdr, BigDecimal refunds, BigDecimal net) {}

    public static BigDecimal fee(Plan p, BigDecimal amount) {
        BigDecimal fee = amount.multiply(nz(p.mdrPercentage())).add(nz(p.fixedFee())).setScale(2, RoundingMode.HALF_EVEN);
        if (p.minFee() != null && fee.compareTo(p.minFee()) < 0) {
            fee = p.minFee().setScale(2, RoundingMode.HALF_EVEN);
        }
        if (p.maxFee() != null && fee.compareTo(p.maxFee()) > 0) {
            fee = p.maxFee().setScale(2, RoundingMode.HALF_EVEN);
        }
        return fee.min(amount.setScale(2, RoundingMode.HALF_EVEN));
    }

    /**
     * Settlement totals: gross collections, MDR (+ settlement fee when anything is settled), refunds and net. Fees that
     * already left the collection wallet during the period (refund fees) are deducted from the net only.
     */
    public static Totals settle(Plan p, List<BigDecimal> payments, BigDecimal refunds, BigDecimal feesAlreadyCharged) {
        BigDecimal gross = BigDecimal.ZERO.setScale(2);
        BigDecimal mdr = BigDecimal.ZERO.setScale(2);
        for (BigDecimal a : payments) {
            gross = gross.add(a);
            mdr = mdr.add(fee(p, a));
        }
        if (!payments.isEmpty() && p.settlementFee() != null) {
            mdr = mdr.add(p.settlementFee().setScale(2, RoundingMode.HALF_EVEN));
        }
        BigDecimal r = refunds == null ? BigDecimal.ZERO : refunds;
        BigDecimal charged = feesAlreadyCharged == null ? BigDecimal.ZERO : feesAlreadyCharged;
        BigDecimal net = gross.subtract(mdr).subtract(r).subtract(charged).setScale(2, RoundingMode.HALF_EVEN);
        return new Totals(payments.size(), gross.setScale(2, RoundingMode.HALF_EVEN), mdr, r.setScale(2, RoundingMode.HALF_EVEN), net);
    }

    public static void validate(Plan p) {
        for (BigDecimal v : new BigDecimal[] {p.mdrPercentage(), p.fixedFee(), p.minFee(), p.maxFee(), p.refundFee(),
                p.settlementFee()}) {
            if (v != null && v.signum() < 0) {
                throw ApiException.unprocessable("invalid_fee_plan", "Fee values must not be negative");
            }
        }
        if (p.mdrPercentage() != null && p.mdrPercentage().compareTo(BigDecimal.ONE) >= 0) {
            throw ApiException.unprocessable("invalid_fee_plan", "mdr_percentage is a fraction (0.01 = 1%) below 1");
        }
        if (p.minFee() != null && p.maxFee() != null && p.minFee().compareTo(p.maxFee()) > 0) {
            throw ApiException.unprocessable("invalid_fee_plan", "min_fee must be <= max_fee");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
